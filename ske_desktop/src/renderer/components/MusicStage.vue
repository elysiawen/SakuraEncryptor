<!--
  Music window.

  The track itself plays in the shell (`App.vue`), so this window is a remote:
  it mirrors the transport published over IPC and sends actions back. That is
  what makes closing the window harmless — the audio element is not in here.
-->
<template>
  <div class="music" :class="{ playing: state.playing }">
    <div class="blobs" aria-hidden="true">
      <span class="blob blob-1" />
      <span class="blob blob-2" />
      <span class="blob blob-3" />
    </div>
    <div class="veil" aria-hidden="true" />

    <header class="titlebar">
      <div class="title" :title="state.name">{{ state.name || '音乐' }}</div>

      <div class="actions">
        <button
          v-if="total > 1"
          class="icon"
          :class="{ active: listOpen }"
          type="button"
          :title="listOpen ? '收起播放列表' : '播放列表'"
          @click="listOpen = !listOpen"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
            <path d="M4 6h16M4 12h16M4 18h9" />
            <path d="M17 15.5 21 18l-4 2.5z" fill="currentColor" stroke="none" />
          </svg>
        </button>

        <button class="icon" type="button" title="最小化" @click="minimize">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M6 12h12" />
          </svg>
        </button>

        <button class="icon" type="button" :title="maximized ? '还原' : '最大化'" @click="toggleMaximize">
          <svg v-if="maximized" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
            <rect x="5" y="8" width="11" height="11" rx="1.4" />
            <path d="M9 8V6.4A1.4 1.4 0 0 1 10.4 5H18a1.4 1.4 0 0 1 1.4 1.4V14" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
            <rect x="6" y="6" width="12" height="12" rx="1.5" />
          </svg>
        </button>

        <button class="icon" type="button" title="关闭窗口（继续播放）" @click="closeWindow">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M7 7l10 10M17 7L7 17" />
          </svg>
        </button>
      </div>
    </header>

    <main class="stage">
      <p v-if="state.error" class="empty">
        {{ state.error }}
        <button class="ghost sm" type="button" @click="retry">重试</button>
      </p>

      <div v-else-if="!state.url" class="empty">
        <span v-if="state.loading && !bridgeError" class="spinner" />
        <p>{{ bridgeError || (state.loading ? '正在解密…' : '暂无播放内容') }}</p>
        <button v-if="bridgeError" class="ghost sm" type="button" @click="retryBridge">
          重试
        </button>
        <button v-else-if="!state.loading" class="ghost sm" type="button" @click="closeWindow">
          关闭窗口
        </button>
      </div>

      <template v-else>
        <VinylVisualizer :playing="state.playing" :cover-url="cover" />

        <div class="info">
          <div class="track" :title="state.name">{{ state.name || '—' }}</div>
          <div class="meta">{{ state.mime || '音频' }}<template v-if="sizeLabel"> · {{ sizeLabel }}</template></div>
        </div>

        <div class="seek-row">
          <span class="time mono">{{ formatTime(state.currentTime) }}</span>
          <input
            class="seek"
            type="range"
            min="0"
            :max="state.duration || 0"
            step="0.1"
            :value="state.currentTime"
            :style="{ '--p': `${percent}%` }"
            :disabled="!state.duration"
            aria-label="播放进度"
            @input="onSeek"
          />
          <span class="time mono">{{ formatTime(state.duration) }}</span>
        </div>

        <div class="controls">
          <button class="round" type="button" title="上一首" :disabled="total < 2" @click="send('prev')">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h2v14H6zM19 5.4 9.5 12l9.5 6.6z" /></svg>
          </button>

          <button class="round main" type="button" :title="state.playing ? '暂停' : '播放'" @click="send('toggle')">
            <svg v-if="state.playing" viewBox="0 0 24 24" fill="currentColor">
              <path d="M8 5h3.2v14H8zM12.8 5H16v14h-3.2z" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8z" /></svg>
          </button>

          <button class="round" type="button" title="下一首" :disabled="total < 2" @click="send('next')">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M16 5h2v14h-2zM5 5.4 14.5 12 5 18.6z" /></svg>
          </button>

          <div class="volume">
            <button class="round small" type="button" :title="muted ? '取消静音' : '静音'" @click="onMute">
              <svg v-if="muted" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
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
              :value="muted ? 0 : state.volume"
              :style="{ '--p': `${(muted ? 0 : state.volume) * 100}%` }"
              aria-label="音量"
              @input="onVolume"
            />
          </div>

          <button class="round small stop" type="button" title="停止播放" @click="stop">
            <svg viewBox="0 0 24 24" fill="currentColor"><rect x="7" y="7" width="10" height="10" rx="1.5" /></svg>
          </button>
        </div>
      </template>
    </main>

    <Transition name="fade">
      <aside v-if="listOpen && total" class="queue" @click.stop>
        <div class="queue-head">
          <span>播放列表</span>
          <span class="count mono">{{ total }}</span>
        </div>
        <div class="queue-body">
          <button
            v-for="(item, i) in state.playlist"
            :key="item.path || i"
            class="queue-item"
            :class="{ on: i === state.index }"
            type="button"
            @click="select(i)"
          >
            <span class="idx mono">{{ i + 1 }}</span>
            <span class="qname" :title="item.name">{{ item.name }}</span>
          </button>
        </div>
      </aside>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import VinylVisualizer from './VinylVisualizer.vue'
