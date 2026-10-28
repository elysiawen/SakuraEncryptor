/**
 * The decrypting media endpoint.
 *
 * One URL — `/media/<id>?t=<token>` — serves plaintext bytes of a `.ske`
 * container over standard HTTP Range requests. That single design point is what
 * lets every playback backend share the same transport:
 *
 *   - Chromium `<video>` streams it directly (progressive download),
 *   - mpv/VLC can open it and seek natively,
 *   - "decrypt to disk" reuses the same block path.
 *
 * Files that are not containers are streamed byte-for-byte instead: a folder can
 * legitimately hold plain media next to encrypted entries, and the web client
 * plays those from their raw URL. The probe below decides which path to take, so
 * every caller keeps using one URL shape.
 *
 * Plaintext never touches the disk: it lives in the block cache and in the
 * response buffer, which is the whole point of the zero-knowledge design.
 */
import crypto from 'node:crypto'
import {
  blockAad,
  blockNonce,
  decryptBlock,
  deriveKey,
  geometry,
  looksLikeContainer,
  parsePrefix,
  verifyKeyCheck,
  CHUNK_SIZE,
  ENC_BLOCK_SIZE,
  PROBE_SIZE,
} from './ske-format.js'
import { closeQuietly, createSource } from './sources.js'

/** Decrypted blocks held per session (~8 MiB), enough to smooth over seeks. */
const BLOCK_CACHE_LIMIT = 8
/** Response slice size for pass-through files: bounds what one read allocates. */
const PLAIN_CHUNK_SIZE = 512 * 1024

const MIME_BY_EXT = {
  mp4: 'video/mp4',
  m4v: 'video/x-m4v',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  webm: 'video/webm',
  avi: 'video/x-msvideo',
  wmv: 'video/x-ms-wmv',
  flv: 'video/x-flv',
  ts: 'video/mp2t',
  m2ts: 'video/mp2t',
  mpg: 'video/mpeg',
  mpeg: 'video/mpeg',
  '3gp': 'video/3gpp',

  mp3: 'audio/mpeg',
  flac: 'audio/flac',
  m4a: 'audio/mp4',
  aac: 'audio/aac',
  wav: 'audio/wav',
  ogg: 'audio/ogg',
  opus: 'audio/ogg',
  wma: 'audio/x-ms-wma',

  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  avif: 'image/avif',
  heic: 'image/heif',
}

export function extensionOf(name = '') {
  const clean = name.split('?')[0].split('#')[0]
  const dot = clean.lastIndexOf('.')
  return dot >= 0 ? clean.slice(dot + 1).toLowerCase() : ''
}

export function mimeFor(name) {
  return MIME_BY_EXT[extensionOf(name)] || 'application/octet-stream'
}

class MediaSession {
  constructor({ id, source, name, password, probe = null }) {
    this.id = id
    this.source = source
    this.name = name
    this.password = password
    this.mime = mimeFor(name)
    this.cache = new Map() // blockIndex -> Buffer, insertion order doubles as LRU
    this.inflight = new Map() // blockIndex -> Promise<Buffer>
    this.probe = probe
    this.prefix = null
    this.key = null
    this.aad = null
    this.blocks = 0
    this.plainSize = 0
  }

  async init() {
    const probe = this.probe || (await this.source.read(0, PROBE_SIZE))
    this.probe = null
    this.prefix = parsePrefix(probe)

    const key = deriveKey(this.password, this.prefix.salt, this.prefix.iterations)
    verifyKeyCheck(this.prefix, key)
    this.key = key
    this.aad = blockAad(this.prefix)

    const { blocks, plainSize } = geometry(this.source.size, this.prefix.bodyOffset)
    this.blocks = blocks
    this.plainSize = plainSize
    return this
  }

