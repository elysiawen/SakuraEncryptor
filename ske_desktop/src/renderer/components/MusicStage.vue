<!--
  Music window.

  The track itself plays in the shell (`App.vue`), so this window is a remote:
  it mirrors the transport published over IPC and sends actions back. That is
  what makes closing the window harmless — the audio element is not in here.

  Layout follows the web player's full-screen page (sakura-music): the cover
  fills the left column over a blurred copy of itself, the lyrics scroll on the
  right, and the transport sits under both.
-->
<template>
  <div class="music" :class="{ playing: state.playing }">
    <div
      class="backdrop"
      :style="track.cover ? { backgroundImage: `url(${track.cover})` } : {}"
      aria-hidden="true"
    />
    <div class="scrim" aria-hidden="true" />

    <header class="titlebar">
      <div class="titlebar-left">
        <span class="badge">
          <span class="eq" :class="{ on: state.playing }" aria-hidden="true"><i /><i /><i /></span>
          正在播放
        </span>
        <span class="title" :title="track.title || state.name">{{ track.title || '音乐' }}</span>
      </div>

      <div class="actions">
        <button
          v-if="state.url"
          class="icon"
          :class="{ active: linkState === 'copied' }"
          type="button"
          :title="copyTitle"
          @click="copyLink"
        >
          <svg v-if="linkState === 'copied'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12.5l4.5 4.5L19 7.5" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
            <rect x="9" y="9" width="10" height="10" rx="2" />
            <path d="M15 8.6V6.4A1.4 1.4 0 0 0 13.6 5H6.4A1.4 1.4 0 0 0 5 6.4v7.2A1.4 1.4 0 0 0 6.4 15h2.2" />
          </svg>
        </button>

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
        <aside class="aside">
          <div class="art">
            <img v-if="track.cover" :src="track.cover" alt="" />
            <span v-else class="art-glyph" aria-hidden="true">♪</span>
          </div>

          <div class="titles">
            <h1 class="track" :title="track.title || state.name">{{ track.title || '—' }}</h1>
            <p v-if="track.byline" class="byline">{{ track.byline }}</p>
          </div>

          <p class="meta">
            <span class="chip mono">{{ mimeLabel }}</span>
            <span v-if="sizeLabel" class="chip mono">{{ sizeLabel }}</span>
          </p>
        </aside>

        <section class="lyric-area">
          <div
            ref="lyricBox"
            class="lyric-scroll"
            @wheel.passive="onLyricScroll"
            @touchmove.passive="onLyricScroll"
          >
            <template v-if="lyric.lines.length">
              <p
                v-for="(line, index) in lyric.lines"
                :key="`${line.time}-${index}`"
                :ref="(element) => setLineRef(index, element)"
                class="lyric-line"
                :class="{ on: index === activeLine }"
                :style="{ opacity: lyricOpacity(index) }"
                @click="seekToLine(line.time)"
              >
                <span class="lyric-text">{{ line.text || '♪' }}</span>
              </p>
            </template>
          </div>

          <!-- Outside the scroller: the scroll box reserves half a screen of
               padding for the scrolling lyrics, which is what pushed the
               no-lyrics note below the middle of the stage. -->
          <div v-if="!lyric.lines.length" class="lyric-empty">
            <span class="art-glyph" aria-hidden="true">♪</span>
            <strong>{{ lyric.loading ? '歌词加载中…' : '这首歌没有可用的歌词' }}</strong>
            <span>把同名的 .lrc 放在音频旁边即可显示</span>
          </div>
        </section>
      </template>
    </main>

    <footer v-if="state.url && !state.error" class="bar">
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
        <button
          class="round small mode"
          :class="{ on: state.mode !== 'list' }"
          type="button"
          :title="modeLabel"
          @click="send('mode')"
        >
          <svg
            v-if="state.mode === 'one'"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M17 2l4 4-4 4" />
            <path d="M3 11v-1a4 4 0 0 1 4-4h14" />
            <path d="M7 22l-4-4 4-4" />
            <path d="M21 13v1a4 4 0 0 1-4 4H3" />
            <path d="M11 10h1v4" />
          </svg>
          <svg
            v-else-if="state.mode === 'shuffle'"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M16 3h5v5" />
            <path d="M4 20 21 3" />
            <path d="M21 16v5h-5" />
            <path d="M15 15l6 6" />
            <path d="M4 4l5 5" />
          </svg>
          <svg
            v-else
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M17 2l4 4-4 4" />
            <path d="M3 11v-1a4 4 0 0 1 4-4h14" />
            <path d="M7 22l-4-4 4-4" />
            <path d="M21 13v1a4 4 0 0 1-4 4H3" />
          </svg>
        </button>

        <button class="round" type="button" title="上一首" :disabled="total < 2" @click="send('prev')">
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h2v14H6zM19 5.4 9.5 12l9.5 6.6z" /></svg>
        </button>

        <button class="round main" type="button" :title="state.playing ? '暂停（空格）' : '播放（空格）'" @click="send('toggle')">
          <svg v-if="state.playing" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5h3.2v14H8zM12.8 5H16v14h-3.2z" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8z" /></svg>
        </button>

        <button class="round" type="button" title="下一首" :disabled="total < 2" @click="send('next')">
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M16 5h2v14h-2zM5 5.4 14.5 12 5 18.6z" /></svg>
        </button>

        <div class="volume">
          <button class="round flat" type="button" :title="muted ? '取消静音' : '静音'" @click="onMute">
            <svg v-if="muted" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
              <path d="M16 9.5l4 5M20 9.5l-4 5" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
              <path d="M15.5 9a4 4 0 0 1 0 6" />
            </svg>
          </button>
          <!-- Only on hover: the transport row keeps its size, the bar floats. -->
          <div class="vol-pop">
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
        </div>
      </div>
    </footer>

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
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { formatTime, PLAY_MODE_LABELS } from '../state/player.js'
import { session } from '../state/session.js'
import { describeTrack, ensureTags } from '../state/track-tags.js'
import { copyText } from '../utils/clipboard.js'
import { activeLineIndex, parseLrc } from '../utils/lyrics.js'

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
  /** Mirrors the shell: the order lives there, this window only cycles it. */
  mode: 'list',
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