import { coverFor, ensureCover } from '../state/covers.js'
import { formatTime } from '../state/player.js'
import { session } from '../state/session.js'

const props = defineProps({
  /** The payload the main process handed over when this window opened. */
  payload: { type: Object, default: null },
})

const state = reactive({
  url: '',
  name: '',
  mime: '',
  size: 0,
  playing: false,
  currentTime: 0,
  duration: 0,
  volume: Number(session.settings?.playback?.volume ?? 1),
  muted: false,
  playlist: [],
  index: -1,
  loading: true,
  error: '',
})

const listOpen = ref(false)
const maximized = ref(false)
/** Set when the shell cannot be reached: an actionable message, not a spinner. */
const bridgeError = ref('')
let synced = false
let offState = null
let stalledTimer = 0

const total = computed(() => state.playlist.length)
const cover = computed(() => coverFor(state.url))
const sizeLabel = computed(() => (state.size ? formatSize(state.size) : ''))
const muted = computed(() => state.muted || state.volume === 0)
const percent = computed(() =>
  state.duration ? Math.min(100, (state.currentTime / state.duration) * 100) : 0,
)

function formatSize(bytes) {
  if (!bytes) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

/** Seed from the payload so the window is not blank before the first sync. */
function adoptPayload(payload) {
  if (!payload?.item) return
  state.name = payload.item.name || ''
  state.playlist = payload.playlist || []
  state.index = Number.isInteger(payload.index) ? payload.index : -1
  document.title = `${payload.item.name || '音乐'} — Sakura Encryptor`
}

function applyState(next) {
  if (!next) return
  synced = true
  bridgeError.value = ''
  clearTimeout(stalledTimer)
  Object.assign(state, {
    url: next.url || '',
    name: next.name || state.name,
    mime: next.mime || '',
    size: next.size || 0,
    playing: !!next.playing,
    currentTime: Number(next.currentTime) || 0,
    duration: Number(next.duration) || 0,
    volume: Number.isFinite(next.volume) ? next.volume : state.volume,
    muted: !!next.muted,
    playlist: Array.isArray(next.playlist) ? next.playlist : [],
    index: Number.isInteger(next.index) ? next.index : -1,
    loading: !!next.loading,
    error: next.error || '',
  })
  ensureCover(state.url)
}

/**
 * Forward one action to the shell.
 *
 * A rejected call or an `{ ok: false }` reply means the bridge is dead — usually
 * because the app was upgraded while this window's older main process kept
 * running. Saying so beats leaving the user on an empty vinyl.
 */
function send(type, value) {
  const command = window.ske?.player?.command
  if (!command) {
    bridgeError.value = '主程序版本过旧，请完全退出应用后重新打开'
    return Promise.resolve(null)
  }

  return command({ type, value })
    .then((res) => {
      if (res && res.ok === false) bridgeError.value = res.error || '主窗口没有响应'
      return res
    })
    .catch((err) => {
      bridgeError.value = err?.message || '主窗口没有响应'
      return null
    })
}

function armStallTimer() {
  clearTimeout(stalledTimer)
  stalledTimer = setTimeout(() => {
    if (synced) return
    state.loading = false
    bridgeError.value = '无法连接主窗口：音轨可能已停止，或应用升级后尚未重启'
  }, 4000)
}

function retryBridge() {
  bridgeError.value = ''
  state.loading = true
  armStallTimer()
  send('sync')
}

function onSeek(event) {
  const value = Number(event.target.value)
  state.currentTime = value
  send('seek', value)
}

function onVolume(event) {
  const value = Number(event.target.value)
  state.volume = value
  state.muted = value === 0
  send('volume', value)
}

function onMute() {
  state.muted = !state.muted
  send('mute')
}

function select(index) {
  listOpen.value = false
  send('playAt', index)
}

function retry() {
  if (state.index >= 0) send('playAt', state.index)
}

async function stop() {
  // Stopping also dismisses this window: a dead player on screen would only
  // invite clicks that do nothing.
  await send('stop')
  closeWindow()
}

function minimize() {
  window.ske?.window?.minimize()
}

async function toggleMaximize() {
  const res = await window.ske?.window?.toggleMaximize()
  if (res?.ok) maximized.value = res.maximized
}

function closeWindow() {
  window.ske?.window?.close()
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    closeWindow()
    return
  }

  if (
    event.target instanceof HTMLInputElement ||
    (event.target instanceof HTMLElement && event.target.closest('button'))
  ) {
    return
  }

  const step = Number(session.settings?.playback?.seekStep) || 5

  if (event.code === 'Space') {
    event.preventDefault()
    send('toggle')
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    send('seek', Math.max(0, state.currentTime - step))
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    send('seek', state.currentTime + step)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    send('volume', Math.min(1, state.volume + 0.05))
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    send('volume', Math.max(0, state.volume - 0.05))
  } else if (event.key === 'm' || event.key === 'M') {
    onMute()
  }
}

