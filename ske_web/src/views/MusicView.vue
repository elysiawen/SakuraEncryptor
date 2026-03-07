<template>
  <div class="music-view" :class="{ 'is-playing': isPlaying }">
    <!-- Dynamic Background -->
    <div class="bg-blobs">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
    </div>
    <div class="bg-overlay"></div>

    <header class="topbar glass-nav">
      <button class="btn btn-ghost btn-icon-mobile" @click="goBack">
        <span class="desktop-text">← 返回</span>
        <span class="mobile-text">←</span>
      </button>
      <div class="header-center">
        <div class="music-title truncate" :title="decryptedName">{{ decryptedName || '音频加载中…' }}</div>
        <div class="music-subtitle" v-if="formattedSize">{{ formattedSize }}</div>
      </div>
      <button class="btn btn-ghost btn-icon-mobile" @click="downloadDecrypted" :disabled="!playUrl">
        <span class="desktop-text">⬇️ 下载</span>
        <span class="mobile-text">⬇️</span>
      </button>
    </header>

    <main class="music-container">
      <div v-if="error" class="error-msg glass-card">
        <span class="error-icon">❌</span>
        <p>{{ error }}</p>
        <button class="btn btn-primary" @click="initPlayer">重试</button>
      </div>

      <div v-else class="player-center">
        <!-- Visualizer Area -->
        <div class="vinyl-container">
          <div class="vinyl-record" :class="{ 'spin': isPlaying }">
            <img v-if="coverUrl" :src="coverUrl" class="vinyl-cover" />
            <div v-else class="vinyl-grooves"></div>
            
            <div class="vinyl-label">
              <span class="music-icon" v-if="!coverUrl">🎵</span>
              <img v-else :src="coverUrl" class="label-cover" />
            </div>
          </div>
          <!-- Tone Arm (Now outside vinyl-record to prevent spinning with it) -->
          <div class="tone-arm" :class="{ 'arm-active': isPlaying }"></div>

          <!-- Pulse rings behind record when playing -->
          <div class="pulse-ring ring-1" v-if="isPlaying"></div>
          <div class="pulse-ring ring-2" v-if="isPlaying"></div>
        </div>

        <!-- Custom Audio Controls -->
        <div class="custom-audio-player glass-card">
          <!-- Hidden Audio Element (src set via JS to allow retries) -->
          <audio 
            ref="audioEl" 
            @timeupdate="onTimeUpdate" 
            @loadedmetadata="onLoadedMetadata" 
            @ended="onEnded"
            @play="isPlaying = true"
            @pause="isPlaying = false"
            @error="onError"
            preload="metadata"
          ></audio>

          <!-- Timeline -->
          <div class="timeline-container">
            <span class="timetext current-time">{{ formatTime(currentTime) }}</span>
            <div class="progress-bar-wrapper" @click="seek" ref="progressWrapper">
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" :style="{ width: progressPercent + '%' }"></div>
                <div class="progress-bar-thumb" :style="{ left: progressPercent + '%' }"></div>
              </div>
            </div>
            <span class="timetext duration">{{ formatTime(duration) }}</span>
          </div>

          <!-- Controls -->
          <div class="controls-row">
            <!-- Volume -->
            <div class="volume-control">
              <button class="ctrl-btn sm" @click="toggleMute">
                {{ isMuted || volume === 0 ? '🔇' : (volume > 0.5 ? '🔊' : '🔉') }}
              </button>
              <input type="range" min="0" max="1" step="0.01" v-model="volume" @input="updateVolume" class="volume-slider">
            </div>

            <!-- Play/Pause -->
            <div class="main-controls">
              <button class="ctrl-btn play-pause-btn" @click="togglePlay" :disabled="!playUrl">
                <span class="icon" v-if="!isPlaying">▶</span>
                <span class="icon pause-icon" v-else>❚❚</span>
              </button>
            </div>

            <!-- Empty slot for balance -->
            <div class="right-slot">
              <button class="ctrl-btn sm" @click="toggleLoop" :class="{ 'active-mode': isLooping }" title="循环播放">
                🔁
              </button>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import jsmediatags from 'jsmediatags/dist/jsmediatags.min.js'
