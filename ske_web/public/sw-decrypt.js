/**
 * Sakura Encryptor Service Worker — Decrypt Proxy
 *
 * Intercepts fetch requests for /ske-decrypt/<encoded-url> paths.
 * Fetches the encrypted .ske file from AList, decrypts it using
 * AES-256-GCM (Web Crypto API), and returns the plaintext video stream.
 *
 * Supports Range requests via chunked decryption for video seek.
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
const CHUNK_SIZE = 1024 * 1024 // 1 MiB plaintext per block
const TAG_SIZE = 16            // AES-GCM tag
const ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE
const KDF_ITERATIONS = 100_000
const MAX_CACHE_SIZE = 32 // Store up to 32MB of decrypted blocks

let masterPassword = null
const blockCache = new Map() // LRU: Key = "url#blockIndex", Value = Uint8Array

// ── Lifecycle ──────────────────────────────────────────────────
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('message', (e) => {
    if (e.data?.type === 'SET_PASSWORD') {
        masterPassword = e.data.password
        console.log('[SW] Password received')
    }
})

// ── Fetch intercept ────────────────────────────────────────────
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url)

    // Only intercept our virtual decrypt paths (check both with and without trailing slash)
    if (!url.pathname.startsWith('/ske-decrypt')) return

    event.respondWith(handleDecrypt(event.request, url))
})

async function handleDecrypt(request, url) {
    if (!masterPassword) {
        // Try to ask open tabs for the password
        const clientsList = await self.clients.matchAll({ type: 'window' })
        for (const client of clientsList) {
            client.postMessage({ type: 'REQUEST_PASSWORD' })
        }

        // Wait up to 1.5 seconds for a tab to reply
        await new Promise((resolve) => {
            let elapsed = 0
            const timer = setInterval(() => {
                elapsed += 50
                if (masterPassword || elapsed >= 1500) {
                    clearInterval(timer)
                    resolve()
                }
            }, 50)
        })

        if (!masterPassword) {
            return new Response('No decryption key set. Please log in first via the web UI.', { status: 403 })
        }
    }

    // The real file URL is passed as a query parameter
    const realUrl = url.searchParams.get('url')
    if (!realUrl) return new Response('No URL provided', { status: 400 })

    try {
        // Fetch the .ske header first to get salt & IV
        const headerResp = await fetch(realUrl, {
            headers: { Range: `bytes=0-${HEADER_SIZE - 1}` },
        })

        let headerBytes
        if (headerResp.status === 206) {
            headerBytes = new Uint8Array(await headerResp.arrayBuffer())
        } else {
            // Server doesn't support Range — fetch the whole file
            const full = new Uint8Array(await headerResp.arrayBuffer())
            return await decryptFull(full, realUrl)
        }

        // Parse header
        const magic = new TextDecoder().decode(headerBytes.slice(0, 7))
        if (magic !== MAGIC) {
            return new Response('Invalid .ske file', { status: 400 })
        }
        const salt = headerBytes.slice(10, 26)
        const masterIv = headerBytes.slice(26, 38)

        // Derive key
        const key = await deriveKey(masterPassword, salt)

        // Check Content-Range to get total file size
        const contentRange = headerResp.headers.get('Content-Range')
        const querySize = url.searchParams.get('size')
        let totalSize = null

        if (contentRange) {
            const m = contentRange.match(/\/(\d+)/)
            if (m) totalSize = parseInt(m[1], 10)
        } else if (querySize) {
            // Fallback: AList API size passed via URL (bypasses CORS stripping Content-Range)
            totalSize = parseInt(querySize, 10)
        }

        let rangeHeader = request.headers.get('Range')
        const mime = getMimeType(realUrl)
        const isMedia = mime.startsWith('video/') || mime.startsWith('audio/')

        // If the browser didn't send a Range request but we know it's a media file,
        // force a Range request from 0- to prevent memory balloon crashing in handleFullRequest
        if (!rangeHeader && totalSize && isMedia) {
            rangeHeader = 'bytes=0-'
        }

        if (rangeHeader && totalSize) {
            return await handleRangeRequest(rangeHeader, realUrl, key, masterIv, totalSize)
        }

        // Full file decrypt (fallback for completely unknown sizes or simple files)
        return await handleFullRequest(realUrl, key, masterIv, totalSize)

    } catch (err) {
        console.error('[SW] Decrypt error:', err)
        return new Response(`Decryption error: ${err.message}`, { status: 500 })
    }
}

// ── Full file decrypt ──────────────────────────────────────────
async function handleFullRequest(url, key, masterIv, totalSize) {
    const bodyResp = await fetch(url, {
        headers: totalSize ? { Range: `bytes=${HEADER_SIZE}-${totalSize - 1}` } : {},
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

    const totalPlain = plainChunks.reduce((s, c) => s + c.length, 0)
    const result = new Uint8Array(totalPlain)
    let pos = 0
    for (const c of plainChunks) {
        result.set(c, pos)
        pos += c.length
    }

    return new Response(result, {
        status: 200,
        headers: {
            'Content-Type': getMimeType(url),
            'Content-Length': String(totalPlain),
            'Accept-Ranges': 'bytes',
        },
    })
}

// ── Range request handling ─────────────────────────────────────
async function handleRangeRequest(rangeHeader, url, key, masterIv, totalSize) {
    console.log(`[SW] handleRangeRequest: ${rangeHeader} for size ${totalSize}`)
    const match = rangeHeader.match(/bytes=(\d+)-(\d*)/)
    if (!match) {
        console.error(`[SW] Invalid Range: ${rangeHeader}`)
        return new Response('Invalid Range', { status: 416 })
    }

    const bodySize = totalSize - HEADER_SIZE
    const totalBlocks = Math.ceil(bodySize / ENC_BLOCK_SIZE)

    const lastEncBlockSize = bodySize - (totalBlocks - 1) * ENC_BLOCK_SIZE
    const lastPlainSize = lastEncBlockSize - TAG_SIZE
    const totalPlainSize = (totalBlocks - 1) * CHUNK_SIZE + lastPlainSize

    const rangeStart = parseInt(match[1], 10)
    let rangeEnd = match[2] ? parseInt(match[2], 10) : totalPlainSize - 1

    const MAX_CHUNK = 5 * 1024 * 1024
    if (rangeEnd - rangeStart + 1 > MAX_CHUNK) {
        rangeEnd = rangeStart + MAX_CHUNK - 1
    }

    if (rangeEnd >= totalPlainSize) rangeEnd = totalPlainSize - 1

    if (rangeStart >= totalPlainSize) {
        return new Response('', {
            status: 416,
            headers: { 'Content-Range': `bytes */${totalPlainSize}` }
        })
    }

    const startBlock = Math.floor(rangeStart / CHUNK_SIZE)
    const endBlock = Math.floor(rangeEnd / CHUNK_SIZE)

    console.log(`[SW] Plaintext Range: ${rangeStart}-${rangeEnd} | Blocks: ${startBlock}-${endBlock}`)

    const plainChunks = []
    
    try {
        for (let bi = startBlock; bi <= endBlock; bi++) {
            let blockData = getCachedBlock(url, bi)
            
            if (blockData) {
                // console.log(`[SW] Cache Hit: block ${bi}`)
                plainChunks.push(blockData)
            } else {
                const bEncStart = HEADER_SIZE + bi * ENC_BLOCK_SIZE
                const bEncEnd = Math.min(HEADER_SIZE + (bi + 1) * ENC_BLOCK_SIZE - 1, totalSize - 1)
                
                console.log(`[SW] Fetching Block ${bi}: bytes ${bEncStart}-${bEncEnd}`)
                const bResp = await fetch(url, {
                    headers: { Range: `bytes=${bEncStart}-${bEncEnd}` },
                })
                
                if (!bResp.ok) throw new Error(`Upstream fetch failed: ${bResp.status}`)
                
                let encBlockData = new Uint8Array(await bResp.arrayBuffer())
                if (bResp.status === 200) {
                    encBlockData = encBlockData.slice(bEncStart, bEncEnd + 1)
                }
                
                if (encBlockData.length <= TAG_SIZE) {
                    throw new Error(`Block ${bi} too small: ${encBlockData.length} bytes (expected >16)`)
                }

                const nonce = blockNonce(masterIv, bi)
                const decrypted = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, encBlockData)
                
                blockData = new Uint8Array(decrypted)
                setCachedBlock(url, bi, blockData)
                plainChunks.push(blockData)
            }
        }
    } catch (e) {
        console.error(`[SW] Decryption Error (Block-level):`, e)
        return new Response(`Decryption failed: ${e.message}`, { status: 403 })
    }

    const allPlain = concatBuffers(plainChunks)
    const offsetInFirstBlock = rangeStart - startBlock * CHUNK_SIZE
    const result = allPlain.slice(offsetInFirstBlock, offsetInFirstBlock + (rangeEnd - rangeStart + 1))

    return new Response(result, {
        status: 206, // Partial Content
        headers: {
            'Content-Type': getMimeType(url),
            'Content-Range': `bytes ${rangeStart}-${rangeEnd}/${totalPlainSize}`,
            'Content-Length': String(result.length),
            'Accept-Ranges': 'bytes',
            'Cache-Control': 'no-cache',
        },
    })
}

// ── Cache Helpers ──────────────────────────────────────────────
function getCachedBlock(url, bi) {
    const key = `${url}#${bi}`
    if (blockCache.has(key)) {
        const data = blockCache.get(key)
        blockCache.delete(key) // move to end
        blockCache.set(key, data)
        return data
    }
    return null
}

