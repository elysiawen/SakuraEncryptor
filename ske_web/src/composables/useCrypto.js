/**
 * useCrypto — Web Crypto API composable
 *
 * Mirrors the Python CLI's PBKDF2 + AES-256-GCM logic so that
 * the browser can derive the same key and decrypt names / files.
 */

const KDF_ITERATIONS = 100_000
// Must match NAME_SALT in ske_cli/crypto.py — deterministic name encryption.
const NAME_SALT = new TextEncoder().encode('ske-name-salt-00')
const IV_TAG = new TextEncoder().encode('ske-name-iv')
const FILE_MAGIC = new TextEncoder().encode('SakuraE')
const FILE_VERSION = new TextEncoder().encode('003')
const SALT_SIZE = 16
const IV_SIZE = 12
const HEADER_TAG_SIZE = 12
const CHUNK_SIZE = 1024 * 1024

// v003 thumbnail envelope: magic | nonce | ciphertext length (big-endian)
const THUMB_MAGIC = new TextEncoder().encode('SKTH')
const THUMB_NONCE_SIZE = 12
const THUMB_LEN_SIZE = 4
const THUMB_ENVELOPE_SIZE = 4 + THUMB_NONCE_SIZE + THUMB_LEN_SIZE
const THUMB_MAX_SIDE = 512
const VIDEO_NAME_RE = /\.(mp4|m4v|mkv|mov|avi|webm|ts|m2ts|flv|wmv|mpg|mpeg|3gp)$/i
const POSTER_TIMEOUT_MS = 15000

/** Derive a raw 256-bit key from password + salt via PBKDF2. */
async function deriveRawKey(password, salt) {
    const enc = new TextEncoder()
    const baseKey = await crypto.subtle.importKey(
        'raw',
        enc.encode(password),
        'PBKDF2',
        false,
        ['deriveBits', 'deriveKey'],
    )
    return crypto.subtle.deriveKey(
        { name: 'PBKDF2', salt, iterations: KDF_ITERATIONS, hash: 'SHA-256' },
        baseKey,
        { name: 'AES-GCM', length: 256 },
        true, // extractable — needed to export for SW
        ['encrypt', 'decrypt'],
    )
}

/** Derive the "name key" used for deterministic path encryption. */
export async function deriveNameKey(password) {
    return deriveRawKey(password, NAME_SALT)
}

/** Derive a file-level key from password + salt embedded in the .ske header. */
export async function deriveFileKey(password, salt) {
    return deriveRawKey(password, salt)
}

/** Fixed 12-byte IV for deterministic name encryption (matches Python). */
async function nameIv(key) {
    const raw = await crypto.subtle.exportKey('raw', key)
    const hmacKey = await crypto.subtle.importKey(
        'raw',
        raw,
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign'],
    )
    const sig = await crypto.subtle.sign('HMAC', hmacKey, IV_TAG)
    return new Uint8Array(sig).slice(0, 12)
}

/** Encrypt a single path component (deterministic). Returns URL-safe Base64. */
export async function encryptName(name, key) {
    const iv = await nameIv(key)
    const enc = new TextEncoder()
    const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(name))
    return base64UrlEncode(new Uint8Array(ct))
}

/** Decrypt a URL-safe Base64 token back to the original name. */
export async function decryptName(token, key) {
    const iv = await nameIv(key)
    let ct
    try {
        ct = base64UrlDecode(token)
    } catch {
        return null // Not valid Base64Url (e.g. unencrypted Chinese folder)
    }
    try {
        const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct)
        return new TextDecoder().decode(pt)
    } catch {
        return null // wrong key or corrupted
    }
}

/** Export CryptoKey as raw bytes, store in sessionStorage, and post to SW. */
export async function storeAndPostKey(password) {
    const nameKey = await deriveNameKey(password)
    const rawNameKey = await crypto.subtle.exportKey('raw', nameKey)

    // Store password itself (needed to derive per-file keys from different salts)
    sessionStorage.setItem('ske_password', password)

    // Store exported name key for the route guard
    const b64 = base64UrlEncode(new Uint8Array(rawNameKey))
    sessionStorage.setItem('ske_key_exported', b64)

    // Post password to Service Worker so it can derive file keys
    if (navigator.serviceWorker.controller) {
        navigator.serviceWorker.controller.postMessage({
            type: 'SET_PASSWORD',
            password,
        })
    } else {
        // SW may not be active yet — wait for it
        const reg = await navigator.serviceWorker.ready
        reg.active.postMessage({ type: 'SET_PASSWORD', password })
    }

    return nameKey
}