watch(
  () => props.payload,
  (payload) => {
    adoptPayload(payload)
    // A reused window must forget the previous track's position immediately;
    // the shell's next publish fills in the real one.
    state.url = ''
    state.currentTime = 0
    state.duration = 0
    state.error = ''
    state.loading = true
    bridgeError.value = ''
    send('sync')
    armStallTimer()
  },
  { immediate: true },
)

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  offState = window.ske?.player?.onState?.(applyState) ?? null

  // Push updates only arrive on change; asking once fills the window at open.
  send('sync')
  armStallTimer()
})

onBeforeUnmount(() => {
  clearTimeout(stalledTimer)
  window.removeEventListener('keydown', onKeydown)
  offState?.()
})
</script>

<style scoped>
.music {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #0f0c29;
  color: #fff;
}

/* ── Ambient background, same recipe as the web client ── */
.blobs {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.55;
  animation: float 20s infinite alternate ease-in-out;
}

.blob-1 {
  top: -10%;
  left: -10%;
  width: 50vw;
  height: 50vw;
  background: #8b5cf6;
}

.blob-2 {
  right: -10%;
  bottom: -20%;
  width: 60vw;
  height: 60vw;
  background: #ec4899;
  animation-delay: -5s;
}

.blob-3 {
  top: 40%;
  left: 30%;
  width: 40vw;
  height: 40vw;
  background: #3b82f6;
  animation-delay: -10s;
}

/* Playback speeds the drift up, the way the web page reacts to sound. */
.playing .blob {
  animation-duration: 10s;
  opacity: 0.75;
}

.veil {
  position: absolute;
  inset: 0;
  background: rgba(10, 10, 20, 0.42);
  backdrop-filter: blur(90px);
  pointer-events: none;
}