import { getFileInfo } from '../composables/useAList.js'
import { getNameKeyFromSession, decryptName, storeAndPostKey } from '../composables/useCrypto.js'

const route = useRoute()
const router = useRouter()

const audioEl = ref(null)
const progressWrapper = ref(null)

const decryptedName = ref('')
const error = ref('')
const playUrl = ref('')
const fileSize = ref(0)
const coverUrl = ref('')

const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const volume = ref(1)
const isMuted = ref(false)
const isLooping = ref(false)

const filePath = computed(() => {
  const p = route.params.path
  return '/' + (Array.isArray(p) ? p.join('/') : (p || ''))
})

const formattedSize = computed(() => {
  if (!fileSize.value) return ''
  const b = fileSize.value
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  return (b / (1024 * 1024)).toFixed(1) + ' MB'
})

const progressPercent = computed(() => {
  if (!duration.value) return 0
  return (currentTime.value / duration.value) * 100
})

function formatTime(secs) {
  if (!secs || isNaN(secs)) return '0:00'
  const m = Math.floor(secs / 60)
  const s = Math.floor(secs % 60)
  return `${m}:${s < 10 ? '0' : ''}${s}`
}

function goBack() {
  const segments = filePath.value.split('/').filter(Boolean)
  segments.pop()
  router.push('/browse/' + segments.join('/'))
}

async function initPlayer() {
  error.value = ''
  try {
    const nameKey = await getNameKeyFromSession()
    const segments = filePath.value.split('/').filter(Boolean)
    const encFileName = segments[segments.length - 1]

    if (nameKey) {
      let nameToDec = encFileName
      if (encFileName.endsWith('.ske')) nameToDec = encFileName.slice(0, -4)
      decryptedName.value = await decryptName(nameToDec, nameKey) || encFileName
    } else {
      decryptedName.value = encFileName
    }

    const info = await getFileInfo(filePath.value)
    if (!info.url) throw new Error('无法获取音频链接')
    fileSize.value = info.size || 0

    const isEncrypted = encFileName.endsWith('.ske')
    const sizeParam = info.size ? `&size=${info.size}` : ''
    
    if (isEncrypted) {
      // Proactively give SW the password before it intercepts
      const pwd = sessionStorage.getItem('ske_password')
      if (pwd && navigator.serviceWorker?.controller) {
        navigator.serviceWorker.controller.postMessage({ type: 'SET_PASSWORD', password: pwd })
      }
      playUrl.value = `/ske-decrypt/?url=${encodeURIComponent(info.url)}${sizeParam}`
    } else {
      playUrl.value = info.url
    }

    // Assign source to native audio element
    retryCount = 0
    if (audioEl.value) {
      audioEl.value.src = playUrl.value
    }

    // Try extracting ID3 Cover Art
    coverUrl.value = ''
    jsmediatags.read(window.location.origin + playUrl.value, {
      onSuccess: function(tag) {
        if (tag.tags && tag.tags.picture) {
          const picture = tag.tags.picture
          let base64String = ''
          for (let i = 0; i < picture.data.length; i++) {
              base64String += String.fromCharCode(picture.data[i])
          }
          coverUrl.value = `data:${picture.format};base64,${window.btoa(base64String)}`
        }
      },
      onError: function(error) {
        console.log('[jsmediatags] No ID3 picture found or error reading tags', error)
      }
    })

  } catch (err) {
    error.value = err.message || '音频加载失败'
  }
}

// ── Audio Control Logic ────────────────────────────────────────
let retryCount = 0
const MAX_RETRIES = 3

function togglePlay() {
  if (!audioEl.value || !playUrl.value) return
  if (isPlaying.value) {
    audioEl.value.pause()
  } else {
    audioEl.value.play().catch(e => {
      console.error("Play prevented", e)
      error.value = "无法自动播放，请手动点击播放"
    })
  }
}

function onTimeUpdate() {
  if (!audioEl.value) return
  currentTime.value = audioEl.value.currentTime
}

