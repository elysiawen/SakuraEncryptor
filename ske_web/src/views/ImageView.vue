<template>
  <div class="image-view" @click="toggleUI">
    <header class="topbar glass-card" :class="{ 'ui-hidden': !uiVisible }">
      <div class="topbar-left">
        <button class="btn btn-ghost btn-icon-mobile" @click.stop="goBack">
          <span class="desktop-text">⬅️ 返回</span>
          <span class="mobile-text">←</span>
        </button>
        <div class="file-name truncate" :title="decryptedName">{{ decryptedName }}</div>
      </div>
      <div class="topbar-right">
        <button class="btn btn-ghost" @click.stop="rotateImage" title="旋转">🔄</button>
        <a :href="playUrl" :download="decryptedName" class="btn btn-primary" @click.stop>
          <span class="desktop-text">下载</span>
          <span class="mobile-text">⬇️</span>
        </a>
      </div>
    </header>

    <main class="viewer-container" @mousedown="startDrag" @touchstart="startDrag">
      <!-- Loading & Error overlays -->
      <div v-if="loading && !error" class="loading-state">
        <div class="spinner"></div>
        <p>{{ isEncrypted ? '正在解密并加载图片…' : '正在加载图片…' }}</p>
      </div>
      
      <div v-if="error" class="error-state">
        <p>❌ {{ error }}</p>
        <button class="btn btn-primary" @click="initViewer">重试</button>
      </div>

      <!-- Image itself -->
      <div 
        v-show="!loading && !error"
        class="image-wrapper"
        :style="{ transform: `translate(${posX}px, ${posY}px) scale(${zoom}) rotate(${rotation}deg)` }"
      >
        <img 
          v-if="playUrl" 
          :src="playUrl" 
          :alt="decryptedName" 
          @load="onImgLoad" 
          @error="onImgError"
        />
      </div>

      <!-- Zoom Controls -->
      <div class="zoom-controls glass-card" :class="{ 'ui-hidden': !uiVisible }" @click.stop>
        <button @click="zoomOut">➖</button>
        <span class="zoom-text">{{ Math.round(zoom * 100) }}%</span>
        <button @click="zoomIn">➕</button>
        <button @click="resetView">🏠</button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFileInfo } from '../composables/useAList.js'
import { getNameKeyFromSession, decryptName } from '../composables/useCrypto.js'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const decryptedName = ref('')
const playUrl = ref('')
const isEncrypted = ref(false)
const zoom = ref(1)
const rotation = ref(0)
const posX = ref(0)
const posY = ref(0)
const uiVisible = ref(true)

const isDragging = ref(false)
let startX = 0, startY = 0
let lastTap = 0

function toggleUI() {
  uiVisible.value = !uiVisible.value
}

function goBack() {
  const parts = route.params.path || []
  const parentPath = parts.slice(0, -1).join('/')
  router.push('/browse/' + parentPath)
}

function rotateImage() {
  rotation.value = (rotation.value + 90) % 360
}

function zoomIn() { zoom.value = Math.min(zoom.value + 0.2, 5) }
function zoomOut() { zoom.value = Math.max(zoom.value - 0.2, 0.1) }
function resetView() {
  zoom.value = 1
  rotation.value = 0
  posX.value = 0
  posY.value = 0
}

function onImgLoad() {
  loading.value = false
}

function onImgError() {
  error.value = '图片加载失败 (可能是解密失败或网络问题)'
  loading.value = false
}

function startDrag(e) {
  if (loading.value || error.value) return
  
  // Double tap to reset
  const now = Date.now()
  if (now - lastTap < 300) {
    resetView()
    lastTap = 0
    return
  }
  lastTap = now

  isDragging.value = true
  const event = e.touches ? e.touches[0] : e
  startX = event.clientX - posX.value
  startY = event.clientY - posY.value
  
  window.addEventListener('mousemove', handleDrag)
  window.addEventListener('mouseup', stopDrag)
  window.addEventListener('touchmove', handleDrag)
  window.addEventListener('touchend', stopDrag)
}

function handleDrag(e) {
  if (!isDragging.value) return
  const event = e.touches ? e.touches[0] : e
  posX.value = event.clientX - startX
  posY.value = event.clientY - startY
}

