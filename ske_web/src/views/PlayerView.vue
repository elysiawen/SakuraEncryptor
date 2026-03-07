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

      <!-- File info + Performance Metrics -->
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

        <!-- Performance Metrics (only for encrypted files) -->
        <template v-if="isEncryptedRef">
          <div class="info-separator"></div>
          <div class="perf-metrics">
            <div class="perf-item" title="视频帧率">
              <span class="perf-icon">🎞️</span>
              <span class="perf-value">{{ perf.fps }}</span>
              <span class="perf-unit">FPS</span>
            </div>
            <div class="perf-item" :class="{ 'perf-warn': perf.droppedPct > 5 }" title="丢帧率">
              <span class="perf-icon">📉</span>
              <span class="perf-value">{{ perf.dropped }}</span>
              <span class="perf-unit">丢帧</span>
            </div>
            <div class="perf-item" title="JS 堆内存使用">
              <span class="perf-icon">🧠</span>
              <span class="perf-value">{{ perf.heapUsed }}</span>
              <span class="perf-unit">/ {{ perf.heapTotal }}</span>
            </div>
            <div class="perf-item" title="已传输数据量">
              <span class="perf-icon">📡</span>
              <span class="perf-value">{{ perf.netTransferred }}</span>
              <span class="perf-unit">传输</span>
            </div>
            <div class="perf-item" title="缓存命中率">
              <span class="perf-icon">💾</span>
              <span class="perf-value">{{ perf.cacheHitRate }}</span>
              <span class="perf-unit">缓存</span>
            </div>
            <div class="perf-item" title="实时解密延迟 (每块)">
              <span class="perf-icon">⚡</span>
              <span class="perf-value">{{ perf.decryptLatency }}</span>
              <span class="perf-unit">ms</span>
            </div>
            <div class="perf-item" title="实时下载网速">
              <span class="perf-icon">🌐</span>
              <span class="perf-value">{{ perf.netSpeed }}</span>
              <span class="perf-unit">/s</span>
            </div>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, computed } from 'vue'
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

// ── Performance state ─────────────────────────────────────────
const perf = reactive({
  fps: '—',
  dropped: 0,
  droppedPct: 0,
  heapUsed: '—',
  heapTotal: '—',
  netTransferred: '0 B',
  netSpeed: '0 B',
  cacheHitRate: '—',
  decryptLatency: '—',
  decryptLatencyRaw: 0,
})

let perfTimer = null

// Video FPS tracking (via getVideoPlaybackQuality delta)
let lastTotalFrames = 0
let lastPerfTime = performance.now()

// Network speed tracking
let lastNetBytes = 0

// SW reports: { totalBytes, cacheHits, cacheMisses, lastDecryptMs }
let swNetBytes = 0
let swCacheHits = 0
let swCacheMisses = 0
let swLastDecryptMs = 0

function onSwMessage(e) {
  if (e.data?.type === 'PERF_REPORT') {
    swNetBytes = e.data.totalBytes || 0
    swCacheHits = e.data.cacheHits || 0
    swCacheMisses = e.data.cacheMisses || 0
    swLastDecryptMs = e.data.lastDecryptMs || 0
  }
}

