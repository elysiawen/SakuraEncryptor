<template>
  <div class="browser-page">
    <!-- Content -->
    <main class="browser-main">
      <div v-if="loading" class="loading-state">
        <div class="spinner" style="width:32px;height:32px;border-width:3px"></div>
        <p>正在加载目录…</p>
      </div>

      <div v-else-if="error" class="error-state">
        <p>❌ {{ error }}</p>
        <button class="btn btn-ghost" @click="loadDir(currentPath)">重试</button>
      </div>

      <div v-else-if="items.length === 0" class="empty-state">
        <p>📂 该目录为空</p>
      </div>

      <div v-else class="file-grid">
        <div
          v-for="item in items"
          :key="item.encName"
          class="file-item glass-card"
          @click="handleClick(item)"
        >
          <div class="file-icon">
            <span v-if="item.is_dir">📁</span>
            <span v-else-if="isImage(item.decName)">🖼️</span>
            <span v-else-if="isVideo(item.decName)">🎬</span>
            <span v-else-if="isMusic(item.decName)">🎵</span>
            <span v-else>📄</span>
          </div>
          <div class="file-info">
            <div class="file-name truncate" :title="item.decName">{{ item.decName || item.encName }}</div>
            <div class="file-meta">
              <span v-if="!item.is_dir && item.size">{{ formatSize(item.size) }}</span>
              <span v-if="item.is_dir" class="tag tag-folder">文件夹</span>
              <span v-else-if="isImage(item.decName)" class="tag tag-image">图片</span>
              <span v-else-if="isVideo(item.decName)" class="tag tag-video">视频</span>
              <span v-else-if="isMusic(item.decName)" class="tag tag-music">音乐</span>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listDir } from '../composables/useAList.js'
import { getNameKeyFromSession, decryptName } from '../composables/useCrypto.js'
import { globalState } from '../composables/useGlobalState.js'

const route = useRoute()
const router = useRouter()

const items = ref([])
const loading = ref(false)
const error = ref('')
const currentPath = computed(() => {
  const p = route.params.path
  if (!p) return '/'
  const joined = Array.isArray(p) ? p.join('/') : p
  return '/' + joined
})

function navigateTo(path) {
  router.push('/browse' + path)
}

function handleClick(item) {
  if (item.is_dir) {
    const encPath = currentPath.value === '/' ? '/' + item.encName : currentPath.value + '/' + item.encName
    router.push('/browse' + encPath)
  } else if (isImage(item.decName)) {
    const filePath = currentPath.value === '/' ? '/' + item.encName : currentPath.value + '/' + item.encName
    router.push('/view' + filePath)
  } else if (isVideo(item.decName)) {
    const filePath = currentPath.value === '/' ? '/' + item.encName : currentPath.value + '/' + item.encName
    router.push('/play' + filePath)
  } else if (isMusic(item.decName)) {
    const filePath = currentPath.value === '/' ? '/' + item.encName : currentPath.value + '/' + item.encName
    router.push('/listen' + filePath)
  }
}

function isImage(name) {
  if (!name) return false
  const ext = name.split('.').pop()?.toLowerCase()
  return ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(ext)
}

function isMusic(name) {
  if (!name) return false
  const ext = name.split('.').pop()?.toLowerCase()
  return ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a'].includes(ext)
}

function isVideo(name) {
  if (!name) return false
  // The original name before encryption — check common video extensions
  const ext = name.split('.').pop()?.toLowerCase()
  return ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'ts', 'm4v'].includes(ext)
}

function formatSize(bytes) {
  if (!bytes) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return size.toFixed(i > 0 ? 1 : 0) + ' ' + units[i]
}

function handleLogout() {
  sessionStorage.clear()
  router.push('/login')
}

async function loadDir(path) {
  loading.value = true
  error.value = ''
  items.value = []

  try {
    const nameKey = await getNameKeyFromSession()
    const data = await listDir(path)
    const content = data.content || []

    // Decrypt names in parallel
    const decrypted = await Promise.all(
      content.map(async (entry) => {
        const encName = entry.name
        let decName = encName

        if (nameKey) {
          // If it ends with .ske, strip extension before decrypting
          let nameToDecrypt = encName
          if (encName.endsWith('.ske')) {
            nameToDecrypt = encName.slice(0, -4)
          }
          const result = await decryptName(nameToDecrypt, nameKey)
          if (result) decName = result
        }

        return {
          encName,
          decName,
          is_dir: entry.is_dir,
          size: entry.size,
          modified: entry.modified,
        }
      })
    )

    // Sort: folders first, then alphabetical
    decrypted.sort((a, b) => {
      if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1
      return a.decName.localeCompare(b.decName, 'zh-CN')
    })

    items.value = decrypted

    // Decrypt breadcrumb segments
    const pathParts = path.split('/').filter(Boolean)
    if (nameKey && pathParts.length > 0) {
      const decSeg = await Promise.all(
        pathParts.map(async (seg) => {
          const result = await decryptName(seg, nameKey)
          return result || seg
        })
      )
      globalState.decryptedSegments = decSeg
    } else {
      globalState.decryptedSegments = pathParts
    }
  } catch (err) {
    error.value = err.message || '加载失败'
  } finally {
    loading.value = false
  }
}

watch(currentPath, (p) => loadDir(p), { immediate: true })
</script>

<style scoped>
.browser-page {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* ── Main ── */
.browser-main {
  flex: 1;
  padding: 24px 16px;
}

@media (max-width: 600px) {
  .browser-main {
    padding: 12px 8px;
  }
}

.loading-state, .error-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 80px 24px;
  color: var(--text-secondary);
  font-size: 15px;
}

/* ── File Grid ── */
.file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

@media (max-width: 600px) {
  .file-grid {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  
  .file-item {
    padding: 14px 16px;
  }
}

.file-item:hover {
  transform: translateY(-2px);
}

.file-icon {
  font-size: 28px;
  flex-shrink: 0;
  width: 40px;
  text-align: center;
}

.file-info {
  min-width: 0;
  flex: 1;
}

.file-name {
  font-size: 14px;
  font-weight: 500;
}

.file-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.tag {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.tag-folder {
  background: rgba(139, 92, 246, 0.15);
  color: var(--accent-start);
}
.tag-video {
  background: rgba(6, 182, 212, 0.15);
  color: var(--accent-end);
}
.tag-image {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
}
.tag-music {
  background: rgba(244, 63, 94, 0.15);
  color: #f43f5e;
}
</style>