function setCachedBlock(url, bi, data) {
    const key = `${url}#${bi}`
    if (blockCache.has(key)) blockCache.set(key, data)
    else {
        blockCache.set(key, data)
        if (blockCache.size > MAX_CACHE_SIZE) {
            const first = blockCache.keys().next().value
            blockCache.delete(first)
        }
    }
}

async function decryptFull(data, urlStr) {
    if (!masterPassword) {
        return new Response('No decryption key set.', { status: 403 })
    }

    const magic = new TextDecoder().decode(data.slice(0, 7))
    if (magic !== MAGIC) {
        return new Response('Invalid .ske file', { status: 400 })
    }

    const salt = data.slice(10, 26)
    const masterIv = data.slice(26, 38)
    const key = await deriveKey(masterPassword, salt)
    const bodyBytes = data.slice(HEADER_SIZE)

    const plainChunks = []
    let offset = 0
    let blockIndex = 0
    try {
        while (offset < bodyBytes.length) {
            const end = Math.min(offset + ENC_BLOCK_SIZE, bodyBytes.length)
            const block = bodyBytes.slice(offset, end)
            const nonce = blockNonce(masterIv, blockIndex)
            const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, block)
            plainChunks.push(new Uint8Array(plain))
            offset += ENC_BLOCK_SIZE
            blockIndex++
        }
    } catch (e) {
        console.error('[SW] Decryption failed (FullRequest)', e)
        return new Response('Decryption failed (Wrong password or corrupt data)', { status: 403 })
    }

    const result = concatBuffers(plainChunks)
    return new Response(result, {
        status: 200,
        headers: {
            'Content-Type': getMimeType(urlStr),
            'Content-Length': String(result.length),
            'Accept-Ranges': 'bytes',
        },
    })
}

