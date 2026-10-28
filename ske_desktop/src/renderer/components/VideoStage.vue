<template>
  <div class="video-stage" @mousemove="poke" @mouseleave="hideNow">
    <video
      ref="videoEl"
      class="video"
      :src="url"
      playsinline
      @loadedmetadata="onMeta"
      @timeupdate="onTime"
      @play="onPlay"
      @pause="onPause"
      @ended="onEnded"
      @error="onError"
      @click="toggle"
    />

    <p v-if="activeText" class="subtitle" :class="{ lifted: chromeVisible }" :style="subtitleStyle">
      {{ activeText }}
    </p>

    <p v-if="error" class="stage-error">{{ error }}</p>

    <!-- Big translucent play button, web-player style: the whole surface is the
         play control while paused. -->
    <Transition name="fade">
      <button v-if="!playing && !error && url" class="center-play" type="button" @click.stop="toggle">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8z" /></svg>
      </button>
    </Transition>

    <Transition name="fade">
      <div v-show="uiVisible || !playing" class="chrome" @mousemove.stop="poke">
        <button class="btn" type="button" :title="playing ? '暂停' : '播放'" @click="toggle">
          <svg v-if="playing" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5h3.2v14H8zM12.8 5H16v14h-3.2z" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8z" /></svg>
        </button>

        <span class="time mono">{{ format(current) }}</span>

        <input
          class="seek"
          type="range"
          min="0"
          :max="duration || 0"
          step="0.1"
          :value="current"
          :style="{ '--p': `${progress}%` }"
          :disabled="!duration"
          @input="onSeek"
        />

        <span class="time mono">{{ format(duration) }}</span>

        <div class="vol-wrap">
          <button class="btn" type="button" :title="muted ? '取消静音' : '静音'" @click="toggleMute">
            <svg v-if="muted || volume === 0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
              <path d="M16 9.5l4 5M20 9.5l-4 5" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
              <path d="M15.5 9a4 4 0 0 1 0 6" />
            </svg>
          </button>
          <input
            class="vol"
            type="range"
            min="0"
            max="1"
            step="0.01"
            :value="muted ? 0 : volume"
            :style="{ '--p': `${(muted ? 0 : volume) * 100}%` }"
            @input="onVolume"
          />
        </div>

        <div class="rate-wrap">
          <button class="btn text" type="button" title="播放速度" @click="menuOpen = !menuOpen">
            {{ rate }}×
          </button>
          <Transition name="fade">
            <div v-if="menuOpen" class="rate-menu">
              <button
                v-for="value in RATES"
                :key="value"
                type="button"
                :class="{ on: value === rate }"
                @click="setRate(value)"
              >
                {{ value }}×
              </button>
            </div>
          </Transition>
        </div>

        <!-- The caption control belongs in the window's top bar, next to the
             playlist button, so it teleports into the header slot. -->
        <Teleport to=".sub-slot">
          <div v-if="tracks.length" class="sub-wrap">
            <button
              class="btn"
              :class="{ on: activeTrackId !== 'off' }"
              type="button"
              title="字幕"
              @click="subMenuOpen = !subMenuOpen"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
                <rect x="3" y="6" width="18" height="12" rx="2.5" />
                <path d="M7 13h3.5M13.5 13H17" />
              </svg>
            </button>

            <Transition name="fade">
              <div v-if="subMenuOpen" class="sub-menu" @click.stop>
                <button
                  class="sub-item"
                  :class="{ on: activeTrackId === 'off' }"
                  type="button"
                  @click="selectTrack(null)"
                >
                  关闭字幕
                </button>
                <button
                  v-for="track in tracks"
                  :key="track.id"
                  class="sub-item"
                  :class="{ on: track.id === activeTrackId }"
                  type="button"
                  :title="track.name"
                  @click="selectTrack(track)"
                >
                  {{ track.label }}
                </button>

                <div class="sub-sep" />
                <div class="sub-head">字幕大小</div>
                <div class="sub-sizes">
                  <button
                    v-for="option in SCALES"
                    :key="option"
                    :class="{ on: option === subtitleScale }"
                    type="button"
                    @click="setScale(option)"
                  >
                    {{ Math.round(option * 100) }}%
                  </button>
                </div>
              </div>
            </Transition>
          </div>
        </Teleport>

        <button class="btn" type="button" title="全屏" @click="toggleFullscreen">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
            <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
          </svg>
        </button>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { session, toast } from '../state/session.js'
