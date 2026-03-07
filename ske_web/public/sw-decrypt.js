/**
 * Sakura Encryptor Service Worker — Decrypt Proxy  (v2.0 — Optimized)
 *
 * Architecture: Session-based caching.
 *   - Header, derived key, and metadata fetched & computed ONCE per file.
 *   - Decrypted blocks cached in-memory (LRU, 64 blocks ≈ 64 MiB).
 *   - Concurrent requests for the same block are deduplicated.
 *   - Adjacent blocks are batch-fetched in a single HTTP Range request.
 *
 * .ske body layout (chunked):
 *   [Header 50B]
 *   [Block 0: CHUNK_SIZE + 16B tag]
 *   [Block 1: CHUNK_SIZE + 16B tag]
 *   ...
 *   [Block N: <=CHUNK_SIZE + 16B tag]  (last block)
 */

const MAGIC = 'SakuraE'
const HEADER_SIZE = 50
const CHUNK_SIZE = 1024 * 1024   // 1 MiB plaintext per block
const TAG_SIZE = 16              // AES-GCM tag
const ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE
const KDF_ITERATIONS = 100_000
const MAX_CACHED_BLOCKS = 64     // ~64 MiB of decrypted data
const SESSION_TTL = 10 * 60_000  // Clean idle sessions after 10 min

let masterPassword = null

// ── Session Store ─────────────────────────────────────────────
// Keyed by the real upstream URL. Each session stores everything
// needed to serve range requests WITHOUT re-fetching or re-deriving.
const sessions = new Map()        // url → FileSession
const blockLRU = new Map()        // "url#bi" → Uint8Array  (global LRU)
const inflightBlocks = new Map()  // "url#bi" → Promise<Uint8Array>

// ── Performance Telemetry ─────────────────────────────────────
let perfTotalNetBytes = 0
let perfCacheHits = 0
let perfCacheMisses = 0
let perfLastDecryptMs = 0   // Latest block decryption latency in ms

class FileSession {
    constructor(url, salt, masterIv, key, totalSize) {
        this.url = url
        this.salt = salt
        this.masterIv = masterIv
        this.key = key
        this.totalSize = totalSize
        this.lastUsed = Date.now()

        // Precompute plaintext metrics
        const bodySize = totalSize - HEADER_SIZE
        this.totalBlocks = Math.ceil(bodySize / ENC_BLOCK_SIZE)
        const lastEncBlockSize = bodySize - (this.totalBlocks - 1) * ENC_BLOCK_SIZE
        const lastPlainSize = lastEncBlockSize - TAG_SIZE
        this.totalPlainSize = (this.totalBlocks - 1) * CHUNK_SIZE + lastPlainSize
    }

    touch() { this.lastUsed = Date.now() }
}

// Periodically clean stale sessions
setInterval(() => {
    const now = Date.now()
    for (const [url, s] of sessions) {
        if (now - s.lastUsed > SESSION_TTL) {
            sessions.delete(url)
            // Also purge blocks belonging to this session
            for (const key of blockLRU.keys()) {
                if (key.startsWith(url + '#')) blockLRU.delete(key)
            }
        }
    }
}, 60_000)

// ── Lifecycle ─────────────────────────────────────────────────
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('message', async (e) => {
    if (e.data?.type === 'SET_PASSWORD') {
        masterPassword = e.data.password
        sessions.clear()
        blockLRU.clear()
        inflightBlocks.clear()
        perfTotalNetBytes = 0
        perfCacheHits = 0
        perfCacheMisses = 0
        perfLastDecryptMs = 0
        console.log('[SW] Password set, sessions cleared')
    }
    if (e.data?.type === 'RESET_PERF') {
        perfTotalNetBytes = 0
        perfCacheHits = 0
        perfCacheMisses = 0
        perfLastDecryptMs = 0
    }
    if (e.data?.type === 'GET_PERF') {
        const clientsList = await self.clients.matchAll({ type: 'window' })
        for (const client of clientsList) {
            client.postMessage({
                type: 'PERF_REPORT',
                totalBytes: perfTotalNetBytes,
                cacheHits: perfCacheHits,
                cacheMisses: perfCacheMisses,
                lastDecryptMs: perfLastDecryptMs,
            })
        }
    }
})

// ── Fetch intercept ───────────────────────────────────────────
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url)
    if (!url.pathname.startsWith('/ske-decrypt')) return
    event.respondWith(handleDecrypt(event.request, url))
})

