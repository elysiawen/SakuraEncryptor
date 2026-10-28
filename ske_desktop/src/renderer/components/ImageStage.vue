<template>
  <div
    ref="containerRef"
    class="image-stage"
    @pointerdown="startDrag"
    @pointermove="handleDrag"
    @pointerup="stopDrag"
    @pointercancel="stopDrag"
    @dblclick="resetView"
    @click="onSurfaceClick"
  >
    <div v-if="loading && !error" class="overlay">
      <span class="spinner" />
      <p>正在解密并加载图片…</p>
    </div>

    <div v-if="error" class="overlay">
      <p>{{ error }}</p>
      <button class="pill-btn" type="button" @click="retry">重试</button>
    </div>

    <div
      v-show="!loading && !error"
      class="image-wrapper"
      :class="{ dragging }"
      :style="{ transform: `translate(${posX}px, ${posY}px) scale(${zoom}) rotate(${rotation}deg)` }"
    >
      <img :src="src" :alt="alt" draggable="false" @load="loading = false" @error="onImgError" />
    </div>

    <!-- Floating glass controls, same vocabulary as the web viewer: paging on the
         sides, one centred pill for zoom / rotate / reset. -->
    <template v-if="canPrev || canNext">
      <button
        v-if="canPrev"
        class="glass-btn nav prev"
        :class="{ hidden: !uiVisible }"
        type="button"
        title="上一张"
        @click.stop="emit('prev')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <path d="M14.5 6 9 12l5.5 6" />
        </svg>
      </button>
      <button
        v-if="canNext"
        class="glass-btn nav next"
        :class="{ hidden: !uiVisible }"
        type="button"
        title="下一张"
        @click.stop="emit('next')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <path d="M9.5 6 15 12l-5.5 6" />
        </svg>
      </button>
    </template>

    <!-- Two controls either side of the percentage keeps the readout optically
         centred instead of leaning left. -->
    <div class="zoom-pill" :class="{ hidden: !uiVisible }" @click.stop>
      <button type="button" title="重置视图" @click="resetView">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
          <path d="M4 10l8-6 8 6v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" />
        </svg>
      </button>
      <button type="button" title="缩小" @click="zoomOut">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round">
          <path d="M6 12h12" />
        </svg>
      </button>
      <span class="zoom-text mono">{{ Math.round(zoom * 100) }}%</span>
      <button type="button" title="放大" @click="zoomIn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round">
          <path d="M12 6v12M6 12h12" />
        </svg>
      </button>
      <button type="button" title="旋转 90°" @click="rotate">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
          <path d="M20 12a8 8 0 1 1-2.4-5.7" />
          <path d="M20 4v4h-4" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  canPrev: { type: Boolean, default: false },
  canNext: { type: Boolean, default: false },
})

const emit = defineEmits(['prev', 'next', 'retry', 'error'])

const MIN_ZOOM = 0.2
const MAX_ZOOM = 5
const WHEEL_COOLDOWN_MS = 250

const containerRef = ref(null)
const loading = ref(true)
const error = ref('')
const zoom = ref(1)
const rotation = ref(0)
const posX = ref(0)
const posY = ref(0)
const uiVisible = ref(true)
const dragging = ref(false)

let dragStart = null
let lastTap = 0
let lastWheelAt = 0

function zoomIn() {
  zoom.value = Math.min(MAX_ZOOM, Math.round((zoom.value + 0.2) * 10) / 10)
}

function zoomOut() {
  zoom.value = Math.max(MIN_ZOOM, Math.round((zoom.value - 0.2) * 10) / 10)
}

function rotate() {
  rotation.value = (rotation.value + 90) % 360
}

function resetView() {
  zoom.value = 1
  rotation.value = 0
  posX.value = 0
  posY.value = 0
}

function retry() {
  error.value = ''
  loading.value = true
  emit('retry')
}

function onImgError() {
  loading.value = false
  error.value = '图片加载失败，文件可能已损坏或无法解密'
  emit('error', error.value)
}

