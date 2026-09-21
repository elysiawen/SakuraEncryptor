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
      @prev="playlist.goPrev()"
      @next="playlist.goNext()"
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

    <!-- Previous / Next -->
    <template v-if="hasMultiple">
      <button
        class="nav-btn prev glass-card"
        :class="{ 'ui-hidden': !uiVisible }"
        @click="playlist.goPrev()"
        title="上一张"
      >
        ❮
      </button>
      <button
        class="nav-btn next glass-card"
        :class="{ 'ui-hidden': !uiVisible }"
        @click="playlist.goNext()"
        title="下一张"
      >
        ❯
      </button>
    </template>

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
const hasMultiple = computed(() => playlist.items.value.length > 1)

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

/* ── Previous / next ── */
.nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 100;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-card);
  border: none;
  color: white;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  border-radius: 50%;
  opacity: 0.85;
  transition: opacity 0.3s ease, background 0.2s ease;
}

.nav-btn.prev {
  left: 20px;
}

.nav-btn.next {
  right: 20px;
}

.nav-btn:hover {
  background: var(--bg-card-hover);
  opacity: 1;
}

/* Only fade — a transform here would clobber the vertical centering. */
.nav-btn.ui-hidden {
  opacity: 0;
  pointer-events: none;
}

@media (max-width: 600px) {
  .nav-btn {
    width: 40px;
    height: 40px;
    font-size: 16px;
  }

  .nav-btn.prev {
    left: 10px;
  }

  .nav-btn.next {
    right: 10px;
  }
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
