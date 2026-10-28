/**
 * SKE container reader (format version 001), Node implementation.
 *
 * This mirrors `ske_cli/crypto.py` byte for byte; the format spec lives in
 * `docs/SKE-FORMAT.md`. Only the read path is implemented here, because the
 * desktop client reuses the browser's writer (`ske_web/src/composables/
 * useCrypto.js`) for anything it needs to encrypt.
 *
 * Layout:
 *   Header    55 B  MAGIC(8) VERSION(3) SALT(16) MASTER_IV(12) SHMAC(16)
 *   Options   22 B  KEY_CHECK(16) KDF_ITERATIONS(5) FLAGS(1)
 *   Envelope  22+N  "SKETH" NONCE(12) THUMB_LEN(5) THUMB_CT(N)
 *   Body      blocks of AES-256-GCM(chunk) || TAG(16)
 */
import crypto from 'node:crypto'

export const MAGIC = Buffer.from('SakuraEK', 'ascii')
export const VERSION = Buffer.from('001', 'ascii')
export const THUMB_MAGIC = Buffer.from('SKETH', 'ascii')

export const SALT_SIZE = 16
export const IV_SIZE = 12
export const TAG_SIZE = 16
export const SHMAC_SIZE = 16
export const KEY_CHECK_SIZE = 16
export const KDF_ITERATIONS_SIZE = 5
export const OPTIONS_FLAGS_SIZE = 1
export const OPTIONS_SIZE = KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE + OPTIONS_FLAGS_SIZE // 22

export const HEADER_SIZE = MAGIC.length + VERSION.length + SALT_SIZE + IV_SIZE + SHMAC_SIZE // 55
export const AAD_PREFIX_SIZE = HEADER_SIZE - SHMAC_SIZE // 39
export const AAD_SIZE = AAD_PREFIX_SIZE + OPTIONS_SIZE // 61

export const THUMB_NONCE_SIZE = 12
export const THUMB_LEN_SIZE = 5
export const THUMB_HEADER_SIZE = THUMB_MAGIC.length + THUMB_NONCE_SIZE + THUMB_LEN_SIZE // 22
export const PROBE_SIZE = HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE // 99

export const CHUNK_SIZE = 1024 * 1024
export const ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE

export const KDF_ITERATIONS = 100_000
export const MAX_KDF_ITERATIONS = 2_000_000
const KEY_CHECK_LABEL = Buffer.from('ske-keycheck', 'ascii')

/**
 * Cheapest possible container test: the 8-byte magic.
 *
 * A plain file (one the user dropped into the tree by hand, or uploaded without
 * encryption) has no magic, so callers can pass it through untouched instead of
 * reporting an invalid container.
 */
export function looksLikeContainer(probe) {
  return !!probe && probe.length >= MAGIC.length && probe.subarray(0, MAGIC.length).equals(MAGIC)
}

/**
 * Parse the fixed part of a container from its first PROBE_SIZE bytes.
 *
 * The returned `bodyOffset` is where the first encrypted block starts, so a
 * reader can jump straight to any block without scanning the whole file.
 */
export function parsePrefix(probe) {
  if (!probe || probe.length < PROBE_SIZE) {
    throw new Error('File too small to be a valid .ske container')
  }
  if (!probe.subarray(0, MAGIC.length).equals(MAGIC)) {
    throw new Error('Invalid magic number: not a .ske file')
  }
  const version = probe.subarray(MAGIC.length, MAGIC.length + VERSION.length).toString('ascii')
  if (version !== VERSION.toString('ascii')) {
    throw new Error(`Unsupported .ske version: ${version}`)
  }

  const salt = probe.subarray(MAGIC.length + VERSION.length, MAGIC.length + VERSION.length + SALT_SIZE)
  const masterIv = probe.subarray(AAD_PREFIX_SIZE - IV_SIZE, AAD_PREFIX_SIZE)
  const options = probe.subarray(HEADER_SIZE, HEADER_SIZE + OPTIONS_SIZE)

  const flags = options[KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE]
  if (flags !== 0) throw new Error('Unsupported .ske options flags')

  const iterations = options.readUIntBE(KEY_CHECK_SIZE, KDF_ITERATIONS_SIZE)
  if (iterations < 1 || iterations > MAX_KDF_ITERATIONS) {
    throw new Error(`Unsupported KDF iteration count: ${iterations}`)
  }

  const thumbStart = HEADER_SIZE + OPTIONS_SIZE
  if (!probe.subarray(thumbStart, thumbStart + THUMB_MAGIC.length).equals(THUMB_MAGIC)) {
    throw new Error('Corrupted thumbnail envelope')
  }
  const thumbNonce = probe.subarray(
    thumbStart + THUMB_MAGIC.length,
    thumbStart + THUMB_MAGIC.length + THUMB_NONCE_SIZE,
  )
  const thumbLen = probe.readUIntBE(
    thumbStart + THUMB_MAGIC.length + THUMB_NONCE_SIZE,
    THUMB_LEN_SIZE,
  )

  return {
    // The header prefix and options block are the AAD for every body block;
    // keeping the raw slices avoids rebuilding them per block.
    aadPrefix: probe.subarray(0, AAD_PREFIX_SIZE),
    options,
    keyCheck: options.subarray(0, KEY_CHECK_SIZE),
    shmac: probe.subarray(AAD_PREFIX_SIZE, HEADER_SIZE),
    salt,
    masterIv,
    thumbNonce,
    thumbLen,
    iterations,
    bodyOffset: PROBE_SIZE + thumbLen,
  }
}

