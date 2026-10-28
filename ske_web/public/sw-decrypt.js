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
 *   [Header 55B]                      MAGIC | VERSION | SALT | MASTER_IV | SHMAC
 *   [Options 22B]                     KEY_CHECK | KDF_ITERATIONS | FLAGS
 *   [Thumbnail envelope 22B + N]      (always present)
 *   [Block 0: CHUNK_SIZE + 16B tag]
 *   [Block 1: CHUNK_SIZE + 16B tag]
 *   ...
 *   [Block N: <=CHUNK_SIZE + 16B tag]  (last block)
 *
 * SHMAC is HMAC-SHA256(key, prefix | options | envelope | body)[:16], and the
 * 38-byte prefix plus the options block are the AAD of every body block.
 */

const MAGIC = 'SakuraEK'
const VERSION_SIZE = 3
const SALT_SIZE = 16
const IV_SIZE = 12
const SHMAC_SIZE = 16
const SALT_AT = MAGIC.length + VERSION_SIZE          // 11
const IV_AT = SALT_AT + SALT_SIZE                    // 27
const HEADER_SIZE = IV_AT + IV_SIZE + SHMAC_SIZE     // 55
const AAD_PREFIX_SIZE = HEADER_SIZE - SHMAC_SIZE     // 39: the part that defines the key
const SUPPORTED_VERSIONS = new Set(['001'])
const THUMB_MAGIC = 'SKETH'
const THUMB_NONCE_SIZE = 12
const THUMB_LEN_SIZE = 5
const THUMB_MAGIC_LEN = THUMB_MAGIC.length
const THUMB_ENVELOPE_SIZE = THUMB_MAGIC_LEN + THUMB_NONCE_SIZE + THUMB_LEN_SIZE  // 22

// Options block: key check | work factor | reserved flags (zero).
const KEY_CHECK_SIZE = 16
const KEY_CHECK_LABEL = new TextEncoder().encode('ske-keycheck')
const KDF_ITERATIONS_SIZE = 5
const OPTIONS_FLAGS_SIZE = 1
const OPTIONS_SIZE = KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE + OPTIONS_FLAGS_SIZE  // 22
const AAD_SIZE = AAD_PREFIX_SIZE + OPTIONS_SIZE  // 60: bound into every block

/** THUMB_LEN is a 5-byte big-endian ciphertext length (GCM tag included). */
function readThumbLength(bytes, offset) {
    let value = 0
    for (let i = 0; i < THUMB_LEN_SIZE; i++) value = value * 256 + bytes[offset + i]
    return value
}

function writeThumbLength(bytes, offset, value) {
    let remaining = value
    for (let i = THUMB_LEN_SIZE - 1; i >= 0; i--) {
        bytes[offset + i] = remaining % 256
        remaining = Math.floor(remaining / 256)
    }
}
// Header + options + envelope: enough bytes to locate the first body block.
const ENVELOPE_START = HEADER_SIZE + OPTIONS_SIZE
const PROBE_SIZE = ENVELOPE_START + THUMB_ENVELOPE_SIZE
const CHUNK_SIZE = 1024 * 1024   // 1 MiB plaintext per block
const TAG_SIZE = 16              // AES-GCM tag
const ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE
const MAX_KDF_ITERATIONS = 2_000_000  // reject hostile files that would stall the CPU
const MAX_CACHED_BLOCKS = 64     // ~64 MiB of decrypted data
const DOWNLOAD_BATCH_BLOCKS = 16 // blocks fetched per round during a download
const SESSION_TTL = 10 * 60_000  // Clean idle sessions after 10 min
const MAX_RESPONSE = 5 * 1024 * 1024      // per-range cap for playback
const POSTER_RESPONSE = 1 * 1024 * 1024   // per-range cap for thumbnail grabs
const THUMB_CACHE_MAX = 300               // decrypted previews kept in memory
const THUMB_TTL = 30 * 60_000             // previews are immutable per container

let masterPassword = null

// ── Upstream access failures ──────────────────────────────────
/**
 * Raised when the browser never got to read the bytes at all: CORS refused,
 * the host answered 401/403 (hotlink protection, expired direct link), or it
 * is simply unreachable. None of these are fixable by retrying here — the
 * container cannot be decrypted without the server's data — so the page is
 * told *why* instead of being handed a bare 500.
 */