// ── Lyrics ──
const lyric = reactive({ lines: [], loading: false })
const lyricBox = ref(null)
/** Elements of the rendered lines, indexed the same way as `lyric.lines`. */
const lineEls = []
/** Pause auto-follow for a moment after the user scrolls by hand. */
let manualScrollUntil = 0
/** Guards against a slow read landing after the track already changed. */
let lyricToken = 0

const total = computed(() => state.playlist.length)
/** Cover, title and names: from the file's tags, else from the file name. */
const track = computed(() => describeTrack(state.url, state.name))
const mimeLabel = computed(() => (state.mime || 'audio').replace(/^audio\//, '').toUpperCase())
const sizeLabel = computed(() => (state.size ? formatSize(state.size) : ''))
const muted = computed(() => state.muted || state.volume === 0)
const modeLabel = computed(() => `${PLAY_MODE_LABELS[state.mode] || PLAY_MODE_LABELS.list}（点击切换）`)
const percent = computed(() =>
  state.duration ? Math.min(100, (state.currentTime / state.duration) * 100) : 0,
)
const activeLine = computed(() => activeLineIndex(lyric.lines, state.currentTime))

/** The current entry: the playlist item carries the path lyrics are found by. */
const currentItem = computed(() => state.playlist[state.index] || null)

const trackKey = computed(
  () => `${currentItem.value?.mode || ''}|${currentItem.value?.path || ''}|${state.name}`,
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

/** Distance from the active line fades the rest, as the web player does. */
function lyricOpacity(index) {
  const active = activeLine.value
  if (active < 0) return 0.62
  const distance = Math.abs(index - active)
  if (distance === 0) return 1
  if (distance === 1) return 0.46
  if (distance === 2) return 0.3
  if (distance === 3) return 0.2
  return 0.12
}

function setLineRef(index, element) {
  if (element instanceof HTMLElement) lineEls[index] = element
}

/** Put the active line at ~36% height instead of dead centre. */
function scrollToActive(index) {
  const box = lyricBox.value
  const element = lineEls[index]
  if (!box || !element) return
  const top = element.offsetTop - box.clientHeight * 0.36 + element.clientHeight / 2
  box.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
}

function onLyricScroll() {
  manualScrollUntil = Date.now() + 4000
}

function seekToLine(time) {
  state.currentTime = time
  send('seek', time)
}

/**
 * Read the sidecar `.lrc` for the current track.
 *
 * Discovery happens in the main process: this window only knows the entry's
 * path, and the folder listing (and any decryption) belongs over there.
 */
async function loadLyrics() {
  const token = (lyricToken += 1)
  lineEls.length = 0
  lyric.lines = []
  manualScrollUntil = 0
  // A new track starts at its first line, not where the old one was reading.
  if (lyricBox.value) lyricBox.value.scrollTop = 0

  const item = currentItem.value
  const read = window.ske?.lyrics?.read
  if (!read || (!item?.path && !state.name)) {
    lyric.loading = false
    return
  }

  lyric.loading = true
  try {
    const res = await read({
      mode: item?.mode || 'cloud',
      path: item?.path || '',
      name: item?.name || state.name,
      sessionId: item?.sessionId,
    })
    if (token !== lyricToken) return
    lyric.lines = parseLrc(res?.text || '')
  } catch {
    // A missing or unreadable .lrc is normal, not an error worth a dialog.
    if (token === lyricToken) lyric.lines = []
  } finally {
    if (token === lyricToken) lyric.loading = false
  }
}

watch(activeLine, (index) => {
  if (index < 0 || Date.now() < manualScrollUntil) return
  void nextTick(() => scrollToActive(index))
})

watch([trackKey, () => state.url], () => loadLyrics(), { immediate: true })

/** Seed from the payload so the window is not blank before the first sync. */
function adoptPayload(payload) {
  if (!payload?.item) return
  state.name = payload.item.name || ''
  state.playlist = payload.playlist || []
  state.index = Number.isInteger(payload.index) ? payload.index : -1
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
    mode: next.mode || state.mode,
    playlist: Array.isArray(next.playlist) ? next.playlist : [],
    index: Number.isInteger(next.index) ? next.index : -1,
    loading: !!next.loading,
    error: next.error || '',
  })
  ensureTags(state.url)
}

/**
 * The window title follows the track name, not the file name, once the tags
 * have been read: it is what the taskbar shows.
 */
watch(
  () => track.value.title,
  (title) => {
    if (title) document.title = `${title} — Sakura Encryptor`
  },
  { immediate: true },
)

/**
 * Forward one action to the shell.
 *
 * A rejected call or an `{ ok: false }` reply means the bridge is dead — usually
 * because the app was upgraded while this window's older main process kept
 * running. Saying so beats leaving the user on an empty cover.
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

/**
 * Copy the decrypted stream URL, for an external player.
 *
 * The audio element lives in the shell, so `state.url` arrives over IPC — it is
 * the same loopback endpoint the shell reads, token included. Feedback is the
 * button itself: this window renders no toast list.
 */
const linkState = ref('')
let linkTimer = 0

const copyTitle = computed(() => {
  if (linkState.value === 'copied') return '链接已复制，可粘贴到外部播放器；关闭应用后失效'
  if (linkState.value === 'failed') return '复制失败，请重试'
  return '复制解密播放链接'
})

async function copyLink() {
  if (!state.url) return
  const ok = await copyText(state.url)
  linkState.value = ok ? 'copied' : 'failed'
  clearTimeout(linkTimer)
  linkTimer = setTimeout(() => {
    linkState.value = ''
  }, 2400)
}

function retry() {
  if (state.index >= 0) send('playAt', state.index)
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
  background: var(--bg);
  color: var(--text);
}

/* ── Ambience: the cover, blown up and blurred, under a theme-tinted scrim ── */
.backdrop {
  position: absolute;
  inset: -14%;
  background-size: cover;
  background-position: center;
  filter: blur(64px) saturate(1.5);
  transform: scale(1.18);
  opacity: 0.5;
  pointer-events: none;
}

.scrim {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(75% 60% at 16% 0%, color-mix(in srgb, var(--accent) 26%, transparent), transparent 64%),
    radial-gradient(70% 60% at 92% 100%, color-mix(in srgb, var(--accent) 14%, transparent), transparent 62%),
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--bg) 76%, transparent) 0%,
      color-mix(in srgb, var(--bg) 90%, transparent) 55%,
      color-mix(in srgb, var(--bg) 96%, transparent) 100%
    );
  backdrop-filter: blur(3px);
}

