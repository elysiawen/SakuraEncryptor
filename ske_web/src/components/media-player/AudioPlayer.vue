<template>
  <div class="transport">
    <audio
      ref="audioEl"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @ended="handleEnded"
      @play="isPlaying = true"
      @pause="isPlaying = false"
      @error="(e) => onError(e, (msg) => $emit('error', msg))"
      preload="metadata"
    ></audio>

    <div class="seek-row">
      <span class="time">{{ formatTime(currentTime) }}</span>
      <input
        class="seek"
        type="range"
        min="0"
        :max="duration || 0"
        step="0.1"
        :value="currentTime"
        :style="{ '--p': `${percent}%` }"
        :disabled="!duration"
        :aria-label="t('music.seek')"
        @input="onSeek"
      />
      <span class="time">{{ formatTime(duration) }}</span>
    </div>

    <div class="controls">
      <button
        class="round small mode"
        :class="{ on: mode !== 'list' }"
        type="button"
        :title="modeTitle"
        @click="$emit('cycle-mode')"
      >
        <svg
          v-if="mode === 'one'"
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
          v-else-if="mode === 'shuffle'"
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

      <button class="round" type="button" :title="t('music.previous')" :disabled="!canSkip" @click="$emit('prev')">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h2v14H6zM19 5.4 9.5 12l9.5 6.6z" /></svg>
      </button>

      <button class="round main" type="button" :title="isPlaying ? t('music.pause') : t('music.play')" @click="togglePlay">
        <svg v-if="isPlaying" viewBox="0 0 24 24" fill="currentColor">
          <path d="M8 5h3.2v14H8zM12.8 5H16v14h-3.2z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8z" /></svg>
      </button>

      <button class="round" type="button" :title="t('music.next')" :disabled="!canSkip" @click="$emit('next')">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M16 5h2v14h-2zM5 5.4 14.5 12 5 18.6z" /></svg>
      </button>

      <div class="volume">
        <button class="round flat" type="button" :title="muted ? t('music.unmute') : t('music.mute')" @click="toggleMute">
          <svg v-if="muted" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
            <path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
            <path d="M16 9.5l4 5M20 9.5l-4 5" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
            <path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z" />
            <path d="M15.5 9a4 4 0 0 1 0 6" />
          </svg>
        </button>
        <!-- Collapsed to the icon: the slider is a popover, so revealing it
             never nudges the transport row sideways. -->
        <div class="vol-pop">
          <input
            class="vol"
            type="range"
            min="0"
            max="1"
            step="0.01"
            :value="muted ? 0 : volume"
            :style="{ '--p': `${(muted ? 0 : volume) * 100}%` }"
            :aria-label="t('music.volume')"
            @input="onVolume"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAudioControls } from '../../composables/useAudioControls.js'

const props = defineProps({
  playUrl: { type: String, default: '' },
  /** Play order: `list` repeats the queue, `one` repeats the track. */
  mode: { type: String, default: 'list' },
  /** With a single track there is nothing to skip to. */
  canSkip: { type: Boolean, default: false },
})

const emit = defineEmits(['error', 'ended', 'prev', 'next', 'cycle-mode'])

const { t } = useI18n()

const audioEl = ref(null)

const {
  isPlaying, currentTime, duration, volume, isMuted,
  togglePlay, onTimeUpdate, onLoadedMetadata, onEnded, onError,
  updateVolume, toggleMute, resetRetry, formatTime,
} = useAudioControls(audioEl, computed(() => props.playUrl))

const muted = computed(() => isMuted.value || volume.value === 0)

const percent = computed(() => {
  if (!duration.value) return 0
  return Math.min(100, (currentTime.value / duration.value) * 100)
})

const modeTitle = computed(() => `${t(`music.mode.${props.mode}`)} · ${t('music.mode.hint')}`)

function handleEnded() {
  onEnded()
  emit('ended')
}

