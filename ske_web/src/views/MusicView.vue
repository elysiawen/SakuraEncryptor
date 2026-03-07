<template>
  <div class="music-view">
    <header class="topbar glass-card">
      <button class="btn btn-ghost btn-icon-mobile" @click="goBack">
        <span class="desktop-text">← 返回</span>
        <span class="mobile-text">←</span>
      </button>
      <div class="music-title truncate">{{ decryptedName || '音频加载中…' }}</div>
      <button class="btn btn-ghost btn-icon-mobile" @click="downloadDecrypted" :disabled="!playUrl">
        <span class="desktop-text">⬇️ 下载</span>
        <span class="mobile-text">⬇️</span>
      </button>
    </header>

    <main class="music-container">
      <div class="player-card glass-card">
        <!-- Visualizer Area -->
        <div class="visualizer-area">
          <div class="disc-wrapper" :class="{ 'is-playing': isPlaying }">
            <div class="disc">
              <div class="disc-inner">
                <span class="music-icon">🎵</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Audio Controls -->
        <div class="audio-controls">
          <div ref="artPlayerContainer" class="artplayer-audio-hidden"></div>
          <div v-if="error" class="error-msg">❌ {{ error }}</div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Artplayer from 'artplayer'
import { getFileInfo } from '../composables/useAList.js'
import { getNameKeyFromSession, decryptName } from '../composables/useCrypto.js'

const route = useRoute()
const router = useRouter()

const artPlayerContainer = ref(null)
const decryptedName = ref('')
const error = ref('')
const playUrl = ref('')
const isPlaying = ref(false)
let art = null

const filePath = computed(() => {
  const p = route.params.path
  return '/' + (Array.isArray(p) ? p.join('/') : (p || ''))
})

function goBack() {
  const segments = filePath.value.split('/').filter(Boolean)
  segments.pop()
  router.push('/browse/' + segments.join('/'))
}

async function initPlayer() {
  error.value = ''
  try {
    // 1. Decrypt Name
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

    // 2. Get AList Info
    const info = await getFileInfo(filePath.value)
    if (!info.url) throw new Error('无法获取音频链接')

    // 3. Construct URL
    const isEncrypted = encFileName.endsWith('.ske')
    const sizeParam = info.size ? `&size=${info.size}` : ''
    
    if (isEncrypted) {
      playUrl.value = `/ske-decrypt/?url=${encodeURIComponent(info.url)}${sizeParam}`
    } else {
      playUrl.value = info.url
    }

    // 4. Init ArtPlayer (Audio Mode)
    if (art) art.destroy()

    const ext = decryptedName.value.split('.').pop()?.toLowerCase() || 'mp3'
    const mimeType = ext === 'm4a' ? 'audio/mp4' : `audio/${ext}`

    art = new Artplayer({
      container: artPlayerContainer.value,
      url: playUrl.value,
      type: mimeType,
      container: artPlayerContainer.value,
      isLive: false,
      muted: false,
      autoplay: false,
      pip: false,
      setting: true,
      loop: true,
      flip: false,
      playbackRate: true,
      aspectRatio: false,
      fullscreen: false,
      fullscreenWeb: false,
      subtitleOffset: false,
      miniProgressBar: false,
      mutex: true,
      backdrop: true,
      playsInline: true,
      autoPlayback: false,
      airplay: true,
      theme: '#8b5cf6',
      lang: 'zh-cn',
    })

    art.on('video:error', () => {
      error.value = '音频解密失败或播放出错。请确认密码是否正确。'
    })

    art.on('play', () => { isPlaying.value = true })
    art.on('pause', () => { isPlaying.value = false })
  } catch (err) {
    error.value = err.message || '音频播放初始化失败'
  }
}

async function downloadDecrypted() {
  if (!playUrl.value) return
  window.open(playUrl.value, '_blank')
}

onMounted(() => {
  initPlayer()
})

onBeforeUnmount(() => {
  if (art) art.destroy()
})
</script>

<style scoped>
.music-view {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: radial-gradient(circle at 50% 50%, #1e1e3f 0%, #0a0a14 100%);
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  margin: 16px;
  z-index: 10;
}

.music-title {
  font-weight: 600;
  max-width: 60%;
}

.music-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.player-card {
  width: 100%;
  max-width: 500px;
  padding: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 30px;
  box-shadow: 0 20px 50px rgba(0,0,0,0.5);
}

.visualizer-area {
  position: relative;
  width: 240px;
  height: 240px;
}

.disc-wrapper {
  width: 100%;
  height: 100%;
  padding: 10px;
  background: rgba(0,0,0,0.3);
  border-radius: 50%;
  border: 4px solid var(--border-glass);
  display: flex;
  align-items: center;
  justify-content: center;
}

.disc {
  width: 100%;
  height: 100%;
  background: #111;
  background-image: repeating-radial-gradient(circle, #222 0, #111 2px, #222 4px);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  animation: rotate-disc 12s linear infinite;
  animation-play-state: paused;
}

.disc-inner {
  width: 70px;
  height: 70px;
  background: var(--accent-gradient);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 5px solid #111;
  box-shadow: 0 0 20px rgba(139, 92, 246, 0.4);
}

.music-icon {
  font-size: 30px;
}

.is-playing .disc {
  animation-play-state: running;
}

@keyframes rotate-disc {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.audio-controls {
  width: 100%;
}

/* Style ArtPlayer for Audio mode */
.artplayer-audio-hidden {
  width: 100%;
  height: 60px; /* Slimmer for audio */
  border-radius: var(--radius-md);
  overflow: hidden;
  background: rgba(255,255,255,0.05);
}

.error-msg {
  color: var(--danger);
  margin-top: 10px;
  text-align: center;
  font-size: 14px;
}

@media (max-width: 600px) {
  .player-card {
    padding: 24px;
  }
  .visualizer-area {
    width: 180px;
    height: 180px;
  }
  .disc-inner {
    width: 50px;
    height: 50px;
  }
  .mobile-text { display: inline; }
  .desktop-text { display: none; }
}

@media (min-width: 601px) {
  .mobile-text { display: none; }
  .desktop-text { display: inline; }
}
</style>
