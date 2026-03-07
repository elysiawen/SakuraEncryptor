<template>
  <div class="player-page">
    <!-- Top bar -->
    <header class="player-topbar glass-card">
      <button class="btn btn-ghost btn-icon-mobile" @click="goBack">
        <span class="desktop-text">← 返回</span>
        <span class="mobile-text">←</span>
      </button>
      <div class="player-title truncate">{{ decryptedName || '加载中…' }}</div>
      <div class="player-actions">
        <button class="btn btn-ghost btn-icon-mobile" @click="downloadDecrypted" :disabled="!decryptUrl" title="下载原始文件">
          <span class="desktop-text">⬇️ 下载原始文件</span>
          <span class="mobile-text">⬇️</span>
        </button>
      </div>
    </header>

    <!-- Player Container -->
    <main class="player-main">
      <div v-if="error" class="error-state">
        <p>❌ {{ error }}</p>
        <button class="btn btn-ghost" @click="initPlayer">重试</button>
      </div>

      <div v-else class="player-wrapper">
        <div ref="playerContainer" class="artplayer-container"></div>
      </div>

      <!-- File info -->
      <div v-if="decryptedName" class="file-info-bar glass-card">
        <div class="info-item">
          <span class="info-label">文件名</span>
          <span class="info-value">{{ decryptedName }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">状态</span>
          <span v-if="isEncryptedRef" class="info-value status-ok">🔓 已解密播放</span>
          <span v-else class="info-value">▶️ 原始流媒体</span>
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

const playerContainer = ref(null)
const decryptedName = ref('')
const error = ref('')
const decryptUrl = ref('')
const isEncryptedRef = ref(false)
let art = null

const filePath = computed(() => {
  const p = route.params.path
  if (!p) return '/'
  return '/' + (Array.isArray(p) ? p.join('/') : p)
})

function goBack() {
  // Go to parent directory
  const parts = filePath.value.split('/').filter(Boolean)
  parts.pop()
  router.push('/browse/' + parts.join('/'))
}

async function initPlayer() {
  error.value = ''

  try {
    // Decrypt the file name for display
    const nameKey = await getNameKeyFromSession()
    const segments = filePath.value.split('/').filter(Boolean)
    const encFileName = segments[segments.length - 1]

    if (nameKey) {
      let nameToDecrypt = encFileName
      if (nameToDecrypt.endsWith('.ske')) {
        nameToDecrypt = nameToDecrypt.slice(0, -4)
      }
      const dec = await decryptName(nameToDecrypt, nameKey)
      decryptedName.value = dec || encFileName
    } else {
      decryptedName.value = encFileName
    }

    // Get the real download URL and File Size from AList
    const fileInfo = await getFileInfo(filePath.value)
    const rawUrl = fileInfo.url
    const rawSize = fileInfo.size

    let playUrl = rawUrl
    const isEncrypted = encFileName.endsWith('.ske')
    isEncryptedRef.value = isEncrypted

    if (isEncrypted) {
      // Construct the SW-intercepted URL using query parameter, size and decrypted name
      playUrl = `/ske-decrypt/?url=${encodeURIComponent(rawUrl)}&size=${rawSize}&name=${encodeURIComponent(decryptedName.value)}`
    }

    decryptUrl.value = playUrl

    // Initialize ArtPlayer
    if (art) {
      art.destroy()
      art = null
    }

    const fileExt = (isEncrypted ? decryptedName.value : rawUrl).split('.').pop().toLowerCase()
    
    const mimeTypes = {
      'mp4': 'video/mp4',
      'm4v': 'video/x-m4v',
      'webm': 'video/webm',
      'ogv': 'video/ogg',
      'mov': 'video/quicktime',
      'm2ts': 'video/mp2t',
      'ts': 'video/mp2t',
      'mkv': 'video/x-matroska',
      'avi': 'video/x-msvideo',
      'asf': 'video/x-ms-asf',
      'wmv': 'video/x-ms-wmv',
      'flv': 'video/x-flv',
    }
    const mimeType = mimeTypes[fileExt] || 'video/mp4'

    art = new Artplayer({
      container: playerContainer.value,
      url: playUrl,
      type: mimeType,
      autoplay: false,
      fullscreen: true,
      fullscreenWeb: true,
      pip: true,
      playbackRate: true,
      aspectRatio: true,
      setting: true,
      flip: true,
      lock: true,
      FAST_FORWARD: true,
      theme: '#8b5cf6',
      lang: 'zh-cn',
    })

    art.on('video:error', () => {
      error.value = '视频解密失败或播放出错。请确认密码是否正确，或检查网络连接。'
    })

    art.on('error', (err) => {
      console.error('ArtPlayer error:', err)
      if (!error.value) error.value = '播放出错: ' + (err.message || '未知错误')
    })
  } catch (err) {
    error.value = err.message || '播放器初始化失败'
  }
}

async function downloadDecrypted() {
  if (!decryptUrl.value) return

  try {
    const response = await fetch(decryptUrl.value)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = decryptedName.value || 'video.mp4'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (err) {
    error.value = '下载失败: ' + err.message
  }
}

onMounted(() => {
  initPlayer()
})

onBeforeUnmount(() => {
  if (art) {
    art.destroy()
    art = null
  }
})
</script>

<style scoped>
.player-page {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.player-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  margin: 16px 16px 0;
  border-radius: var(--radius-md);
  gap: 16px;
}

.player-title {
  flex: 1;
  text-align: center;
  font-size: 15px;
  font-weight: 600;
  min-width: 0;
}

.player-actions {
  flex-shrink: 0;
}

.player-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  gap: 16px;
}

.player-wrapper {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  min-height: 480px;
  max-height: calc(100vh - 200px);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: #000;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.4);
}

@media (max-width: 768px) {
  .player-wrapper {
    min-height: 240px;
    border-radius: var(--radius-md);
  }
}

.artplayer-container {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  width: 100%;
  height: 100%;
}

.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 80px 24px;
  color: var(--text-secondary);
}

.file-info-bar {
  display: flex;
  gap: 32px;
  padding: 14px 24px;
  border-radius: var(--radius-md);
}

@media (max-width: 600px) {
  .file-info-bar {
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px;
  }
  
  .player-topbar {
    margin: 8px 8px 0;
    padding: 8px 12px;
  }
  
  .player-title {
    font-size: 13px;
  }

  .mobile-text { display: inline; }
  .desktop-text { display: none; }
}

@media (min-width: 601px) {
  .mobile-text { display: none; }
  .desktop-text { display: inline; }
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-label {
  font-size: 12px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.info-value {
  font-size: 13px;
  font-weight: 500;
}

.status-ok {
  color: var(--success);
}
</style>

<style>
/* Global CSS for ArtPlayer to bypass Vue 3 scoping issues */
.artplayer-container .art-video-player,
.artplayer-container .art-video {
  width: 100% !important;
  height: 100% !important;
  object-fit: contain !important;
}
</style>
