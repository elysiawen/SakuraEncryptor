/**
 * Playback state shared by the shell and the player window.
 *
 * Video and images play in the dedicated player window (`StageView`). Audio is
 * different: it plays in the shell itself, so closing the music page keeps the
 * track going and the sidebar keeps a transport bar. That also means the audio
 * element lives in `App.vue` and is registered here, which is why the control
 * helpers below work on a module-level reference.
 *
 * Only one stream is open at a time. Opening a new file closes the previous
 * registry session, so decrypted blocks for media nobody watches are released.
 */
import { reactive } from 'vue'

export const player = reactive({
  id: '',
  url: '',
  name: '',
  mime: '',
  size: 0,
  kind: 'video',
  /** Play order: which track comes after this one. */
  mode: 'list',
  /** The entry the stream came from: needed to find sibling subtitles. */
  item: null,

  loading: false,
  error: '',

  playing: false,
  currentTime: 0,
  duration: 0,
  volume: 1,
  muted: false,

  /** Mini-playlist: same folder, same media kind. */
  playlist: [],
  index: -1,
})

function kindFromMime(mime = '') {
  if (mime.startsWith('image/')) return 'image'
  if (mime.startsWith('audio/')) return 'audio'
  return 'video'
}

export function hasMedia() {
  return !!player.id
}

/**
 * Sessions opened ahead of time for the next picture.
 *
 * Opening one costs a PBKDF2 derivation and the first range reads, which is the
 * "正在解密…" pause between pages. Warming the next entry while the current one
 * is on screen makes the switch a cache hit instead.
 */
const prefetched = new Map() // entry key -> { id, url, mime, size, name }
/** Entries whose warmed session is still being opened, so two watcher ticks
 * cannot open (and leak) a second one for the same picture. */
const opening = new Set()

/**
 * The prefetched image kept referenced while it is on screen, so Chromium holds
 * on to its decoded bitmap instead of freeing it between frames.
 */
let heldWarmedImage = null

function entryKey(item) {
  if (!item) return ''
  return `${item.mode || ''}|${item.sessionId || ''}|${item.path || item.name || ''}`
}

function takePrefetch(item) {
  const key = entryKey(item)
  const warmed = prefetched.get(key)
  if (!warmed) return null
  prefetched.delete(key)
  return warmed
}

function closePrefetch(key) {
  const warmed = prefetched.get(key)
  if (!warmed) return
  prefetched.delete(key)
  window.ske?.media?.close(warmed.id).catch(() => {})
}

/** Close every idle warmed session; the stage window and lock both land here. */
function clearPrefetches() {
  const ids = [...prefetched.values()].map((entry) => entry.id)
  prefetched.clear()
  for (const id of ids) window.ske?.media?.close(id).catch(() => {})
}

/**
 * Decrypt the next picture in the background while the current one is read.
 *
 * Only one idle session is kept: warming a second entry would just hold a
 * decrypted cache for a picture nobody asked for yet. The URL is also fed to an
 * `Image` so Chromium's private image cache holds the bytes under the exact
 * URL the stage will use when it adopts this session.
 */
export async function prefetchEntry(item) {
  if (!item || (player.url && sameEntry(item, player.item))) return

  const key = entryKey(item)
  if (prefetched.has(key) || opening.has(key)) return
  opening.add(key)
  for (const idleKey of [...prefetched.keys()]) {
    if (idleKey !== key) closePrefetch(idleKey)
  }

  try {
    const res =
      item.mode === 'local'
        ? await window.ske.local.play({ path: item.path, name: item.name })
        : await window.ske.media.openCloud({
            sessionId: item.sessionId,
            path: item.path,
            name: item.name,
          })
    if (!res?.ok || !res.url) return

    // The user may have moved on while this was opening: nothing left to warm.
    if (player.url && sameEntry(item, player.item)) {
      window.ske?.media?.close(res.id).catch(() => {})
      return
    }

    // Keeping the decoded image alive (rather than a fire-and-forget loader)
    // stops it being collected before the fetch finishes, and hands the stage
    // a ready bitmap when the page is adopted.
    const image = new Image()
    image.decoding = 'async'
    image.src = res.url
    // Decode now, while the user is still looking at the previous picture: the
    // `<img>` that adopts this URL then finds a decoded bitmap, not just bytes.
    image.decode?.().catch(() => {})

    prefetched.set(key, {
      id: res.id,
      url: res.url,
      mime: res.mime || '',
      size: res.size || 0,
      name: res.name || item.name || '',
      image,
    })
  } catch {
    // A failed prefetch stays invisible: the normal open path retries it.
  } finally {
    opening.delete(key)
  }
}