function startDrag(event) {
  if (loading.value || error.value) return
  if (event.button !== 0) return

  // Presses that start on a floating control belong to that control. Capturing
  // the pointer on the stage would retarget the events and swallow the click,
  // which is why the paging arrows felt dead.
  if (event.target.closest?.('.glass-btn, .zoom-pill, button')) return

  // Double click resets; the manual timer is what makes it work while dragging.
  const now = Date.now()
  if (now - lastTap < 300) {
    resetView()
    lastTap = 0
    return
  }
  lastTap = now

  dragging.value = true
  dragStart = { x: event.clientX - posX.value, y: event.clientY - posY.value }
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function handleDrag(event) {
  if (!dragging.value || !dragStart) return
  posX.value = event.clientX - dragStart.x
  posY.value = event.clientY - dragStart.y
}

function stopDrag(event) {
  if (!dragging.value) return
  dragging.value = false
  dragStart = null
  event.currentTarget?.releasePointerCapture?.(event.pointerId)
}

function onSurfaceClick(event) {
  if (event.target.closest('.glass-btn, .zoom-pill')) return
  if (Math.abs(posX.value) > 4 || Math.abs(posY.value) > 4) return
  uiVisible.value = !uiVisible.value
}

/**
 * Ctrl + wheel zooms, a plain wheel pages. Wheel bursts (trackpad momentum) are
 * throttled so one gesture cannot skip five images.
 */
function onWheel(event) {
  if (event.ctrlKey) {
    event.preventDefault()
    if (event.deltaY < 0) zoomIn()
    else zoomOut()
    return
  }

  if (!event.deltaY) return
  event.preventDefault()

  const now = Date.now()
  if (now - lastWheelAt < WHEEL_COOLDOWN_MS) return
  lastWheelAt = now

  if (event.deltaY > 0 && props.canNext) emit('next')
  else if (event.deltaY < 0 && props.canPrev) emit('prev')
}

watch(
  () => props.src,
  () => {
    resetView()
    error.value = ''
    loading.value = true
    uiVisible.value = true
  },
)

// Rebound per mount: the listener must sit on the viewer, not on window, so
// scrolling the page behind it does not flip images.
let boundEl = null
let wheelHandler = null

watch(
  containerRef,
  (el) => {
    if (boundEl && wheelHandler) boundEl.removeEventListener('wheel', wheelHandler)
    boundEl = el
    wheelHandler = onWheel
    if (el) el.addEventListener('wheel', wheelHandler, { passive: false })
  },
  { immediate: true },
)

/** Paging and zoom keys, matching the web viewer's shortcuts. */
function onKeydown(event) {
  if (event.target instanceof HTMLInputElement) return

  if (event.key === 'ArrowRight' && props.canNext) emit('next')
  else if (event.key === 'ArrowLeft' && props.canPrev) emit('prev')
  else if (event.key === '+' || event.key === '=') zoomIn()
  else if (event.key === '-') zoomOut()
  else if (event.key === '0') resetView()
  else return

  event.preventDefault()
}

onMounted(() => window.addEventListener('keydown', onKeydown))

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  if (boundEl && wheelHandler) boundEl.removeEventListener('wheel', wheelHandler)
})

defineExpose({ zoomIn, zoomOut, resetView, rotate })
</script>

<style scoped>
.image-stage {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #000;
  cursor: grab;
  touch-action: none;
}

.image-stage:active {
  cursor: grabbing;
}

.image-wrapper {
  position: absolute;
  inset: 0;
  transition: transform 0.1s ease-out;
  will-change: transform;
}

.image-wrapper.dragging {
  transition: none;
}

/* Box = stage, picture fitted inside it. Sizing the <img> with max-width /
   max-height in an auto-sized grid track lets a large photo keep its intrinsic
   size and get cropped, so the box is made definite instead. */
.image-wrapper img {
  display: block;
  width: 100%;
  height: 100%;
  max-width: none;
  max-height: none;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 24px;
  text-align: center;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12.5px;
  line-height: 1.7;
  z-index: 3;
}

.overlay p {
  margin: 0;
  max-width: 420px;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.2);
  border-top-color: #60a5fa;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* One glass recipe for every floating control. */
.glass-btn {
  position: absolute;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 50%;
  background: rgba(12, 12, 14, 0.62);
  backdrop-filter: blur(18px);
  color: rgba(255, 255, 255, 0.9);
  cursor: pointer;
  transition: background 0.16s ease, opacity 0.22s ease, transform 0.22s ease;
  z-index: 4;
}

.glass-btn:hover {
  background: rgba(30, 30, 34, 0.78);
}

.glass-btn svg {
  width: 18px;
  height: 18px;
}

.glass-btn.nav {
  top: 50%;
  width: 44px;
  height: 44px;
  transform: translateY(-50%);
}

.glass-btn.nav.prev {
  left: 16px;
}

.glass-btn.nav.next {
  right: 16px;
}

.glass-btn.hidden {
  opacity: 0;
  pointer-events: none;
}

.zoom-pill {
  position: absolute;
  left: 50%;
  bottom: 20px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 5px 6px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  background: rgba(12, 12, 14, 0.66);
  backdrop-filter: blur(18px);
  color: #fff;
  z-index: 4;
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.zoom-pill.hidden {
  opacity: 0;
  transform: translate(-50%, 16px);
  pointer-events: none;
}

.zoom-pill button {
  display: grid;
  place-items: center;
  flex: 0 0 30px;
  box-sizing: border-box;
  width: 30px;
  height: 30px;
  /* The UA button padding would offset the icon inside a fixed box. */
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: rgba(255, 255, 255, 0.85);
  line-height: 0;
  cursor: pointer;
}

.zoom-pill button:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}

.zoom-pill button svg {
  display: block;
  width: 16px;
  height: 16px;
}

.zoom-text {
  min-width: 48px;
  text-align: center;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.pill-btn {
  padding: 6px 14px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}
</style>
