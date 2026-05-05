<template>
  <div class="video-player">
    <div v-if="error" class="error-state">
      <n-alert type="error" :bordered="false">{{ error }}</n-alert>
      <n-button quaternary @click="init">重试</n-button>
    </div>
    <div v-else class="player-wrapper">
      <div ref="playerContainer" class="artplayer-container"></div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import Artplayer from 'artplayer'

const props = defineProps({
  playUrl: { type: String, required: true },
  mimeType: { type: String, default: 'video/mp4' },
})

const emit = defineEmits(['error', 'ready'])

const playerContainer = ref(null)
const error = ref('')
let art = null

const MIME_MAP = {
  mp4: 'video/mp4', m4v: 'video/x-m4v', webm: 'video/webm',
  ogv: 'video/ogg', mov: 'video/quicktime', m2ts: 'video/mp2t',
  ts: 'video/mp2t', mkv: 'video/x-matroska', avi: 'video/x-msvideo',
  asf: 'video/x-ms-asf', wmv: 'video/x-ms-wmv', flv: 'video/x-flv',
}

function getMimeType(url) {
  const ext = url.split('.').pop()?.toLowerCase()?.split('?')[0]
  return MIME_MAP[ext] || 'video/mp4'
}

async function init() {
  error.value = ''
  await nextTick()

  if (art) {
    art.destroy()
    art = null
  }

  if (!playerContainer.value || !props.playUrl) return

  art = new Artplayer({
    container: playerContainer.value,
    url: props.playUrl,
    type: getMimeType(props.playUrl),
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
    emit('error', error.value)
  })

  art.on('error', (err) => {
    console.error('ArtPlayer error:', err)
    if (!error.value) {
      error.value = '播放出错: ' + (err.message || '未知错误')
      emit('error', error.value)
    }
  })

  art.on('ready', () => {
    emit('ready', art)
  })
}

onMounted(init)

watch(() => props.playUrl, () => {
  init()
})

onBeforeUnmount(() => {
  if (art) {
    art.destroy()
    art = null
  }
})

defineExpose({ getVideoEl: () => art?.video, art: () => art })
</script>

<style scoped>
.video-player {
  width: 100%;
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
}

@media (max-width: 768px) {
  .player-wrapper {
    min-height: 240px;
    border-radius: var(--radius-md);
  }
}
</style>
