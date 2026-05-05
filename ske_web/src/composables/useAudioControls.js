/**
 * useAudioControls — Audio playback control composable
 *
 * Extracted from MusicView: play/pause, seek, volume, mute, loop, retry.
 */
import { ref } from 'vue'

export function useAudioControls(audioEl, playUrl) {
  const isPlaying = ref(false)
  const currentTime = ref(0)
  const duration = ref(0)
  const volume = ref(1)
  const isMuted = ref(false)
  const isLooping = ref(false)

  let retryCount = 0
  const MAX_RETRIES = 3

  function togglePlay() {
    if (!audioEl.value || !playUrl.value) return
    if (isPlaying.value) {
      audioEl.value.pause()
    } else {
      audioEl.value.play().catch((e) => {
        console.error('Play prevented', e)
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

  function onError(e, onErrorMsg) {
    isPlaying.value = false
    if (retryCount < MAX_RETRIES && playUrl.value) {
      retryCount++
      console.warn(`[Audio] Load error, retrying (${retryCount}/${MAX_RETRIES})...`)
      setTimeout(() => {
        if (audioEl.value) {
          audioEl.value.src = ''
          audioEl.value.src = playUrl.value
          audioEl.value.load()
        }
      }, 800)
      return
    }
    if (onErrorMsg) onErrorMsg('音频加载重试失败，请检查网络或刷新页面。')
  }

  function seek(e, progressWrapper) {
    if (!audioEl.value || !duration.value || !progressWrapper) return
    const rect = progressWrapper.getBoundingClientRect()
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

  function resetRetry() {
    retryCount = 0
  }

  function formatTime(secs) {
    if (!secs || isNaN(secs)) return '0:00'
    const m = Math.floor(secs / 60)
    const s = Math.floor(secs % 60)
    return `${m}:${s < 10 ? '0' : ''}${s}`
  }

  return {
    isPlaying,
    currentTime,
    duration,
    volume,
    isMuted,
    isLooping,
    togglePlay,
    onTimeUpdate,
    onLoadedMetadata,
    onEnded,
    onError,
    seek,
    updateVolume,
    toggleMute,
    toggleLoop,
    resetRetry,
    formatTime,
  }
}
