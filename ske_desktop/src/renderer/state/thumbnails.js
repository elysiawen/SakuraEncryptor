/**
 * Thumbnail resolution, ported from the web client
 * (`ske_web/src/composables/useThumbnails.js`).
 *
 * The cheap source is the `/thumb/...` envelope: containers carry a 512px preview
 * that costs ≈40 KB to decrypt, so tiles use it whenever it exists. When a
 * container has no preview the web client does not give up, and neither does
 * this one:
 *
 *   - image  → use the decrypted original directly, the browser scales it;
 *   - video  → load it offscreen, seek to 30% and capture a frame to JPEG.
 *
 * 30% matches the encrypt-time sampler, so a generated poster and a stored one
 * show the same moment. Queues and the cache size also mirror the web client:
 * image work runs three at a time, video captures one at a time (each is a
 * decoder plus a seek), and every result lives in a session-scoped LRU.
 *
 * Wall-clock cost is real: a fallback image streams its whole file and a video
 * poster decodes a chunk of it. That is why this runs behind an error handler
 * (only for entries whose envelope is missing) instead of for every tile.
 */
import { reactive } from 'vue'
import { cloud } from './cloud.js'

const MAX_CACHE = 300
/** Decrypt sessions kept open for `<img>` fallbacks; each holds a file handle. */
const MAX_SESSIONS = 32
const POSTER_MAX_WIDTH = 320
const POSTER_TIMEOUT_MS = 15_000
const POSTER_SEEK_RATIO = 0.3

const cache = new Map() // key -> url
const inflight = new Map() // key -> Promise<string>
/** key -> media session id, kept alive while an <img> is pointed at it. */
const openSessions = new Map()