// ── Main handler ──────────────────────────────────────────────
async function handleDecrypt(request, url) {
    // 1. Ensure password
    if (!masterPassword) {
        const clientsList = await self.clients.matchAll({ type: 'window' })
        for (const client of clientsList) {
            client.postMessage({ type: 'REQUEST_PASSWORD' })
        }
        await new Promise((r) => {
            let t = 0
            const iv = setInterval(() => { t += 50; if (masterPassword || t >= 1500) { clearInterval(iv); r() } }, 50)
        })
        if (!masterPassword) {
            return new Response('No decryption key set.', { status: 403 })
        }
    }

    const realUrl = url.searchParams.get('url')
    if (!realUrl) return new Response('No URL provided', { status: 400 })
    const querySize = url.searchParams.get('size')

    try {
        // 2. Get or create session (header + key cached here)
        const session = await getOrCreateSession(realUrl, querySize)
        session.touch()

        // 3. Parse Range header
        let rangeHeader = request.headers.get('Range')
        const mime = getMimeType(realUrl)
        const isMedia = mime.startsWith('video/') || mime.startsWith('audio/')

        // Force range mode for media to prevent memory balloon
        if (!rangeHeader && isMedia) {
            rangeHeader = 'bytes=0-'
        }

        if (rangeHeader) {
            return handleRangeRequest(rangeHeader, session)
        }

        // Full file fallback (non-media, small files)
        return handleFullRequest(session)
    } catch (err) {
        console.error('[SW] Decrypt error:', err)
        return new Response(`Decryption error: ${err.message}`, { status: 500 })
    }
}

// ── Session management ────────────────────────────────────────
// Creating a session also deduplicates: if two requests arrive at
// the same time for the same URL, only one header fetch occurs.
const sessionInflight = new Map()  // url → Promise<FileSession>

async function getOrCreateSession(url, querySize) {
    if (sessions.has(url)) return sessions.get(url)

    // Deduplicate concurrent session creation
    if (sessionInflight.has(url)) return sessionInflight.get(url)

    const promise = createSession(url, querySize)
    sessionInflight.set(url, promise)
    try {
        const session = await promise
        sessions.set(url, session)
        return session
    } finally {
        sessionInflight.delete(url)
    }
}

async function createSession(url, querySize) {
    console.log('[SW] Creating session for', url.slice(0, 80))

    const headerResp = await fetch(url, {
        headers: { Range: `bytes=0-${HEADER_SIZE - 1}` },
    })

    let headerBytes
    if (headerResp.status === 206) {
        headerBytes = new Uint8Array(await headerResp.arrayBuffer())
    } else {
        // Server doesn't support Range — full file path
        const full = new Uint8Array(await headerResp.arrayBuffer())
        headerBytes = full.slice(0, HEADER_SIZE)
        // If we got the whole file, we might as well cache all blocks
        // (handled in decryptFull if needed)
    }

    const magic = new TextDecoder().decode(headerBytes.slice(0, 7))
    if (magic !== MAGIC) throw new Error('Invalid .ske file (bad magic)')

    const salt = headerBytes.slice(10, 26)
    const masterIv = headerBytes.slice(26, 38)
    const key = await deriveKey(masterPassword, salt)

    // Determine total file size
    let totalSize = null
    const contentRange = headerResp.headers.get('Content-Range')
    if (contentRange) {
        const m = contentRange.match(/\/(\d+)/)
        if (m) totalSize = parseInt(m[1], 10)
    }
    if (!totalSize && querySize) {
        totalSize = parseInt(querySize, 10)
    }
    if (!totalSize) throw new Error('Cannot determine file size')

    console.log(`[SW] Session ready: ${totalSize} bytes, key derived`)
    return new FileSession(url, salt, masterIv, key, totalSize)
}

// ── Range request handling ────────────────────────────────────
function handleRangeRequest(rangeHeader, session) {
    const match = rangeHeader.match(/bytes=(\d+)-(\d*)/)
    if (!match) return new Response('Invalid Range', { status: 416 })

    const { totalPlainSize } = session

    let rangeStart = parseInt(match[1], 10)
    let rangeEnd = match[2] ? parseInt(match[2], 10) : totalPlainSize - 1

    // Cap response to 5 MiB
    const MAX_RESPONSE = 5 * 1024 * 1024
    if (rangeEnd - rangeStart + 1 > MAX_RESPONSE) {
        rangeEnd = rangeStart + MAX_RESPONSE - 1
    }
    if (rangeEnd >= totalPlainSize) rangeEnd = totalPlainSize - 1

    if (rangeStart >= totalPlainSize) {
        return new Response('', {
            status: 416,
            headers: { 'Content-Range': `bytes */${totalPlainSize}` }
        })
    }

    // Which blocks do we need?
    const startBlock = Math.floor(rangeStart / CHUNK_SIZE)
    const endBlock = Math.floor(rangeEnd / CHUNK_SIZE)

    // Fetch & decrypt all needed blocks (with dedup + batch)
    return fetchAndAssembleBlocks(session, startBlock, endBlock, rangeStart, rangeEnd)
}