function stopDrag() {
  isDragging.value = false
  window.removeEventListener('mousemove', handleDrag)
  window.removeEventListener('mouseup', stopDrag)
  window.removeEventListener('touchmove', handleDrag)
  window.removeEventListener('touchend', stopDrag)
}

async function initViewer() {
  loading.value = true
  error.value = ''
  
  try {
    const p = route.params.path
    const fullPath = '/' + (Array.isArray(p) ? p.join('/') : (p || ''))
    const segments = fullPath.split('/').filter(Boolean)
    const encName = segments[segments.length - 1]
    
    // Decrypt name
    const nameKey = await getNameKeyFromSession()
    if (nameKey) {
      let nameToDec = encName
      if (encName.endsWith('.ske')) nameToDec = encName.slice(0, -4)
      decryptedName.value = await decryptName(nameToDec, nameKey) || encName
    } else {
      decryptedName.value = encName
    }
    
    // Get AList URL
    const info = await getFileInfo(fullPath)
    if (!info.url) throw new Error('无法获取文件链接')
    
    // Construct Proxy URL
    // sw-decrypt handles /ske-decrypt/ prefix
    isEncrypted.value = encName.endsWith('.ske')
    const rawUrl = info.url
    const sizeParam = info.size ? `&size=${info.size}` : ''
    
    if (isEncrypted.value) {
      playUrl.value = `/ske-decrypt/?url=${encodeURIComponent(rawUrl)}${sizeParam}`
    } else {
      playUrl.value = rawUrl
    }
    
  } catch (err) {
    error.value = err.message || '加载图片失败'
    loading.value = false
  }
}

onMounted(() => {
  initViewer()
  // Scroll zoom
  window.addEventListener('wheel', handleScrollZoom, { passive: false })
})

onUnmounted(() => {
  window.removeEventListener('wheel', handleScrollZoom)
})

function handleScrollZoom(e) {
  if (e.ctrlKey) {
    e.preventDefault()
    if (e.deltaY < 0) zoomIn()
    else zoomOut()
  }
}
</script>

<style scoped>
.image-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #0a0a0c;
  overflow: hidden;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  margin: 10px;
  z-index: 100;
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.topbar.ui-hidden {
  transform: translateY(-100px);
  opacity: 0;
  pointer-events: none;
}

.topbar-left, .topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.file-name {
  max-width: 200px;
}

@media (max-width: 600px) {
  .topbar {
    margin: 8px;
    padding: 8px 12px;
  }
  .file-name {
    font-size: 14px;
    max-width: 120px;
  }
  .mobile-text { display: inline; }
  .desktop-text { display: none; }
}

@media (min-width: 601px) {
  .mobile-text { display: none; }
  .desktop-text { display: inline; }
}

.viewer-container {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  touch-action: none;
  background: #000;
}

.viewer-container:active {
  cursor: grabbing;
}

.image-wrapper {
  position: absolute;
  /* Use transition for smooth snapping on reset, but dragging will override with direct style */
  transition: transform 0.1s ease-out;
  display: flex;
  align-items: center;
  justify-content: center;
  will-change: transform;
}

.image-wrapper img {
  max-width: 100vw;
  max-height: 100vh;
  object-fit: contain;
  box-shadow: 0 10px 40px rgba(0,0,0,0.8);
  user-select: none;
  pointer-events: none;
}

.loading-state, .error-state {
  color: white;
  text-align: center;
  z-index: 5;
}

.zoom-controls {
  position: absolute;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 10px 20px;
  border-radius: 50px;
  z-index: 10;
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.zoom-controls.ui-hidden {
  transform: translate(-50%, 100px);
  opacity: 0;
  pointer-events: none;
}

.zoom-controls button {
  background: transparent;
  border: none;
  color: white;
  font-size: 18px;
  cursor: pointer;
  padding: 8px;
  border-radius: 50%;
}

@media (max-width: 600px) {
  .zoom-controls {
    bottom: 20px;
    gap: 10px;
    padding: 8px 16px;
  }
  .zoom-text {
    font-size: 12px;
  }
}

.zoom-controls button:hover {
  background: rgba(255,255,255,0.1);
}

.zoom-controls span {
  font-family: 'Cascadia Code', monospace;
  font-size: 14px;
  min-width: 50px;
  text-align: center;
}
</style>