  /** Decrypt one body block, deduplicating concurrent requests for it. */
  block(index) {
    if (index < 0 || index >= this.blocks) {
      return Promise.reject(new Error(`Block ${index} out of range`))
    }

    const cached = this.cache.get(index)
    if (cached) {
      // Refresh LRU position.
      this.cache.delete(index)
      this.cache.set(index, cached)
      return Promise.resolve(cached)
    }

    const pending = this.inflight.get(index)
    if (pending) return pending

    const task = (async () => {
      const offset = this.prefix.bodyOffset + index * ENC_BLOCK_SIZE
      const length = Math.min(ENC_BLOCK_SIZE, this.source.size - offset)
      const ciphertext = await this.source.read(offset, length)
      const plaintext = decryptBlock(
        this.key,
        blockNonce(this.prefix.masterIv, index),
        ciphertext,
        this.aad,
      )

      this.cache.set(index, plaintext)
      while (this.cache.size > BLOCK_CACHE_LIMIT) {
        const oldest = this.cache.keys().next().value
        this.cache.delete(oldest)
      }
      return plaintext
    })()

    this.inflight.set(index, task)
    task.finally(() => this.inflight.delete(index)).catch(() => {})
    return task
  }

  /**
   * Yield plaintext bytes for `[start, end]` (inclusive, zero-based).
   *
   * Blocks are decrypted on demand and only the overlapping slice is written,
   * so seeking to the middle of a 4 GiB file costs exactly one block.
   */
  async *readRange(start, end) {
    const first = Math.floor(start / CHUNK_SIZE)
    const last = Math.floor(end / CHUNK_SIZE)

    for (let index = first; index <= last; index += 1) {
      const plain = await this.block(index)
      const blockStart = index * CHUNK_SIZE
      const from = Math.max(0, start - blockStart)
      const to = Math.min(plain.length - 1, end - blockStart)
      if (to < from) continue
      yield plain.subarray(from, to + 1)
    }
  }

  async dispose() {
    this.cache.clear()
    this.inflight.clear()
    await closeQuietly(this.source)
  }
}

/**
 * A file that is not an SKE container.
 *
 * Serves the source bytes unchanged, so an unencrypted movie or photo sitting
 * in the same folder as encrypted entries still plays — no password involved.
 * Same contract as `MediaSession`, which is all the HTTP handler knows about.
 */
class PlainSession {
  constructor({ id, source, name }) {
    this.id = id
    this.source = source
    this.name = name
    this.mime = mimeFor(name)
    this.plainSize = source.size
  }

  async *readRange(start, end) {
    // An upstream without Range support re-downloads the whole file on every
    // read, so there the span is fetched in one go; ranged sources (and local
    // files) get bounded chunks to keep the response buffer small.
    const step = this.source.ranged === false ? end - start + 1 : PLAIN_CHUNK_SIZE
    for (let offset = start; offset <= end; offset += step) {
      const length = Math.min(step, end - offset + 1)
      const chunk = await this.source.read(offset, length)
      if (!chunk.length) return
      yield chunk
    }
  }

  async dispose() {
    await closeQuietly(this.source)
  }
}

/** Live media sessions, keyed by an opaque id handed to the renderer. */
export class MediaRegistry {
  constructor() {
    this.sessions = new Map()
  }

  /**
   * Open `target` for streaming.
   *
   * The first 99 bytes decide the mode: a `SakuraEK` header means a container
   * that has to be decrypted (and therefore needs the media password), anything
   * else is a plain file that is passed through. Detection is done here rather
   * than by the callers so playback, download, subtitles and thumbnails all
   * agree without threading an `encrypted` flag through every IPC channel.
   */
  async open({ target, password, name, headers }) {
    if (!target) throw new Error('缺少文件地址')

    const source = await createSource(target, headers)
    const id = crypto.randomBytes(9).toString('base64url')
    const displayName = name || target

    let session
    try {
      const probe = await source.read(0, PROBE_SIZE)
      if (looksLikeContainer(probe)) {
        if (!password) throw new Error('缺少密码')
        session = new MediaSession({ id, source, name: displayName, password, probe })
        await session.init()
      } else {
        session = new PlainSession({ id, source, name: displayName })
      }
    } catch (err) {
      await closeQuietly(source)
      throw err
    }

    this.sessions.set(id, session)
    return {
      id,
      name: session.name,
      mime: session.mime,
      size: session.plainSize,
      encrypted: session instanceof MediaSession,
      durationHint: null,
    }
  }

