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
const AAD_SIZE = HEADER_SIZE - 12  // 38: header prefix authenticated as AAD in v002
const CHUNK_SIZE = 1024 * 1024   // 1 MiB plaintext per block
const TAG_SIZE = 16              // AES-GCM tag
const ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE
const KDF_ITERATIONS = 100_000
const MAX_CACHED_BLOCKS = 64     // ~64 MiB of decrypted data
const DOWNLOAD_BATCH_BLOCKS = 16 // blocks fetched per round during a download
const SESSION_TTL = 10 * 60_000  // Clean idle sessions after 10 min

let masterPassword = null

// ── Session Store ─────────────────────────────────────────────
// Keyed by the real upstream URL. Each session stores everything
// needed to serve range requests WITHOUT re-fetching or re-deriving.
const sessions = new Map()        // url → FileSession
const localFiles = new Map()      // logicalPath → File object
const blockLRU = new Map()        // "url#bi" → Uint8Array  (global LRU)
const inflightBlocks = new Map()  // "url#bi" → Promise<Uint8Array>

// ── Performance Telemetry ─────────────────────────────────────
let perfTotalNetBytes = 0
let perfCacheHits = 0
let perfCacheMisses = 0
let perfLastDecryptMs = 0   // Latest block decryption latency in ms

class FileSession {
    constructor(url, salt, masterIv, key, totalSize, isLocal = false, aad = null) {
        this.url = url
        this.salt = salt
        this.masterIv = masterIv
        this.key = key
        this.totalSize = totalSize
        this.isLocal = isLocal
        this.aad = aad  // v002 header prefix, or null for legacy v001 files
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
    if (e.data?.type === 'REGISTER_LOCAL_FILES') {
        const files = e.data.files
        for (const f of files) {
            // Normalize: remove leading slash if present
            const normalizedPath = f.path.startsWith('/') ? f.path.slice(1) : f.path
            localFiles.set(normalizedPath, f.file)
        }
        console.log(`[SW] Registered ${files.length} local files`)
    }
    if (e.data?.type === 'CLEAR_LOCAL_FILES') {
        localFiles.clear()
        console.log('[SW] Local files cleared')
    }
})

// ── Fetch intercept ───────────────────────────────────────────
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url)
    
    // Cloud Decrypt
    if (url.pathname.startsWith('/ske-decrypt')) {
        event.respondWith(handleDecrypt(event.request, url))
    }
    // Local files (decrypted or served verbatim)
    else if (url.pathname.startsWith('/ske-local')) {
        // More robust: strip prefix and redundant leading slashes
        let logicalPath = decodeURIComponent(url.pathname.replace(/^\/ske-local\/?/, ''))
        while (logicalPath.startsWith('/')) logicalPath = logicalPath.slice(1)

        const localFile = localFiles.get(logicalPath)
        if (!localFile) {
            event.respondWith(new Response(`Local file not found: ${logicalPath}`, { status: 404 }))
        } else if (logicalPath.endsWith('.ske')) {
            event.respondWith(handleDecrypt(event.request, url, true))
        } else {
            // Plaintext local file — no key needed, serve it as-is.
            event.respondWith(serveLocalFile(localFile, logicalPath, event.request))
        }
    }
})