@keyframes float {
  0% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(5%, 5%) scale(1.1);
  }
  100% {
    transform: translate(-5%, 10%) scale(0.9);
  }
}

/* ── Window chrome ── */
.titlebar {
  position: relative;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 4px 0 14px;
  flex: 0 0 auto;
  border-bottom: 1px solid rgba(255, 255, 255, 0.09);
  background: rgba(10, 10, 12, 0.55);
  backdrop-filter: blur(18px);
  /* Frameless window: this bar is the title bar. */
  -webkit-app-region: drag;
  user-select: none;
}

.title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.88);
}

.actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
  -webkit-app-region: no-drag;
}

.icon {
  display: grid;
  place-items: center;
  box-sizing: border-box;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.8);
}

.icon:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.icon.active {
  color: #c4b5fd;
  background: rgba(167, 139, 250, 0.18);
}

.icon svg {
  width: 16px;
  height: 16px;
}

/* ── Content ── */
.stage {
  position: relative;
  z-index: 2;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 22px;
  padding: 22px 24px 26px;
  overflow-y: auto;
}

.info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  max-width: 520px;
  text-align: center;
}

.track {
  font-size: 17px;
  font-weight: 600;
  line-height: 1.4;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.5);
}

.seek-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: min(520px, 100%);
}

.time {
  flex: 0 0 auto;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.62);
  font-variant-numeric: tabular-nums;
}

/* Shared filled-track slider styling for seek and volume. */
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
  min-width: 80px;
}

.vol {
  width: 80px;
  flex: 0 0 80px;
}

.seek::-webkit-slider-runnable-track,
.vol::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    #a78bfa 0%,
    #a78bfa var(--p, 0%),
    rgba(255, 255, 255, 0.24) var(--p, 0%),
    rgba(255, 255, 255, 0.24) 100%
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

.seek:disabled,
.vol:disabled {
  cursor: default;
  opacity: 0.5;
}

.controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.round {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.86);
  transition: background 0.14s ease, color 0.14s ease, transform 0.14s ease;
}

.round:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}

.round:disabled {
  opacity: 0.4;
  cursor: default;
}

.round svg {
  width: 17px;
  height: 17px;
}

.round.main {
  width: 54px;
  height: 54px;
  border-color: rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.round.main:hover:not(:disabled) {
  transform: scale(1.04);
}

.round.main svg {
  width: 22px;
  height: 22px;
}

.round.small {
  width: 30px;
  height: 30px;
}

.round.small svg {
  width: 15px;
  height: 15px;
}

.round.stop {
  color: rgba(255, 255, 255, 0.6);
}

.volume {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 8px;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  margin: 0;
  max-width: 420px;
  padding: 28px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 12.5px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.8);
  text-align: center;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.18);
  border-top-color: #a78bfa;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ── Playlist drawer ── */
.queue {
  position: absolute;
  top: 36px;
  right: 0;
  bottom: 0;
  z-index: 4;
  display: flex;
  flex-direction: column;
  width: 264px;
  border-left: 1px solid rgba(255, 255, 255, 0.09);
  background: rgba(10, 10, 16, 0.9);
  backdrop-filter: blur(18px);
}

.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 8px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.58);
}

.count {
  padding: 0 7px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 999px;
}

.queue-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.queue-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  min-width: 0;
  padding: 7px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  text-align: left;
}

.queue-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.queue-item.on {
  background: rgba(167, 139, 250, 0.2);
  color: #ddd6fe;
}

.idx {
  flex: 0 0 22px;
  font-size: 10.5px;
  text-align: right;
  color: rgba(255, 255, 255, 0.42);
}

.qname {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* Short windows (the player opens at 1024×640, but it is resizable): the disc
   has to give way before the controls do. */
@media (max-height: 560px) {
  .stage {
    gap: 14px;
    padding: 14px 20px 18px;
  }

  .stage :deep(.vinyl) {
    transform: scale(0.76);
    margin: -30px 0;
  }
}
</style>