function createQueue(limit) {
  const queue = []
  let active = 0

  const pump = () => {
    while (active < limit && queue.length) {
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

const imageQueue = createQueue(3)
const videoQueue = createQueue(1)

function keyOf(entry) {
  return `${cloud.sessionId}|${entry.path}`
}

function closeSession(key) {
  const mediaId = openSessions.get(key)
  if (!mediaId) return
  openSessions.delete(key)
  window.ske.media.close(mediaId).catch(() => {})
}

function trimSessions() {
  while (openSessions.size > MAX_SESSIONS) {
    const oldest = openSessions.keys().next().value
    closeSession(oldest)
  }
}

function remember(key, url) {
  cache.set(key, url)
  while (cache.size > MAX_CACHE) {
    const oldest = cache.keys().next().value
    closeSession(oldest)
    cache.delete(oldest)
  }
  return url
}

/**
 * Per-tile state, kept for the whole app session.
 *
 * This used to live in `BrowseView` and was cleared on every folder change and
 * unmount, which made each re-entry flash tiles back through their placeholder
 * and re-decode previews that had already been fetched. Keys embed the session
 * id (see `keyOf`), so a different account can never read another one's
 * bookkeeping, and a disconnect clears everything here.
 */
export const thumbsLoaded = reactive(new Set())
export const thumbsFailed = reactive(new Set())
export const fallbackThumbs = reactive(new Map())

/** URL a tile should point at: resolved fallback, envelope, or nothing. */
export function thumbSource(entry) {
  const key = keyOf(entry)
  const fallback = fallbackThumbs.get(key)
  if (fallback) return fallback
  if (thumbsFailed.has(key)) return ''
  return entry.thumbUrl || ''
}

export function isThumbLoaded(entry) {
  return thumbsLoaded.has(keyOf(entry))
}

export function markThumbLoaded(entry) {
  thumbsLoaded.add(keyOf(entry))
}

/** Cached thumbnail for an entry, or '' when none has been resolved yet. */
export function cachedThumbnail(entry) {
  return cache.get(keyOf(entry)) || ''
}

/**
 * Renderer-side preview bookkeeping, for the settings page.
 *
 * `urls` sizes the URL cache, `fallbacks` the previews that needed a generated
 * image, and `tiles` the entries already painted once (which is why they do not
 * fade in again after navigation).
 */
export function thumbnailStats() {
  return { urls: cache.size, fallbacks: fallbackThumbs.size, tiles: thumbsLoaded.size }
}

/** Drop one cached preview and its decrypt session, when its URL went stale. */
function forgetCached(key) {
  cache.delete(key)
  closeSession(key)
}

/**
 * Handle an `<img>` error: try the fallback once, then keep the glyph.
 *
 * A cached fallback URL can outlive its decrypt session — the registry closes
 * the oldest ones — so a failed URL is forgotten before resolving again instead
 * of being reused dead.
 */
export async function noteThumbFailure(entry) {
  const key = keyOf(entry)
  thumbsLoaded.delete(key)

  if (fallbackThumbs.has(key)) {
    fallbackThumbs.delete(key)
    forgetCached(key)
  }

  thumbsFailed.add(key)

  const url = await resolveThumbnail(entry)
  if (!url) return

  thumbsFailed.delete(key)
  fallbackThumbs.set(key, url)
}

/**
 * Resolve and remember the preview for an entry whose `/thumb/` envelope is
 * missing: plain files carry no preview at all, so nothing would ever trigger a
 * fallback for them otherwise.
 */
export async function ensureFallback(entry) {
  const key = keyOf(entry)
  if (fallbackThumbs.get(key)) return
  const url = await resolveThumbnail(entry)
  if (url) fallbackThumbs.set(key, url)
}

/**
 * Drop every cached preview and its decrypt session.
 *
 * Called when the cloud session changes (disconnect / profile switch): the keys
 * are session-scoped, so nothing here can be reused afterwards.
 */
export function resetThumbnailCache() {
  for (const key of [...openSessions.keys()]) closeSession(key)
  cache.clear()
  thumbsLoaded.clear()
  thumbsFailed.clear()
  fallbackThumbs.clear()
}

async function openMedia(entry) {
  const res = await window.ske.media.openCloud({
    sessionId: cloud.sessionId,
    path: entry.path,
    name: entry.name,
  })
  if (!res?.ok) throw new Error(res?.error || '无法解密文件')
  return res
}

function captureVideoPoster(url) {
  return new Promise((resolve) => {
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true
    video.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0'

    let settled = false
    const timer = setTimeout(() => finish(''), POSTER_TIMEOUT_MS)

    function cleanup() {
      clearTimeout(timer)
      video.onloadedmetadata = null
      video.onseeked = null
      video.onerror = null
      video.removeAttribute('src')
      video.load()
      video.remove()
    }

    function finish(value) {
      if (settled) return
      settled = true
      cleanup()
      resolve(value)
    }

    video.onerror = () => finish('')

    video.onloadedmetadata = () => {
      const duration = Number.isFinite(video.duration) ? video.duration : 0
      try {
        video.currentTime = duration > 0 ? duration * POSTER_SEEK_RATIO : 0.5
      } catch {
        finish('')
      }
    }

    video.onseeked = () => {
      try {
        const width = video.videoWidth
        const height = video.videoHeight
        if (!width || !height) {
          finish('')
          return
        }

        const scale = Math.min(1, POSTER_MAX_WIDTH / width)
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(width * scale))
        canvas.height = Math.max(1, Math.round(height * scale))
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height)
        finish(canvas.toDataURL('image/jpeg', 0.72))
      } catch {
        finish('')
      }
    }

    document.body.appendChild(video)
    video.src = url
  })
}

async function resolveOne(entry) {
  const key = keyOf(entry)

  if (entry.kind === 'image') {
    const info = await openMedia(entry)
    openSessions.set(key, info.id)
    trimSessions()
    return remember(key, info.url)
  }

  if (entry.kind === 'video') {
    const info = await openMedia(entry)
    try {
      const poster = await captureVideoPoster(info.url)
      return poster ? remember(key, poster) : ''
    } finally {
      // The frame is already a data URL; the stream is no longer needed.
      await window.ske.media.close(info.id).catch(() => {})
    }
  }

  return ''
}

/**
 * Fallback thumbnail for an entry whose envelope preview is missing.
 * Resolves to '' when nothing can be produced (the tile keeps its glyph).
 */
export function resolveThumbnail(entry) {
  if (!entry || entry.isDir || entry.locked) return Promise.resolve('')
  if (entry.kind !== 'image' && entry.kind !== 'video') return Promise.resolve('')

  const key = keyOf(entry)
  const cached = cache.get(key)
  if (cached) return Promise.resolve(cached)
  if (inflight.has(key)) return inflight.get(key)

  const queue = entry.kind === 'video' ? videoQueue : imageQueue
  const task = queue(() => resolveOne(entry))
    .catch(() => '')
    .then((url) => url || '')

  inflight.set(key, task)
  task.finally(() => inflight.delete(key))
  return task
}