/** Reload name key from sessionStorage (page refresh). */
export async function getNameKeyFromSession() {
    const password = sessionStorage.getItem('ske_password')
    if (!password) return null
    return deriveNameKey(password)
}

/** Read master password from sessionStorage. */
export function getPasswordFromSession() {
    return sessionStorage.getItem('ske_password') || ''
}

/**
 * Encrypt a browser File into the .ske v003 container format used by the CLI.
 *
 * A 512px preview is generated for images and stored in the encrypted
 * thumbnail envelope, never inside the plaintext — the body stays
 * byte-identical to the original file, and the grid can draw a tile after
 * fetching ~40 KB instead of a 1 MiB block.
 */
export async function encryptFileContent(file, password) {
    if (!password) throw new Error('缺少加密主密码')

    const salt = crypto.getRandomValues(new Uint8Array(SALT_SIZE))
    const masterIv = crypto.getRandomValues(new Uint8Array(IV_SIZE))
    const key = await deriveFileKey(password, salt)

    // The header prefix defines the key; it is bound into every block as AAD
    // so salt / IV / version cannot be swapped without breaking decryption.
    const headerPrefix = new Uint8Array(
        FILE_MAGIC.length + FILE_VERSION.length + SALT_SIZE + IV_SIZE,
    )
    headerPrefix.set(FILE_MAGIC, 0)
    headerPrefix.set(FILE_VERSION, FILE_MAGIC.length)
    headerPrefix.set(salt, FILE_MAGIC.length + FILE_VERSION.length)
    headerPrefix.set(masterIv, FILE_MAGIC.length + FILE_VERSION.length + SALT_SIZE)

    // ── Encrypted preview (v003 envelope) ────────────────────────────
    const thumbnail = await makePreview(file)
    const thumbNonce = crypto.getRandomValues(new Uint8Array(THUMB_NONCE_SIZE))
    let thumbCiphertext = new Uint8Array(0)
    if (thumbnail) {
        const thumbAad = new Uint8Array(
            headerPrefix.length + THUMB_MAGIC.length + THUMB_LEN_SIZE,
        )
        thumbAad.set(headerPrefix, 0)
        thumbAad.set(THUMB_MAGIC, headerPrefix.length)
        new DataView(thumbAad.buffer).setUint32(
            headerPrefix.length + THUMB_MAGIC.length,
            thumbnail.length + 16, // GCM tag included
            false,
        )
        thumbCiphertext = new Uint8Array(await crypto.subtle.encrypt(
            { name: 'AES-GCM', iv: thumbNonce, additionalData: thumbAad },
            key,
            thumbnail,
        ))
    }

    const envelope = new Uint8Array(THUMB_ENVELOPE_SIZE)
    envelope.set(THUMB_MAGIC, 0)
    envelope.set(thumbNonce, THUMB_MAGIC.length)
    new DataView(envelope.buffer).setUint32(
        THUMB_MAGIC.length + THUMB_NONCE_SIZE,
        thumbCiphertext.length,
        false,
    )

    // ── Body ─────────────────────────────────────────────────────────
    const encryptedChunks = []
    let offset = 0
    let blockIndex = 0
    while (offset < file.size) {
        const chunk = new Uint8Array(await file.slice(offset, offset + CHUNK_SIZE).arrayBuffer())
        const nonce = blockNonce(masterIv, blockIndex)
        const encrypted = await crypto.subtle.encrypt(
            { name: 'AES-GCM', iv: nonce, additionalData: headerPrefix },
            key,
            chunk,
        )
        encryptedChunks.push(new Uint8Array(encrypted))
        offset += chunk.length
        blockIndex += 1
    }

    const encryptedBody = new Blob(encryptedChunks, { type: 'application/octet-stream' })
    const bodyHash = new Uint8Array(await crypto.subtle.digest('SHA-256', await encryptedBody.arrayBuffer()))
    const header = new Uint8Array(
        FILE_MAGIC.length + FILE_VERSION.length + SALT_SIZE + IV_SIZE + HEADER_TAG_SIZE,
    )
    header.set(headerPrefix, 0)
    header.set(bodyHash.slice(0, HEADER_TAG_SIZE), headerPrefix.length)

    return new Blob([header, envelope, thumbCiphertext, encryptedBody], {
        type: 'application/octet-stream',
    })
}

