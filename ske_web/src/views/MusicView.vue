<template>
  <div class="music-view" :class="{ 'is-playing': isPlaying }">
    <!-- Dynamic Background -->
    <div class="bg-blobs">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
    </div>
    <div class="bg-overlay"></div>

    <header class="topbar glass-nav">
      <n-button quaternary size="small" @click="goBack">
        <span class="desktop-text">← 返回</span>
        <span class="mobile-text">←</span>
      </n-button>
      <div class="header-center">
        <div class="music-title truncate" :title="decryptedName">{{ decryptedName || '音频加载中…' }}</div>
        <div class="music-subtitle" v-if="formattedSize">{{ formattedSize }}</div>
      </div>
      <div class="header-actions">
        <n-button quaternary size="small" :disabled="!playUrl" @click="handleDownload">
          <span class="desktop-text">⬇️ 下载</span>
          <span class="mobile-text">⬇️</span>
        </n-button>
        <n-button quaternary size="small" @click="showPlaylist = !showPlaylist">
          <span class="desktop-text">📋 列表</span>
          <span class="mobile-text">📋</span>
        </n-button>
      </div>
    </header>

    <main class="music-container">
      <div v-if="error" class="error-msg glass-card">
        <span class="error-icon">❌</span>
        <p>{{ error }}</p>
        <n-button type="primary" @click="init">重试</n-button>
      </div>

      <div v-else class="player-center">
        <VinylVisualizer :is-playing="isPlaying" :cover-url="coverUrl" />
        <AudioPlayer
          ref="audioPlayerRef"
          :play-url="playUrl"
          @error="(msg) => error = msg"
          @ended="playlist.goNext()"
        />
      </div>
    </main>

    <PlaylistPanel
      :items="playlist.items.value"
      :current-index="playlist.currentIndex.value"
      :visible="showPlaylist"
      label="首"
      @close="showPlaylist = false"
      @select="onPlaylistSelect"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import jsmediatags from 'jsmediatags/dist/jsmediatags.min.js'
import VinylVisualizer from '../components/media-player/VinylVisualizer.vue'
import AudioPlayer from '../components/media-player/AudioPlayer.vue'
import PlaylistPanel from '../components/media-player/PlaylistPanel.vue'
import { useFileResolver } from '../composables/useFileResolver.js'
import { usePlaylist } from '../composables/usePlaylist.js'
import { formatSize } from '../composables/useFileDetection.js'
import { useDownloadManager } from '../composables/useDownloadManager.js'
import { toDownloadUrl } from '../composables/useFileDownload.js'

const route = useRoute()
const { decryptedName, playUrl, isEncrypted, isLocal, rawUrl, rawSize, error, resolve, goBack } = useFileResolver()
const playlist = usePlaylist('audio')
const audioPlayerRef = ref(null)
const coverUrl = ref('')
const showPlaylist = ref(false)
const { addTask } = useDownloadManager()

const isPlaying = computed(() => audioPlayerRef.value?.isPlaying || false)

const formattedSize = computed(() => formatSize(rawSize.value))

function handleDownload() {
  // Encrypted files must be downloaded through the Service Worker, otherwise
  // the user only gets the .ske container instead of the plaintext audio.
  if (isEncrypted.value && !isLocal.value && playUrl.value) {
    addTask({
      url: toDownloadUrl(playUrl.value),
      name: decryptedName.value || 'download',
      total: 0, // plaintext size comes from the response Content-Length
    })
    return
  }

  if (!rawUrl.value) return
  addTask({
    url: rawUrl.value,
    name: decryptedName.value || 'download',
    total: rawSize.value || 0,
  })
}

async function init() {
  await resolve()
  playlist.load()

  // Extract ID3 Cover Art
  coverUrl.value = ''
  if (playUrl.value) {
    const mediaTagsUrl = window.location.origin + playUrl.value
    jsmediatags.read(mediaTagsUrl, {
      onSuccess(tag) {
        if (tag.tags?.picture) {
          const picture = tag.tags.picture
          let base64String = ''
          for (let i = 0; i < picture.data.length; i++) {
            base64String += String.fromCharCode(picture.data[i])
          }
          coverUrl.value = `data:${picture.format};base64,${window.btoa(base64String)}`
        }
      },
      onError(err) {
        console.log('[jsmediatags] No ID3 picture found or error', err)
      },
    })
  }
}

function onPlaylistSelect(item) {
  showPlaylist.value = false
  playlist.goTo(item)
}

watch(() => route.params.path, () => {
  resolve().then(() => playlist.load())
})

onMounted(init)
</script>

<style scoped>
.music-view {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  overflow: hidden;
  background-color: #0f0c29;
}

/* ── Dynamic Background ── */
.bg-blobs {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.6;
  animation: float 20s infinite alternate ease-in-out;
}

.blob-1 { top: -10%; left: -10%; width: 50vw; height: 50vw; background: #8b5cf6; animation-delay: 0s; }
.blob-2 { bottom: -20%; right: -10%; width: 60vw; height: 60vw; background: #ec4899; animation-delay: -5s; }
.blob-3 { top: 40%; left: 30%; width: 40vw; height: 40vw; background: #3b82f6; animation-delay: -10s; }

.is-playing .blob {
  animation-duration: 10s;
  opacity: 0.8;
}

@keyframes float {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(5%, 5%) scale(1.1); }
  100% { transform: translate(-5%, 10%) scale(0.9); }
}

.bg-overlay {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(10, 10, 20, 0.4);
  backdrop-filter: blur(100px);
  z-index: 1;
  pointer-events: none;
}

/* ── Layout ── */
.glass-nav {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-bottom: 1px solid var(--border-glass);
}

.header-actions {
  display: flex;
  gap: 4px;
}

.header-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 50%;
  min-width: 0;
  overflow: hidden;
}

.music-title {
  font-weight: 600;
  font-size: 16px;
  text-align: center;
  color: #fff;
}

.music-subtitle {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

.music-container {
  position: relative;
  z-index: 10;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.player-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 480px;
  gap: 40px;
}

.error-msg {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 40px;
}

.error-icon {
  font-size: 48px;
}

@media (max-width: 600px) {
  .glass-nav {
    padding: 10px 12px;
    gap: 8px;
  }

  .header-center {
    max-width: 45%;
  }

  .music-title {
    font-size: 14px;
  }

  .music-container {
    padding: 16px 12px;
  }

  .player-center {
    gap: 28px;
  }
}
</style>
