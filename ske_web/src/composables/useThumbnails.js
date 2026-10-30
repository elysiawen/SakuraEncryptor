/**
 * useThumbnails — build thumbnails for image and video entries.
 *
 * Images are served through the service worker decrypt proxy (remote) or the
 * /ske-local route (local folders). Sources are tried cheapest-and-sharpest
 * first:
 *
 *   - Containers carry a 512px preview in their encrypted thumbnail envelope.
 *     `/ske-thumb` decrypts just that envelope (≈40 KB), so the tile never
 *     pays for a 1 MiB body block and stays sharp.
 *   - When the envelope holds no preview (`THUMB_LEN = 0`), a JPEG's embedded
 *     EXIF preview is used for files above 2 MiB, where skipping the body
 *     actually saves traffic; smaller files just use the full image, which is
 *     both sharper and no more expensive.
 *   - Plain files have no envelope at all, so the server's own thumbnail is
 *     used when present. `/api/fs/list` already returns it per entry (AList or
 *     the storage driver generates the URL), so a grid of photos needs no
 *     request per tile; `fs/get` is only consulted for entries the listing did
 *     not decorate. Either way it is an ordinary image URL, which keeps a
 *     folder of multi-megabyte originals from downloading every photo.
 *
 * Videos are loaded into an offscreen <video>, seeked to the middle and
 * captured to a data URL. The poster request is flagged so the service worker
 * caps each range response at 1 MiB instead of 5 MiB, and a server-provided
 * poster is preferred when the storage can make one.
 *
 * Callers load lazily (IntersectionObserver), requests run through small
 * concurrency queues, and results are cached for the session.
 */
import { getFileInfo } from './useAList.js'
import { buildDecryptProxyUrl } from './useDecryptProxy.js'
import { getFileType } from './useFileDetection.js'

const IMAGE_CONCURRENCY = 3
const VIDEO_CONCURRENCY = 1
const MAX_CACHE = 400
const POSTER_MAX_WIDTH = 320
const POSTER_TIMEOUT_MS = 15000
const THUMB_PROBE_BYTES = 128 * 1024

/** Ciphertext block size: AES-GCM cannot decrypt a partial block. */
const ENC_BLOCK_BYTES = 1024 * 1024 + 16
/** Below this, fetching only the first block costs the same as the whole file. */
const EXIF_FALLBACK_MIN_BYTES = 2 * ENC_BLOCK_BYTES

const cache = new Map() // key -> { value, blob }
const inflight = new Map()

function createQueue(concurrency) {
  const queue = []
  let active = 0

  const pump = () => {
    while (active < concurrency && queue.length > 0) {
      const job = queue.shift()
      active += 1
      job
        .run()
        .then(job.resolve, job.reject)
        .finally(() => {
          active -= 1
          pump()
        })
    }
  }

  return (run) =>
    new Promise((resolve, reject) => {
      queue.push({ run, resolve, reject })
      pump()
    })
}

const imageQueue = createQueue(IMAGE_CONCURRENCY)
const videoQueue = createQueue(VIDEO_CONCURRENCY)

function remember(key, entry) {
  cache.set(key, entry)
  while (cache.size > MAX_CACHE) {
    const oldestKey = cache.keys().next().value
    const oldest = cache.get(oldestKey)
    if (oldest?.blob) URL.revokeObjectURL(oldest.value)
    cache.delete(oldestKey)
  }
  return entry
}

/** A `.ske` entry (or one whose name decrypted): the server stores ciphertext. */
function isEncryptedEntry(item) {
  return item.encrypted ?? (item.encName || '').endsWith('.ske')
}

/** 'image' | 'video' | null — what sort of thumbnail [item] can have. */
export function thumbnailKind(item) {
  if (!item || item.is_dir) return null
  const type = getFileType(item.decName || item.encName)
  return type === 'image' || type === 'video' ? type : null
}

export function isImageEntry(item) {
  return thumbnailKind(item) === 'image'
}

export function isVideoEntry(item) {
  return thumbnailKind(item) === 'video'
}

/**
 * Resolve the URLs needed to display [item], or null when it cannot be reached.
 *
 * @returns {Promise<{playback: string, upstream: string|null, encrypted: boolean, size: number, thumb: string|null}|null>}
 */
