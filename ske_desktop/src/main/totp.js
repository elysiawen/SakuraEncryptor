/**
 * RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30-second steps.
 *
 * The defaults every authenticator app assumes, and the same ones the Android
 * client uses — a secret bound on either client has to be usable on the other.
 *
 * Pure Node, so the algorithm is pinned by test vectors
 * (`scripts/verify-totp.mjs`) instead of discovered on a phone. A wrong secret
 * or a drifting clock here means being locked out of the app.
 */
import crypto from 'node:crypto'

export const TOTP_DIGITS = 6
export const TOTP_PERIOD_SECONDS = 30
export const TOTP_ISSUER = 'Sakura Encryptor'

/** 160 bits, the size every authenticator app offers for HMAC-SHA1. */
const SECRET_BYTES = 20
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const POWERS = [1, 10, 100, 1_000, 10_000, 100_000, 1_000_000]

export function generateSecret() {
  return crypto.randomBytes(SECRET_BYTES)
}

/** The code for the step that contains `timeMillis`. */
export function codeAt(secret, timeMillis = Date.now()) {
  return codeForCounter(secret, Math.floor(timeMillis / 1000 / TOTP_PERIOD_SECONDS))
}

/**
 * @return true only for the code of the current step.
 *
 * No neighbouring step is accepted, matching the Android client: the instant an
 * authenticator rolls over, the number the user just watched expire must stop
 * working. A ±1 window is the usual concession to clock drift, but both sides
 * of this check run on machines whose clocks are already synchronised.
 */
export function verifyCode(secret, input, timeMillis = Date.now()) {
  if (!secret?.length) return false
  const digits = String(input ?? '').replace(/\D/g, '')
  if (digits.length !== TOTP_DIGITS) return false

  return crypto.timingSafeEqual(
    Buffer.from(digits, 'ascii'),
    Buffer.from(codeAt(secret, timeMillis), 'ascii'),
  )
}

function codeForCounter(secret, counter) {
  const message = Buffer.alloc(8)
  message.writeBigUInt64BE(BigInt(counter))
  const hash = crypto.createHmac('sha1', secret).update(message).digest()

  const offset = hash[hash.length - 1] & 0x0f
  const binary =
    ((hash[offset] & 0x7f) << 24) |
    ((hash[offset + 1] & 0xff) << 16) |
    ((hash[offset + 2] & 0xff) << 8) |
    (hash[offset + 3] & 0xff)
  return String(binary % POWERS[TOTP_DIGITS]).padStart(TOTP_DIGITS, '0')
}

/** RFC 4648 Base32 without padding — the form authenticator apps accept. */
export function encodeBase32(bytes) {
  let out = ''
  let buffer = 0
  let bits = 0

  for (const byte of bytes) {
    buffer = ((buffer << 8) | (byte & 0xff)) & 0xffffff
    bits += 8
    while (bits >= 5) {
      bits -= 5
      out += ALPHABET[(buffer >>> bits) & 0x1f]
    }
  }
  if (bits > 0) out += ALPHABET[(buffer << (5 - bits)) & 0x1f]
  return out
}

/** RFC 4648 Base32, tolerant of lowercase, spaces, dashes and `=` padding. */
export function decodeBase32(input) {
  const cleaned = String(input || '')
    .toUpperCase()
    .replace(/[=\-\s]/g, '')
  if (!cleaned) return null

  const out = []
  let buffer = 0
  let bits = 0

  for (const char of cleaned) {
    const value = ALPHABET.indexOf(char)
    // Anything else is not Base32: refuse instead of guessing.
    if (value < 0) return null
    buffer = ((buffer << 5) | value) & 0xffff
    bits += 5
    if (bits >= 8) {
      bits -= 8
      out.push((buffer >>> bits) & 0xff)
    }
  }
  return out.length ? Buffer.from(out) : null
}

/**
 * Accepts either a Base32 secret (`JBSWY3DPEHPK3PXP`) or a full
 * `otpauth://totp/...?secret=...` uri, i.e. whatever an authenticator app lets
 * the user copy.
 *
 * @return the shared secret, or null when the input is not usable.
 */
export function decodeSecret(input) {
  const text = String(input || '').trim()
  if (!text) return null

  const secret = /^otpauth:\/\//i.test(text) ? uriSecret(text) : text
  return secret ? decodeBase32(secret) : null
}

/** The `otpauth://` uri an authenticator app can import. */
export function otpauthUri(secret, { issuer = TOTP_ISSUER, account = 'app' } = {}) {
  const label = encodeURIComponent(`${issuer}:${account}`)
  const params = new URLSearchParams({
    secret,
    issuer,
    algorithm: 'SHA1',
    digits: String(TOTP_DIGITS),
    period: String(TOTP_PERIOD_SECONDS),
  })
  return `otpauth://totp/${label}?${params.toString()}`
}

function uriSecret(uri) {
  try {
    return new URL(uri).searchParams.get('secret')
  } catch {
    return null
  }
}
