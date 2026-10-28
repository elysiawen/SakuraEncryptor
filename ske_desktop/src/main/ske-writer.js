/**
 * SKE container writer (format version 001), Node implementation.
 *
 * Mirrors `ske_cli/crypto.py::encrypt_file` and the browser's
 * `useCrypto.js::encryptFileContent`. All three must produce containers that
 * every other implementation can read, so the byte order here is not free to
 * change — see docs/SKE-FORMAT.md §14 before touching anything.
 *
 * Write order matters: SHMAC covers the header prefix, options, the preview
 * envelope and every ciphertext block, so the header is reserved first and
 * back-filled after the body is written.
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import {
  AAD_PREFIX_SIZE,
  CHUNK_SIZE,
  HEADER_SIZE,
  IV_SIZE,
  KDF_ITERATIONS,
  MAGIC,
  SALT_SIZE,
  SHMAC_SIZE,
  TAG_SIZE,
  THUMB_MAGIC,
  THUMB_NONCE_SIZE,
  VERSION,
  blockNonce,
  computeKeyCheck,
  deriveKey,
  encodeKdfIterations,
  encodeThumbLength,
} from './ske-format.js'

/**
 * Encrypt `src` into `dst`.
 *
 * @param {object} options
 * @param {string} options.src
 * @param {string} options.dst
 * @param {string} options.password
 * @param {Buffer|null} [options.thumbnail] pre-rendered JPEG, or null for none
 * @param {(processed: number, total: number) => void} [options.onProgress]
 * @param {AbortSignal} [options.signal]
 */
export async function encryptFile({
  src,
  dst,
  password,
  thumbnail = null,
  onProgress,
  signal,
}) {
  if (!password) throw new Error('缺少加密密码')

  const stat = await fsp.stat(src)
  if (!stat.isFile()) throw new Error('源不是文件')
  const total = stat.size

  const salt = crypto.randomBytes(SALT_SIZE)
  const masterIv = crypto.randomBytes(IV_SIZE)
  const key = deriveKey(password, salt, KDF_ITERATIONS)

  // MAGIC | VERSION | SALT | MASTER_IV — the AAD prefix shared by every block.
  const headerPrefix = Buffer.concat([MAGIC, VERSION, salt, masterIv])
  if (headerPrefix.length !== AAD_PREFIX_SIZE) {
    throw new Error(`AAD 前缀长度异常: ${headerPrefix.length}`)
  }

  const options = Buffer.concat([
    computeKeyCheck(key),
    encodeKdfIterations(KDF_ITERATIONS),
    Buffer.alloc(1), // reserved flags, must be zero
  ])
  const aad = Buffer.concat([headerPrefix, options])

  // The envelope is always written, even when there is no preview: readers rely
  // on a fixed 99-byte probe to find the body.
  const thumbNonce = crypto.randomBytes(THUMB_NONCE_SIZE)
  let thumbCipher = Buffer.alloc(0)
  if (thumbnail && thumbnail.length) {
    const thumbAad = Buffer.concat([
      aad,
      THUMB_MAGIC,
      encodeThumbLength(thumbnail.length + TAG_SIZE),
    ])
    const cipher = crypto.createCipheriv('aes-256-gcm', key, thumbNonce)
    cipher.setAAD(thumbAad)
    thumbCipher = Buffer.concat([
      cipher.update(thumbnail),
      cipher.final(),
      cipher.getAuthTag(),
    ])
  }

  const envelope = Buffer.concat([
    THUMB_MAGIC,
    thumbNonce,
    encodeThumbLength(thumbCipher.length),
    thumbCipher,
  ])

  await fsp.mkdir(path.dirname(dst), { recursive: true })
  const handle = await fsp.open(dst, 'w')
  let failed = false

  try {
    // Reserve the header; SHMAC is only known once every block is written.
    await handle.write(Buffer.alloc(HEADER_SIZE), 0, HEADER_SIZE, 0)
    await handle.write(options, 0, options.length, HEADER_SIZE)
    await handle.write(envelope, 0, envelope.length, HEADER_SIZE + options.length)

    const mac = crypto.createHmac('sha256', key)
    mac.update(headerPrefix)
    mac.update(options)
    mac.update(envelope)

    let index = 0
    let processed = 0
    let position = HEADER_SIZE + options.length + envelope.length

    const stream = fs.createReadStream(src, { highWaterMark: CHUNK_SIZE })
    for await (const chunk of stream) {
      if (signal?.aborted) throw new Error('已取消')

      const cipher = crypto.createCipheriv('aes-256-gcm', key, blockNonce(masterIv, index))
      cipher.setAAD(aad)
      const ciphertext = Buffer.concat([
        cipher.update(chunk),
        cipher.final(),
        cipher.getAuthTag(),
      ])

      await handle.write(ciphertext, 0, ciphertext.length, position)
      position += ciphertext.length
      mac.update(ciphertext)

      index += 1
      processed += chunk.length
      onProgress?.(processed, total)
    }

    if (signal?.aborted) throw new Error('已取消')

    // Back-fill prefix and SHMAC now that the digest is complete.
    await handle.write(headerPrefix, 0, AAD_PREFIX_SIZE, 0)
    const shmac = mac.digest().subarray(0, SHMAC_SIZE)
    await handle.write(shmac, 0, SHMAC_SIZE, AAD_PREFIX_SIZE)
  } catch (err) {
    failed = true
    throw err
  } finally {
    await handle.close().catch(() => {})
    // A cancelled or failed run must not leave a half-written container behind:
    // it would look like a real file to the browser and to any sync tool.
    if (failed) await fsp.unlink(dst).catch(() => {})
  }
}