// ── Main handler ──────────────────────────────────────────────
async function handleDecrypt(request, url, isLocal = false) {
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

    let realUrl = isLocal ? decodeURIComponent(url.pathname.replace('/ske-local', '')) : url.searchParams.get('url')
    if (isLocal && realUrl.startsWith('/')) realUrl = realUrl.slice(1)
    if (!realUrl) return new Response('No URL provided', { status: 400 })
    const querySize = isLocal ? localFiles.get(realUrl)?.size : url.searchParams.get('size')

    try {
        // 2. Get or create session (header + key cached here)
        const session = await getOrCreateSession(realUrl, querySize, isLocal)
        session.touch()

        // Whole-file streaming download: returns plaintext and is NOT capped by
        // MAX_RESPONSE, unlike the playback range handler below.
        if (url.searchParams.get('download') === '1') {
            return handleDownloadRequest(request, session)
        }

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

async function getOrCreateSession(url, querySize, isLocal = false) {
    if (sessions.has(url)) return sessions.get(url)

    // Deduplicate concurrent session creation
    if (sessionInflight.has(url)) return sessionInflight.get(url)

    const promise = createSession(url, querySize, isLocal)
    sessionInflight.set(url, promise)
    try {
        const session = await promise
        session.isLocal = isLocal // Ensure it's marked
        sessions.set(url, session)
        return session
    } finally {
        sessionInflight.delete(url)
    }
}

async function createSession(url, querySize, isLocal = false) {
    console.log('[SW] Creating session for', url.slice(0, 80))

    let headerBytes
    let totalSize = null

    if (isLocal) {
        const file = localFiles.get(url)
        if (!file) throw new Error('Local file lost')
        headerBytes = new Uint8Array(await file.slice(0, HEADER_SIZE).arrayBuffer())
        totalSize = file.size
    } else {
        const headerResp = await fetch(url, {
            headers: { Range: `bytes=0-${HEADER_SIZE - 1}` },
        })

        if (headerResp.status === 206) {
            headerBytes = new Uint8Array(await headerResp.arrayBuffer())
        } else {
            const full = new Uint8Array(await headerResp.arrayBuffer())
            headerBytes = full.slice(0, HEADER_SIZE)
        }

        // Determine total file size from network
        const contentRange = headerResp.headers.get('Content-Range')
        if (contentRange) {
            const m = contentRange.match(/\/(\d+)/)
            if (m) totalSize = parseInt(m[1], 10)
        }
    }

    if (!headerBytes) throw new Error('Failed to get header')
    const magic = new TextDecoder().decode(headerBytes.slice(0, 7))
    if (magic !== MAGIC) throw new Error('Invalid .ske file (bad magic)')

    const version = new TextDecoder().decode(headerBytes.slice(7, 10))
    const salt = headerBytes.slice(10, 26)
    const masterIv = headerBytes.slice(26, 38)
    // v002 authenticates the 38-byte header prefix as AES-GCM AAD;
    // v001 (legacy) was written without AAD.
    const aad = version === '002' ? headerBytes.slice(0, AAD_SIZE) : null
    const key = await deriveKey(masterPassword, salt)

    if (!totalSize && querySize) {
        totalSize = parseInt(querySize, 10)
    }
    if (!totalSize) throw new Error('Cannot determine file size')

    console.log(`[SW] Session ready: ${totalSize} bytes, v${version}, key derived`)
    return new FileSession(url, salt, masterIv, key, totalSize, isLocal, aad)
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
    const { url, key, masterIv, totalSize, aad } = session

    // Find the contiguous encrypted byte range covering all blocks
    const minBi = Math.min(...blockIndices)
    const maxBi = Math.max(...blockIndices)
    const encStart = HEADER_SIZE + minBi * ENC_BLOCK_SIZE
    const encEnd = Math.min(HEADER_SIZE + (maxBi + 1) * ENC_BLOCK_SIZE - 1, totalSize - 1)

    const fetchSize = encEnd - encStart + 1
    console.log(`[SW] Batch fetch blocks ${minBi}-${maxBi} (${blockIndices.length} blocks, ${fetchSize} bytes, local=${session.isLocal})`)

    let encData
    if (session.isLocal) {
        const file = localFiles.get(url)
        if (!file) throw new Error('Local file lost during batch fetch')
        encData = new Uint8Array(await file.slice(encStart, encEnd + 1).arrayBuffer())
    } else {
        const resp = await fetch(url, {
            headers: { Range: `bytes=${encStart}-${encEnd}` },
        })
        if (!resp.ok) throw new Error(`Upstream fetch failed: ${resp.status}`)
        encData = new Uint8Array(await resp.arrayBuffer())
        if (resp.status === 200) {
            encData = encData.slice(encStart, encEnd + 1)
        }
    }

    perfTotalNetBytes += encData.length
    perfCacheMisses += blockIndices.length

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
        const params = { name: 'AES-GCM', iv: nonce }
        if (aad) params.additionalData = aad
        const t0 = performance.now()
        const plain = await crypto.subtle.decrypt(params, key, encBlock)
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
    const { url, key, masterIv, totalSize, totalPlainSize, isLocal, aad } = session

    let bodyBytes
    if (isLocal) {
        const file = localFiles.get(url)
        bodyBytes = new Uint8Array(await file.slice(HEADER_SIZE).arrayBuffer())
    } else {
        const bodyResp = await fetch(url, {
            headers: { Range: `bytes=${HEADER_SIZE}-${totalSize - 1}` },
        })
        if (bodyResp.status === 206) {
            bodyBytes = new Uint8Array(await bodyResp.arrayBuffer())
        } else {
            const full = new Uint8Array(await bodyResp.arrayBuffer())
            bodyBytes = full.slice(HEADER_SIZE)
        }
    }

    const plainChunks = []
    let offset = 0
    let blockIndex = 0

    while (offset < bodyBytes.length) {
        const end = Math.min(offset + ENC_BLOCK_SIZE, bodyBytes.length)
        const block = bodyBytes.slice(offset, end)
        const nonce = blockNonce(masterIv, blockIndex)
        const params = { name: 'AES-GCM', iv: nonce }
        if (aad) params.additionalData = aad
        const plain = await crypto.subtle.decrypt(params, key, block)
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

// ── Streaming download (full plaintext) ───────────────────────
// Playback caps every response at MAX_RESPONSE so seeking never balloons
// memory. Downloads must return the WHOLE plaintext file, so instead of
// buffering everything we stream decrypted blocks out as they arrive.
// Ranges are expressed in PLAINTEXT coordinates, which lets the download
// manager pause and resume via ordinary Range requests.
async function handleDownloadRequest(request, session) {
    const { totalPlainSize } = session
    const rangeHeader = request.headers.get('Range')

    let start = 0
    let end = totalPlainSize - 1
    if (rangeHeader) {
        const m = rangeHeader.match(/bytes=(\d+)-(\d*)/)
        if (!m) return new Response('Invalid Range', { status: 416 })
        start = parseInt(m[1], 10)
        end = m[2] ? parseInt(m[2], 10) : totalPlainSize - 1
    }
    if (end >= totalPlainSize) end = totalPlainSize - 1

    if (totalPlainSize > 0 && start >= totalPlainSize) {
        return new Response('', {
            status: 416,
            headers: { 'Content-Range': `bytes */${totalPlainSize}` },
        })
    }

    const length = end - start + 1
    const startBlock = Math.floor(start / CHUNK_SIZE)
    const endBlock = Math.floor(end / CHUNK_SIZE)
    const offsetInFirst = start - startBlock * CHUNK_SIZE

    const stream = new ReadableStream({
        async start(controller) {
            try {
                let produced = 0
                for (let first = startBlock; first <= endBlock; first += DOWNLOAD_BATCH_BLOCKS) {
                    const last = Math.min(first + DOWNLOAD_BATCH_BLOCKS - 1, endBlock)
                    const indices = []
                    for (let bi = first; bi <= last; bi++) indices.push(bi)

                    const batch = await batchFetchBlocks(session, indices)
                    for (const bi of indices) {
                        let block = batch.get(bi)
                        if (bi === startBlock && offsetInFirst > 0) {
                            block = block.subarray(offsetInFirst)
                        }
                        if (produced + block.length > length) {
                            block = block.subarray(0, length - produced)
                        }
                        controller.enqueue(block)
                        produced += block.length
                        if (produced >= length) break
                    }
                    if (produced >= length) break

                    // Simple back-pressure: yield so the consumer can drain.
                    if (controller.desiredSize !== null && controller.desiredSize <= 0) {
                        await new Promise((resolve) => setTimeout(resolve, 0))
                    }
                }
                controller.close()
            } catch (err) {
                console.error('[SW] Download stream failed:', err)
                controller.error(err)
            }
        },
    })

    const headers = {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(length),
        'Accept-Ranges': 'bytes',
    }
    if (rangeHeader) headers['Content-Range'] = `bytes ${start}-${end}/${totalPlainSize}`

    return new Response(stream, { status: rangeHeader ? 206 : 200, headers })
}

// ── Plaintext local files ─────────────────────────────────────
// Encrypted local files go through handleDecrypt; everything else is served
// verbatim. Range support matters so <video> can seek on plaintext files.
function serveLocalFile(file, path, request) {
    const type = getMimeType(path)
    const rangeHeader = request.headers.get('Range')

    if (rangeHeader) {
        const m = rangeHeader.match(/bytes=(\d+)-(\d*)/)
        if (!m) return new Response('Invalid Range', { status: 416 })

        const start = parseInt(m[1], 10)
        let end = m[2] ? parseInt(m[2], 10) : file.size - 1
        if (end >= file.size) end = file.size - 1

        if (file.size > 0 && start >= file.size) {
            return new Response('', {
                status: 416,
                headers: { 'Content-Range': `bytes */${file.size}` },
            })
        }

        return new Response(file.slice(start, end + 1), {
            status: 206,
            headers: {
                'Content-Type': type,
                'Content-Range': `bytes ${start}-${end}/${file.size}`,
                'Content-Length': String(end - start + 1),
                'Accept-Ranges': 'bytes',
            },
        })
    }

    return new Response(file, {
        status: 200,
        headers: {
            'Content-Type': type,
            'Content-Length': String(file.size),
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