import {
  baseName,
  cueAt,
  extensionOf,
  isSubtitleName,
  langTagOf,
  parseCues,
  trackLabel,
} from '../utils/subtitles.js'

const props = defineProps({
  url: { type: String, default: '' },
  mime: { type: String, default: '' },
  autoplay: { type: Boolean, default: true },
  /** Source entry, used to list the folder for sibling subtitle containers. */
  item: { type: Object, default: null },
})

const emit = defineEmits(['ended', 'error', 'retry'])

const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2]
/** Control size presets, same values as the web viewer. */
const SCALES = [0.75, 0.9, 1, 1.15, 1.3, 1.5]
const SCALE_KEY = 'ske_subtitle_scale'
/** Controls fade out this long after the pointer stops while playing. */
const IDLE_MS = 2600

const videoEl = ref(null)
const playing = ref(false)
const current = ref(0)
const duration = ref(0)
const volume = ref(Number(session.settings?.playback?.volume ?? 1))
const muted = ref(volume.value === 0)
const rate = ref(1)
const uiVisible = ref(true)
const menuOpen = ref(false)
const error = ref('')

const tracks = ref([])
const activeTrackId = ref('off')
const subMenuOpen = ref(false)
const subtitleScale = ref(loadScale())
const cues = ref([])
const activeText = ref('')

let idleTimer = null

const progress = computed(() => (duration.value ? (current.value / duration.value) * 100 : 0))
/** The chrome is what the subtitle overlay has to stay clear of. */
const chromeVisible = computed(() => uiVisible.value || !playing.value)
const subtitleStyle = computed(() => ({ fontSize: `${15 * subtitleScale.value}px` }))