class UpstreamBlocked extends Error {
    constructor(reason, status = 0) {
        super(`Upstream blocked: ${reason}${status ? ` (${status})` : ''}`)
        this.name = 'UpstreamBlocked'
        this.reason = reason      // 'cors' | 'forbidden' | 'offline'
        this.status = status
    }
}

/**
 * fetch() for anything cross-origin, with the failure modes named.
 *
 * A response without `Access-Control-Allow-Origin` is invisible to us: the
 * browser rejects the promise with a bare TypeError and refuses to expose the
 * status, so CORS and a dead network look identical from here.
 */
async function fetchUpstream(url, init) {
    let resp
    try {
        resp = await fetch(url, init)
    } catch {
        // Offline is not the host's fault, and telling the user to switch to the
        // client would not help: the client is just as offline.
        if (self.navigator && self.navigator.onLine === false) {
            throw new UpstreamBlocked('offline')
        }
        throw new UpstreamBlocked('cors')
    }
    if (resp.status === 401 || resp.status === 403) {
        // Logged next to the error: an expired sign, a one-shot DPoP proof and a
        // hotlink block all look like this, and the URL is the only way to tell.
        console.warn('[SW] Upstream refused:', resp.status, url.slice(0, 200))
        throw new UpstreamBlocked('forbidden', resp.status)
    }
    return resp
}

// A folder of previews would otherwise fire one notice per image.
const blockedNotices = new Map()   // reason → last sent at
const BLOCKED_NOTICE_TTL = 15_000

function notifyUpstreamBlocked(reason, status, url) {
    if (Date.now() - (blockedNotices.get(reason) || 0) < BLOCKED_NOTICE_TTL) return
    blockedNotices.set(reason, Date.now())

    let origin = ''
    try {
        origin = new URL(url).origin
    } catch {
        // Local files and malformed links carry no origin worth naming.
    }

    self.clients
        .matchAll({ type: 'window', includeUncontrolled: true })
        .then((clients) => {
            for (const client of clients) {
                // `url` lets the page match the refusal to the file it is
                // showing, instead of blaming whatever is on screen.
                client.postMessage({ type: 'UPSTREAM_BLOCKED', reason, status, origin, url })
            }
        })
        .catch(() => {})
}

function blockedResponse(err, url) {
    notifyUpstreamBlocked(err.reason, err.status, url)
    return new Response(blockedMessage(err, url), {
        status: 502,
        headers: {
            'Content-Type': 'text/plain; charset=utf-8',
            // The page cannot read a media error body, so the reason also
            // travels as a header and as the message above.
            'X-Ske-Error': 'UPSTREAM_BLOCKED',
            'X-Ske-Reason': err.reason,
            'Cache-Control': 'no-store',
        },
    })
}

function blockedMessage(err, url) {
    if (err.reason === 'offline') {
        return '网络已断开，无法读取网盘上的加密文件。请检查网络后重试。'
    }
    if (err.reason === 'forbidden') {
        return `网盘拒绝访问（HTTP ${err.status}），可能是直链已过期、需要登录或开启了防盗链。` +
            '浏览器端无法绕过，请改用桌面客户端或安卓客户端浏览。'
    }
    let origin = ''
    try {
        origin = new URL(url).origin
    } catch {
        // Local files and malformed links carry no origin worth naming.
    }
    // 与 src/composables/useProjectLinks.js 保持一致（SW 无法 import 打包模块）
    return `浏览器直连 ${origin || '该网盘'} 被跨域策略（CORS）拦截，` +
        '请使用浏览器插件，或直接下载客户端使用。\n' +
        '下载地址：https://github.com/elysiawen/SakuraEncryptor/releases'
}

// ── Named decryption failures ─────────────────────────────────
/**
 * A failure we can name. The page reads `X-Ske-Error` from the 4xx/5xx response
 * and shows that one cause — never a list of guesses.
 */
class SkeError extends Error {
    constructor(code, message) {
        super(message)
        this.name = 'SkeError'
        this.code = code   // 'NO_KEY' | 'BAD_PASSWORD' | 'CORRUPT'
    }
}