async function sourceInfo(mode, item, absPath) {
  const encrypted = isEncryptedEntry(item)

  if (mode === 'local') {
    // The service worker already serves local files (decrypting as needed).
    return {
      playback: `/ske-local/${absPath}`,
      upstream: null,
      encrypted,
      isLocal: true,
      size: item.size || 0,
      thumb: null,
    }
  }

  const info = await getFileInfo(absPath)
  if (!info.url) return null

  if (!encrypted) {
    return {
      playback: info.url,
      upstream: info.url,
      encrypted: false,
      isLocal: false,
      size: info.size || 0,
      // Server-side preview, when the storage can produce one.
      thumb: info.thumb || null,
    }
  }

  // The proxy decrypts with the key handed over at login; re-send it in case
  // the worker was restarted since.
  const password = sessionStorage.getItem('ske_password')
  if (password && navigator.serviceWorker?.controller) {
    navigator.serviceWorker.controller.postMessage({ type: 'SET_PASSWORD', password })
  }
  return {
    playback: buildDecryptProxyUrl(info.url, info.size),
    upstream: info.url,
    encrypted: true,
    isLocal: false,
    size: info.size || 0,
    // A container previews from its own envelope; the server sees ciphertext.
    thumb: null,
  }
}

/** `/ske-thumb` URL for the stored preview, or null when the entry is not a container. */
function thumbnailEndpoint(info, absPath) {
  if (!info.encrypted) return null
  if (info.upstream) return `/ske-thumb?url=${encodeURIComponent(info.upstream)}`
  return `/ske-thumb?local=${encodeURIComponent(absPath.replace(/^\/+/, ''))}`
}

/** Fetch the encrypted preview; null when the container has none (or decoding failed). */
async function fetchEnvelopeThumbnail(endpoint) {
  try {
    // The worker answers 404 when the container carries no preview.
    const res = await fetch(endpoint)
    if (!res.ok) return null
    const blob = await res.blob()
    return blob.size > 0 ? blob : null
  } catch {
    return null
  }
}

// ── EXIF preview extraction ──────────────────────────────────────────