async function fetchAndAssembleBlocks(session, startBlock, endBlock, rangeStart, rangeEnd) {
    const { totalPlainSize } = session

    // Collect blocks — use cache, dedup in-flight, or batch-fetch missing
    const blockPromises = []
    const missingBlocks = []

    for (let bi = startBlock; bi <= endBlock; bi++) {
        const cacheKey = session.url + '#' + bi
        if (blockLRU.has(cacheKey)) {
            // Cache hit — touch LRU
            const data = blockLRU.get(cacheKey)
            blockLRU.delete(cacheKey)
            blockLRU.set(cacheKey, data)
            perfCacheHits++
            blockPromises.push({ bi, promise: Promise.resolve(data) })
        } else if (inflightBlocks.has(cacheKey)) {
            // Already being fetched — piggyback on existing request
            blockPromises.push({ bi, promise: inflightBlocks.get(cacheKey) })
        } else {
            // Need to fetch
            missingBlocks.push(bi)
            blockPromises.push({ bi, promise: null })  // placeholder
        }
    }

    // Batch-fetch all missing blocks in one HTTP request
    if (missingBlocks.length > 0) {
        const batchPromise = batchFetchBlocks(session, missingBlocks)
        // Wire up placeholders to the batch result
        for (const bp of blockPromises) {
            if (bp.promise === null) {
                const bi = bp.bi
                const cacheKey = session.url + '#' + bi
                // Create per-block promise that extracts from batch
                const p = batchPromise.then(batchMap => {
                    const data = batchMap.get(bi)
                    if (!data) throw new Error(`Block ${bi} missing from batch`)
                    return data
                })
                inflightBlocks.set(cacheKey, p)
                p.finally(() => inflightBlocks.delete(cacheKey))
                bp.promise = p
            }
        }
    }

    // Wait for all blocks
    const blocks = []
    for (const bp of blockPromises) {
        blocks.push(await bp.promise)
    }

    // Assemble result
    const allPlain = concatBuffers(blocks)
    const offsetInFirst = rangeStart - startBlock * CHUNK_SIZE
    const result = allPlain.slice(offsetInFirst, offsetInFirst + (rangeEnd - rangeStart + 1))

    return new Response(result, {
        status: 206,
        headers: {
            'Content-Type': getMimeType(session.url),
            'Content-Range': `bytes ${rangeStart}-${rangeEnd}/${totalPlainSize}`,
            'Content-Length': String(result.length),
            'Accept-Ranges': 'bytes',
        },
    })
}

// ── Batch block fetching ──────────────────────────────────────
// Fetches a contiguous range of encrypted blocks in ONE HTTP request,
// then decrypts each individually.
async function batchFetchBlocks(session, blockIndices) {
    const { url, key, masterIv, totalSize } = session

    // Find the contiguous encrypted byte range covering all blocks
    const minBi = Math.min(...blockIndices)
    const maxBi = Math.max(...blockIndices)
    const encStart = HEADER_SIZE + minBi * ENC_BLOCK_SIZE
    const encEnd = Math.min(HEADER_SIZE + (maxBi + 1) * ENC_BLOCK_SIZE - 1, totalSize - 1)

    const fetchSize = encEnd - encStart + 1
    console.log(`[SW] Batch fetch blocks ${minBi}-${maxBi} (${blockIndices.length} blocks, ${fetchSize} bytes)`)

    const resp = await fetch(url, {
        headers: { Range: `bytes=${encStart}-${encEnd}` },
    })

    if (!resp.ok) throw new Error(`Upstream fetch failed: ${resp.status}`)

    let encData = new Uint8Array(await resp.arrayBuffer())
    perfTotalNetBytes += encData.length
    perfCacheMisses += blockIndices.length

    if (resp.status === 200) {
        encData = encData.slice(encStart, encEnd + 1)
    }

    // Decrypt each block from the batch
    const result = new Map()
    for (const bi of blockIndices) {
        const offsetInBatch = (bi - minBi) * ENC_BLOCK_SIZE
        const blockEnd = Math.min(offsetInBatch + ENC_BLOCK_SIZE, encData.length)
        const encBlock = encData.slice(offsetInBatch, blockEnd)

        if (encBlock.length <= TAG_SIZE) {
            throw new Error(`Block ${bi} too small: ${encBlock.length} bytes`)
        }

        const nonce = blockNonce(masterIv, bi)
        const t0 = performance.now()
        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, encBlock)
        perfLastDecryptMs = Math.round((performance.now() - t0) * 100) / 100
        const plainData = new Uint8Array(plain)

        // Store in LRU cache
        const cacheKey = url + '#' + bi
        blockLRU.set(cacheKey, plainData)
        evictLRU()

        result.set(bi, plainData)
    }

    return result
}