const SKE_STATUS = {
    NO_KEY: 401,
    BAD_PASSWORD: 403,
    CORRUPT: 422,
}

const SKE_MESSAGE = {
    NO_KEY: 'Service Worker 没有解密密码，请重新输入密码。',
    BAD_PASSWORD: '密码不正确，无法解密该文件。',
    CORRUPT: '数据损坏或被篡改（AES-GCM 校验失败）。',
}

function namedResponse(err) {
    const code = SKE_STATUS[err.code] ? err.code : 'CORRUPT'
    return new Response(SKE_MESSAGE[code], {
        status: SKE_STATUS[code],
        headers: {
            'Content-Type': 'text/plain; charset=utf-8',
            'X-Ske-Error': code,
            'Cache-Control': 'no-store',
        },
    })
}

/**
 * One place that turns a thrown error into a named response, so every endpoint
 * answers with the same vocabulary. WebCrypto reports a failed tag check as a
 * bare `OperationError` with no detail, which is why it is mapped by name.
 */
function failureResponse(err, url) {
    if (err instanceof UpstreamBlocked) return blockedResponse(err, url)
    if (err instanceof SkeError) return namedResponse(err)
    if (err && (err.name === 'OperationError' || err.name === 'DataError')) {
        return namedResponse(new SkeError('CORRUPT', err.message))
    }
    return null
}

// ── Session Store ─────────────────────────────────────────────
// Keyed by the real upstream URL. Each session stores everything
// needed to serve range requests WITHOUT re-fetching or re-deriving.
const sessions = new Map()        // url → FileSession
const localFiles = new Map()      // logicalPath → File object
const blockLRU = new Map()        // "url#bi" → Uint8Array  (global LRU)
const inflightBlocks = new Map()  // "url#bi" → Promise<Uint8Array>
const thumbCache = new Map()      // "url|local" → { bytes, at }  (decrypted previews)

// ── Performance Telemetry ─────────────────────────────────────
let perfTotalNetBytes = 0
let perfCacheHits = 0
let perfCacheMisses = 0
let perfLastDecryptMs = 0   // Latest block decryption latency in ms