function onSeek(event) {
  if (!audioEl.value || !duration.value) return
  const value = Math.min(duration.value, Math.max(0, Number(event.target.value)))
  audioEl.value.currentTime = value
  currentTime.value = value
}

function onVolume(event) {
  volume.value = Number(event.target.value)
  isMuted.value = volume.value === 0
  updateVolume()
}

/** Restart the current track; used when the play order is "one". */
function replay() {
  if (!audioEl.value) return
  audioEl.value.currentTime = 0
  audioEl.value.play().catch(() => {})
}

/** Jump to an absolute position, used by lyric clicks and keyboard seek. */
function seekTo(seconds) {
  if (!audioEl.value) return
  const max = duration.value || 0
  audioEl.value.currentTime = Math.max(0, max ? Math.min(seconds, max) : seconds)
}

function seekBy(delta) {
  if (!audioEl.value) return
  seekTo(audioEl.value.currentTime + delta)
}

function adjustVolume(delta) {
  volume.value = Math.min(1, Math.max(0, Number(volume.value) + delta))
  isMuted.value = false
  updateVolume()
}

// Set source when playUrl changes
watch(() => props.playUrl, (url) => {
  if (!url || !audioEl.value) return
  resetRetry()
  audioEl.value.src = url
  audioEl.value.load()
})

onMounted(() => {
  if (props.playUrl && audioEl.value) {
    audioEl.value.src = props.playUrl
  }
})

onBeforeUnmount(() => {
  if (!audioEl.value) return
  audioEl.value.pause()
  audioEl.value.removeAttribute('src')
  audioEl.value.load()
})

defineExpose({
  audioEl,
  isPlaying,
  currentTime,
  duration,
  togglePlay,
  toggleMute,
  replay,
  seekTo,
  seekBy,
  adjustVolume,
})
</script>

<style scoped>
.transport {
  position: relative;
  z-index: 2;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 clamp(16px, 5vw, 40px) 16px;
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
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
  text-align: center;
}

/* Filled track: the played part is the accent, the rest stays quiet. */
.seek,
.vol {
  --p: 0%;
  flex: 1;
  height: 4px;
  margin: 0;
  border-radius: 2px;
  appearance: none;
  -webkit-appearance: none;
  background: linear-gradient(
    90deg,
    var(--accent-start) 0%,
    var(--accent-start) var(--p),
    rgba(255, 255, 255, 0.14) var(--p),
    rgba(255, 255, 255, 0.14) 100%
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
  -webkit-appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--text-primary);
  border: 2px solid var(--bg-primary);
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
  gap: clamp(8px, 2vw, 14px);
}

.round {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid var(--border-glass);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-primary);
  cursor: pointer;
  transition: background 0.15s ease, transform 0.12s ease, opacity 0.15s ease;
}

.round svg {
  width: 17px;
  height: 17px;
}

.round:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.12);
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
  color: var(--text-secondary);
}

.round.flat:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-primary);
}

.round.main {
  width: 52px;
  height: 52px;
  border: 0;
  background: var(--accent-start);
  color: #fff;
  box-shadow: 0 10px 26px rgba(139, 92, 246, 0.42);
}

.round.main:hover {
  background: #7c3aed;
  transform: translateY(-1px);
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
  color: var(--text-secondary);
}

.round.small svg {
  width: 15px;
  height: 15px;
}

.round.small:hover {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.1);
}

/* An order other than the default is worth seeing without hovering. */
.round.small.on,
.round.small.on:hover {
  color: #a78bfa;
}

.volume {
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
  border: 1px solid var(--border-glass);
  border-radius: 10px;
  background: rgba(16, 14, 36, 0.94);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
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
}

@media (max-width: 600px) {
  .transport {
    padding-bottom: 12px;
  }

  .round {
    width: 36px;
    height: 36px;
  }

  .round.main {
    width: 48px;
    height: 48px;
  }

  .time {
    width: 34px;
    font-size: 10px;
  }
}
</style>
