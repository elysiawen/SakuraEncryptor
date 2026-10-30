/**
 * OS media controls for the shell's audio transport.
 *
 * Chromium turns `navigator.mediaSession` into whatever the platform calls its
 * transport panel: System Media Transport Controls on Windows, Now Playing on
 * macOS, MPRIS2 on Linux. No native module is involved, which is why this file
 * is plain DOM code.
 *
 * Only the shell may publish a session. It is the one renderer that owns the
 * audio element (see `App.vue`); the music window is a remote control with no
 * stream of its own, and a second session there would be a phantom entry in the
 * system panel that nothing can drive.
 */
import { close, next, player, prev, seekTo, togglePlay } from './player.js'

/** Where the system panel's seek buttons jump when it does not say itself. */
const SEEK_STEP = 10
/** System panels draw artwork small; a 1:1 tile at 256 px is plenty and cheap. */
const ARTWORK_SIZE = 256

/** Every action the three platforms can deliver. Unsupported ones throw. */
const ACTIONS = [
  'play',
  'pause',
  'stop',
  'previoustrack',
  'nexttrack',
  'seekbackward',
  'seekforward',
  'seekto',
]

/** Cover src → `MediaImage`, so rasterising happens once per track. */
const artworkCache = new Map()
/** In-flight rasterisation per src: a re-published track must not do it twice. */
const artworkPending = new Map()

let handlersBound = false

function session() {
  // Absent in a non-secure context; the app runs on loopback http, which counts
  // as trustworthy, so this is really just a guard for odd builds.
  return typeof navigator !== 'undefined' ? navigator.mediaSession || null : null
}

/**
 * Wire the keys once.
 *
 * A guarded action is deliberately idempotent: some panels send `play` while
 * already playing (focus changes, for instance), and toggling there would pause
 * the track the user just asked to play.
 */
function bindHandlers(ms) {
  if (handlersBound) return
  handlersBound = true

  const handlers = {
    play: () => {
      if (!player.playing) togglePlay()
    },
    pause: () => {
      if (player.playing) togglePlay()
    },
    // Stop is the same deal as the sidebar's: the remote has nothing left to
    // drive, so it goes away with the stream.
    stop: () => {
      close().catch(() => {})
      window.ske?.player?.closeWindow?.().catch(() => {})
    },
    previoustrack: () => prev().catch(() => {}),
    nexttrack: () => next().catch(() => {}),
    seekbackward: (details) => {
      seekTo((Number(player.currentTime) || 0) - (Number(details?.seekOffset) || SEEK_STEP))
    },
    seekforward: (details) => {
      seekTo((Number(player.currentTime) || 0) + (Number(details?.seekOffset) || SEEK_STEP))
    },
    seekto: (details) => {
      if (Number.isFinite(details?.seekTime)) seekTo(Number(details.seekTime))
    },
  }

  for (const [action, handler] of Object.entries(handlers)) {
    try {
      ms.setActionHandler(action, handler)
    } catch {
      // This platform does not offer that button; the rest still work.
    }
  }
}

/** Publish the metadata a freshly opened track deserves. */
function applyMetadata(ms, track, artwork) {
  try {
    ms.metadata = new MediaMetadata({
      title: track.title || '',
      artist: track.artist || '',
      album: track.album || '',
      artwork,
    })
  } catch {
    // A build without MediaMetadata keeps the transport, minus the labels.
  }
}

/**
 * Square copy of the cover, as a data URL.
 *
 * The tag reader hands out `blob:` URLs, but the system panel fetches artwork
 * through the browser process rather than this renderer, where a blob may not
 * resolve — which shows up as a title with no picture. Rasterising also keeps
 * an 8 MB embedded cover from being sent to the shell's media flyout as is.
 */