/** PBKDF2-HMAC-SHA256, UTF-8 password, 32-byte key. */
export function deriveKey(password, salt, iterations = KDF_ITERATIONS) {
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > MAX_KDF_ITERATIONS) {
    throw new Error(`Unsupported KDF iteration count: ${iterations}`)
  }
  return crypto.pbkdf2Sync(Buffer.from(password, 'utf8'), salt, iterations, 32, 'sha256')
}

export function computeKeyCheck(key) {
  return crypto.createHmac('sha256', key).update(KEY_CHECK_LABEL).digest().subarray(0, KEY_CHECK_SIZE)
}

/**
 * Reject a wrong password before touching a single block.
 *
 * Constant-time comparison matters here even offline: a timing oracle would let
 * an attacker brute-force the 16-byte probe instead of a full block decrypt.
 */
export function verifyKeyCheck(prefix, key) {
  const expected = computeKeyCheck(key)
  if (expected.length !== prefix.keyCheck.length || !crypto.timingSafeEqual(expected, prefix.keyCheck)) {
    throw new Error('Wrong password')
  }
}

/** nonce(i) = MASTER_IV XOR big-endian 12-byte block index. */
export function blockNonce(masterIv, index) {
  const nonce = Buffer.alloc(IV_SIZE)
  masterIv.copy(nonce)
  // Only the low 8 bytes of the counter are ever non-zero for real files.
  let remaining = index
  for (let i = IV_SIZE - 1; i >= 0 && remaining > 0; i -= 1) {
    nonce[i] ^= remaining & 0xff
    remaining = Math.floor(remaining / 256)
  }
  return nonce
}

/** AAD bound into every body block: 39-byte prefix followed by the options. */
export function blockAad(prefix) {
  return Buffer.concat([prefix.aadPrefix, prefix.options])
}

/**
 * Decrypt one block. `ciphertext` includes the trailing 16-byte GCM tag.
 */
export function decryptBlock(key, nonce, ciphertext, aad) {
  if (ciphertext.length < TAG_SIZE) {
    throw new Error('Truncated .ske block')
  }
  const body = ciphertext.subarray(0, ciphertext.length - TAG_SIZE)
  const tag = ciphertext.subarray(ciphertext.length - TAG_SIZE)

  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonce)
  decipher.setAAD(aad)
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(body), decipher.final()])
}

/** THUMB_LEN is a 5-byte big-endian uint40 counting the ciphertext *and* tag. */
export function encodeThumbLength(length) {
  const buffer = Buffer.alloc(THUMB_LEN_SIZE)
  buffer.writeUIntBE(length, 0, THUMB_LEN_SIZE)
  return buffer
}

/** KDF_ITERATIONS is a 5-byte big-endian uint40, stored per container. */
export function encodeKdfIterations(iterations) {
  const buffer = Buffer.alloc(KDF_ITERATIONS_SIZE)
  buffer.writeUIntBE(iterations, 0, KDF_ITERATIONS_SIZE)
  return buffer
}

/**
 * Decrypt the preview envelope from a container.
 *
 * Costs `99 + THUMB_LEN` bytes (≈40 KB) instead of the 1 MiB first block, which
 * is the entire reason the envelope exists. Returns null when the container
 * carries no preview.
 */
export async function readThumbnail(source, password, probe = null) {
  // Callers that already probed the header pass it in: for a remote file one
  // `read` is one HTTP range request, so probing twice is worth avoiding.
  const prefix = parsePrefix(probe || (await source.read(0, PROBE_SIZE)))
  if (!prefix.thumbLen) return null

  const key = deriveKey(password, prefix.salt, prefix.iterations)
  verifyKeyCheck(prefix, key)

  const ciphertext = await source.read(PROBE_SIZE, prefix.thumbLen)
  if (ciphertext.length !== prefix.thumbLen) {
    throw new Error('Truncated thumbnail envelope')
  }

  const aad = Buffer.concat([
    prefix.aadPrefix,
    prefix.options,
    THUMB_MAGIC,
    encodeThumbLength(prefix.thumbLen),
  ])

  const decipher = crypto.createDecipheriv('aes-256-gcm', key, prefix.thumbNonce)
  decipher.setAAD(aad)
  decipher.setAuthTag(ciphertext.subarray(ciphertext.length - TAG_SIZE))
  return Buffer.concat([
    decipher.update(ciphertext.subarray(0, ciphertext.length - TAG_SIZE)),
    decipher.final(),
  ])
}

/**
 * Plaintext geometry of a container of `totalSize` bytes.
 *
 * `plainSize` is what the HTTP layer advertises as Content-Length; keeping it
 * exact is what makes seeking land on the right byte offset in the player.
 */
export function geometry(totalSize, bodyOffset) {
  const cipherSize = Math.max(0, totalSize - bodyOffset)
  const blocks = Math.ceil(cipherSize / ENC_BLOCK_SIZE)
  const plainSize = cipherSize - TAG_SIZE * blocks
  if (plainSize < 0) throw new Error('Corrupted .ske container: body shorter than its tags')
  return { blocks, plainSize, cipherSize }
}