class FileSession {
    constructor(url, salt, masterIv, key, totalSize, isLocal = false, aad = null, bodyOffset = PROBE_SIZE) {
        this.url = url
        this.salt = salt
        this.masterIv = masterIv
        this.key = key
        this.totalSize = totalSize
        this.isLocal = isLocal
        this.aad = aad  // 38-byte header prefix, authenticated as AAD
        this.bodyOffset = bodyOffset  // header + thumbnail envelope
        this.lastUsed = Date.now()

        // Precompute plaintext metrics
        const bodySize = totalSize - bodyOffset
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
        thumbCache.clear()
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
    
    // Encrypted preview (thumbnail envelope) — no body block is touched
    if (url.pathname.startsWith('/ske-thumb')) {
        event.respondWith(handleThumbnail(url))
    }
    // Cloud Decrypt
    else if (url.pathname.startsWith('/ske-decrypt')) {
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

// ── Password plumbing ─────────────────────────────────────────
/** Wait (briefly) for the page to hand us the master password. */
async function ensurePassword() {
    if (masterPassword) return true

    const clientsList = await self.clients.matchAll({ type: 'window' })
    for (const client of clientsList) {
        client.postMessage({ type: 'REQUEST_PASSWORD' })
    }
    await new Promise((resolve) => {
        let waited = 0
        const iv = setInterval(() => {
            waited += 50
            if (masterPassword || waited >= 1500) {
                clearInterval(iv)
                resolve()
            }
        }, 50)
    })
    return !!masterPassword
}

// ── Thumbnail endpoint (thumbnail envelope) ───────────────────
/**
 * Serve the preview stored inside a container's thumbnail envelope.
 *
 * The envelope has its own nonce and AAD, so it can be decrypted without
 * touching a single body block: the whole request costs `70 + THUMB_LEN` bytes
 * instead of the 1 MiB the playback path has to pull for the first block.
 *
 * Query contract:
 *   url=<upstream .ske link>            remote entries
 *   local=<path in the local vault>     local entries
 * Responds 404 when the container carries no preview, so the caller can fall
 * back to the full image.
 */
async function handleThumbnail(url) {
    const isLocal = url.searchParams.has('local')
    const target = isLocal
        ? decodeURIComponent(url.searchParams.get('local') || '').replace(/^\/+/, '')
        : url.searchParams.get('url')
    if (!target) return new Response('No URL provided', { status: 400 })

    try {
        if (!(await ensurePassword())) {
            return namedResponse(new SkeError('NO_KEY', 'No decryption key set.'))
        }

        const cacheKey = `${isLocal ? 'local' : 'remote'}|${target}`
        const cached = thumbCache.get(cacheKey)
        if (cached && Date.now() - cached.at < THUMB_TTL) {
            return thumbnailResponse(cached.bytes)
        }

        const probe = await readUpstreamRange(target, isLocal, 0, PROBE_SIZE - 1)
        if (probe.length < PROBE_SIZE) return new Response('', { status: 404 })

        const decoder = new TextDecoder()
        if (decoder.decode(probe.slice(0, MAGIC.length)) !== MAGIC) {
            return new Response('', { status: 404 })
        }
        if (!SUPPORTED_VERSIONS.has(
            decoder.decode(probe.slice(MAGIC.length, MAGIC.length + VERSION_SIZE)),
        )) {
            return new Response('', { status: 404 })
        }

        const envelope = probe.slice(ENVELOPE_START, PROBE_SIZE)
        if (decoder.decode(envelope.slice(0, THUMB_MAGIC_LEN)) !== THUMB_MAGIC) {
            return new Response('Corrupted thumbnail envelope', { status: 404 })
        }

        const nonce = envelope.slice(THUMB_MAGIC_LEN, THUMB_MAGIC_LEN + THUMB_NONCE_SIZE)
        const thumbLen = readThumbLength(envelope, THUMB_MAGIC_LEN + THUMB_NONCE_SIZE)
        if (!thumbLen) return new Response('', { status: 404 })

        const ctStart = PROBE_SIZE
        const ciphertext = await readUpstreamRange(target, isLocal, ctStart, ctStart + thumbLen - 1)
        if (ciphertext.length !== thumbLen) {
            return new Response('Corrupted thumbnail envelope', { status: 404 })
        }

        // AAD mirrors the writer: prefix | options | magic | ciphertext length.
        const aad = new Uint8Array(AAD_SIZE + THUMB_MAGIC_LEN + THUMB_LEN_SIZE)
        aad.set(probe.slice(0, AAD_PREFIX_SIZE), 0)
        aad.set(probe.slice(HEADER_SIZE, HEADER_SIZE + OPTIONS_SIZE), AAD_PREFIX_SIZE)
        aad.set(new TextEncoder().encode(THUMB_MAGIC), AAD_SIZE)
        writeThumbLength(aad, AAD_SIZE + THUMB_MAGIC_LEN, thumbLen)

        let preview
        try {
            const key = await deriveContainerKey(probe)
            preview = new Uint8Array(await crypto.subtle.decrypt(
                { name: 'AES-GCM', iv: nonce, additionalData: aad },
                key,
                ciphertext,
            ))
        } catch (err) {
            // The key check already passed, so a failed tag means the bytes were
            // altered on the way in, not that the password is wrong.
            console.warn('[SW] Thumbnail decryption failed:', err.message)
            return namedResponse(new SkeError('CORRUPT', err.message))
        }

        perfTotalNetBytes += probe.length + ciphertext.length
        thumbCache.set(cacheKey, { bytes: preview, at: Date.now() })
        while (thumbCache.size > THUMB_CACHE_MAX) {
            thumbCache.delete(thumbCache.keys().next().value)
        }
        return thumbnailResponse(preview)
    } catch (err) {
        const named = failureResponse(err, target)
        if (named) return named

        console.error('[SW] Thumbnail error:', err)
        return new Response(`Thumbnail error: ${err.message}`, {
            status: 500,
            headers: { 'X-Ske-Error': 'UNKNOWN', 'Cache-Control': 'no-store' },
        })
    }
}

function thumbnailResponse(bytes) {
    return new Response(bytes, {
        status: 200,
        headers: {
            'Content-Type': 'image/jpeg',
            'Content-Length': String(bytes.length),
            // Previews are keyed by password; never let the browser keep them
            // after a password change. The worker's own cache is cleared then.
            'Cache-Control': 'no-store',
        },
    })
}

/** Read [start, end] (inclusive) from a remote link or a registered local file. */
async function readUpstreamRange(target, isLocal, start, end) {
    if (isLocal) {
        const file = localFiles.get(target)
        if (!file) throw new Error('Local file not found')
        return new Uint8Array(await file.slice(start, end + 1).arrayBuffer())
    }

    const resp = await fetchUpstream(target, { headers: { Range: `bytes=${start}-${end}` } })
    if (resp.status === 206) return new Uint8Array(await resp.arrayBuffer())
    if (!resp.ok) throw new Error(`Upstream fetch failed: ${resp.status}`)
    // Server ignored Range: slice the full body locally.
    const full = new Uint8Array(await resp.arrayBuffer())
    return full.slice(start, end + 1)
}

// ── Main handler ──────────────────────────────────────────────
async function handleDecrypt(request, url, isLocal = false) {
    if (!(await ensurePassword())) {
        return namedResponse(new SkeError('NO_KEY', 'No decryption key set.'))
    }

    let realUrl = isLocal ? decodeURIComponent(url.pathname.replace('/ske-local', '')) : url.searchParams.get('url')
    if (isLocal && realUrl.startsWith('/')) realUrl = realUrl.slice(1)
    if (!realUrl) return new Response('No URL provided', { status: 400 })
    const querySize = isLocal ? localFiles.get(realUrl)?.size : url.searchParams.get('size')

    try {
        // 1. Get or create session (header + key cached here)
        const session = await getOrCreateSession(realUrl, querySize, isLocal)
        session.touch()

        // Whole-file streaming download: returns plaintext and is NOT capped by
        // MAX_RESPONSE, unlike the playback range handler below.
        if (url.searchParams.get('download') === '1') {
            return handleDownloadRequest(request, session)
        }

        // 2. Parse Range header
        let rangeHeader = request.headers.get('Range')
        const mime = getMimeType(realUrl)
        const isMedia = mime.startsWith('video/') || mime.startsWith('audio/')

        // Force range mode for media to prevent memory balloon
        if (!rangeHeader && isMedia) {
            rangeHeader = 'bytes=0-'
        }

        if (rangeHeader) {
            // Poster grabs (video thumbnails) only need one decodable frame.
            // A smaller response stops the media element from pre-reading
            // megabytes it will never use.
            const isPoster = url.searchParams.get('poster') === '1'
            return handleRangeRequest(rangeHeader, session, isPoster ? POSTER_RESPONSE : MAX_RESPONSE)
        }

        // Full file fallback (non-media, small files)
        return handleFullRequest(session)
    } catch (err) {
        // Name the cause: a host that blocks the browser is not a decryption
        // failure, and saying so is the difference between "try again" and
        // "use the client".
        const named = failureResponse(err, realUrl)
        if (named) return named

        console.error('[SW] Decrypt error:', err)
        return new Response(`Decryption error: ${err.message}`, {
            status: 500,
            headers: { 'X-Ske-Error': 'UNKNOWN', 'Cache-Control': 'no-store' },
        })
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
        headerBytes = new Uint8Array(await file.slice(0, PROBE_SIZE).arrayBuffer())
        totalSize = file.size
    } else {
        const headerResp = await fetchUpstream(url, {
            headers: { Range: `bytes=0-${PROBE_SIZE - 1}` },
        })

        if (headerResp.status === 206) {
            headerBytes = new Uint8Array(await headerResp.arrayBuffer())
        } else {
            const full = new Uint8Array(await headerResp.arrayBuffer())
            headerBytes = full.slice(0, PROBE_SIZE)
        }

        // Determine total file size from network
        const contentRange = headerResp.headers.get('Content-Range')
        if (contentRange) {
            const m = contentRange.match(/\/(\d+)/)
            if (m) totalSize = parseInt(m[1], 10)
        }
    }

    if (!headerBytes) throw new Error('Failed to get header')
    const decoder = new TextDecoder()
    const magic = decoder.decode(headerBytes.slice(0, MAGIC.length))
    if (magic !== MAGIC) throw new SkeError('CORRUPT', 'Invalid .ske file (bad magic)')

    const version = decoder.decode(headerBytes.slice(MAGIC.length, MAGIC.length + VERSION_SIZE))
    const salt = headerBytes.slice(SALT_AT, SALT_AT + SALT_SIZE)
    const masterIv = headerBytes.slice(IV_AT, IV_AT + IV_SIZE)
    if (!SUPPORTED_VERSIONS.has(version)) {
        throw new SkeError('CORRUPT', `Unsupported .ske version: ${version}`)
    }
    if (headerBytes.length < PROBE_SIZE) {
        throw new SkeError('CORRUPT', 'Corrupted .ske container')
    }

    // The options block carries the password probe and the work factor, and
    // is the AAD tail of every block: neither can be swapped underneath us.
    const key = await deriveContainerKey(headerBytes)
    // AAD = 38-byte prefix + options; SHMAC sits between them on disk, so the
    // two pieces have to be concatenated rather than sliced in one go.
    const aad = concatBuffers([
        headerBytes.slice(0, AAD_PREFIX_SIZE),
        headerBytes.slice(HEADER_SIZE, HEADER_SIZE + OPTIONS_SIZE),
    ])

    // An encrypted thumbnail always sits between the options and the body, so
    // the first block starts after the envelope.
    const thumbMagic = new TextDecoder().decode(
        headerBytes.slice(ENVELOPE_START, ENVELOPE_START + THUMB_MAGIC_LEN),
    )
    if (thumbMagic !== THUMB_MAGIC) {
        throw new SkeError('CORRUPT', 'Corrupted .ske thumbnail envelope')
    }
    const thumbLen = readThumbLength(
        headerBytes,
        ENVELOPE_START + THUMB_MAGIC_LEN + THUMB_NONCE_SIZE,
    )
    const bodyOffset = PROBE_SIZE + thumbLen

    if (!totalSize && querySize) {
        totalSize = parseInt(querySize, 10)
    }
    if (!totalSize) throw new Error('Cannot determine file size')
    if (totalSize < bodyOffset) throw new Error('Corrupted .ske thumbnail envelope')

    console.log(`[SW] Session ready: ${totalSize} bytes, v${version}, body@${bodyOffset}, key derived`)
    return new FileSession(url, salt, masterIv, key, totalSize, isLocal, aad, bodyOffset)
}

// ── Range request handling ────────────────────────────────────
function handleRangeRequest(rangeHeader, session, maxResponse = MAX_RESPONSE) {
    const match = rangeHeader.match(/bytes=(\d+)-(\d*)/)
    if (!match) return new Response('Invalid Range', { status: 416 })

    const { totalPlainSize } = session

    let rangeStart = parseInt(match[1], 10)
    let rangeEnd = match[2] ? parseInt(match[2], 10) : totalPlainSize - 1

    // Cap per-response size (5 MiB normally, much less for poster grabs)
    if (rangeEnd - rangeStart + 1 > maxResponse) {
        rangeEnd = rangeStart + maxResponse - 1
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
    const { url, key, masterIv, totalSize, aad, bodyOffset } = session

    // Find the contiguous encrypted byte range covering all blocks
    const minBi = Math.min(...blockIndices)
    const maxBi = Math.max(...blockIndices)
    const encStart = bodyOffset + minBi * ENC_BLOCK_SIZE
    const encEnd = Math.min(bodyOffset + (maxBi + 1) * ENC_BLOCK_SIZE - 1, totalSize - 1)

    const fetchSize = encEnd - encStart + 1
    console.log(`[SW] Batch fetch blocks ${minBi}-${maxBi} (${blockIndices.length} blocks, ${fetchSize} bytes, local=${session.isLocal})`)

    let encData
    if (session.isLocal) {
        const file = localFiles.get(url)
        if (!file) throw new Error('Local file lost during batch fetch')
        encData = new Uint8Array(await file.slice(encStart, encEnd + 1).arrayBuffer())
    } else {
        const resp = await fetchUpstream(url, {
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
    const { url, key, masterIv, totalSize, totalPlainSize, isLocal, aad, bodyOffset } = session

    let bodyBytes
    if (isLocal) {
        const file = localFiles.get(url)
        bodyBytes = new Uint8Array(await file.slice(bodyOffset).arrayBuffer())
    } else {
        const bodyResp = await fetchUpstream(url, {
            headers: { Range: `bytes=${bodyOffset}-${totalSize - 1}` },
        })
        if (bodyResp.status === 206) {
            bodyBytes = new Uint8Array(await bodyResp.arrayBuffer())
        } else {
            const full = new Uint8Array(await bodyResp.arrayBuffer())
            bodyBytes = full.slice(bodyOffset)
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
                // Too late for a status code, but not for an explanation.
                if (err instanceof UpstreamBlocked) {
                    notifyUpstreamBlocked(err.reason, err.status, session.url)
                }
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

/** PBKDF2-HMAC-SHA256 from the container's password and work factor. */
async function deriveKeyBytes(password, salt, iterations) {
    if (!(iterations >= 1 && iterations <= MAX_KDF_ITERATIONS)) {
        throw new Error(`Unsupported KDF iteration count: ${iterations}`)
    }
    const enc = new TextEncoder()
    const baseKey = await crypto.subtle.importKey(
        'raw', enc.encode(password), 'PBKDF2', false, ['deriveBits'],
    )
    const bits = await crypto.subtle.deriveBits(
        { name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, baseKey, 256,
    )
    return new Uint8Array(bits)
}

async function importAesKey(rawKey) {
    return crypto.subtle.importKey(
        'raw', rawKey, { name: 'AES-GCM' }, false, ['decrypt'],
    )
}

/** KEY_CHECK: HMAC-SHA256(key, "ske-keycheck")[:16]. */
async function keyCheckProbe(rawKey) {
    const hmacKey = await crypto.subtle.importKey(
        'raw', rawKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
    )
    const sig = new Uint8Array(await crypto.subtle.sign('HMAC', hmacKey, KEY_CHECK_LABEL))
    return sig.slice(0, KEY_CHECK_SIZE)
}

/** Constant-time comparison, mirroring Python's `hmac.compare_digest`. */
function constantTimeEqual(a, b) {
    if (a.length !== b.length) return false
    let diff = 0
    for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i]
    return diff === 0
}

/**
 * Validate the options block and return its KDF work factor.
 *
 * Reserved flags must be zero and the work factor must be sane, so a hostile
 * file cannot pin the CPU.
 */
function readOptionsIterations(options) {
    for (let i = KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE; i < OPTIONS_SIZE; i++) {
        if (options[i] !== 0) throw new Error('Unsupported .ske options')
    }
    let iterations = 0
    for (let i = 0; i < KDF_ITERATIONS_SIZE; i++) {
        iterations = iterations * 256 + options[KEY_CHECK_SIZE + i]
    }
    if (!(iterations >= 1 && iterations <= MAX_KDF_ITERATIONS)) {
        throw new Error(`Unsupported KDF iteration count: ${iterations}`)
    }
    return iterations
}

/**
 * Derive the container key from a PROBE_SIZE prefix of the file.
 *
 * The work factor comes from the file itself, and a wrong password is rejected
 * through the key check before a single block or preview is touched.
 */
async function deriveContainerKey(probe) {
    if (probe.length < PROBE_SIZE) throw new Error('Corrupted .ske container')
    const options = probe.slice(HEADER_SIZE, HEADER_SIZE + OPTIONS_SIZE)
    const iterations = readOptionsIterations(options)
    const rawKey = await deriveKeyBytes(masterPassword, probe.slice(SALT_AT, SALT_AT + SALT_SIZE), iterations)
    const expected = options.slice(0, KEY_CHECK_SIZE)
    if (!constantTimeEqual(expected, await keyCheckProbe(rawKey))) {
        throw new SkeError('BAD_PASSWORD', 'Wrong password.')
    }
    return importAesKey(rawKey)
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