function formatBytes(b) {
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1024 * 1024 * 1024) return (b / (1024 * 1024)).toFixed(1) + ' MB'
  return (b / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function updatePerf() {
  const now = performance.now()
  const deltaSec = (now - lastPerfTime) / 1000
  lastPerfTime = now

  // Video FPS — computed from actual decoded frames, not rAF
  if (art?.video) {
    const q = art.video.getVideoPlaybackQuality?.()
    if (q) {
      const totalFrames = q.totalVideoFrames || 0
      if (deltaSec > 0 && lastTotalFrames > 0) {
        perf.fps = Math.round((totalFrames - lastTotalFrames) / deltaSec)
      }
      lastTotalFrames = totalFrames

      perf.dropped = q.droppedVideoFrames || 0
      const total = q.totalVideoFrames || 1
      perf.droppedPct = ((perf.dropped / total) * 100)
    }
  }

  // JS Heap Memory (Chrome only)
  if (performance.memory) {
    perf.heapUsed = formatBytes(performance.memory.usedJSHeapSize)
    perf.heapTotal = formatBytes(performance.memory.totalJSHeapSize)
  } else {
    perf.heapUsed = 'N/A'
    perf.heapTotal = 'N/A'
  }

  // Network total and speed
  perf.netTransferred = formatBytes(swNetBytes)
  if (deltaSec > 0) {
    const speedBps = (swNetBytes - lastNetBytes) / deltaSec
    perf.netSpeed = speedBps > 0 ? formatBytes(Math.round(speedBps)) : '0 B'
  }
  lastNetBytes = swNetBytes

  // Cache hit rate
  const totalReqs = swCacheHits + swCacheMisses
  if (totalReqs > 0) {
    perf.cacheHitRate = Math.round((swCacheHits / totalReqs) * 100) + '%'
  } else {
    perf.cacheHitRate = '—'
  }

  // Decryption latency
  perf.decryptLatencyRaw = swLastDecryptMs
  perf.decryptLatency = swLastDecryptMs > 0 ? swLastDecryptMs.toFixed(1) : '—'
}

function startPerfMonitor() {
  // Stop any previous monitors first
  stopPerfMonitor()

  // Reset local state
  swNetBytes = 0
  swCacheHits = 0
  swCacheMisses = 0
  swLastDecryptMs = 0
  lastTotalFrames = 0
  lastPerfTime = performance.now()
  lastNetBytes = 0
  perf.fps = '—'
  perf.dropped = 0
  perf.droppedPct = 0
  perf.heapUsed = '—'
  perf.heapTotal = '—'
  perf.netTransferred = '0 B'
  perf.netSpeed = '0 B'
  perf.cacheHitRate = '—'
  perf.decryptLatency = '—'
  perf.decryptLatencyRaw = 0

  // Listen for SW messages
  navigator.serviceWorker?.addEventListener('message', onSwMessage)

  // Reset SW counters & request current stats
  navigator.serviceWorker?.controller?.postMessage({ type: 'RESET_PERF' })
  navigator.serviceWorker?.controller?.postMessage({ type: 'GET_PERF' })

  // Update metrics every second
  perfTimer = setInterval(() => {
    updatePerf()
    navigator.serviceWorker?.controller?.postMessage({ type: 'GET_PERF' })
  }, 1000)
}

function stopPerfMonitor() {
  if (perfTimer) { clearInterval(perfTimer); perfTimer = null }
  navigator.serviceWorker?.removeEventListener('message', onSwMessage)
}

// ── Player logic ──────────────────────────────────────────────
const filePath = computed(() => {
  const p = route.params.path
  if (!p) return '/'
  return '/' + (Array.isArray(p) ? p.join('/') : p)
})

function goBack() {
  const parts = filePath.value.split('/').filter(Boolean)
  parts.pop()
  router.push('/browse/' + parts.join('/'))
}

async function initPlayer() {
  error.value = ''

  try {
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

    const fileInfo = await getFileInfo(filePath.value)
    const rawUrl = fileInfo.url
    const rawSize = fileInfo.size

    let playUrl = rawUrl
    const isEncrypted = encFileName.endsWith('.ske')
    isEncryptedRef.value = isEncrypted

    if (isEncrypted) {
      playUrl = `/ske-decrypt/?url=${encodeURIComponent(rawUrl)}&size=${rawSize}&name=${encodeURIComponent(decryptedName.value)}`
    }

    decryptUrl.value = playUrl

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

    // Start performance monitoring for encrypted playback
    if (isEncrypted) {
      startPerfMonitor()
    }
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
  stopPerfMonitor()
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
  align-items: center;
  gap: 24px;
  padding: 14px 24px;
  border-radius: var(--radius-md);
  flex-wrap: wrap;
}

.info-separator {
  width: 1px;
  height: 24px;
  background: rgba(255, 255, 255, 0.12);
  flex-shrink: 0;
}

.perf-metrics {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.perf-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  transition: color 0.2s;
}

.perf-item .perf-icon {
  font-size: 11px;
}

.perf-item .perf-value {
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace;
}

.perf-item .perf-unit {
  font-size: 10px;
  opacity: 0.7;
}

.perf-item.perf-warn .perf-value {
  color: #f59e0b;
}

@media (max-width: 600px) {
  .file-info-bar {
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px;
    align-items: flex-start;
  }

  .info-separator {
    width: 100%;
    height: 1px;
  }

  .perf-metrics {
    gap: 12px;
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
