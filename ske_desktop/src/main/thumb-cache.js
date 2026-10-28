/**
 * Encrypted preview cache on disk.
 *
 * What it holds: only the container's 512 px JPEG envelope, re-encrypted with a
 * key derived from the media password. Nothing else is ever written — never a
 * decrypted original image, never a captured video frame — so the directory
 * leaks no picture without that password, and a single stolen file is useless.
 *
 * Why it exists: an envelope is ~40 KB that has to come over the network and be
 * AES-decrypted. On a folder with a few hundred entries that is the difference
 * between an instant grid and a sluggish one every single launch.
 *
 * Layout: one file per entry, named by a hash of (profile, stored path, stamp).
 * The stamp is the listing's size + modified time, so a replaced file misses
 * instead of showing the previous picture. Writes go through a temp file and a
 * rename, so a half-written cache entry is never read back.
 */
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import path from 'node:path'

const MAGIC = Buffer.from('SKETHMB1', 'ascii')
const IV_BYTES = 12
const TAG_BYTES = 16
const HEADER_BYTES = MAGIC.length + IV_BYTES + TAG_BYTES
const FILE_SUFFIX = '.bin'
const DEFAULT_LIMIT_BYTES = 128 * 1024 * 1024

/**
 * Key for at-rest previews.
 *
 * A separate salt from the name key on purpose: the two must not fall together,
 * and this one may change without touching any encrypted file name.
 */
export function deriveThumbKey(password) {
  return crypto.pbkdf2Sync(
    Buffer.from(String(password), 'utf8'),
    Buffer.from('ske-thumb-cache-v1', 'ascii'),
    100_000,
    32,
    'sha256',
  )
}

function seal(jpeg, key) {
  const iv = crypto.randomBytes(IV_BYTES)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  const ciphertext = Buffer.concat([cipher.update(jpeg), cipher.final()])
  return Buffer.concat([MAGIC, iv, cipher.getAuthTag(), ciphertext])
}

/** Returns null for anything that is not one of ours or fails authentication. */
function unseal(blob, key) {
  if (!blob || blob.length <= HEADER_BYTES) return null
  if (!blob.subarray(0, MAGIC.length).equals(MAGIC)) return null

  const iv = blob.subarray(MAGIC.length, MAGIC.length + IV_BYTES)
  const tag = blob.subarray(MAGIC.length + IV_BYTES, HEADER_BYTES)

  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv)
    decipher.setAuthTag(tag)
    return Buffer.concat([decipher.update(blob.subarray(HEADER_BYTES)), decipher.final()])
  } catch {
    // Wrong password, or a file someone edited: treat it as a miss.
    return null
  }
}

export class ThumbDiskCache {
  /**
   * @param {object} options
   * @param {string} options.dir cache directory (created on demand)
   * @param {() => { thumbEnabled?: boolean, thumbLimitMB?: number }} [options.settings]
   */
  constructor({ dir, settings = () => ({}) } = {}) {
    this.dir = dir
    this.settings = settings
    /** name -> bytes; Map order doubles as the LRU order (oldest first). */
    this.index = new Map()
    this.total = 0
    /** Writes in flight, so two tiles asking at once cannot double-count. */
    this.pending = new Set()
    this.ready = null
  }

  get enabled() {
    return this.settings()?.thumbEnabled !== false
  }

  get limitBytes() {
    const mb = Number(this.settings()?.thumbLimitMB)
    return (Number.isFinite(mb) && mb > 0 ? mb : DEFAULT_LIMIT_BYTES / 1024 / 1024) * 1024 * 1024
  }

  /** Read the existing directory once so usage is known before the first write. */
  load() {
    this.ready ??= (async () => {
      try {
        await fsp.mkdir(this.dir, { recursive: true })
        const names = (await fsp.readdir(this.dir)).filter((name) => name.endsWith(FILE_SUFFIX))
        const entries = []
        for (const name of names) {
          try {
            const stat = await fsp.stat(path.join(this.dir, name))
            entries.push({ name, size: stat.size, at: stat.mtimeMs })
          } catch {
            // Vanished between readdir and stat: nothing to index.
          }
        }
        // Oldest first, so eviction drops the least recently written.
        entries.sort((a, b) => a.at - b.at)
        for (const entry of entries) {
          this.index.set(entry.name, entry.size)
          this.total += entry.size
        }
        await this.evict()
      } catch (err) {
        console.error('[thumb-cache] load failed:', err.message)
      }
    })()
    return this.ready
  }

  /** Stable name for one preview; the stamp keeps replaced files apart. */
  keyFor(profileId, storedPath, stamp = '') {
    return (
      crypto
        .createHash('sha256')
        .update(`${profileId || ''}\u0000${storedPath}\u0000${stamp}`)
        .digest('hex')
        .slice(0, 40) + FILE_SUFFIX
    )
  }

  async get(name, key) {
    await this.load()
    if (!key || !this.enabled || !this.index.has(name)) return null

    let blob
    try {
      blob = await fsp.readFile(path.join(this.dir, name))
    } catch {
      this.#forget(name)
      return null
    }

    const jpeg = unseal(blob, key)
    if (!jpeg) {
      // Unreadable with this key: drop it rather than retry on every request.
      await this.#drop(name)
      return null
    }

    this.#touch(name)
    return jpeg
  }

  async put(name, jpeg, key) {
    await this.load()
    if (!key || !this.enabled || !jpeg?.length) return
    if (this.index.has(name) || this.pending.has(name)) return

    const blob = seal(jpeg, key)
    const target = path.join(this.dir, name)
    const temp = `${target}.tmp`

    this.pending.add(name)
    try {
      await fsp.mkdir(this.dir, { recursive: true })
      await fsp.writeFile(temp, blob)
      await fsp.rename(temp, target)
    } catch (err) {
      await fsp.rm(temp, { force: true }).catch(() => {})
      console.error('[thumb-cache] write failed:', err.message)
      return
    } finally {
      this.pending.delete(name)
    }

    this.index.set(name, blob.length)
    this.total += blob.length
    await this.evict()
  }

  stats() {
    return { count: this.index.size, bytes: this.total }
  }

  /** Delete every cached preview. Files are ours alone; nothing else is touched. */
  async clear() {
    await this.load()
    const names = [...this.index.keys()]
    this.index.clear()
    this.total = 0

    await Promise.all(
      names.map((name) => fsp.rm(path.join(this.dir, name), { force: true }).catch(() => {})),
    )
    return { removed: names.length }
  }

  /** Re-apply the size limit after the setting changed. */
  async trim() {
    await this.load()
    await this.evict()
  }

  async evict() {
    const limit = this.limitBytes
    while (this.total > limit && this.index.size) {
      const oldest = this.index.keys().next().value
      await this.#drop(oldest)
    }
  }

  #touch(name) {
    const size = this.index.get(name)
    if (size === undefined) return
    this.index.delete(name)
    this.index.set(name, size)
  }

  #forget(name) {
    const size = this.index.get(name)
    if (size === undefined) return
    this.index.delete(name)
    this.total -= size
  }

  async #drop(name) {
    this.#forget(name)
    this.pending.delete(name)
    await fsp.rm(path.join(this.dir, name), { force: true }).catch(() => {})
  }
}