/* ── Title bar (the window is frameless, so this is the drag strip) ── */
.titlebar {
  position: relative;
  z-index: 3;
  -webkit-app-region: drag;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 52px;
  padding: 0 12px 0 20px;
}

.titlebar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: none;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0.02em;
  color: var(--text-dim);
  background: color-mix(in srgb, var(--panel) 70%, transparent);
  border: 1px solid var(--line);
  backdrop-filter: blur(12px);
}

.eq {
  display: inline-flex;
  align-items: flex-end;
  gap: 2.5px;
  height: 11px;
}

.eq i {
  width: 2.5px;
  border-radius: 2px;
  background: var(--accent-strong);
  height: 4px;
}

.eq.on i {
  animation: eq 0.9s ease-in-out infinite;
}

.eq.on i:nth-child(2) {
  animation-delay: 0.18s;
}

.eq.on i:nth-child(3) {
  animation-delay: 0.36s;
}

@keyframes eq {
  0%, 100% { height: 3px; }
  50% { height: 11px; }
}

.title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--muted);
}

.actions {
  display: flex;
  align-items: center;
  gap: 2px;
  -webkit-app-region: no-drag;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.icon svg {
  width: 16px;
  height: 16px;
}

.icon:hover,
.icon.active {
  background: var(--panel-hover);
  color: var(--text);
}

/* ── Stage: cover left, lyrics right ── */
.stage {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  display: grid;
  /*
   * Two capped columns, centred as a block: the lyric column gives way to a
   * long line but never stretches across the whole window, and short lyrics sit
   * near the middle instead of hugging the cover.
   */
  grid-template-columns:
    clamp(200px, 26vw, 310px)
    minmax(280px, clamp(360px, 44vw, 660px));
  justify-content: center;
  column-gap: clamp(24px, 4.5vw, 84px);
  padding: 8px 40px 12px;
}

.aside {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  min-width: 0;
}

.art {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  border-radius: 18px;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
}

.art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.art-glyph {
  font-size: 44px;
  color: var(--muted-strong);
  opacity: 0.7;
}

.track {
  margin: 0;
  font-size: clamp(19px, 2vw, 26px);
  font-weight: 700;
  line-height: 1.25;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

/* Title and its artist · album line: one block, so the column gap does not
   push the two apart like separate sections. */
.titles {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.byline {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-dim);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}

.chip {
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: color-mix(in srgb, var(--panel) 62%, transparent);
  font-size: 11px;
  color: var(--text-dim);
}

/* ── Lyrics ── */
.lyric-area {
  flex: 1;
  min-width: 0;
  position: relative;
}

.lyric-scroll {
  /* Absolute so `offsetTop` of a line is measured inside this box, which is
     what the scroll-to-active maths assumes. */
  position: absolute;
  inset: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  /* Half a screen of air above and below: the first and last lines can reach
     the middle of the stage instead of stopping at its edge. */
  padding: 32vh 4px 32vh 2px;
  scrollbar-width: none;
  /* Multi-stop so the fade has no visible edge, and it works on any backdrop
     because it only fades the lyrics themselves. */
  mask-image: linear-gradient(
    180deg,
    transparent 0%,
    rgba(0, 0, 0, 0.2) 7%,
    rgba(0, 0, 0, 0.62) 17%,
    #000 30%,
    #000 70%,
    rgba(0, 0, 0, 0.62) 83%,
    rgba(0, 0, 0, 0.2) 93%,
    transparent 100%
  );
  -webkit-mask-image: linear-gradient(
    180deg,
    transparent 0%,
    rgba(0, 0, 0, 0.2) 7%,
    rgba(0, 0, 0, 0.62) 17%,
    #000 30%,
    #000 70%,
    rgba(0, 0, 0, 0.62) 83%,
    rgba(0, 0, 0, 0.2) 93%,
    transparent 100%
  );
}

.lyric-scroll::-webkit-scrollbar {
  display: none;
}

.lyric-line {
  margin: 0;
  padding: 9px 0;
  font-size: clamp(19px, 1.9vw, 27px);
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.01em;
  color: var(--text);
  cursor: pointer;
  /* Scaling happens from the left edge, so the text grows into the empty
     column on the right instead of shifting the whole block. */
  transform-origin: left center;
  transition: opacity 0.45s ease, color 0.3s ease, transform 0.3s ease;
  text-align: left;
  word-break: break-word;
}

.lyric-line:hover .lyric-text {
  color: var(--accent-strong);
}

.lyric-line.on {
  color: var(--accent-strong);
  transform: scale(1.045);
}

.lyric-line.on .lyric-text {
  text-shadow: 0 0 26px color-mix(in srgb, var(--accent-strong) 40%, transparent);
}

.lyric-text {
  display: block;
  transition: color 0.25s ease;
}

/* Centred in the lyric column itself, not in the scroller: the scroller's
   half-screen paddings are for scrolling lyrics, not for this note. */
.lyric-empty {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--muted);
  text-align: center;
}

.lyric-empty strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-dim);
}

