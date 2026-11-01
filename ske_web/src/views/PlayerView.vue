<template>
  <MediaLayout>
    <MediaTopbar
      :title="decryptedName || t('player.loading')"
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
            <span class="desktop-text">💬 {{ t('player.subtitle') }}</span>
            <span class="mobile-text">💬</span>
          </n-button>
        </n-dropdown>
        <n-button quaternary size="small" @click="showPlaylist = !showPlaylist">
          <span class="desktop-text">📋 {{ t('player.playlist') }}</span>
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
        <n-button quaternary @click="resolve">{{ t('common.retry') }}</n-button>
      </div>

      <PerfMonitor v-if="isEncrypted" :perf="perf" />
    </main>

    <PlaylistPanel
      :items="playlist.items.value"
      :current-index="playlist.currentIndex.value"
      :visible="showPlaylist"
      :label="t('playlist.unitVideo')"
      @close="showPlaylist = false"
      @select="onPlaylistSelect"
    />
  </MediaLayout>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
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
const { t } = useI18n()
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

// Keys, not sentences: the menu is rebuilt from the active locale.
const SCALE_STEPS = [
  { labelKey: 'player.sizes.xsmall', value: 0.75 },
  { labelKey: 'player.sizes.small', value: 0.9 },
  { labelKey: 'player.sizes.normal', value: 1 },
  { labelKey: 'player.sizes.large', value: 1.15 },
  { labelKey: 'player.sizes.xlarge', value: 1.3 },
  { labelKey: 'player.sizes.xxlarge', value: 1.5 },
]

const SCALE_STORAGE_KEY = 'ske_subtitle_scale'

function loadScale() {
  const saved = Number(localStorage.getItem(SCALE_STORAGE_KEY))
  return SCALE_STEPS.some(item => item.value === saved) ? saved : 1
}

const subtitleScale = ref(loadScale())

const subtitleMenuOptions = computed(() => {
  const options = [{ label: t('player.subtitleOff'), key: 'off' }]

  if (subtitleTracks.value.length > 0) {
    options.push({ type: 'divider', key: 'divider-tracks' })
    for (const track of subtitleTracks.value) {
      options.push({ label: track.label, key: track.id })
    }
    options.push({ type: 'divider', key: 'divider-scale' })
    options.push({
      label: t('player.subtitleSize'),
      key: 'scale',
      children: SCALE_STEPS.map(item => ({
        label: `${t(item.labelKey)} (${Math.round(item.value * 100)}%)` +
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
    message.error(t('player.subtitleLoadFailed', { reason: err?.message || t('errors.unknownError') }))
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
  await scanSubtitles(dirPath, decryptedName.value, isLocal ? 'local' : 'alist')

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