async function fetchPrefix(url, length) {
  const res = await fetch(url, { headers: { Range: `bytes=0-${length - 1}` } })
  if (!res.ok && res.status !== 206) throw new Error(`range failed: ${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}

/**
 * Pull the thumbnail JPEG out of an EXIF APP1 segment.
 *
 * Only the first [THUMB_PROBE_BYTES] are inspected, which is enough for the
 * Exif IFD and the embedded preview in virtually every camera JPEG.
 *
 * @returns {Uint8Array|null} the preview JPEG bytes, or null when absent
 */
function parseExifThumbnail(bytes) {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null

  // Walk the JPEG marker segments until APP1/Exif (or the scan data).
  let offset = 2
  let tiff = null
  while (offset + 4 <= bytes.length) {
    if (bytes[offset] !== 0xff) break
    const marker = bytes[offset + 1]

    // Standalone markers carry no length.
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2
      continue
    }
    if (marker === 0xd8 || marker === 0xd9) {
      offset += 2
      continue
    }
    if (marker === 0xda) break // start of scan — no more metadata

    const size = (bytes[offset + 2] << 8) | bytes[offset + 3]
    if (size < 2) break

    if (marker === 0xe1 && size >= 8) {
      const p = offset + 4
      if (
        bytes[p] === 0x45 && bytes[p + 1] === 0x78 &&
        bytes[p + 2] === 0x69 && bytes[p + 3] === 0x66
      ) {
        tiff = p + 6 // skip "Exif\0\0"
        break
      }
    }
    offset += 2 + size
  }
  if (tiff == null || tiff + 8 > bytes.length) return null

  const view = new DataView(bytes.buffer, bytes.byteOffset + tiff)
  const little = bytes[tiff] === 0x49 && bytes[tiff + 1] === 0x49
  const u16 = (o) => (little ? view.getUint16(o, true) : view.getUint16(o, false))
  const u32 = (o) => (little ? view.getUint32(o, true) : view.getUint32(o, false))

  if (view.byteLength < 8 || u16(2) !== 42) return null

  // IFD0 lives at the TIFF header's offset; IFD1 (which holds the preview)
  // is linked from the "next IFD" pointer at the end of IFD0.
  const ifd0 = u32(4)
  const readNextIfd = (pos) => {
    if (pos + 2 > view.byteLength) return 0
    const count = u16(pos)
    const next = pos + 2 + count * 12
    if (next + 4 > view.byteLength) return 0
    return u32(next)
  }

  const ifd1 = readNextIfd(ifd0)
  if (!ifd1 || ifd1 + 2 > view.byteLength) return null

  const count = u16(ifd1)
  let thumbOffset = 0
  let thumbLength = 0
  for (let i = 0; i < count; i += 1) {
    const entry = ifd1 + 2 + i * 12
    if (entry + 12 > view.byteLength) break
    const tag = u16(entry)
    if (tag === 0x0201) thumbOffset = u32(entry + 8) // JPEGInterchangeFormat
    else if (tag === 0x0202) thumbLength = u32(entry + 8) // ...Length
  }

  if (!thumbOffset || !thumbLength) return null
  const start = tiff + thumbOffset
  const end = start + thumbLength
  if (end > bytes.length) return null

  const preview = bytes.subarray(start, end)
  if (preview.length < 4 || preview[0] !== 0xff || preview[1] !== 0xd8) return null
  return preview
}

async function imageEntry(info, item, absPath) {
  // Preferred: the 512px preview stored in the thumbnail envelope.
  const endpoint = thumbnailEndpoint(info, absPath)
  if (endpoint) {
    const blob = await fetchEnvelopeThumbnail(endpoint)
    if (blob) return { value: URL.createObjectURL(blob), blob: true }
  }

  // Plain file: the server's thumbnail is already resized, so a folder of
  // full-size photos costs kilobytes instead of megabytes. The original stays
  // as a fallback in case the URL turns out to be dead (expired or unsigned).
  if (info.thumb) {
    return { value: info.thumb, blob: false, imageFallback: info.playback }
  }

  // A container without a stored preview falls back to the JPEG's own EXIF
  // preview, but only for remote files large enough that skipping the first
  // block really saves bandwidth. On small files that block *is* the file
  // (blurrier for free), and local reads are free.
  const ext = (item.decName || item.encName || '').split('.').pop()?.toLowerCase()
  const exifFallbackWorthIt = !info.isLocal && info.encrypted && info.size > EXIF_FALLBACK_MIN_BYTES
  if (exifFallbackWorthIt && (ext === 'jpg' || ext === 'jpeg')) {
    try {
      const prefix = await fetchPrefix(info.playback, THUMB_PROBE_BYTES)
      const preview = parseExifThumbnail(prefix)
      if (preview) {
        const blobUrl = URL.createObjectURL(new Blob([preview], { type: 'image/jpeg' }))
        return { value: blobUrl, blob: true }
      }
    } catch {
      // No usable preview (or the range/CORS request failed): fall through to
      // the full image, which always renders.
    }
  }

  // Full image: always sharp, and the last resort when no preview exists.
  return { value: info.playback, blob: false }
}

// ── Video posters ────────────────────────────────────────────────────

function captureVideoPoster(url) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true
    video.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0'
    video.crossOrigin = 'anonymous'

    let settled = false
    const timer = setTimeout(() => fail(new Error('poster timeout')), POSTER_TIMEOUT_MS)

    function cleanup() {
      clearTimeout(timer)
      video.onloadedmetadata = null
      video.onseeked = null
      video.onerror = null
      video.removeAttribute('src')
      video.load()
      video.remove()
    }

    function fail(err) {
      if (settled) return
      settled = true
      cleanup()
      reject(err)
    }

    video.onerror = () => fail(new Error('video decode failed'))

    video.onloadedmetadata = () => {
      // 30% in: past the intro, still representative, and the same point the
      // encrypt-time sampler uses so both paths agree. A range request jumps
      // straight there instead of streaming from the start.
      const duration = Number.isFinite(video.duration) ? video.duration : 0
      const target = duration > 0 ? duration * 0.3 : 0.5
      try {
        video.currentTime = target
      } catch (err) {
        fail(err)
      }
    }

    video.onseeked = () => {
      if (settled) return
      try {
        const width = video.videoWidth
        const height = video.videoHeight
        if (!width || !height) throw new Error('no video track')

        const scale = Math.min(1, POSTER_MAX_WIDTH / width)
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(width * scale))
        canvas.height = Math.max(1, Math.round(height * scale))
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height)
        const dataUrl = canvas.toDataURL('image/jpeg', 0.72)

        settled = true
        cleanup()
        resolve(dataUrl)
      } catch (err) {
        fail(err)
      }
    }

    document.body.appendChild(video)
    video.src = url
  })
}

/** Cache key: one entry per kind, mode and stored path. */
function cacheKey(kind, mode, absPath, suffix = '') {
  return `${kind}:${mode}:${absPath}${suffix}`
}

/**
 * Build the thumbnail entry for [item].
 *
 * @param {boolean} allowUpstream use the server's own thumbnail when offered
 * @returns {Promise<{value: string, blob: boolean, imageFallback?: string, lazyImageFallback?: boolean, retryWithoutUpstream?: boolean}|null>}
 */
async function loadEntry(kind, mode, item, absPath, allowUpstream) {
  // The listing already carries a preview URL for plain entries, so a grid of
  // photos costs no request per tile. The playback URL stays lazy: it is only
  // fetched when a thumbnail is missing, or when the one we got fails to load.
  if (allowUpstream && mode !== 'local' && item.thumb && !isEncryptedEntry(item)) {
    return kind === 'image'
      ? { value: item.thumb, blob: false, lazyImageFallback: true }
      : { value: item.thumb, blob: false, retryWithoutUpstream: true }
  }

  const info = await sourceInfo(mode, item, absPath)
  if (!info) return null
  const source = allowUpstream ? info : { ...info, thumb: null }

  if (kind === 'image') {
    return imageEntry(source, item, absPath)
  }

  // Containers may already carry a sampled frame: reuse it instead of
  // spinning up a decoder and pulling at least a megabyte of ciphertext.
  const endpoint = thumbnailEndpoint(source, absPath)
  if (endpoint) {
    const blob = await fetchEnvelopeThumbnail(endpoint)
    if (blob) return { value: URL.createObjectURL(blob), blob: true }
  }

  // A server-made poster costs one request; capturing ours costs a decoder
  // plus a range read, so prefer theirs and retry without it if it is dead.
  if (source.thumb) {
    return { value: source.thumb, blob: false, retryWithoutUpstream: true }
  }

  // The flag lets the service worker cap each range response, so the media
  // element cannot pre-read megabytes for a single frame.
  const posterUrl = source.playback + (source.playback.includes('?') ? '&' : '?') + 'poster=1'
  const dataUrl = await captureVideoPoster(posterUrl)
  return dataUrl ? { value: dataUrl, blob: false } : null
}

/** Queue one resolution and cache its result under [key]. */
function resolveInto(key, kind, mode, item, absPath, allowUpstream) {
  const queue = kind === 'video' ? videoQueue : imageQueue
  return queue(() => loadEntry(kind, mode, item, absPath, allowUpstream))
    .then((entry) => (entry ? remember(key, entry) : null))
    .catch(() => null)
    .then((entry) => entry?.value ?? null)
}

/**
 * Resolve the thumbnail for [item] — an image URL or a captured video frame.
 *
 * @param {'alist'|'local'} mode
 * @param {object} item entry as built by FileBrowser
 * @param {string} absPath full stored path (encrypted name for remote entries)
 * @returns {Promise<string|null>} null when the entry has no thumbnail
 */
export function resolveThumbnail(mode, item, absPath) {
  const kind = thumbnailKind(item)
  if (!kind) return Promise.resolve(null)

  const key = cacheKey(kind, mode, absPath)
  if (cache.has(key)) return Promise.resolve(cache.get(key).value)
  if (inflight.has(key)) return inflight.get(key)

  const task = resolveInto(key, kind, mode, item, absPath, true)
  inflight.set(key, task)
  task.finally(() => inflight.delete(key))
  return task
}

/**
 * Second URL for a tile whose preview failed to load in the browser.
 *
 * A server thumbnail can be unavailable to the page even when the API handed
 * one out (expired signature, hotlink protection, a driver that only signs for
 * some clients). Images fall back to the original; videos re-run the capture
 * path without asking the server again. Returns null when there is nothing
 * better, which leaves the tile on its file icon.
 */
export async function resolveThumbnailFallback(mode, item, absPath) {
  const kind = thumbnailKind(item)
  if (!kind) return null

  const entry = cache.get(cacheKey(kind, mode, absPath))
  if (!entry) return null

  // Promote whatever worked, so revisiting the folder does not repeat the
  // failing request that led here.
  if (entry.imageFallback) {
    entry.value = entry.imageFallback
    entry.imageFallback = null
    return entry.value
  }

  // Its thumbnail came from the listing, so the original still needs a lookup.
  if (entry.lazyImageFallback) {
    entry.lazyImageFallback = false
    try {
      const info = await getFileInfo(absPath)
      if (info.url) {
        entry.value = info.url
        return info.url
      }
    } catch {
      // Unreachable: the tile keeps its icon.
    }
    return null
  }

  if (!entry.retryWithoutUpstream) return null

  const key = cacheKey(kind, mode, absPath, ':plain')
  if (cache.has(key)) return cache.get(key).value
  if (inflight.has(key)) return inflight.get(key)

  const task = resolveInto(key, kind, mode, item, absPath, false).then((value) => {
    if (value) {
      entry.value = value
      entry.retryWithoutUpstream = false
    }
    return value
  })
  inflight.set(key, task)
  task.finally(() => inflight.delete(key))
  return task
}