.lyric-empty span:last-child {
  font-size: 12px;
  color: var(--muted-strong);
}

/* ── Transport ── */
.bar {
  position: relative;
  z-index: 2;
  flex: none;
  padding: 0 40px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.seek-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.time {
  flex: none;
  width: 42px;
  font-size: 11px;
  color: var(--muted);
  text-align: center;
}

.seek,
.vol {
  --p: 0%;
  flex: 1;
  height: 4px;
  margin: 0;
  border-radius: 2px;
  appearance: none;
  background: linear-gradient(
    90deg,
    var(--accent-strong) 0%,
    var(--accent-strong) var(--p),
    var(--line) var(--p),
    var(--line) 100%
  );
  cursor: pointer;
}

.seek:disabled {
  cursor: default;
  opacity: 0.5;
}

.seek::-webkit-slider-thumb,
.vol::-webkit-slider-thumb {
  appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--text);
  border: 2px solid var(--bg);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.seek:hover::-webkit-slider-thumb,
.seek:focus-visible::-webkit-slider-thumb,
.vol:hover::-webkit-slider-thumb,
.vol:focus-visible::-webkit-slider-thumb {
  opacity: 1;
}

.controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
}

.round {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: color-mix(in srgb, var(--panel) 70%, transparent);
  color: var(--text);
  cursor: pointer;
  transition: background 0.15s ease, transform 0.12s ease, opacity 0.15s ease;
}