function rasterize(src) {
  return new Promise((resolve) => {
    const image = new Image()
    image.decoding = 'async'
    image.onload = () => {
      try {
        const side = ARTWORK_SIZE
        const canvas = document.createElement('canvas')
        canvas.width = side
        canvas.height = side
        const ctx = canvas.getContext('2d')
        if (!ctx) return resolve('')
        // Cover-crop: the panel draws a square, and letterboxing a wide scan
        // would read as a rendering bug rather than as the album's shape.
        const scale = Math.max(side / image.width, side / image.height)
        const width = image.width * scale
        const height = image.height * scale
        ctx.drawImage(image, (side - width) / 2, (side - height) / 2, width, height)
        resolve(canvas.toDataURL('image/png'))
      } catch {
        resolve('')
      }
    }
    image.onerror = () => resolve('')
    image.src = src
  })
}

/** Cached artwork for one cover; falls back to the original URL on failure. */
function artworkFor(src) {
  if (!src) return Promise.resolve(null)
  const cached = artworkCache.get(src)
  if (cached) return Promise.resolve(cached)
  if (artworkPending.has(src)) return artworkPending.get(src)

  const task = rasterize(src)
    .then((dataUrl) => {
      const image = dataUrl
        ? { src: dataUrl, sizes: `${ARTWORK_SIZE}x${ARTWORK_SIZE}`, type: 'image/png' }
        : { src }
      artworkCache.set(src, image)
      return image
    })
    .catch(() => null)
    .finally(() => artworkPending.delete(src))

  artworkPending.set(src, task)
  return task
}

/** Only what the platform can actually draw: a finite, sanely bounded range. */
function publishPosition(ms) {
  // A live-ish stream reports `Infinity`; the panel would reject that pair on
  // every timeupdate, so treat it like "no range" instead.
  const duration = Number(player.duration)
  if (!Number.isFinite(duration) || duration <= 0) {
    try {
      ms.setPositionState(null)
    } catch {
      // Nothing to clear on a session that never had a range.
    }
    return
  }

  try {
    ms.setPositionState({
      duration,
      position: Math.min(Math.max(Number(player.currentTime) || 0, 0), duration),
      playbackRate: 1,
    })
  } catch {
    // The element's duration moved between the read and the call; the next
    // timeupdate publishes a consistent pair.
  }
}

/** Hand the current track to the system panel. */
export function publishTrack(track) {
  const ms = session()
  if (!ms) return

  // `close()` empties both the URL and the name, so this is the same test as
  // "nothing is playing".
  if (!track?.title || !player.url) {
    clearMediaSession()
    return
  }

  bindHandlers(ms)

  const url = player.url
  const cached = artworkCache.get(track.cover)
  applyMetadata(ms, track, cached ? [cached] : [])

  if (track.cover && !cached) {
    // Covers are read asynchronously after playback starts; until the raster is
    // ready the panel shows the labels only, then gets the picture.
    artworkFor(track.cover).then((image) => {
      // The user may have skipped on while the cover decoded.
      if (!image || player.url !== url) return
      applyMetadata(ms, track, [image])
    })
  }
}

/** Playback state and timeline: cheap, and called on every timeupdate. */
export function publishTransport() {
  const ms = session()
  if (!ms) return

  if (!player.url || player.kind !== 'audio') {
    clearMediaSession()
    return
  }

  bindHandlers(ms)
  ms.playbackState = player.playing ? 'playing' : 'paused'
  publishPosition(ms)
}

/**
 * Empty the panel.
 *
 * Locking the app and stopping a track both land here: leaving the last song's
 * title on the lock screen would leak what the user is listening to.
 */
export function clearMediaSession() {
  const ms = session()
  if (!ms) return

  ms.metadata = null
  ms.playbackState = 'none'
  try {
    ms.setPositionState(null)
  } catch {
    // Same as above: no range, nothing to clear.
  }
}

/** Keys stay bound while the shell lives; this is the teardown for a reload. */
export function unbindMediaSession() {
  const ms = session()
  if (!ms) return

  clearMediaSession()
  if (!handlersBound) return
  handlersBound = false

  for (const action of ACTIONS) {
    try {
      ms.setActionHandler(action, null)
    } catch {
      // Unknown action on this platform: it was never bound either.
    }
  }
}
