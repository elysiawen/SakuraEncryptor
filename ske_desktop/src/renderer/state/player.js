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

async function openEntry(item) {
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
    // Leaving audio for a video/image: the shell's own stream has to stop, or
    // the two would play over each other.
    await close()

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

export async function next() {
  if (player.playlist.length < 2) return
  await playAt((player.index + 1) % player.playlist.length)
}

export async function prev() {
  if (player.playlist.length < 2) return
  await playAt((player.index - 1 + player.playlist.length) % player.playlist.length)
}

export async function close() {
  if (player.id) {
    const previous = player.id
    player.id = ''
    await window.ske.media.close(previous).catch(() => {})
  }
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