// ── Crypto helpers ─────────────────────────────────────────────
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
    // Correctly XOR 12 bytes like Python's to_bytes(12, 'big')
    const nonce = new Uint8Array(masterIv)
    
    // Convert blockIndex to 12-byte big-endian
    const idx = new Uint8Array(12)
    const view = new DataView(idx.buffer)
    
    // JS Numbers are safe up to 2^53-1, so we write into the buffer
    const high = Math.floor(blockIndex / 0x100000000)
    const low = blockIndex >>> 0
    
    view.setUint32(4, high, false)   // index 4,5,6,7
    view.setUint32(8, low, false)    // index 8,9,10,11
    
    for (let i = 0; i < 12; i++) {
        nonce[i] ^= idx[i]
    }
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
    // Strip .ske if present
    const cleanPath = path.endsWith('.ske') ? path.slice(0, -4) : path
    const ext = cleanPath.split('.').pop()

    const types = {
        'mp4': 'video/mp4',
        'mkv': 'video/x-matroska',
        'avi': 'video/x-msvideo',
        'mov': 'video/quicktime',
        'webm': 'video/webm',
        'ts': 'video/mp2t',
        'jpg': 'image/jpeg',
        'jpeg': 'image/jpeg',
        'png': 'image/png',
        'gif': 'image/gif',
        'webp': 'image/webp',
        'bmp': 'image/bmp',
        'svg': 'image/svg+xml',
        'mp3': 'audio/mpeg',
        'wav': 'audio/wav',
        'ogg': 'audio/ogg',
        'flac': 'audio/flac',
        'aac': 'audio/aac',
        'm4a': 'audio/mp4'
    }
    return types[ext] || 'application/octet-stream'
}