.round svg {
  width: 17px;
  height: 17px;
}

.round:hover:not(:disabled) {
  background: var(--panel-hover);
  transform: translateY(-1px);
}

.round:disabled {
  opacity: 0.35;
  cursor: default;
}

/* Full-size hit area without the chip: the mute glyph belongs to the volume
   zone rather than to the transport buttons. */
.round.flat {
  border-color: transparent;
  background: transparent;
  color: var(--text-dim);
}

.round.flat:hover:not(:disabled) {
  background: var(--panel-hover);
  color: var(--text);
}

.round.main {
  width: 52px;
  height: 52px;
  border: 0;
  background: var(--accent);
  color: #fff;
  box-shadow: 0 10px 26px color-mix(in srgb, var(--accent) 42%, transparent);
}

.round.main:hover {
  background: var(--accent-strong);
}

.round.main svg {
  width: 20px;
  height: 20px;
}

.round.small {
  width: 32px;
  height: 32px;
  border-color: transparent;
  background: transparent;
  color: var(--muted);
}

.round.small svg {
  width: 15px;
  height: 15px;
}

.round.small:hover {
  color: var(--text);
  background: var(--panel-hover);
}

/* An order other than the default is worth seeing without hovering. */
.round.small.on,
.round.small.on:hover {
  color: var(--accent-strong);
}