function blockNonce(masterIv, blockIndex) {
    const nonce = new Uint8Array(masterIv)
    const idx = new Uint8Array(12)
    const view = new DataView(idx.buffer)
    view.setUint32(4, Math.floor(blockIndex / 0x100000000), false)
    view.setUint32(8, blockIndex >>> 0, false)
    for (let i = 0; i < nonce.length; i++) nonce[i] ^= idx[i]
    return nonce
}

// ── Preview generation ─────────────────────────────────────────
//
// The preview is only ever stored in the v003 envelope, so the plaintext is
// never rewritten: decrypting the container still yields the exact original
// bytes, EXIF and all.

/**
 * Downscale [file] to a small JPEG, or return null when the browser cannot
 * decode it (PDF, video, unsupported image, canvas unavailable).
 */
async function makeJpegThumbnail(file) {
    let bitmap
    try {
        bitmap = await createImageBitmap(file)
    } catch {
        return null
    }

    try {
        // Already small enough: a preview would only be an upscale.
        if (Math.max(bitmap.width, bitmap.height) <= THUMB_MAX_SIDE) return null

        const scale = Math.min(1, THUMB_MAX_SIDE / Math.max(bitmap.width, bitmap.height))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(bitmap.width * scale))
        canvas.height = Math.max(1, Math.round(bitmap.height * scale))
        canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height)
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.8))
        // Never spend more than a quarter of the payload on its own preview.
        if (!blob || blob.size < 128 || blob.size * 4 >= file.size) return null
        return new Uint8Array(await blob.arrayBuffer())
    } catch {
        return null
    } finally {
        bitmap.close?.()
    }
}

/** Preview bytes for [file]: a downscale for images, a sampled frame for video. */
async function makePreview(file) {
    const type = file.type || ''
    const looksVideo = type.startsWith('video/') || VIDEO_NAME_RE.test(file.name || '')
    return looksVideo ? makeVideoPoster(file) : makeJpegThumbnail(file)
}

/**
 * Grab one frame from a video with an offscreen <video> + canvas.
 *
 * Returns null when the browser cannot decode the codec (e.g. some HEVC
 * variants), when the seek times out, or when the frame would cost more than a
 * quarter of the file itself.
 */
async function makeVideoPoster(file) {
    const objectUrl = URL.createObjectURL(file)
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true
    video.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0'

    try {
        await new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('poster timeout')), POSTER_TIMEOUT_MS)
            const fail = (err) => {
                clearTimeout(timer)
                reject(err)
            }

            video.onerror = () => fail(new Error('video decode failed'))
            video.onloadedmetadata = () => {
                const duration = Number.isFinite(video.duration) ? video.duration : 0
                // 30% in: past the intro, still representative — and exactly
                // what the playback-time fallback grabs, so a video looks the
                // same before and after its container is upgraded.
                // Very short clips cannot seek meaningfully: take frame one.
                if (duration > 0 && duration < 0.4) {
                    clearTimeout(timer)
                    resolve()
                    return
                }
                try {
                    video.currentTime = duration > 0 ? duration * 0.3 : 0.5
                } catch (err) {
                    fail(err)
                }
            }
            video.onseeked = () => {
                clearTimeout(timer)
                resolve()
            }

            document.body.appendChild(video)
            video.src = objectUrl
        })

        const width = video.videoWidth
        const height = video.videoHeight
        if (!width || !height) return null

        const scale = Math.min(1, THUMB_MAX_SIDE / Math.max(width, height))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(width * scale))
        canvas.height = Math.max(1, Math.round(height * scale))
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height)
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.8))
        // Never spend more than a quarter of the payload on its own preview.
        if (!blob || blob.size < 128 || blob.size * 4 >= file.size) return null
        return new Uint8Array(await blob.arrayBuffer())
    } catch {
        return null
    } finally {
        video.removeAttribute('src')
        video.load()
        video.remove()
        URL.revokeObjectURL(objectUrl)
    }
}

// ── Base64Url helpers ──────────────────────────────────────────
export function base64UrlEncode(bytes) {
    let binary = ''
    for (const b of bytes) binary += String.fromCharCode(b)
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function base64UrlDecode(str) {
    str = str.replace(/-/g, '+').replace(/_/g, '/')
    while (str.length % 4) str += '='
    const binary = atob(str)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return bytes
}