function format(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function poke() {
  uiVisible.value = true
  clearTimeout(idleTimer)
  if (playing.value) idleTimer = setTimeout(() => (uiVisible.value = false), IDLE_MS)
}

function hideNow() {
  subMenuOpen.value = false
  if (playing.value) uiVisible.value = false
}

// --- subtitles ---------------------------------------------------------------

function loadScale() {
  const saved = Number(localStorage.getItem(SCALE_KEY))
  return SCALES.includes(saved) ? saved : 1
}

function setScale(value) {
  subtitleScale.value = value
  localStorage.setItem(SCALE_KEY, String(value))
}

function updateActiveText() {
  const el = videoEl.value
  if (!el || !cues.value.length) {
    activeText.value = ''
    return
  }
  activeText.value = cueAt(cues.value, el.currentTime)?.text || ''
}

/** Sibling containers of the folder the media came from. */
async function listSiblings(item) {
  const dir = String(item.path || '').replace(/\/[^/]*$/, '') || '/'

  if (item.mode === 'local') {
    const res = await window.ske?.local?.list(dir)
    return res?.ok ? res.entries || [] : []
  }
  if (!item.sessionId) return []
  const res = await window.ske?.cloud?.list(item.sessionId, dir)
  return res?.ok ? res.entries || [] : []
}

/**
 * Files named after the video (`ep01.zh.srt`, `ep01.ass`) are its subtitles,
 * same rule as the web viewer: the video's base name plus an optional language tag.
 */
async function refreshTracks() {
  tracks.value = []
  activeTrackId.value = 'off'
  cues.value = []
  activeText.value = ''

  const item = props.item
  if (!item?.path) return

  let entries = []
  try {
    entries = await listSiblings(item)
  } catch {
    return
  }

  const videoBase = baseName(item.name || item.path)
  const found = []

  for (const entry of entries) {
    if (entry.isDir || entry.locked || !isSubtitleName(entry.name)) continue
    const tag = langTagOf(videoBase, baseName(entry.name))
    if (tag === null) continue

    const ext = extensionOf(entry.name)
    found.push({
      id: entry.path,
      path: entry.path,
      name: entry.name,
      mode: item.mode,
      sessionId: item.sessionId,
      ext,
      tag,
      label: trackLabel(tag, ext),
    })
  }

  // An exact-name match reads as the default track; language tags come after.
  found.sort((a, b) => {
    if (a.tag === b.tag) return a.name.localeCompare(b.name)
    if (a.tag === '') return -1
    if (b.tag === '') return 1
    return a.tag.localeCompare(b.tag)
  })

  tracks.value = found
  if (found.length) selectTrack(found[0])
}

async function selectTrack(track) {
  subMenuOpen.value = false

  if (!track) {
    activeTrackId.value = 'off'
    cues.value = []
    activeText.value = ''
    return
  }

  activeTrackId.value = track.id
  try {
    const res = await window.ske?.subtitles?.read({
      mode: track.mode,
      path: track.path,
      name: track.name,
      sessionId: track.sessionId,
    })
    if (!res?.ok) throw new Error(res?.error || '字幕读取失败')

    cues.value = parseCues(res.text, track.ext)
    if (!cues.value.length) {
      toast('字幕内容为空或格式无法识别', 'error')
      activeTrackId.value = 'off'
      activeText.value = ''
      return
    }
    updateActiveText()
  } catch (err) {
    cues.value = []
    activeText.value = ''
    activeTrackId.value = 'off'
    toast(err.message, 'error')
  }
}

function toggle() {
  const el = videoEl.value
  if (!el) return
  if (el.paused) el.play().catch(() => {})
  else el.pause()
}

function seekBy(delta) {
  const el = videoEl.value
  if (!el || !Number.isFinite(el.duration)) return
  el.currentTime = Math.min(el.duration, Math.max(0, el.currentTime + delta))
  poke()
}

function onSeek(event) {
  const el = videoEl.value
  if (!el) return
  el.currentTime = Number(event.target.value)
}

function onVolume(event) {
  const el = videoEl.value
  const value = Number(event.target.value)
  volume.value = value
  muted.value = value === 0
  if (el) {
    el.volume = value
    el.muted = muted.value
  }
}

function toggleMute() {
  const el = videoEl.value
  if (!el) return
  muted.value = !muted.value
  el.muted = muted.value
}

function setRate(value) {
  rate.value = value
  if (videoEl.value) videoEl.value.playbackRate = value
  menuOpen.value = false
}

function toggleFullscreen() {
  window.ske?.window?.toggleFullScreen()
}

function onMeta() {
  const el = videoEl.value
  if (!el) return
  duration.value = Number.isFinite(el.duration) ? el.duration : 0
  el.volume = volume.value
  el.muted = muted.value
  if (props.autoplay) el.play().catch(() => {})
}

function onTime() {
  if (!videoEl.value) return
  current.value = videoEl.value.currentTime
  updateActiveText()
}

function onPlay() {
  playing.value = true
  poke()
}

function onPause() {
  playing.value = false
  uiVisible.value = true
  clearTimeout(idleTimer)
}

function onEnded() {
  playing.value = false
  uiVisible.value = true
  emit('ended')
}

function onError() {
  const el = videoEl.value
  const code = el?.error?.code
  error.value =
    code === 4
      ? '当前格式无法由内置播放器解码（常见于 avi / wmv / 老编码）'
      : '视频加载失败，文件可能已损坏或无法解密'
  playing.value = false
  activeText.value = ''
  emit('error', error.value)
}

function retry() {
  error.value = ''
  emit('retry')
}

/** Keyboard parity with the shell: Space, arrows and F for full screen. */
function onKeydown(event) {
  if (event.target instanceof HTMLInputElement) return

  // Esc backs out of the caption menu first; swallowing the event keeps the
  // stage window's own Esc handler (close drawer, then window) out of the way.
  if (event.key === 'Escape' && subMenuOpen.value) {
    subMenuOpen.value = false
    event.stopImmediatePropagation()
    return
  }

  const step = Number(session.settings?.playback?.seekStep) || 5

  if (event.code === 'Space') {
    event.preventDefault()
    toggle()
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    seekBy(-step)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    seekBy(step)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    onVolume({ target: { value: Math.min(1, volume.value + 0.05) } })
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    onVolume({ target: { value: Math.max(0, volume.value - 0.05) } })
  } else if (event.key === 'm' || event.key === 'M') {
    toggleMute()
  }
  poke()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  if (videoEl.value) {
    videoEl.value.volume = volume.value
    videoEl.value.muted = muted.value
  }
  refreshTracks()
})

onUnmounted(() => {
  clearTimeout(idleTimer)
  window.removeEventListener('keydown', onKeydown)
  const el = videoEl.value
  if (el) {
    el.pause()
    el.removeAttribute('src')
    el.load()
  }
})

// A new URL in the same component must not keep the old time or error.
watch(
  () => props.url,
  () => {
    error.value = ''
    current.value = 0
    duration.value = 0
    playing.value = false
    refreshTracks()
  },
)
</script>

<style scoped>
.video-stage {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  background: #000;
  overflow: hidden;
}