function onLoadedMetadata() {
  if (!audioEl.value) return
  duration.value = audioEl.value.duration
}

function onEnded() {
  if (isLooping.value && audioEl.value) {
    audioEl.value.currentTime = 0
    audioEl.value.play()
  } else {
    isPlaying.value = false
  }
}

function onError(e) {
  isPlaying.value = false
  
  if (retryCount < MAX_RETRIES && playUrl.value) {
    retryCount++
    console.warn(`[MusicView] Audio load error, retrying (${retryCount}/${MAX_RETRIES})...`)
    setTimeout(() => {
      if (audioEl.value) {
        audioEl.value.src = ''
        audioEl.value.src = playUrl.value
        audioEl.value.load()
      }
    }, 800)
    return
  }

  error.value = '音频加载重试失败，请检查网络或刷新页面。'
  console.error("Audio error after retries", e)
}

function seek(e) {
  if (!audioEl.value || !duration.value || !progressWrapper.value) return
  const rect = progressWrapper.value.getBoundingClientRect()
  const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
  audioEl.value.currentTime = pos * duration.value
}

function updateVolume() {
  if (!audioEl.value) return
  audioEl.value.volume = volume.value
  audioEl.value.muted = isMuted.value
}

function toggleMute() {
  if (!audioEl.value) return
  isMuted.value = !isMuted.value
  audioEl.value.muted = isMuted.value
}

function toggleLoop() {
  isLooping.value = !isLooping.value
}

