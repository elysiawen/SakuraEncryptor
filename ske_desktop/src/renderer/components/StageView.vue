<template>
  <div class="stage-shell">
    <!-- Audio brings its own window chrome (drag region included), so the video
         bar stays out of its way. -->
    <header v-if="stageKind !== 'audio'" class="bar">
      <div class="title" :title="player.name">{{ player.name || '播放器' }}</div>

      <div class="actions">
        <!-- VideoStage teleports its caption button here: controls that belong to
             the picture live together at the window's top bar. -->
        <div class="sub-slot" />

        <button
          v-if="player.url"
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

        <button class="icon" type="button" title="关闭" @click="closeWindow">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M7 7l10 10M17 7L7 17" />
          </svg>
        </button>
      </div>
    </header>

    <!-- The music window is a remote for the shell's audio element; it owns no
         stream, so closing it cannot stop the track. -->
    <MusicStage v-if="stageKind === 'audio'" :payload="stagePayload" />

    <template v-else>
      <VideoStage
        v-if="player.kind === 'video' && player.url"
        :url="player.url"
        :item="player.item"
        @ended="onEnded"
        @error="onMediaError"
      />

      <ImageStage
        v-else-if="player.kind === 'image' && player.url"
        :src="player.url"
        :alt="player.name"
        :can-prev="player.index > 0"
        :can-next="player.index >= 0 && player.index < total - 1"
        @prev="prev"
        @next="next"
        @error="onMediaError"
      />

      <div v-else class="centre">
        <span v-if="!player.error && !localError" class="spinner" />
        <p>{{ localError || player.error || '正在解密…' }}</p>
      </div>
    </template>

    <!-- Playlist drawer: the player window has no shell bar, so the folder's
         queue lives here instead. -->
    <Transition name="fade">
      <aside v-if="listOpen && total > 1" class="queue" @click.stop>
        <div class="queue-head">
          <span>播放列表</span>
          <span class="count mono">{{ total }}</span>
        </div>
        <div class="queue-body">
          <button
            v-for="(item, i) in player.playlist"
            :key="item.path || i"
            class="queue-item"
            :class="{ on: i === player.index }"
            type="button"
            @click="jumpTo(i)"
          >
            <span class="idx mono">{{ i + 1 }}</span>
            <span class="qname">{{ item.name }}</span>
          </button>
        </div>
      </aside>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import VideoStage from './VideoStage.vue'
import ImageStage from './ImageStage.vue'
import MusicStage from './MusicStage.vue'
import { close, next, player, playAt, playEntry, prev, sameEntry } from '../state/player.js'
import { session, toast } from '../state/session.js'
import { copyText } from '../utils/clipboard.js'

const total = computed(() => player.playlist.length)
const listOpen = ref(false)
const maximized = ref(false)
const localError = ref('')
/** Which kind this window is showing; audio gets a different chrome entirely. */
const stageKind = ref('')
const stagePayload = ref(null)
const autoPlayNext = computed(() => session.settings?.playback?.autoPlayNext !== false)

let offLoad = null

/**
 * The window is handed its file by the main process, then opens it here.
 * `inline: true` is essential: playEntry would otherwise ask for *another*
 * player window and nothing would ever be decrypted.
 */
async function load(payload) {
  if (!payload?.item) {
    localError.value = '播放窗口没有收到文件信息'
    return
  }

  // The same file is already on stage: reloading would only restart it from
  // zero. A fresh queue can still be adopted without touching playback.
  if (player.url && sameEntry(payload.item, player.item)) {
    if (payload.playlist?.length) {
      player.playlist = payload.playlist
      if (Number.isInteger(payload.index)) player.index = payload.index
    }
    return
  }

  localError.value = ''
  listOpen.value = false
  document.title = `${payload.item.name || '播放'} — Sakura Encryptor`

  stageKind.value = payload.item.kind || 'video'
  stagePayload.value = payload

  // Audio plays in the shell; decrypting it here would open a second stream
  // that dies with this window. `close()` only drops a video this window may
  // have been showing before it was handed a track.
  if (stageKind.value === 'audio') {
    await close()
    return
  }

  try {
    await playEntry(payload.item, {
      playlist: payload.playlist || [],
      index: Number.isInteger(payload.index) ? payload.index : -1,
      inline: true,
    })
  } catch (err) {
    localError.value = err.message
  }
}

function onEnded() {
  if (autoPlayNext.value) next()
}

