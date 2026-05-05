<template>
  <div class="image-view">
    <MediaTopbar
      :title="decryptedName || '加载中…'"
      :download-url="rawUrl"
      :download-name="decryptedName"
      :download-size="rawSize"
      @back="goBack"
      class="image-topbar"
      :class="{ 'ui-hidden': !uiVisible }"
    >
      <template #extra>
        <n-button quaternary size="small" @click="showPlaylist = !showPlaylist">
          <span class="desktop-text">📋 列表</span>
          <span class="mobile-text">📋</span>
        </n-button>
      </template>
    </MediaTopbar>

    <ImageViewer
      v-if="playUrl"
      ref="viewerRef"
      :src="playUrl"
      :alt="decryptedName"
      :is-encrypted="isEncrypted"
      @retry="resolve"
    />

    <div v-if="error" class="error-overlay">
      <n-alert type="error" :bordered="false">{{ error }}</n-alert>
      <n-button type="primary" @click="resolve">重试</n-button>
    </div>

    <!-- Rotation button -->
    <button
      class="rotate-btn glass-card"
      :class="{ 'ui-hidden': !uiVisible }"
      @click="viewerRef?.rotateImage()"
      title="旋转"
    >
      🔄
    </button>

    <PlaylistPanel
      :items="playlist.items.value"
      :current-index="playlist.currentIndex.value"
      :visible="showPlaylist"
      label="张"
      @close="showPlaylist = false"
      @select="onPlaylistSelect"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import MediaTopbar from '../components/media-player/MediaTopbar.vue'
import ImageViewer from '../components/media-player/ImageViewer.vue'
import PlaylistPanel from '../components/media-player/PlaylistPanel.vue'
import { useFileResolver } from '../composables/useFileResolver.js'
import { usePlaylist } from '../composables/usePlaylist.js'

const route = useRoute()
const { decryptedName, playUrl, isEncrypted, rawUrl, rawSize, error, resolve, goBack } = useFileResolver()
const playlist = usePlaylist('image')
const viewerRef = ref(null)
const showPlaylist = ref(false)
const uiVisible = computed(() => viewerRef.value?.uiVisible ?? true)

function onPlaylistSelect(item) {
  showPlaylist.value = false
  playlist.goTo(item)
}

function onKeydown(e) {
  if (e.key === 'ArrowLeft') playlist.goPrev()
  else if (e.key === 'ArrowRight') playlist.goNext()
}

watch(() => route.params.path, () => {
  resolve().then(() => playlist.load())
})

onMounted(() => {
  resolve()
  playlist.load()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.image-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #0a0a0c;
  overflow: hidden;
  position: relative;
}

.image-topbar {
  z-index: 100;
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.image-topbar.ui-hidden {
  transform: translateY(-100px);
  opacity: 0;
  pointer-events: none;
}

.rotate-btn {
  position: absolute;
  top: 80px;
  right: 20px;
  z-index: 100;
  background: var(--bg-card);
  border: none;
  color: white;
  font-size: 20px;
  cursor: pointer;
  padding: 10px 14px;
  border-radius: 50%;
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.rotate-btn.ui-hidden {
  transform: translateX(100px);
  opacity: 0;
  pointer-events: none;
}

.rotate-btn:hover {
  background: var(--bg-card-hover);
}

.error-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  z-index: 50;
}
</style>