// ── Full file decrypt (non-media fallback) ────────────────────
async function handleFullRequest(session) {
    const { url, key, masterIv, totalSize, totalPlainSize } = session

    const bodyResp = await fetch(url, {
        headers: { Range: `bytes=${HEADER_SIZE}-${totalSize - 1}` },
    })

    let bodyBytes
    if (bodyResp.status === 206) {
        bodyBytes = new Uint8Array(await bodyResp.arrayBuffer())
    } else {
        const full = new Uint8Array(await bodyResp.arrayBuffer())
        bodyBytes = full.slice(HEADER_SIZE)
    }

    const plainChunks = []
    let offset = 0
    let blockIndex = 0

    while (offset < bodyBytes.length) {
        const end = Math.min(offset + ENC_BLOCK_SIZE, bodyBytes.length)
        const block = bodyBytes.slice(offset, end)
        const nonce = blockNonce(masterIv, blockIndex)
        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, block)
        plainChunks.push(new Uint8Array(plain))
        offset = end
        blockIndex++
    }

    const result = concatBuffers(plainChunks)
    return new Response(result, {
        status: 200,
        headers: {
            'Content-Type': getMimeType(url),
            'Content-Length': String(result.length),
            'Accept-Ranges': 'bytes',
        },
    })
}

// ── LRU eviction ──────────────────────────────────────────────
function evictLRU() {
    while (blockLRU.size > MAX_CACHED_BLOCKS) {
        const oldest = blockLRU.keys().next().value
        blockLRU.delete(oldest)
    }
}

// ── Crypto helpers ────────────────────────────────────────────
async function deriveKey(password, salt) {
    const enc = new TextEncoder()
    const baseKey = await crypto.subtle.importKey(
        'raw', enc.encode(password), 'PBKDF2', false, ['deriveKey'],
    )
    return crypto.subtle.deriveKey(
        { name: 'PBKDF2', salt, iterations: KDF_ITERATIONS, hash: 'SHA-256' },
        baseKey,
        { name: 'AES-GCM', length: 256 },
        false,
        ['decrypt'],
    )
}

function blockNonce(masterIv, blockIndex) {
    const nonce = new Uint8Array(masterIv)
    const idx = new Uint8Array(12)
    const view = new DataView(idx.buffer)
    view.setUint32(4, Math.floor(blockIndex / 0x100000000), false)
    view.setUint32(8, blockIndex >>> 0, false)
    for (let i = 0; i < 12; i++) nonce[i] ^= idx[i]
    return nonce
}

function concatBuffers(chunks) {
    const total = chunks.reduce((s, c) => s + c.length, 0)
    const result = new Uint8Array(total)
    let pos = 0
    for (const c of chunks) { result.set(c, pos); pos += c.length }
    return result
}

function getMimeType(urlStr) {
    if (!urlStr || typeof urlStr !== 'string') return 'application/octet-stream'
    const path = urlStr.split('?')[0].toLowerCase()
    const cleanPath = path.endsWith('.ske') ? path.slice(0, -4) : path
    const ext = cleanPath.split('.').pop()
    const types = {
        'mp4': 'video/mp4', 'mkv': 'video/x-matroska', 'avi': 'video/x-msvideo',
        'mov': 'video/quicktime', 'webm': 'video/webm', 'ts': 'video/mp2t',
        'flv': 'video/x-flv', 'wmv': 'video/x-ms-wmv', 'm4v': 'video/x-m4v',
        'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'png': 'image/png',
        'gif': 'image/gif', 'webp': 'image/webp', 'bmp': 'image/bmp',
        'svg': 'image/svg+xml',
        'mp3': 'audio/mpeg', 'wav': 'audio/wav', 'ogg': 'audio/ogg',
        'flac': 'audio/flac', 'aac': 'audio/aac', 'm4a': 'audio/mp4',
    }
    return types[ext] || 'application/octet-stream'
}