/** Picking from the drawer: `playAt` reopens the entry inline in this window. */
async function jumpTo(index) {
  listOpen.value = false
  try {
    await playAt(index)
  } catch (err) {
    toast(err.message, 'error')
  }
}

/**
 * Hand the decrypted stream to an external player.
 *
 * The loopback URL is a live decrypt endpoint — mpv, VLC and PotPlayer can open
 * it and seek it over Range without the plaintext ever being written to disk.
 * Its lifetime is the app's and the track's, so the tooltip says as much.
 *
 * The feedback lives in the button instead of a toast: this window draws no
 * toast list, and a message nobody can see is worse than no message at all.
 */
const linkState = ref('')
let linkTimer = 0

const copyTitle = computed(() => {
  if (linkState.value === 'copied') return '链接已复制，可粘贴到外部播放器；关闭应用后失效'
  if (linkState.value === 'failed') return '复制失败，请重试'
  return '复制解密播放链接'
})

async function copyLink() {
  if (!player.url) return
  const ok = await copyText(player.url)
  linkState.value = ok ? 'copied' : 'failed'
  clearTimeout(linkTimer)
  linkTimer = setTimeout(() => {
    linkState.value = ''
  }, 2400)
}

function onMediaError(message) {
  toast(message, 'error')
}

function toggleFullscreen() {
  window.ske?.window?.toggleFullScreen()
}

function minimize() {
  window.ske?.window?.minimize()
}

async function toggleMaximize() {
  const res = await window.ske?.window?.toggleMaximize()
  if (res?.ok) maximized.value = res.maximized
}

function closeWindow() {
  close()
  window.ske?.window?.close()
}

function onKeydown(event) {
  // The music window carries its own shortcuts (Space, arrows, Esc).
  if (stageKind.value === 'audio') return

  if (event.key === 'Escape') {
    // Escape backs out of the drawer first; a second press closes the window.
    if (listOpen.value) listOpen.value = false
    else closeWindow()
  } else if (event.key === 'f' || event.key === 'F') toggleFullscreen()
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)

  const res = await window.ske.player.payload()
  if (res?.ok && res.payload) await load(res.payload)
  else localError.value = res?.error || '播放窗口没有收到文件信息'

  // Reusing the open window: a later pick in the library arrives as an event
  // instead of another window stacking up.
  offLoad = window.ske.player.onLoad((payload) => load(payload))
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  offLoad?.()
  close()
})
</script>

<style scoped>
.stage-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #000;
}

/* In flow, not overlay: the media gets the space below the bar instead of
   being covered by it. */
.bar {
  position: relative;
  flex: 0 0 auto;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 4px 0 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(10, 10, 12, 0.72);
  backdrop-filter: blur(18px);
  /* The window is frameless, so this bar is the title bar: drag it to move. */
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
  color: rgba(255, 255, 255, 0.9);
}

.actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
  /* Buttons must stay clickable inside the drag region. */
  -webkit-app-region: no-drag;
}

.sub-slot {
  position: relative;
  display: flex;
  align-items: center;
}

.icon {
  display: grid;
  place-items: center;
  box-sizing: border-box;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.82);
  cursor: pointer;
  outline: none;
  transition: background 0.14s ease, color 0.14s ease;
}

.icon:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.icon svg {
  display: block;
  width: 16px;
  height: 16px;
}

.icon.active {
  color: #60a5fa;
  background: rgba(96, 165, 250, 0.16);
}

/* Drawer sits under the in-flow bar, over the picture. */
.queue {
  position: absolute;
  top: 34px;
  right: 0;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  width: 264px;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(10, 10, 12, 0.9);
  backdrop-filter: blur(18px);
  color: #fff;
}

.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
  gap: 8px;
  padding: 10px 12px 8px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.6);
}

.count {
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 999px;
  padding: 0 7px;
}

.queue-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 10px;
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
  box-sizing: border-box;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  line-height: 1.45;
  text-align: left;
  cursor: pointer;
}

.queue-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.queue-item.on {
  background: rgba(96, 165, 250, 0.18);
  color: #93c5fd;
}

.idx {
  flex: 0 0 22px;
  width: 22px;
  font-size: 10.5px;
  text-align: right;
  color: rgba(255, 255, 255, 0.45);
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

.centre {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 24px;
  text-align: center;
  color: rgba(255, 255, 255, 0.7);
  font-size: 12.5px;
  line-height: 1.7;
}

.centre p {
  margin: 0;
  max-width: 420px;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.18);
  border-top-color: #60a5fa;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