function downloadDecrypted() {
  if (!playUrl.value) return
  const a = document.createElement('a')
  a.href = playUrl.value
  a.download = decryptedName.value || 'audio.mp3'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

onMounted(() => {
  initPlayer()
})

onBeforeUnmount(() => {
  if (audioEl.value) {
    audioEl.value.pause()
    audioEl.value.removeAttribute('src')
    audioEl.value.load()
  }
})
</script>

<style scoped>
.music-view {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  overflow: hidden;
  background-color: #0f0c29;
}

/* ── Dynamic Background ── */
.bg-blobs {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.6;
  animation: float 20s infinite alternate ease-in-out;
}
.blob-1 { top: -10%; left: -10%; width: 50vw; height: 50vw; background: #8b5cf6; animation-delay: 0s; }
.blob-2 { bottom: -20%; right: -10%; width: 60vw; height: 60vw; background: #ec4899; animation-delay: -5s; }
.blob-3 { top: 40%; left: 30%; width: 40vw; height: 40vw; background: #3b82f6; animation-delay: -10s; }

.is-playing .blob {
  animation-duration: 10s; /* speed up blobs when playing */
  opacity: 0.8;
}

@keyframes float {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(5%, 5%) scale(1.1); }
  100% { transform: translate(-5%, 10%) scale(0.9); }
}

.bg-overlay {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(10, 10, 20, 0.4);
  backdrop-filter: blur(100px);
  z-index: 1;
  pointer-events: none;
}

/* ── Layout ── */
.glass-nav {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-bottom: 1px solid var(--border-glass);
}

.header-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 50%;
}

.music-title {
  font-weight: 600;
  font-size: 16px;
  text-align: center;
  color: #fff;
}

.music-subtitle {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

.music-container {
  position: relative;
  z-index: 10;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.player-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 480px;
  gap: 40px;
}

/* ── Vinyl Visualizer ── */
.vinyl-container {
  position: relative;
  width: 280px;
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.vinyl-record {
  position: relative;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: #111;
  box-shadow: 0 20px 50px rgba(0,0,0,0.8), inset 0 0 10px rgba(255,255,255,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3;
  transition: transform 0.5s ease;
}

.vinyl-grooves {
  position: absolute;
  top: 4px; left: 4px; right: 4px; bottom: 4px;
  border-radius: 50%;
  background: 
    repeating-radial-gradient(
      #111,
      #111 2px,
      #1a1a1a 3px,
      #111 4px
    );
  mask-image: radial-gradient(white, black);
  -webkit-mask-image: radial-gradient(white, black);
  opacity: 0.8;
}

.vinyl-cover {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  border-radius: 50%;
  object-fit: cover;
  opacity: 0.6;
  mask-image: radial-gradient(white, black);
  -webkit-mask-image: radial-gradient(white, black);
}

.vinyl-label {
  position: absolute;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: linear-gradient(135deg, #8b5cf6, #ec4899);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 4px solid #000;
  box-shadow: 0 0 15px rgba(0,0,0,0.5);
  z-index: 2;
  overflow: hidden;
}

.vinyl-label::after {
  content: '';
  position: absolute;
  width: 12px;
  height: 12px;
  background: #000;
  border-radius: 50%;
  box-shadow: inset 0 2px 4px rgba(255,255,255,0.4);
}

.label-cover {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}

.music-icon {
  font-size: 28px;
  filter: drop-shadow(0 2px 4px rgba(0,0,0,0.3));
}

.tone-arm {
  position: absolute;
  top: 0px;
  right: 20px;
  width: 12px;
  height: 140px;
  background: linear-gradient(90deg, #d4d4d8, #a1a1aa, #d4d4d8);
  border-radius: 6px;
  transform-origin: top center;
  transform: rotate(-35deg);    /* Off position */
  transition: transform 0.6s cubic-bezier(0.68, -0.55, 0.27, 1.55);
  box-shadow: 2px 5px 10px rgba(0,0,0,0.5);
  z-index: 4;
}
.tone-arm::before {
  content: '';
  position: absolute;
  top: 4px; left: -6px;
  width: 24px; height: 24px;
  background: #3f3f46;
  border-radius: 50%;
  box-shadow: inset 0 2px 4px rgba(255,255,255,0.3);
}
.tone-arm::after {
  content: '';
  position: absolute;
  bottom: -15px; left: -8px;
  width: 28px; height: 35px;
  background: #27272a;
  border-radius: 4px;
  transform: rotate(-15deg);
}

.vinyl-record.spin {
  animation: spin 10s linear infinite;
}

.tone-arm.arm-active {
  transform: rotate(15deg); /* On record */
}

@keyframes spin {
  100% { transform: rotate(360deg); }
}

/* Pulse rings */
.pulse-ring {
  position: absolute;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: rgba(139, 92, 246, 0.3);
  z-index: 1;
  animation: pulse 2s ease-out infinite;
}
.ring-2 {
  animation-delay: 1s;
  background: rgba(236, 72, 153, 0.2);
}

@keyframes pulse {
  0% { transform: scale(1); opacity: 0.8; }
  100% { transform: scale(1.5); opacity: 0; }
}

/* ── Custom Audio Player Controls ── */
.custom-audio-player {
  width: 100%;
  padding: 24px 30px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  background: rgba(20, 20, 30, 0.6);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 24px;
  box-shadow: 0 30px 60px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.1);
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
  group: hover;
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
  box-shadow: 0 0 10px rgba(0,0,0,0.5);
  transition: transform 0.2s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  pointer-events: none;
}

.progress-bar-wrapper:hover .progress-bar-thumb {
  transform: translate(-50%, -50%) scale(1);
}

/* Controls Row */
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
  background: rgba(255,255,255,0.2);
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
  width: 100px; /* Balance the flex layout visually */
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
  text-shadow: 0 0 10px rgba(236,72,153,0.5);
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
  margin-left: 4px; /* visually center play triangle */
}
.pause-icon {
  margin-left: 0;
  font-size: 16px;
  letter-spacing: -2px;
}

/* Error State */
.error-msg {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 40px;
}
.error-icon {
  font-size: 48px;
}

@media (max-width: 600px) {
  .vinyl-container {
    width: 220px;
    height: 220px;
  }
  .vinyl-record {
    width: 200px;
    height: 200px;
  }
  .vinyl-label { width: 60px; height: 60px; }
  .pulse-ring { width: 200px; height: 200px; }
  
  .custom-audio-player {
    padding: 20px;
    border-radius: 20px;
  }
  
  .desktop-text { display: none; }
  .mobile-text { display: inline; }
}

@media (min-width: 601px) {
  .mobile-text { display: none; }
}
</style>
