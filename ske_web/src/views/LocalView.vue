<template>
  <div class="browser-page">
    <!-- Header -->
    <header class="browser-header">
      <div class="brand" @click="goHome">
        <span class="logo-text">🌸 Sakura Encryptor</span>
        <span class="mode-badge">本地库</span>
      </div>
      <div class="breadcrumb">
        <span class="crumb-item" @click="currentPath = ''">🏠 根目录</span>
        <span v-for="(seg, i) in decryptedSegments" :key="i" class="crumb-sep">
          / <span class="crumb-item" @click="goToSegment(i)">{{ seg }}</span>
        </span>
      </div>
      <div class="actions">
        <button class="btn btn-outline btn-sm" @click="goHome">退出库</button>
      </div>
    </header>

    <!-- Main Content -->
    <main class="browser-main container">
      <div v-if="loading" class="loading-state">
        <div class="spinner"></div>
        <p>正在解密目录…</p>
      </div>

      <!-- Empty State -->
      <div v-else-if="displayFiles.length === 0" class="empty-state">
        <div class="empty-icon">📂</div>
        <h3>这个文件夹是空的</h3>
        <p>或者没有可识别的媒体文件 / .ske 文件</p>
        <button class="btn btn-primary" @click="goHome">返回选择文件夹</button>
      </div>

      <!-- File Grid -->
      <div v-else class="file-grid">
        <div v-for="item in displayFiles" :key="item.path" class="file-card glass-card ripple"
          @click="handleItemClick(item)">
          <div class="file-icon">
            <span v-if="item.isDir">📁</span>
            <span v-else-if="isImage(item.decName)">🖼️</span>
            <span v-else-if="isAudio(item.decName)">🎵</span>
            <span v-else-if="isVideo(item.decName)">🎬</span>
            <span v-else>📄</span>
          </div>
          <div class="file-info">
            <div class="file-name" :title="item.decName">{{ item.decName }}</div>
            <div class="file-meta">
              {{ item.isDir ? '文件夹' : '文件' }}
              <span v-if="item.name.endsWith('.ske')" class="enc-tag">已加密</span>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useLocalFiles } from '../composables/useLocalFiles.js'
import { getNameKeyFromSession, decryptName } from '../composables/useCrypto.js'

const router = useRouter()
const { state } = useLocalFiles()

const currentPath = ref('') // e.g. "my-folder/sub"
const displayFiles = ref([])
const loading = ref(false)
const decryptedSegments = ref([])

const pathSegments = computed(() => {
    return currentPath.value.split('/').filter(Boolean)
})

async function refreshDisplayFiles() {
    loading.value = true
    try {
        const nameKey = await getNameKeyFromSession()
        const prefix = currentPath.value ? currentPath.value + '/' : ''
        const contents = new Map()

        // 1. Decrypt Breadcrumb
        const segs = pathSegments.value
        const decSegs = await Promise.all(
            segs.map(async (s) => {
                if (!nameKey) return s
                const res = await decryptName(s, nameKey)
                return res || s
            })
        )
        decryptedSegments.value = decSegs

        // 2. Process Files in current view
        for (const f of state.files) {
            if (!f.path.startsWith(prefix)) continue
            
            const relative = f.path.slice(prefix.length)
            const parts = relative.split('/')
            
            if (parts.length > 1) {
                // It's a directory
                const dirName = parts[0]
                if (!contents.has(dirName)) {
                    contents.set(dirName, {
                        name: dirName,
                        decName: dirName, // placeholders
                        path: prefix + dirName,
                        isDir: true
                    })
                }
            } else {
                // It's a file
                contents.set(f.name, {
                    name: f.name,
                    decName: f.name,
                    path: f.path,
                    isDir: false
                })
            }
        }

        const items = Array.from(contents.values())

        // 3. Decrypt Item Names
        const decryptedItems = await Promise.all(
            items.map(async (item) => {
                if (!nameKey) return { ...item, decName: item.name }
                
                let nameToDec = item.name
                const isSke = item.name.endsWith('.ske')
                if (isSke) nameToDec = item.name.slice(0, -4)
                
                const dec = await decryptName(nameToDec, nameKey)
                return { ...item, decName: dec || item.name }
            })
        )

        // 4. Sort
        decryptedItems.sort((a, b) => {
            if (a.isDir && !b.isDir) return -1
            if (!a.isDir && b.isDir) return 1
            return a.decName.localeCompare(b.decName, 'zh-CN')
        })

        displayFiles.value = decryptedItems
    } catch (e) {
        console.error('[LocalView] Refresh failed', e)
    } finally {
        loading.value = false
    }
}

watch([currentPath, () => state.files], () => {
    refreshDisplayFiles()
}, { immediate: true })

function goToSegment(index) {
    currentPath.value = pathSegments.value.slice(0, index + 1).join('/')
}

function handleItemClick(item) {
    if (item.isDir) {
        currentPath.value = item.path
    } else {
        const ext = item.decName.split('.').pop().toLowerCase()
        const isSke = item.name.endsWith('.ske')
        let realExt = ext
        // If it's a .ske file, extension is likely inside decName
        // ext is already from decName, so we are good.

        const type = getFileType(realExt)
        if (type === 'video') router.push('/play/local/' + encodeURIComponent(item.path))
        else if (type === 'audio') router.push('/listen/local/' + encodeURIComponent(item.path))
        else if (type === 'image') router.push('/view/local/' + encodeURIComponent(item.path))
        else alert('不支持的文件格式')
    }
}

function getFileType(ext) {
    if (['mp4', 'mkv', 'webm', 'mov', 'avi'].includes(ext)) return 'video'
    if (['mp3', 'wav', 'flac', 'm4a', 'ogg'].includes(ext)) return 'audio'
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(ext)) return 'image'
    return 'other'
}

function isImage(n) { return getFileType(n?.split('.').pop().toLowerCase()) === 'image' }
function isAudio(n) { return getFileType(n?.split('.').pop().toLowerCase()) === 'audio' }
function isVideo(n) { return getFileType(n?.split('.').pop().toLowerCase()) === 'video' }

function goHome() {
    router.push('/login')
}
</script>

<style scoped>
.browser-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.browser-header {
  height: 64px;
  background: rgba(15, 12, 41, 0.7);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  align-items: center;
  padding: 0 24px;
  gap: 20px;
  position: sticky;
  top: 0;
  z-index: 100;
}

.brand {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo-text {
  font-weight: 700;
  font-size: 18px;
  background: var(--accent-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.mode-badge {
  font-size: 10px;
  background: rgba(255, 255, 255, 0.1);
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.breadcrumb {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-secondary);
  overflow: hidden;
  white-space: nowrap;
}

.crumb-item {
  cursor: pointer;
  transition: color 0.2s;
}

.crumb-item:hover {
  color: var(--text-primary);
}

.crumb-sep {
  color: var(--text-muted);
}

.browser-main {
  flex: 1;
  padding: 32px 0;
}

.file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}

.file-card {
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border: 1px solid transparent;
}

.file-card:hover {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.1);
  transform: translateY(-4px);
}

.file-icon {
  font-size: 40px;
  margin-bottom: 12px;
}

.file-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 4px;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta {
  font-size: 12px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.enc-tag {
  background: rgba(139, 92, 246, 0.15);
  color: var(--accent-start);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 10px;
}

.empty-state {
  text-align: center;
  padding: 80px 0;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 24px;
  opacity: 0.3;
}

.empty-state h3 {
  font-size: 20px;
  margin-bottom: 8px;
}

.empty-state p {
  color: var(--text-muted);
  margin-bottom: 32px;
}
</style>
