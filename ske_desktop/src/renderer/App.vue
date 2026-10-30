<template>
  <!-- A file dropped outside a drop target would otherwise navigate the window
       to `file://…`, replacing the whole UI. Views that accept drops handle the
       event first; this only stops the leftovers. -->
  <div class="shell" @dragover.prevent @drop.prevent>
    <!-- The window is frameless, so the title bar is ours. It stays outside the
         page branches: boot, error and the app all need a caption to move and
         close the window with. -->
    <TitleBar />

    <!-- Audio is hosted by the shell, not by a page or a window. That is what
         lets a track keep playing after the music page is closed, and what the
         sidebar transport bar and the music page both drive. -->
    <audio
      ref="audioEl"
      :src="player.url || undefined"
      preload="metadata"
      @loadedmetadata="syncAudioMeta"
      @durationchange="syncAudioMeta"
      @timeupdate="syncAudioTime"
      @play="syncAudioPlay(true)"
      @pause="syncAudioPlay(false)"
      @ended="onAudioEnded"
      @error="onAudioError"
    />

    <div v-if="!session.ready" class="boot">
      <span class="pulse" />
    </div>

    <div v-else-if="session.bootError" class="boot boot-error">
      <h1>无法启动</h1>
      <p>{{ session.bootError }}</p>
    </div>

    <div v-else class="app">
      <AppSidebar />

      <div class="main">
        <!-- Indeterminate bar for anything that waits on I/O (folder listing,
             connect, downloads). A spinner in the corner is easy to miss; a bar
             at the top edge is what tells the user "it is working, not frozen". -->
        <div class="progress" :class="{ on: busy }" aria-hidden="true">
          <span />
        </div>

        <!-- Video and images open in their own player window; audio stays in
             the shell, where its transport sits in the sidebar and its full
             view is the music page. -->
        <div class="viewport">
          <RouterView v-if="!isLocked" v-slot="{ Component, route }">
            <Transition name="page" mode="out-in">
              <!-- Keyed by path, not fullPath: opening a file through a query
                   must not remount the library the user is standing in. -->
              <component :is="Component" :key="route.path" />
            </Transition>
          </RouterView>
        </div>
      </div>
    </div>

    <Transition name="fade">
      <LockScreen v-if="isLocked" />
    </Transition>

    <div class="toasts">
      <TransitionGroup name="toast">
        <div
          v-for="item in session.toasts"
          :key="item.id"
          class="toast"
          :class="item.kind"
          @click="dismissToast(item.id)"
        >
          {{ item.message }}
        </div>
      </TransitionGroup>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import LockScreen from './components/LockScreen.vue'
import TitleBar from './components/TitleBar.vue'
import { bootstrap, dismissToast, isLocked, session } from './state/session.js'
import {
  advance,
  attachAudio,
  close,
  cycleMode,
  detachAudio,
  next,
  playAt,
  player,
  prev,
  seekTo,
  setVolume,
  toggleMute,
  togglePlay,
} from './state/player.js'
import { publishTrack, publishTransport, unbindMediaSession } from './state/media-session.js'
import { describeTrack } from './state/track-tags.js'
import { subscribeDownloads, downloads } from './state/downloads.js'
import { subscribeUploads } from './state/uploads.js'
import { cloud } from './state/cloud.js'
import { local } from './state/local.js'

const router = useRouter()
const audioEl = ref(null)

/** Any blocking I/O in the current view feeds the top bar. */
const busy = computed(
  () => cloud.loading || cloud.connecting || local.loading || downloads.loading,
)
let offOpenFile = null
let offCommand = null

// A `.ske` opened from the OS becomes a local-library visit. The view waits for
// the library to be unlocked before it plays anything.
function onOpenFile(payload) {
  if (!payload?.path) return
  router.push({
    path: '/local',
    query: { root: payload.dir || '', file: payload.name || '' },
  })
}

// Locking must also stop the stream: otherwise audio keeps playing behind the
// lock screen and the main process keeps a decrypted block cache alive.
watch(isLocked, (locked) => {
  if (locked) close()
})

// ── Shell audio ──
// A new URL means a new track: the element adopts it, and playback starts here
// rather than in the page that requested it, so leaving that page is harmless.
watch(
  () => player.url,
  async (url) => {
    const el = audioEl.value
    if (!el) return

    player.error = ''
    if (!url) {
      el.pause()
      el.removeAttribute('src')
      el.load()
      return
    }

    el.volume = player.volume
    el.muted = player.muted
    try {
      await el.play()
    } catch {
      // The element's error handler reports failures; a rejected play() alone
      // carries no useful text.
    }
  },
  // Post-flush: the `src` binding must be patched before play(), or the element
  // would still be sitting on the previous track.
  { flush: 'post' },
)

// The settings volume only seeds the transport before anything plays, so a
// later change cannot overwrite a level the user picked in the player.
watch(
  () => session.settings?.playback?.volume,
  (value) => {
    if (value == null || player.url) return
    setVolume(Number(value))
  },
  { immediate: true },
)

// ── OS media controls ──
// Windows SMTC, macOS Now Playing and Linux MPRIS all read a renderer's media
// session, and this renderer is the only one with an audio element to publish:
// the music window merely mirrors the transport. `describeTrack` is reactive,
// so the panel picks up embedded tags as they finish reading rather than only
// the file name.
const currentTrack = computed(() => describeTrack(player.url, player.name))

watch(currentTrack, (track) => publishTrack(track), { immediate: true })