.video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
  cursor: default;
}

.center-play {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  box-sizing: border-box;
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(10px);
  color: #fff;
  cursor: pointer;
  transition: background 0.16s ease, transform 0.16s ease;
}

.center-play:hover {
  background: rgba(0, 0, 0, 0.62);
  transform: translate(-50%, -50%) scale(1.04);
}

.center-play svg {
  width: 26px;
  height: 26px;
  margin-left: 3px;
}

.chrome {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(12, 12, 14, 0.72);
  backdrop-filter: blur(18px);
}

.btn {
  display: grid;
  place-items: center;
  box-sizing: border-box;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  /* The UA button padding offsets the icon inside the fixed box. */
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.86);
  line-height: 1;
  cursor: pointer;
  transition: background 0.14s ease, color 0.14s ease;
}

.btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.btn svg {
  display: block;
  width: 17px;
  height: 17px;
}

.btn.text {
  width: auto;
  padding: 0 8px;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

/* Lit while a subtitle track is active. */
.btn.on {
  color: #93c5fd;
}

/* Caption overlay: plain text, bottom-centred, above the chrome while shown. */
.subtitle {
  position: absolute;
  left: 6%;
  right: 6%;
  bottom: 26px;
  z-index: 3;
  margin: 0;
  color: #fff;
  font-size: 15px;
  line-height: 1.45;
  text-align: center;
  white-space: pre-line;
  pointer-events: none;
  text-shadow:
    0 1px 2px rgba(0, 0, 0, 0.95),
    0 2px 6px rgba(0, 0, 0, 0.75),
    0 0 2px #000;
  transition: bottom 0.18s ease;
}

.subtitle.lifted {
  bottom: 74px;
}

/* Subtitle picker, opened downward from the top-bar CC button. */
.sub-wrap {
  position: relative;
  flex: 0 0 auto;
}

.sub-menu {
  position: absolute;
  top: 34px;
  right: 0;
  z-index: 7;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 180px;
  max-height: 280px;
  overflow-y: auto;
  padding: 5px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  background: rgba(12, 12, 14, 0.94);
  backdrop-filter: blur(18px);
}

.sub-item {
  padding: 6px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.78);
  font-size: 12px;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}

.sub-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.sub-item.on {
  color: #93c5fd;
}

.sub-sep {
  height: 1px;
  margin: 4px 2px;
  background: rgba(255, 255, 255, 0.1);
}

.sub-head {
  padding: 2px 9px;
  font-size: 10.5px;
  color: rgba(255, 255, 255, 0.45);
}

.sub-sizes {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 2px 4px 3px;
}

.sub-sizes button {
  flex: 1 0 auto;
  padding: 4px 6px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.7);
  font-size: 11px;
  cursor: pointer;
}

.sub-sizes button.on {
  border-color: #60a5fa;
  color: #93c5fd;
}

.time {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.7);
  font-variant-numeric: tabular-nums;
  flex: 0 0 auto;
}

/* Range inputs painted with a filled track, matching the shell's sliders. */
.seek,
.vol {
  -webkit-appearance: none;
  appearance: none;
  height: 16px;
  background: transparent;
  cursor: pointer;
}

.seek {
  flex: 1;
  min-width: 60px;
}

.vol {
  width: 68px;
  flex: 0 0 68px;
}

.seek::-webkit-slider-runnable-track,
.vol::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    #60a5fa 0%,
    #60a5fa var(--p, 0%),
    rgba(255, 255, 255, 0.22) var(--p, 0%),
    rgba(255, 255, 255, 0.22) 100%
  );
}

.seek::-webkit-slider-thumb,
.vol::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 11px;
  height: 11px;
  margin-top: -4px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.28);
}

.vol-wrap {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
}

.rate-wrap {
  position: relative;
  flex: 0 0 auto;
}

.rate-menu {
  position: absolute;
  right: 0;
  bottom: 36px;
  display: flex;
  flex-direction: column;
  padding: 4px;
  min-width: 76px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(12, 12, 14, 0.92);
  backdrop-filter: blur(18px);
}

.rate-menu button {
  padding: 5px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.78);
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
}

.rate-menu button:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.rate-menu button.on {
  color: #60a5fa;
}

.stage-error {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  margin: 0;
  padding: 24px;
  text-align: center;
  color: rgba(255, 255, 255, 0.75);
  font-size: 12.5px;
  line-height: 1.7;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
