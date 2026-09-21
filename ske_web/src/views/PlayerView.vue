<template>
  <MediaLayout>
    <MediaTopbar
      :title="decryptedName || '加载中…'"
      :download-url="downloadUrl"
      :download-name="decryptedName"
      :download-size="downloadSize"
      @back="goBack"
    >
      <template #extra>
        <n-dropdown
          v-if="subtitleTracks.length > 0"
          trigger="click"
          :options="subtitleMenuOptions"
          @select="onSubtitleSelect"
        >
          <n-button quaternary size="small" :loading="subtitleBusy">
            <span class="desktop-text">💬 字幕</span>
            <span class="mobile-text">💬</span>
          </n-button>
        </n-dropdown>
        <n-button quaternary size="small" @click="showPlaylist = !showPlaylist">
          <span class="desktop-text">📋 列表</span>
          <span class="mobile-text">📋</span>
        </n-button>
      </template>
    </MediaTopbar>

    <main class="player-main">
      <VideoPlayer
        v-if="playUrl"
        ref="videoPlayerRef"
        :play-url="playUrl"
        :subtitle="subtitlePayload"
        :subtitle-scale="subtitleScale"
        @ready="onPlayerReady"
        @error="(msg) => error = msg"
      />

      <div v-if="error" class="error-msg">
        <n-alert type="error" :bordered="false">{{ error }}</n-alert>
        <n-button quaternary @click="resolve">重试</n-button>
      </div>

      <PerfMonitor v-if="isEncrypted" :perf="perf" />
    </main>

    <PlaylistPanel
      :items="playlist.items.value"
      :current-index="playlist.currentIndex.value"
      :visible="showPlaylist"
      label="个"
      @close="showPlaylist = false"
      @select="onPlaylistSelect"
    />
  </MediaLayout>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import MediaLayout from '../layouts/MediaLayout.vue'
import MediaTopbar from '../components/media-player/MediaTopbar.vue'
import VideoPlayer from '../components/media-player/VideoPlayer.vue'
import PerfMonitor from '../components/media-player/PerfMonitor.vue'
import PlaylistPanel from '../components/media-player/PlaylistPanel.vue'
import { useFileResolver } from '../composables/useFileResolver.js'
import { usePlaylist } from '../composables/usePlaylist.js'
import { usePerfMonitor } from '../composables/usePerfMonitor.js'
import { useSubtitles, buildSubtitlePayload } from '../composables/useSubtitles.js'
import { toDownloadUrl } from '../composables/useFileDownload.js'

const route = useRoute()
const message = useMessage()
const { decryptedName, playUrl, isEncrypted, isLocal, rawUrl, rawSize, error, resolve, goBack } = useFileResolver()
const playlist = usePlaylist('video')
const { perf, start: startPerf, stop: stopPerf } = usePerfMonitor()
const { tracks: subtitleTracks, scan: scanSubtitles } = useSubtitles()
const videoPlayerRef = ref(null)
const showPlaylist = ref(false)

// Encrypted files are downloaded through the Service Worker's plaintext
// streaming endpoint; unencrypted ones use the raw link directly.
const downloadUrl = computed(() => {
  if (isEncrypted.value && !isLocal.value && playUrl.value) {
    return toDownloadUrl(playUrl.value)
  }
  return rawUrl.value
})

// Plaintext size is only known once the SW responds, so let the download
// manager derive it from Content-Length instead of the encrypted size.
const downloadSize = computed(() => (isEncrypted.value && !isLocal.value ? 0 : rawSize.value))

const subtitlePayload = ref(null)
const activeSubtitleId = ref('off')
const subtitleBusy = ref(false)

const SUBTITLE_SCALES = [
  { label: '很小', value: 0.75 },
  { label: '小', value: 0.9 },
  { label: '标准', value: 1 },
  { label: '大', value: 1.15 },
  { label: '很大', value: 1.3 },
  { label: '超大', value: 1.5 },
]

const SCALE_STORAGE_KEY = 'ske_subtitle_scale'

function loadScale() {
  const saved = Number(localStorage.getItem(SCALE_STORAGE_KEY))
  return SUBTITLE_SCALES.some(item => item.value === saved) ? saved : 1
}

const subtitleScale = ref(loadScale())

const subtitleMenuOptions = computed(() => {
  const options = [{ label: '关闭字幕', key: 'off' }]

  if (subtitleTracks.value.length > 0) {
    options.push({ type: 'divider', key: 'divider-tracks' })
    for (const track of subtitleTracks.value) {
      options.push({ label: track.label, key: track.id })
    }
    options.push({ type: 'divider', key: 'divider-scale' })
    options.push({
      label: '字幕大小',
      key: 'scale',
      children: SUBTITLE_SCALES.map(item => ({
        label: `${item.label} (${Math.round(item.value * 100)}%)` +
          (item.value === subtitleScale.value ? ' ✓' : ''),
        key: `scale:${item.value}`,
      })),
    })
  }

  return options
})

function parseDir() {
  const p = route.params.path
  const fullPath = '/' + (Array.isArray(p) ? p.join('/') : (p || ''))
  const segments = fullPath.split('/').filter(Boolean)
  return {
    isLocal: segments[0] === 'local',
    dirPath: '/' + segments.slice(0, -1).join('/'),
  }
}

async function selectSubtitle(id) {
  activeSubtitleId.value = id
  if (id === 'off') {
    subtitlePayload.value = null
    return
  }
  const track = subtitleTracks.value.find(item => item.id === id)
  if (!track) {
    subtitlePayload.value = null
    return
  }
  subtitleBusy.value = true
  try {
    subtitlePayload.value = await buildSubtitlePayload(track)
  } catch (err) {
    subtitlePayload.value = null
    message.error('字幕加载失败：' + (err?.message || '未知错误'))
  } finally {
    subtitleBusy.value = false
  }
}

function onSubtitleSelect(key) {
  if (typeof key === 'string' && key.startsWith('scale:')) {
    subtitleScale.value = Number(key.slice('scale:'.length))
    return
  }
  selectSubtitle(key)
}

async function loadSubtitles() {
  subtitlePayload.value = null
  activeSubtitleId.value = 'off'

  const { isLocal, dirPath } = parseDir()
  if (isLocal) {
    // Local-file playback is out of scope for subtitle discovery (AList only).
    await scanSubtitles(null, '')
    return
  }

  await scanSubtitles(dirPath, decryptedName.value)
  if (subtitleTracks.value.length > 0) {
    // Auto-load the best matching track (exact basename wins).
    await selectSubtitle(subtitleTracks.value[0].id)
  }
}

function onPlayerReady(art) {
  if (isEncrypted.value) {
    startPerf(art.video)
  }
}

function onPlaylistSelect(item) {
  showPlaylist.value = false
  playlist.goTo(item)
}

watch(() => route.params.path, async () => {
  await resolve()
  playlist.load()
  loadSubtitles()
})

watch(subtitleScale, (value) => {
  localStorage.setItem(SCALE_STORAGE_KEY, String(value))
})

onMounted(async () => {
  await resolve()
  playlist.load()
  loadSubtitles()
})
</script>

<style scoped>
.player-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  gap: 16px;
}

.error-msg {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 40px 24px;
}
</style>
