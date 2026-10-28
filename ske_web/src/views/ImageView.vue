<template>
  <div class="image-view" :class="{ 'comic-mode': comicMode }">
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
        <n-button
          v-if="canUseComicMode"
          quaternary
          size="small"
          @click="toggleComicMode"
        >
          <span class="desktop-text">{{ comicMode ? '🖼 单页' : '📖 条漫' }}</span>
          <span class="mobile-text">{{ comicMode ? '🖼' : '📖' }}</span>
        </n-button>
        <n-button quaternary size="small" @click="showPlaylist = !showPlaylist">
          <span class="desktop-text">📋 列表</span>
          <span class="mobile-text">📋</span>
        </n-button>
      </template>
    </MediaTopbar>

    <ComicReader
      v-if="comicMode"
      :items="playlist.items.value"
      :dir-path="playlist.dirPath.value"
      :is-local="isLocal"
      :start-index="comicIndex"
      @progress="onComicProgress"
      @scroll="onComicScroll"
    />

    <ImageViewer
      v-else-if="playUrl"
      ref="viewerRef"
      :src="playUrl"
      :alt="decryptedName"
      :is-encrypted="isEncrypted"
      @retry="resolve"
      @prev="playlist.goPrev()"
      @next="playlist.goNext()"
    />

    <div v-if="comicMode && hasMultiple" class="comic-counter">
      {{ comicIndex + 1 }} / {{ playlist.items.value.length }}
    </div>

    <div v-if="error" class="error-overlay">
      <n-alert type="error" :bordered="false">{{ error }}</n-alert>
      <n-button type="primary" @click="resolve">重试</n-button>
    </div>

    <!-- Rotation button (meaningless when pages are stacked vertically) -->
    <button
      v-if="!comicMode"
      class="rotate-btn glass-card"
      :class="{ 'ui-hidden': !uiVisible }"
      @click="viewerRef?.rotateImage()"
      title="旋转"
    >
      🔄
    </button>

    <!-- Previous / Next -->
    <template v-if="hasMultiple && !comicMode">
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
      :current-index="activeIndex"
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
import ComicReader from '../components/media-player/ComicReader.vue'
import PlaylistPanel from '../components/media-player/PlaylistPanel.vue'
import { useFileResolver } from '../composables/useFileResolver.js'
import { usePlaylist } from '../composables/usePlaylist.js'

const route = useRoute()
const { decryptedName, playUrl, isEncrypted, rawUrl, rawSize, error, resolve, goBack, isLocal } = useFileResolver()
const playlist = usePlaylist('image')
const viewerRef = ref(null)
const showPlaylist = ref(false)
const comicMode = ref(false)
const comicIndex = ref(0)
// In comic mode the bar fades out while scrolling and returns once the reader
// stops, so the strip is not permanently covered by a 60 px overlay.
const comicBarHidden = ref(false)
let comicBarTimer = null
const uiVisible = computed(() =>
  comicMode.value ? !comicBarHidden.value : (viewerRef.value?.uiVisible ?? true)
)
const hasMultiple = computed(() => playlist.items.value.length > 1)
/**
 * Page the list should mark as "being read". In comic mode the strip's scroll
 * position is the source of truth (the route deliberately stays put while
 * scrolling), so the highlight has to follow `comicIndex` instead of the
 * playlist index captured when the reader was opened.
 */
const activeIndex = computed(() =>
  comicMode.value ? comicIndex.value : playlist.currentIndex.value
)
// The continuous reader needs the whole sibling list, which only the remote
// backend can enumerate; a single image has nothing to scroll through.
const canUseComicMode = computed(() => !isLocal.value && hasMultiple.value)

function onPlaylistSelect(item) {
  showPlaylist.value = false
  playlist.goTo(item)
}

/**
 * Enter the continuous reader at the current page; leaving it navigates to
 * whichever page was being read, so the single-page view continues from there.
 */
function onComicProgress(index) {
  comicIndex.value = index
}

// Short enough that the bar returns as soon as the finger/momentum stops, but
// long enough that a slow drag does not make it flicker between frames.
const COMIC_BAR_IDLE_MS = 300

function onComicScroll() {
  comicBarHidden.value = true
  clearTimeout(comicBarTimer)
  comicBarTimer = setTimeout(() => {
    comicBarHidden.value = false
  }, COMIC_BAR_IDLE_MS)
}

function toggleComicMode() {
  if (!comicMode.value) {
    comicIndex.value = Math.max(playlist.currentIndex.value, 0)
    comicBarHidden.value = false
    comicMode.value = true
    return
  }

  comicMode.value = false
  comicBarHidden.value = false
  clearTimeout(comicBarTimer)
  const target = playlist.items.value[comicIndex.value]
  const current = playlist.items.value[playlist.currentIndex.value]
  if (target && target.encName !== current?.encName) playlist.goTo(target)
}

function onKeydown(e) {
  // Arrow keys page through the playlist; in the continuous reader they would
  // jump a whole image per press, which is not what scrolling expects.
  if (comicMode.value) return
  if (e.key === 'ArrowLeft') playlist.goPrev()
  else if (e.key === 'ArrowRight') playlist.goNext()
}

watch(() => route.params.path, () => {
  comicMode.value = false
  resolve().then(() => playlist.load())
})

onMounted(() => {
  resolve()
  playlist.load()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  clearTimeout(comicBarTimer)
})
</script>

<style scoped>
.image-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  /* Transparent on purpose: the topbar is a glass bar, so whatever sits behind
     it (the page gradient) must show through its margins. A solid backdrop here
     turns the 16px padding around the bar into a black slab. */
  background: transparent;
  overflow: hidden;
  position: relative;
}

.image-topbar {
  z-index: 100;
  /* Quick both ways: while scrolling the bar must not lag behind the strip. */
  transition: transform 0.25s ease, opacity 0.25s ease;
}

.image-topbar.ui-hidden {
  transform: translateY(-100px);
  opacity: 0;
  pointer-events: none;
}

/* Comic mode: the topbar floats over the strip. Pages scroll underneath the
   glass and get blurred by it, while the area outside the bar stays
   transparent — that is the "blurred bar, transparent surround" look. */
.image-view.comic-mode .image-topbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 120;
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

.comic-counter {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 100;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.62);
  color: #f0f0f2;
  font-size: 12px;
  letter-spacing: 0.02em;
  pointer-events: none;
}
</style>
