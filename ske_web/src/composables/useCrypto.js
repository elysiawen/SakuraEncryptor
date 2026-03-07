/**
 * useCrypto — Web Crypto API composable
 *
 * Mirrors the Python CLI's PBKDF2 + AES-256-GCM logic so that
 * the browser can derive the same key and decrypt names / files.
 */

const KDF_ITERATIONS = 100_000
const NAME_SALT = new TextEncoder().encode('ske-name-salt-00') // fixed salt for name keys
const IV_TAG = new TextEncoder().encode('ske-name-iv')

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

/** Derive a file-level key from password + salt embedded in .skmod header. */
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