/**
 * Open the stream behind one page of the continuous comic strip.
 *
 * The caller owns the returned session and closes it once the page scrolls far
 * enough away. The page currently open in the single-page view is shared rather
 * than opened twice, and a warmed prefetch is adopted when it matches — the
 * strip should not pay for a decrypt the prefetcher already did.
 */
export async function openStripSession(item) {
  if (player.url && sameEntry(item, player.item)) {
    return { id: player.id, url: player.url, shared: true }
  }

  const warmed = takePrefetch(item)
  if (warmed) return { id: warmed.id, url: warmed.url }

  const res =
    item.mode === 'local'
      ? await window.ske.local.play({ path: item.path, name: item.name })
      : await window.ske.media.openCloud({
          sessionId: item.sessionId,
          path: item.path,
          name: item.name,
        })
  if (!res?.ok || !res.url) throw new Error(res?.error || '无法打开文件')
  return { id: res.id, url: res.url }
}

async function openEntry(item) {
  // Take the warmed session before any await. Closing the previous session
  // suspends, and the stage's watcher runs during that gap: it would warm the
  // *following* entry and, in doing so, discard the very session this open is
  // about to adopt.
  const warmed = takePrefetch(item)

  if (player.id) {
    const previous = player.id
    player.id = ''
    await window.ske.media.close(previous).catch(() => {})
  }

  player.loading = true
  player.error = ''
  player.playing = false
  player.currentTime = 0
  player.duration = 0
  player.item = item

  // Already decrypted: adopting the warmed session skips PBKDF2 and the first
  // range read, so the new picture paints without another spinner.
  if (warmed) {
    heldWarmedImage = warmed.image || null
    player.id = warmed.id
    player.url = warmed.url
    player.mime = warmed.mime
    player.size = warmed.size
    player.name = item.name || warmed.name
    player.kind = item.kind || kindFromMime(warmed.mime)
    player.playing = true
    player.loading = false
    return
  }

  heldWarmedImage = null

  try {
    const res =
      item.mode === 'local'
        ? await window.ske.local.play({ path: item.path, name: item.name })
        : await window.ske.media.openCloud({
            sessionId: item.sessionId,
            path: item.path,
            name: item.name,
          })

    if (!res?.ok) throw new Error(res?.error || '无法打开文件')

    player.id = res.id
    player.url = res.url
    player.mime = res.mime || ''
    player.size = res.size || 0
    player.name = item.name || res.name || ''
    player.kind = item.kind || kindFromMime(res.mime)
    player.playing = true
  } catch (err) {
    player.error = err.message
    player.url = ''
    player.playing = false
  } finally {
    player.loading = false
  }
}

/**
 * Video and images play in their own window: watching something should not cost
 * the user their file browser. Audio stays in the shell instead, where the
 * sidebar can keep offering controls after the music page is left.
 */
const OWN_WINDOW_KINDS = new Set(['video', 'image'])

export function wantsOwnWindow(kind) {
  return OWN_WINDOW_KINDS.has(kind)
}

/**
 * Play one entry, optionally with a playlist and a starting position.
 *
 * `inline` forces playback in this renderer. The stage window uses it: calling
 * the routing version there would ask for another window and loop forever.
 */
export async function playEntry(item, { playlist = [], index = -1, inline = false } = {}) {
  // The file that is already open is the same for every kind: re-picking it
  // must bring its window forward, never restart what is on. A paused track
  // resumes, so a click on "the same song" never looks like a no-op.
  if (player.url && sameEntry(item, player.item)) {
    player.playlist = playlist
    player.index = index
    if (item.kind === 'audio' && !player.playing) togglePlay()
    if (!inline) await window.ske.player.openWindow({ item, playlist, index }).catch(() => {})
    return
  }

  // Audio plays in the shell, but still gets a window: that window is a remote
  // control for this transport, so opening it never opens a second stream.
  if (item.kind === 'audio' && !inline) {
    player.playlist = playlist
    player.index = index
    await openEntry(item)

    // A window that refuses to open costs the user nothing: the sidebar bar is
    // still there and the track keeps playing.
    await window.ske.player.openWindow({ item, playlist, index }).catch(() => {})
    return
  }

  if (!inline && wantsOwnWindow(item.kind)) {
    // Independent windows: opening a video leaves the shell's audio and its
    // music window alone. Stopping the track is the sidebar stop button's job.
    const res = await window.ske.player.openWindow({ item, playlist, index })
    if (!res?.ok) throw new Error(res?.error || '无法打开播放窗口')
    return
  }

  player.playlist = playlist
  player.index = index
  await openEntry(item)
}

