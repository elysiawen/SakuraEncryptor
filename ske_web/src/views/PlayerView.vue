<template>
  <MediaLayout>
    <MediaTopbar
      :title="decryptedName || '加载中…'"
      :download-url="rawUrl"
      :download-name="decryptedName"
      :download-size="rawSize"
      @back="goBack"
    >
      <template #extra>
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
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import MediaLayout from '../layouts/MediaLayout.vue'
import MediaTopbar from '../components/media-player/MediaTopbar.vue'
import VideoPlayer from '../components/media-player/VideoPlayer.vue'
import PerfMonitor from '../components/media-player/PerfMonitor.vue'
import PlaylistPanel from '../components/media-player/PlaylistPanel.vue'
import { useFileResolver } from '../composables/useFileResolver.js'
import { usePlaylist } from '../composables/usePlaylist.js'
import { usePerfMonitor } from '../composables/usePerfMonitor.js'

const route = useRoute()
const { decryptedName, playUrl, isEncrypted, rawUrl, rawSize, error, resolve, goBack } = useFileResolver()
const playlist = usePlaylist('video')
const { perf, start: startPerf, stop: stopPerf } = usePerfMonitor()
const videoPlayerRef = ref(null)
const showPlaylist = ref(false)

function onPlayerReady(art) {
  if (isEncrypted.value) {
    startPerf(art.video)
  }
}

function onPlaylistSelect(item) {
  showPlaylist.value = false
  playlist.goTo(item)
}

watch(() => route.params.path, () => {
  resolve().then(() => playlist.load())
})

onMounted(() => {
  resolve()
  playlist.load()
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