.volume {
  /* Collapsed to the icon: the slider is a popover, so revealing it never
     nudges the transport row sideways. */
  position: relative;
  display: flex;
  align-items: center;
  flex: 0 0 auto;
}

.vol-pop {
  position: absolute;
  left: calc(100% + 8px);
  top: 50%;
  display: flex;
  padding: 7px 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: color-mix(in srgb, var(--panel) 94%, transparent);
  backdrop-filter: blur(14px);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.34);
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%) translateX(-6px);
  transition: opacity 0.16s ease, transform 0.16s ease;
}

/* Bridges the gap to the icon: without it the hover ends the moment the
   pointer leaves the button, and the bar vanishes on the way to it. */
.vol-pop::after {
  content: '';
  position: absolute;
  right: 100%;
  top: 0;
  bottom: 0;
  width: 10px;
}

.volume:hover .vol-pop,
.volume:focus-within .vol-pop {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(-50%);
}

.vol-pop .vol {
  flex: none;
  width: 104px;
  height: 4px;
  cursor: pointer;
}

/* ── Empty / error states ── */
.empty {
  grid-column: 1 / -1;
  align-self: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}

.spinner {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2px solid var(--line-strong);
  border-top-color: var(--accent-strong);
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.ghost {
  padding: 6px 14px;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--text-dim);
  font-size: 12px;
  cursor: pointer;
}

.ghost:hover {
  background: var(--panel-hover);
  color: var(--text);
}

/* ── Queue ── */
.queue {
  position: absolute;
  top: 52px;
  right: 0;
  bottom: 0;
  width: 280px;
  z-index: 4;
  display: flex;
  flex-direction: column;
  background: color-mix(in srgb, var(--panel) 92%, transparent);
  border-left: 1px solid var(--line);
  backdrop-filter: blur(16px);
}

.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 10px;
  font-size: 12px;
  font-weight: 650;
  color: var(--text-dim);
  border-bottom: 1px solid var(--line-soft);
}

.queue-head .count {
  color: var(--muted-strong);
}

.queue-body {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}

.queue-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-dim);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}

.queue-item:hover {
  background: var(--panel-hover);
  color: var(--text);
}

.queue-item.on {
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.idx {
  flex: none;
  width: 18px;
  color: var(--muted-strong);
  font-size: 11px;
  text-align: right;
}

.qname {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease, transform 0.22s cubic-bezier(0.22, 0.61, 0.36, 1);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateX(16px);
}

/* Short windows: the cover gives way before the lyrics do. */
@media (max-height: 560px) {
  .stage {
    grid-template-columns:
      clamp(150px, 22vw, 210px)
      minmax(260px, clamp(320px, 46vw, 620px));
    column-gap: clamp(18px, 3vw, 48px);
    padding: 4px 24px 8px;
  }

  .bar {
    padding: 0 24px 10px;
  }
}
</style>