export async function playAt(index) {
  if (index < 0 || index >= player.playlist.length) return
  player.index = index
  await openEntry(player.playlist[index])
}

/**
 * True when two entries point at the same file.
 *
 * Mode and session are part of the identity: `/a.mp4` from two cloud profiles
 * is two files, even though the paths are equal.
 */
export function sameEntry(a, b) {
  if (!a || !b) return false
  return (
    (a.mode || '') === (b.mode || '') &&
    (a.sessionId || '') === (b.sessionId || '') &&
    (a.path || a.name || '') === (b.path || b.name || '')
  )
}

// ── Play order ──
/** Cycled by the button on the transport; the first one is the default. */
export const PLAY_MODES = ['list', 'one', 'shuffle']
export const PLAY_MODE_LABELS = {
  list: '列表循环',
  one: '单曲循环',
  shuffle: '随机播放',
}

export function cycleMode() {
  const at = PLAY_MODES.indexOf(player.mode)
  player.mode = PLAY_MODES[(at + 1) % PLAY_MODES.length]
  return player.mode
}

/** Any track but this one: a "random" pick that repeats itself is a bug. */
function randomOtherIndex() {
  const total = player.playlist.length
  if (total < 2) return player.index
  let pick = player.index
  while (pick === player.index) pick = Math.floor(Math.random() * total)
  return pick
}

/**
 * The order is the audio transport's setting: the video window keeps its
 * playlist in file order even when the music side is shuffled.
 */
function shuffled() {
  return player.kind === 'audio' && player.mode === 'shuffle'
}

export async function next() {
  const total = player.playlist.length
  if (total < 2) return
  await playAt(shuffled() ? randomOtherIndex() : (player.index + 1) % total)
}

export async function prev() {
  const total = player.playlist.length
  if (total < 2) return
  await playAt(shuffled() ? randomOtherIndex() : (player.index - 1 + total) % total)
}

/**
 * What happens when a track reaches its end on its own.
 *
 * Repeat-one restarts the same track here rather than reopening the stream:
 * the entry is already decrypted and the element is sitting at the end of it.
 */
export async function advance() {
  if (player.mode === 'one' && player.url) {
    seekTo(0)
    audioEl?.play().catch(() => {})
    return
  }
  await next()
}

export async function close() {
  if (player.id) {
    const previous = player.id
    player.id = ''
    await window.ske.media.close(previous).catch(() => {})
  }
  clearPrefetches()
  heldWarmedImage = null
  player.url = ''
  player.name = ''
  player.playing = false
  player.currentTime = 0
  player.duration = 0
  player.playlist = []
  player.index = -1
  player.item = null
  player.error = ''
}

// ── Shell audio transport ──
// The <audio> element lives in `App.vue`. Registering it here lets the sidebar
// bar and the music page share one transport, and keeps the track alive when a
// page (rather than the app) goes away.
let audioEl = null

export function attachAudio(el) {
  audioEl = el
}

export function detachAudio(el) {
  if (audioEl === el) audioEl = null
}

export function togglePlay() {
  if (!audioEl) return
  if (audioEl.paused) audioEl.play().catch(() => {})
  else audioEl.pause()
}

export function seekTo(seconds) {
  if (!audioEl || !Number.isFinite(seconds)) return
  audioEl.currentTime = Math.max(0, seconds)
  player.currentTime = audioEl.currentTime
}

export function setVolume(value) {
  const level = Math.min(1, Math.max(0, Number(value) || 0))
  player.volume = level
  player.muted = level === 0
  if (audioEl) {
    audioEl.volume = level
    audioEl.muted = player.muted
  }
}

export function toggleMute() {
  player.muted = !player.muted
  if (audioEl) audioEl.muted = player.muted
}

export function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '0:00'
  const total = Math.floor(seconds)
  const mins = Math.floor(total / 60)
  const secs = total % 60
  if (mins < 60) return `${mins}:${String(secs).padStart(2, '0')}`
  const hours = Math.floor(mins / 60)
  return `${hours}:${String(mins % 60).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}
