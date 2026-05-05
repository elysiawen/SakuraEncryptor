<template>
  <div class="audio-player glass-card">
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

    <!-- Timeline -->
    <div class="timeline-container">
      <span class="timetext">{{ formatTime(currentTime) }}</span>
      <div class="progress-bar-wrapper" @click="handleSeek" ref="progressWrapper">
        <div class="progress-bar-bg">
          <div class="progress-bar-fill" :style="{ width: progressPercent + '%' }"></div>
          <div class="progress-bar-thumb" :style="{ left: progressPercent + '%' }"></div>
        </div>
      </div>
      <span class="timetext">{{ formatTime(duration) }}</span>
    </div>

    <!-- Controls -->
    <div class="controls-row">
      <div class="volume-control">
        <button class="ctrl-btn sm" @click="toggleMute">
          {{ isMuted || volume === 0 ? '🔇' : (volume > 0.5 ? '🔊' : '🔉') }}
        </button>
        <input
          type="range" min="0" max="1" step="0.01"
          :value="volume"
          @input="e => { volume = parseFloat(e.target.value); updateVolume() }"
          class="volume-slider"
        />
      </div>

      <div class="main-controls">
        <button class="ctrl-btn play-pause-btn" @click="togglePlay" :disabled="!playUrl">
          <span class="icon" v-if="!isPlaying">▶</span>
          <span class="icon pause-icon" v-else>❚❚</span>
        </button>
      </div>

      <div class="right-slot">
        <button class="ctrl-btn sm" @click="toggleLoop" :class="{ 'active-mode': isLooping }" title="循环播放">
          🔁
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useAudioControls } from '../../composables/useAudioControls.js'

const props = defineProps({
  playUrl: { type: String, default: '' },
})

const emit = defineEmits(['play', 'pause', 'error', 'ended'])

const audioEl = ref(null)
const progressWrapper = ref(null)

const {
  isPlaying, currentTime, duration, volume, isMuted, isLooping,
  togglePlay, onTimeUpdate, onLoadedMetadata, onEnded, onError,
  seek, updateVolume, toggleMute, toggleLoop, resetRetry, formatTime,
} = useAudioControls(audioEl, computed(() => props.playUrl))

const progressPercent = computed(() => {
  if (!duration.value) return 0
  return (currentTime.value / duration.value) * 100
})

function handleSeek(e) {
  seek(e, progressWrapper.value)
}

function handleEnded() {
  onEnded()
  if (!isLooping.value) {
    emit('ended')
  }
}

// Set source when playUrl changes
watch(() => props.playUrl, (url) => {
  if (url && audioEl.value) {
    resetRetry()
    audioEl.value.src = url
    audioEl.value.load()
  }
})

onMounted(() => {
  if (props.playUrl && audioEl.value) {
    audioEl.value.src = props.playUrl
  }
})

onBeforeUnmount(() => {
  if (audioEl.value) {
    audioEl.value.pause()
    audioEl.value.removeAttribute('src')
    audioEl.value.load()
  }
})

defineExpose({ audioEl, isPlaying })
</script>

<style scoped>
.audio-player {
  width: 100%;
  padding: 24px 30px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  background: rgba(20, 20, 30, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 24px;
  box-shadow: 0 30px 60px rgba(0, 0, 0, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.timeline-container {
  display: flex;
  align-items: center;
  gap: 15px;
}

.timetext {
  font-size: 12px;
  font-family: 'SF Mono', monospace;
  color: var(--text-muted);
  min-width: 40px;
  text-align: center;
}

.progress-bar-wrapper {
  flex: 1;
  height: 20px;
  display: flex;
  align-items: center;
  cursor: pointer;
}

.progress-bar-bg {
  position: relative;
  width: 100%;
  height: 6px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 3px;
  overflow: visible;
  transition: transform 0.2s;
}

.progress-bar-wrapper:hover .progress-bar-bg {
  transform: scaleY(1.2);
}

.progress-bar-fill {
  position: absolute;
  top: 0; left: 0; bottom: 0;
  background: linear-gradient(90deg, #8b5cf6, #ec4899);
  border-radius: 3px;
  pointer-events: none;
}

.progress-bar-thumb {
  position: absolute;
  top: 50%;
  width: 14px;
  height: 14px;
  background: #fff;
  border-radius: 50%;
  transform: translate(-50%, -50%) scale(0);
  box-shadow: 0 0 10px rgba(0, 0, 0, 0.5);
  transition: transform 0.2s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  pointer-events: none;
}

.progress-bar-wrapper:hover .progress-bar-thumb {
  transform: translate(-50%, -50%) scale(1);
}

.controls-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.volume-control {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100px;
}

.volume-slider {
  width: 60px;
  height: 4px;
  -webkit-appearance: none;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 2px;
  outline: none;
  cursor: pointer;
}

.volume-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 10px; height: 10px;
  border-radius: 50%;
  background: #fff;
}

.main-controls {
  display: flex;
  align-items: center;
  justify-content: center;
}

.right-slot {
  width: 100px;
  display: flex;
  justify-content: flex-end;
}

.ctrl-btn {
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.ctrl-btn.sm {
  font-size: 16px;
  opacity: 0.7;
}

.ctrl-btn.sm:hover {
  opacity: 1;
  transform: scale(1.1);
}

.ctrl-btn.active-mode {
  opacity: 1;
  color: #ec4899;
  text-shadow: 0 0 10px rgba(236, 72, 153, 0.5);
}

.play-pause-btn {
  width: 56px;
  height: 56px;
  background: linear-gradient(135deg, #8b5cf6, #3b82f6);
  border-radius: 50%;
  color: white;
  font-size: 20px;
  box-shadow: 0 10px 20px rgba(139, 92, 246, 0.4);
}

.play-pause-btn:hover {
  transform: scale(1.05);
  box-shadow: 0 15px 25px rgba(139, 92, 246, 0.5);
}

.play-pause-btn:active {
  transform: scale(0.95);
}

.icon {
  margin-left: 4px;
}

.pause-icon {
  margin-left: 0;
  font-size: 16px;
  letter-spacing: -2px;
}

@media (max-width: 600px) {
  .audio-player {
    padding: 20px;
    border-radius: 20px;
  }
}
</style>
