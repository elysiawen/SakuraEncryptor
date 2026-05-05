/**
 * usePerfMonitor — SW performance monitoring composable
 *
 * Extracted from PlayerView: handles PERF_REPORT messages,
 * FPS tracking, heap memory, network speed, cache hit rate.
 */
import { reactive, onBeforeUnmount } from 'vue'
import { formatBytes } from './useFileDetection.js'

export function usePerfMonitor() {
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
  let lastTotalFrames = 0
  let lastPerfTime = performance.now()
  let lastNetBytes = 0
  let swNetBytes = 0
  let swCacheHits = 0
  let swCacheMisses = 0
  let swLastDecryptMs = 0
  let videoEl = null

  function onSwMessage(e) {
    if (e.data?.type === 'PERF_REPORT') {
      swNetBytes = e.data.totalBytes || 0
      swCacheHits = e.data.cacheHits || 0
      swCacheMisses = e.data.cacheMisses || 0
      swLastDecryptMs = e.data.lastDecryptMs || 0
    }
  }

  function updatePerf() {
    const now = performance.now()
    const deltaSec = (now - lastPerfTime) / 1000
    lastPerfTime = now

    // Video FPS
    if (videoEl) {
      const q = videoEl.getVideoPlaybackQuality?.()
      if (q) {
        const totalFrames = q.totalVideoFrames || 0
        if (deltaSec > 0 && lastTotalFrames > 0) {
          perf.fps = Math.round((totalFrames - lastTotalFrames) / deltaSec)
        }
        lastTotalFrames = totalFrames
        perf.dropped = q.droppedVideoFrames || 0
        const total = q.totalVideoFrames || 1
        perf.droppedPct = (perf.dropped / total) * 100
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
    perf.cacheHitRate = totalReqs > 0 ? Math.round((swCacheHits / totalReqs) * 100) + '%' : '—'

    // Decryption latency
    perf.decryptLatencyRaw = swLastDecryptMs
    perf.decryptLatency = swLastDecryptMs > 0 ? swLastDecryptMs.toFixed(1) : '—'
  }

  function start(videoElement) {
    stop()
    videoEl = videoElement

    // Reset state
    swNetBytes = 0; swCacheHits = 0; swCacheMisses = 0; swLastDecryptMs = 0
    lastTotalFrames = 0; lastPerfTime = performance.now(); lastNetBytes = 0
    perf.fps = '—'; perf.dropped = 0; perf.droppedPct = 0
    perf.heapUsed = '—'; perf.heapTotal = '—'
    perf.netTransferred = '0 B'; perf.netSpeed = '0 B'
    perf.cacheHitRate = '—'; perf.decryptLatency = '—'; perf.decryptLatencyRaw = 0

    navigator.serviceWorker?.addEventListener('message', onSwMessage)
    navigator.serviceWorker?.controller?.postMessage({ type: 'RESET_PERF' })
    navigator.serviceWorker?.controller?.postMessage({ type: 'GET_PERF' })

    perfTimer = setInterval(() => {
      updatePerf()
      navigator.serviceWorker?.controller?.postMessage({ type: 'GET_PERF' })
    }, 1000)
  }

  function stop() {
    if (perfTimer) {
      clearInterval(perfTimer)
      perfTimer = null
    }
    navigator.serviceWorker?.removeEventListener('message', onSwMessage)
    videoEl = null
  }

  onBeforeUnmount(stop)

  return { perf, start, stop }
}