  get(id) {
    return this.sessions.get(id)
  }

  async close(id) {
    const session = this.sessions.get(id)
    if (!session) return false
    this.sessions.delete(id)
    await session.dispose()
    return true
  }

  async closeAll() {
    const all = [...this.sessions.values()]
    this.sessions.clear()
    await Promise.all(all.map((s) => s.dispose()))
  }

  /**
   * What the live sessions are actually holding.
   *
   * `bytes` only counts decrypted block buffers: the sources themselves keep no
   * plaintext, so a session with no reads costs a handle and nothing else.
   */
  stats() {
    let blocks = 0
    let bytes = 0
    for (const session of this.sessions.values()) {
      if (!session.cache) continue
      blocks += session.cache.size
      for (const buffer of session.cache.values()) bytes += buffer.length
    }
    return { sessions: this.sessions.size, blocks, bytes }
  }

  /** Drop decrypted blocks but keep the sessions (and their handles) open. */
  clearBlocks() {
    for (const session of this.sessions.values()) {
      session.cache?.clear()
      session.inflight?.clear()
    }
  }
}

/**
 * Parse a single-range `Range` header. Returns null when absent/unparseable and
 * `{ unsatisfiable: true }` when the range lies outside the payload.
 */
export function parseRange(header, size) {
  if (!header) return null
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim())
  if (!match) return null
  const [, rawStart, rawEnd] = match
  if (rawStart === '' && rawEnd === '') return null

  if (rawStart === '') {
    // Suffix form: `bytes=-500` means the last 500 bytes.
    const length = Number(rawEnd)
    if (!Number.isFinite(length) || length <= 0) return { unsatisfiable: true }
    return { start: Math.max(0, size - length), end: size - 1 }
  }

  const start = Number(rawStart)
  const end = rawEnd === '' ? size - 1 : Math.min(Number(rawEnd), size - 1)
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) {
    return { unsatisfiable: true }
  }
  return { start, end }
}

function writeChunk(res, chunk) {
  // Honour backpressure: a 4K stream would otherwise queue the whole file in
  // memory while the renderer decodes.
  return new Promise((resolve) => {
    if (res.write(chunk)) resolve()
    else res.once('drain', resolve)
  })
}

export function createMediaHandler(registry) {
  return async function handleMedia(req, res, url) {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD' })
      res.end()
      return
    }

    const id = decodeURIComponent(url.pathname.slice('/media/'.length))
    const session = registry.get(id)
    if (!session) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('会话不存在或已关闭')
      return
    }

    const size = session.plainSize
    // Images are re-used by `<img>` tiles when the user comes back to a folder,
    // so a private cache entry saves a full re-stream. Video and audio are
    // open-ended range sessions and must never be cached.
    const cacheControl = session.mime.startsWith('image/') ? 'private, max-age=300' : 'no-store'
    const baseHeaders = {
      'Content-Type': session.mime,
      'Accept-Ranges': 'bytes',
      'Cache-Control': cacheControl,
    }

    if (size === 0) {
      res.writeHead(200, { ...baseHeaders, 'Content-Length': 0 })
      res.end()
      return
    }

    const range = parseRange(req.headers.range, size)
    if (range?.unsatisfiable) {
      res.writeHead(416, {
        ...baseHeaders,
        'Content-Range': `bytes */${size}`,
      })
      res.end()
      return
    }

    const start = range ? range.start : 0
    const end = range ? range.end : size - 1
    const length = end - start + 1

    res.writeHead(range ? 206 : 200, {
      ...baseHeaders,
      'Content-Length': length,
      ...(range ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}),
    })

    if (req.method === 'HEAD') {
      res.end()
      return
    }

    try {
      for await (const chunk of session.readRange(start, end)) {
        if (res.destroyed) return
        await writeChunk(res, chunk)
      }
      res.end()
    } catch (err) {
      // The status line is already out, so the only honest signal left is a
      // truncated response — which is exactly what a player treats as failure.
      console.error('[media] stream failed:', err.message)
      res.destroy(err)
    }
  }
}
