<template>
  <div
    class="viewer-container"
    @mousedown="startDrag"
    @touchstart="startDrag"
  >
    <div v-if="loading && !error" class="loading-overlay">
      <n-spin size="large" />
      <p>{{ isEncrypted ? '正在解密并加载图片…' : '正在加载图片…' }}</p>
    </div>

    <div v-if="error" class="error-overlay">
      <n-alert type="error" :bordered="false">{{ error }}</n-alert>
      <n-button type="primary" @click="$emit('retry')">重试</n-button>
    </div>

    <div
      v-show="!loading && !error"
      class="image-wrapper"
      :style="{ transform: `translate(${posX}px, ${posY}px) scale(${zoom}) rotate(${rotation}deg)` }"
    >
      <img
        v-if="src"
        :src="src"
        :alt="alt"
        @load="loading = false"
        @error="onImgError"
      />
    </div>

    <ZoomControls
      :zoom="zoom"
      :visible="uiVisible"
      @zoom-in="zoomIn"
      @zoom-out="zoomOut"
      @reset="resetView"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import ZoomControls from './ZoomControls.vue'

const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  isEncrypted: { type: Boolean, default: false },
})

defineEmits(['retry'])

const loading = ref(true)
const error = ref('')
const zoom = ref(1)
const rotation = ref(0)
const posX = ref(0)
const posY = ref(0)
const uiVisible = ref(true)
const isDragging = ref(false)

let startX = 0, startY = 0
let lastTap = 0
let clickHandler = null

function zoomIn() { zoom.value = Math.min(zoom.value + 0.2, 5) }
function zoomOut() { zoom.value = Math.max(zoom.value - 0.2, 0.1) }

function rotateImage() {
  rotation.value = (rotation.value + 90) % 360
}

function resetView() {
  zoom.value = 1
  rotation.value = 0
  posX.value = 0
  posY.value = 0
}

function onImgError() {
  error.value = '图片加载失败 (可能是解密失败或网络问题)'
  loading.value = false
}

function startDrag(e) {
  if (loading.value || error.value) return

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

function handleScrollZoom(e) {
  if (e.ctrlKey) {
    e.preventDefault()
    if (e.deltaY < 0) zoomIn()
    else zoomOut()
  }
}

onMounted(() => {
  window.addEventListener('wheel', handleScrollZoom, { passive: false })
  clickHandler = (e) => {
    if (e.target.closest('.zoom-controls')) return
    uiVisible.value = !uiVisible.value
  }
  document.querySelector('.viewer-container')?.addEventListener('click', clickHandler)
})

onUnmounted(() => {
  window.removeEventListener('wheel', handleScrollZoom)
  if (clickHandler) {
    document.querySelector('.viewer-container')?.removeEventListener('click', clickHandler)
  }
})

defineExpose({ zoom, rotation, posX, posY, rotateImage, resetView, uiVisible })
</script>

<style scoped>
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
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.8);
  user-select: none;
  pointer-events: none;
}

.loading-overlay, .error-overlay {
  color: white;
  text-align: center;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
</style>