// Cheap fields only: this runs on every timeupdate, which is what keeps the
// system timeline in step.
watch(
  () => [player.url, player.kind, player.playing, player.currentTime, player.duration],
  () => publishTransport(),
  { immediate: true },
)

function syncAudioMeta() {
  const el = audioEl.value
  if (!el) return
  player.duration = Number.isFinite(el.duration) ? el.duration : 0
}

function syncAudioTime() {
  if (audioEl.value) player.currentTime = audioEl.value.currentTime
}

function syncAudioPlay(playing) {
  player.playing = playing
}

function onAudioEnded() {
  player.playing = false
  // Repeat-one keeps looping the same track even when "play the next one" is
  // switched off: that setting is about leaving the current track.
  if (player.mode !== 'one' && session.settings?.playback?.autoPlayNext === false) return
  advance().catch(() => {})
}

function onAudioError() {
  const el = audioEl.value
  if (!el || !player.url) return

  // A replaced source can still report an error for the old file; only trust
  // the event when the element is actually sitting on the current URL.
  const source = el.currentSrc || el.getAttribute('src') || ''
  if (source && source !== player.url) return

  player.playing = false
  player.error = '音频加载失败，文件可能已损坏或无法解密'
}

// ── Music window bridge ──
// The window shows this transport and sends actions back, so it follows every
// field that is visible there: a new URL is a new track, a stop empties it.
function publishAudioState() {
  try {
    window.ske?.player?.publish?.({
      url: player.url,
      name: player.name,
      mime: player.mime,
      size: player.size,
      playing: player.playing,
      currentTime: player.currentTime,
      duration: player.duration,
      volume: player.volume,
      muted: player.muted,
      mode: player.mode,
      // A Vue reactive array is a Proxy, and structured cloning refuses those.
      // Spreading the items keeps the IPC payload plain.
      playlist: player.playlist.map((item) => ({ ...item })),
      index: player.index,
      loading: player.loading,
      error: player.error,
    })
  } catch (err) {
    // A clone failure would otherwise kill every later push, leaving the music
    // window on its fallback message with no clue why.
    console.error('[player] 无法同步播放状态', err)
  }
}

watch(
  () => [
    player.url,
    player.name,
    player.mime,
    player.size,
    player.playing,
    player.currentTime,
    player.duration,
    player.volume,
    player.muted,
    player.mode,
    player.index,
    player.playlist,
    player.loading,
    player.error,
  ],
  publishAudioState,
)

function onPlayerCommand(command) {
  switch (command?.type) {
    case 'toggle':
      togglePlay()
      break
    case 'seek':
      seekTo(Number(command.value))
      break
    case 'volume':
      setVolume(Number(command.value))
      break
    case 'mute':
      toggleMute()
      break
    case 'mode':
      cycleMode()
      break
    case 'next':
      next().catch(() => {})
      break
    case 'prev':
      prev().catch(() => {})
      break
    case 'playAt':
      playAt(Number(command.value)).catch(() => {})
      break
    case 'stop':
      close()
      break
    case 'sync':
      publishAudioState()
      break
  }
}

onMounted(() => {
  attachAudio(audioEl.value)
  offCommand = window.ske?.player?.onCommand?.(onPlayerCommand) ?? null
  bootstrap()

  // Progress must keep arriving while the user browses, not only on the
  // download page, so the subscription lives at the shell level.
  subscribeDownloads()
  subscribeUploads()
  offOpenFile = window.ske?.app?.onOpenFile?.(onOpenFile) ?? null
})

onUnmounted(() => {
  offOpenFile?.()
  offCommand?.()
  detachAudio(audioEl.value)
  unbindMediaSession()
})
</script>

<style scoped>
/* The title bar takes the first 40 px; everything else fills the rest. */
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.app {
  display: flex;
  flex: 1;
  min-height: 0;
}

.main {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.progress {
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  overflow: hidden;
  opacity: 0;
  transition: opacity 0.18s ease;
  pointer-events: none;
  z-index: 5;
}

.progress.on {
  opacity: 1;
}

.progress span {
  display: block;
  width: 38%;
  height: 100%;
  background: linear-gradient(90deg, transparent, var(--accent-strong), transparent);
  animation: progress-slide 1.1s ease-in-out infinite;
}

@keyframes progress-slide {
  0% {
    transform: translateX(-110%);
  }
  100% {
    transform: translateX(380%);
  }
}

.viewport {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.boot {
  flex: 1;
  display: grid;
  place-items: center;
  background: var(--bg);
}

.pulse {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 18px var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.9);
  }
  50% {
    opacity: 1;
    transform: scale(1.15);
  }
}

.boot-error {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 40px;
  text-align: center;
}

.boot-error h1 {
  font-size: 16px;
}

.boot-error p {
  margin: 0;
  color: var(--muted);
  font-size: 12.5px;
}

.toasts {
  position: fixed;
  /* Centred under the caption strip. The window is frameless, so the top 40 px
     is the drag region: a toast there would move the window instead of being
     read. */
  top: 56px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 200;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  max-width: 380px;
  padding: 9px 14px;
  border-radius: var(--radius-sm);
  background: var(--panel);
  border: 1px solid var(--line);
  box-shadow: var(--shadow);
  font-size: 12.5px;
  cursor: pointer;
}

.toast.ok {
  border-color: color-mix(in srgb, var(--ok) 45%, transparent);
  color: var(--ok);
}

.toast.error {
  border-color: color-mix(in srgb, var(--danger) 50%, transparent);
  color: var(--danger);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

/* Drops in from above now that the stack sits at the top of the window. */
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.toast-move {
  transition: transform 0.18s ease;
}
</style>
