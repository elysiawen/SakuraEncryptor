/**
 * Deterministic file-name encryption, Node implementation.
 *
 * Names use a *different* key from file contents: a PBKDF2 key over a constant
 * salt, plus a fixed IV derived from that key. Determinism is the point — the
 * same plaintext component must always produce the same token so the encrypted
 * directory tree can be rebuilt without a database (docs/SKE-FORMAT.md §10).
 *
 * The trade-off is inherent: identical file names produce identical tokens.
 */
import crypto from 'node:crypto'

export const NAME_SALT = Buffer.from('skenamesalt-00-s', 'ascii')
const NAME_IV_SEED = Buffer.from('ske-name-iv', 'ascii')
// Fixed by the format: the name key has no per-file salt, so there is nowhere
// to store a work factor. Changing this breaks every existing name on disk.
const NAME_KDF_ITERATIONS = 100_000

export function deriveNameKey(password) {
  return crypto.pbkdf2Sync(
    Buffer.from(password, 'utf8'),
    NAME_SALT,
    NAME_KDF_ITERATIONS,
    32,
    'sha256',
  )
}

export function nameIv(key) {
  return crypto.createHmac('sha256', key).update(NAME_IV_SEED).digest().subarray(0, 12)
}

/** Encrypt one path component into a URL-safe Base64 token (no padding). */
export function encryptName(name, key) {
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nameIv(key))
  const ciphertext = Buffer.concat([cipher.update(String(name), 'utf8'), cipher.final()])
  return Buffer.concat([ciphertext, cipher.getAuthTag()]).toString('base64url')
}

/** Decrypt a token back to the original component. Throws on a wrong key. */
export function decryptName(token, key) {
  // Some writers keep the `=` padding; base64url decoding here tolerates it,
  // but strip it first so both forms hash to the same bytes.
  const raw = Buffer.from(String(token).replace(/=+$/, ''), 'base64url')
  if (raw.length <= 16) throw new Error('无效的文件名 token')
  const ciphertext = raw.subarray(0, raw.length - 16)
  const tag = raw.subarray(raw.length - 16)

  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nameIv(key))
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8')
}

/**
 * Decrypt a stored file name.
 *
 * A `.ske` suffix is stripped first: on disk a container is `token + '.ske'`,
 * and the token itself never contains a dot.
 */
export function decryptStoredName(stored, key) {
  const token = stored.toLowerCase().endsWith('.ske') ? stored.slice(0, -4) : stored
  return decryptName(token, key)
}

/** True when a stored name looks like an encrypted container. */
export function isEncryptedName(stored) {
  return typeof stored === 'string' && stored.toLowerCase().endsWith('.ske')
}

/** Best-effort decrypt: returns null instead of throwing for foreign entries. */
export function tryDecryptName(stored, key) {
  try {
    return decryptStoredName(stored, key)
  } catch {
    return null
  }
}

/**
 * Validate one path component typed by the user.
 *
 * A name is a *single* component: a separator would silently move the entry, so
 * it is rejected instead of being sanitised into something the user did not ask
 * for. Returns the trimmed name.
 */
export function checkName(value, label = '名称') {
  const name = String(value || '').trim()
  if (!name) throw new Error(`${label}不能为空`)
  if (name.includes('/') || name.includes('\\')) throw new Error(`${label}不能包含斜杠`)
  return name
}
