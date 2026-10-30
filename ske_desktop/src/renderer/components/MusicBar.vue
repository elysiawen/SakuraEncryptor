<!--
  Sidebar transport for the shell's audio element.

  It is not a mini player window: the track plays in the shell (see `App.vue`),
  so this bar is the persistent way back to the music page after that page is
  closed, and the only control surface while browsing.
-->
<template>
  <div v-if="visible" class="music-bar">
    <button class="head" type="button" title="打开音乐页" @click="openPage">
      <span class="art">
        <img v-if="track.cover" :src="track.cover" alt="" />
        <span v-else class="note" aria-hidden="true">♪</span>
      </span>
      <span class="meta">
        <span class="title" :title="track.title || player.name">{{ track.title || '音乐' }}</span>
        <span class="state">{{ track.byline || (player.playing ? '正在播放' : '已暂停') }}</span>
      </span>
    </button>

    <!-- Anchored to the card's top-right corner, not the control row: stopping
         is about the whole bar, not about the track. -->
    <button class="mini close" type="button" title="停止播放" @click="stop">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
        <path d="M7 7l10 10M17 7L7 17" />
      </svg>
    </button>

    <input
      class="seek"
      type="range"
      min="0"
      :max="player.duration || 0"
      step="0.1"
      :value="player.currentTime"
      :style="{ '--p': `${percent}%` }"
      :disabled="!player.duration"
      aria-label="播放进度"
      @input="onSeek"
    />

    <div class="controls">
      <button class="mini" type="button" title="上一首" :disabled="player.playlist.length < 2" @click="onPrev">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h2v14H6zM19 5.4 9.5 12l9.5 6.6z" /></svg>
      </button>

      <button class="mini main" type="button" :title="player.playing ? '暂停' : '播放'" @click="togglePlay">
        <svg v-if="player.playing" viewBox="0 0 24 24" fill="currentColor">
          <path d="M8 5h3.2v14H8zM12.8 5H16v14h-3.2z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8z" /></svg>
      </button>

      <button class="mini" type="button" title="下一首" :disabled="player.playlist.length < 2" @click="onNext">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M16 5h2v14h-2zM5 5.4 14.5 12 5 18.6z" /></svg>
      </button>

      <span class="time mono">
        {{ formatTime(player.currentTime) }}<template v-if="player.duration"> / {{ formatTime(player.duration) }}</template>
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { close, formatTime, next, player, prev, seekTo, togglePlay } from '../state/player.js'
import { describeTrack, ensureTags } from '../state/track-tags.js'

const visible = computed(() => player.kind === 'audio' && !!player.url)
const track = computed(() => describeTrack(player.url, player.name))
const percent = computed(() =>
  player.duration ? Math.min(100, (player.currentTime / player.duration) * 100) : 0,
)

watch(
  () => player.url,
  (url) => ensureTags(url),
  { immediate: true },
)

/**
 * Reopen (or refocus) the music window; the payload is how it learns the track.
 *
 * Both values come off the reactive store, so they are proxies: spreading them
 * is what makes the payload survive the structured clone of IPC.
 */
function openPage() {
  if (!player.item) return
  window.ske.player
    .openWindow({
      item: { ...player.item },
      playlist: player.playlist.map((item) => ({ ...item })),
      index: player.index,
      // Come back to the remote that is already open instead of adding another.
      reuse: true,
    })
    .catch(() => {})
}

function onSeek(event) {
  seekTo(Number(event.target.value))
}

function onPrev() {
  prev().catch(() => {})
}

function onNext() {
  next().catch(() => {})
}

async function stop() {
  // Stop also dismisses the music window: leaving a dead player on screen
  // would only invite a click that does nothing.
  await close().catch(() => {})
  window.ske.player.closeWindow().catch(() => {})
}
</script>

<style scoped>
.music-bar {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 0 2px 8px;
  padding: 9px 9px 7px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--panel);
  /* Mounts with the track, not with the app: the rise is what tells the user
     where the music went after clicking a song. */
  animation: bar-rise 0.24s cubic-bezier(0.22, 0.61, 0.36, 1) both;
}

@keyframes bar-rise {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  /* Room for the close button in the corner, so the title ellipsises before
     it runs underneath. */
  padding: 0 18px 0 0;
  border: 0;
  background: transparent;
  text-align: left;
}

.art {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 9px;
  overflow: hidden;
  background: linear-gradient(135deg, #8b5cf6, #ec4899);
  color: rgba(255, 255, 255, 0.95);
}

.art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.note {
  font-size: 15px;
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.title {
  font-size: 11.5px;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.state {
  font-size: 10px;
  color: var(--muted);
  /* The artist · album line is as long as the tags make it. */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Filled track: the same slider language as the player window. */
.seek {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 12px;
  margin: 0;
  background: transparent;
  cursor: pointer;
}

.seek::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--accent-strong) 0%,
    var(--accent-strong) var(--p, 0%),
    var(--line-strong) var(--p, 0%),
    var(--line-strong) 100%
  );
}

.seek::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 9px;
  height: 9px;
  margin-top: -3px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 2px rgba(0, 0, 0, 0.25);
}

.seek:disabled {
  cursor: default;
  opacity: 0.5;
}

.controls {
  display: flex;
  align-items: center;
  gap: 2px;
}

.mini {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-dim);
}

.mini:hover:not(:disabled) {
  background: var(--panel-hover);
  color: var(--text);
}

.mini svg {
  width: 14px;
  height: 14px;
}

.mini.main {
  color: var(--accent-strong);
}

/* Out of the control row and into the corner of the card. */
.mini.close {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
}

.mini.close svg {
  width: 12px;
  height: 12px;
}

/* Played out of total, right-aligned under the controls. */
.time {
  flex: 1;
  text-align: right;
  font-size: 10px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
