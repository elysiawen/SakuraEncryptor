<template>
  <div
    ref="root"
    class="file-card file-tile glass-card"
    @click="$emit('click', item)"
    @contextmenu.stop.prevent="$emit('contextmenu', $event, item)"
    @touchstart.passive="onTouchStart"
    @touchend.passive="onTouchEnd"
    @touchmove.passive="onTouchCancel"
  >
    <div class="thumb">
      <img
        v-if="thumbUrl && !failed"
        :src="thumbUrl"
        :alt="displayName"
        loading="lazy"
        @error="failed = true"
      />
      <FileIcon v-else class="fallback-icon" :item="item" />

      <span v-if="isVideo" class="play-badge" title="视频">▶</span>
      <span v-if="encrypted" class="badge" title="加密文件">🔒</span>
      <span v-if="!item.is_dir && item.size" class="badge size-badge">
        {{ formatSize(item.size) }}
      </span>
    </div>

    <div class="tile-name" :title="displayName">{{ displayName }}</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import FileIcon from '../common/FileIcon.vue'
import { formatSize, isEncryptedFileName } from '../../composables/useFileDetection.js'
import { isVideoEntry, resolveThumbnail, thumbnailKind } from '../../composables/useThumbnails.js'

const props = defineProps({
  item: { type: Object, required: true },
  mode: { type: String, default: 'alist' },
  parentPath: { type: String, default: '/' },
})

const emit = defineEmits(['click', 'contextmenu'])

const root = ref(null)
const thumbUrl = ref('')
const failed = ref(false)

const displayName = computed(() => props.item.decName || props.item.encName || '')
const encrypted = computed(
  () => props.item.encrypted ?? isEncryptedFileName(props.item.encName || props.item.name),
)
const hasThumbnail = computed(() => thumbnailKind(props.item) !== null)
const isVideo = computed(() => isVideoEntry(props.item))

let observer = null
let requested = false

function absPath() {
  if (props.mode === 'local') return props.item.path || ''
  const base = props.parentPath || '/'
  return base === '/' ? `/${props.item.encName}` : `${base}/${props.item.encName}`
}

async function load() {
  if (requested || !hasThumbnail.value) return
  requested = true
  try {
    const url = await resolveThumbnail(props.mode, props.item, absPath())
    if (url) thumbUrl.value = url
  } catch {
    failed.value = true
  }
}

onMounted(() => {
  if (!hasThumbnail.value) return
  if (!('IntersectionObserver' in window)) {
    load()
    return
  }
  // Load when the tile approaches the viewport. Video posters are capped by the
  // service worker per range response, so this stays cheap even for folders
  // full of clips.
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        load()
        observer?.disconnect()
        observer = null
      }
    },
    { rootMargin: '150px' },
  )
  if (root.value) observer.observe(root.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

// Long-press opens the same context menu on touch devices.
let longPressTimer = null

function onTouchStart(e) {
  longPressTimer = setTimeout(() => {
    const touch = e.touches[0]
    emit('contextmenu', { clientX: touch.clientX, clientY: touch.clientY }, props.item)
  }, 500)
}

function onTouchEnd() {
  clearTimeout(longPressTimer)
}

function onTouchCancel() {
  clearTimeout(longPressTimer)
}
</script>

<style scoped>
.file-tile {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
  overflow: hidden;
  transition: all var(--transition-fast);
}

.file-tile:hover {
  transform: translateY(-2px);
}

.thumb {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--radius-md);
  background: var(--bg-glass);
}

.thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.fallback-icon {
  font-size: 34px;
  width: auto;
  opacity: 0.85;
}

.play-badge {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  padding-left: 3px;
  font-size: 14px;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 50%;
  backdrop-filter: blur(4px);
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.badge {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 3px 6px;
  font-size: 11px;
  line-height: 1.2;
  color: var(--text-primary);
  background: rgba(0, 0, 0, 0.55);
  border-radius: 999px;
  backdrop-filter: blur(4px);
}

.size-badge {
  top: auto;
  bottom: 6px;
  color: var(--text-secondary);
}

.tile-name {
  font-size: 12.5px;
  line-height: 1.35;
  color: var(--text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

@media (max-width: 600px) {
  .tile-name {
    font-size: 12px;
  }

  .fallback-icon {
    font-size: 26px;
  }
}
</style>
