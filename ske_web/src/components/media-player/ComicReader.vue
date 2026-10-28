<template>
  <div ref="scrollerRef" class="comic-reader" @scroll.passive="onScroll">
    <!-- Keeps the first page below the floating topbar; it scrolls away with
         the strip, so later pages do pass underneath the glass. -->
    <div class="comic-spacer" aria-hidden="true"></div>

    <div
      v-for="(item, index) in items"
      :key="item.encName"
      class="comic-page"
      :data-index="index"
    >
      <img
        v-if="urls[item.encName]"
        class="comic-image"
        :src="urls[item.encName]"
        :alt="item.decName"
        decoding="async"
        @error="onImageError(item)"
      />
      <button
        v-else-if="failed[item.encName]"
        class="page-hint"
        @click="retry(item)"
      >
        加载失败，点击重试
      </button>
      <n-spin v-else size="small" />
    </div>

    <div v-if="items.length === 0" class="page-hint">没有可阅读的图片</div>
  </div>
</template>

<script setup>
/**
 * ComicReader — vertical continuous ("webtoon") reading mode.
 *
 * The whole directory is laid out as one long strip, so the reader just scrolls
 * down; nothing is paginated and no image is cropped to the viewport.
 *
 * Images are resolved lazily: a `.ske` page costs one `fs/get` request plus the
 * service worker decrypting a single block, so pre-resolving a 200-page chapter
 * would hit the server 200 times before the first page renders. An
 * IntersectionObserver resolves only the pages within ~800 px of the viewport.
 */
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { getFileInfo } from '../../composables/useAList.js'
import { buildDecryptProxyUrl } from '../../composables/useDecryptProxy.js'
import { getPasswordFromSession } from '../../composables/useCrypto.js'

const props = defineProps({
  /** Playlist entries in reading order: `{ encName, decName }`. */
  items: { type: Array, default: () => [] },
  /** Stored directory path, e.g. `/comics/vol1`. */
  dirPath: { type: String, default: '' },
  /** Local files go through `/ske-local/` instead of AList. */
  isLocal: { type: Boolean, default: false },
  /** Page to scroll to on open — the image the user came from. */
  startIndex: { type: Number, default: 0 },
})

// `scroll` is emitted (not the native event) so the parent can fade its topbar
// without attaching listeners to an element it does not own.
const emit = defineEmits(['progress', 'scroll'])

const scrollerRef = ref(null)
const urls = ref({})
const failed = ref({})

/**
 * Height of the floating topbar plus its margin. `scrollToIndex` and the
 * "currently reading" anchor both measure from under the bar, and the spacer
 * at the top of the strip uses the same value in CSS.
 */
const TOP_SPACE = 84

const pending = new Set()
let lazyObserver = null
let pages = []
let scrollFrame = 0
let lastIndex = -1

function joinPath(dir, name) {
  return `${dir.replace(/\/+$/, '')}/${name}`
}

/**
 * The service worker keeps the password only in memory, so it can lose it when
 * the browser reclaims the worker; re-send it before each page instead of
 * relying on the single message sent when the view was opened.
 */
function pushPasswordToSW() {
  const password = getPasswordFromSession()
  if (password && navigator.serviceWorker?.controller) {
    navigator.serviceWorker.controller.postMessage({ type: 'SET_PASSWORD', password })
  }
}

/** Same URL rules as `useFileResolver`: decrypt proxy for `.ske`, raw otherwise. */
async function resolveUrl(item) {
  const path = joinPath(props.dirPath, item.encName)

  if (props.isLocal) {
    const localPath = decodeURIComponent(path.replace(/^\/?local\//, ''))
    return `/ske-local/${localPath}`
  }

  pushPasswordToSW()
  const info = await getFileInfo(path)
  if (!info.url) throw new Error('无法获取文件链接')
  return item.encName.toLowerCase().endsWith('.ske')
    ? buildDecryptProxyUrl(info.url, info.size)
    : info.url
}

async function loadItem(item) {
  // A failed page stays failed until the reader taps it: the observer fires
  // again on every pass and would otherwise hammer the backend.
  if (urls.value[item.encName] || failed.value[item.encName] || pending.has(item.encName)) return

  pending.add(item.encName)
  try {
    const url = await resolveUrl(item)
    urls.value = { ...urls.value, [item.encName]: url }
  } catch {
    failed.value = { ...failed.value, [item.encName]: true }
  } finally {
    pending.delete(item.encName)
  }
}

function retry(item) {
  const next = { ...failed.value }
  delete next[item.encName]
  failed.value = next
  loadItem(item)
}

function onImageError(item) {
  failed.value = { ...failed.value, [item.encName]: true }
  const next = { ...urls.value }
  delete next[item.encName]
  urls.value = next
}

function onScroll() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    const el = scrollerRef.value
    if (!el) return

    emit('scroll')

    // The page under the top quarter of the viewport is "what is being read":
    // that is the one the single-page view opens when the reader leaves.
    // Pages are laid out in order, so offsetTop is monotonic — binary search
    // keeps this from forcing a full layout read on every scroll frame.
    const anchor = el.scrollTop + TOP_SPACE + (el.clientHeight - TOP_SPACE) * 0.25
    let index = 0
    let low = 0
    let high = pages.length - 1
    while (low <= high) {
      const mid = (low + high) >> 1
      if (pages[mid].offsetTop <= anchor) {
        index = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }
    const page = pages[index]
    const pageIndex = page ? Number(page.dataset.index) : 0
    if (pageIndex !== lastIndex) {
      lastIndex = pageIndex
      emit('progress', pageIndex)
    }
  })
}

function scrollToIndex(index) {
  const el = scrollerRef.value
  if (!el) return
  const page = el.querySelector(`[data-index="${index}"]`)
  // Park the page just under the translucent bar instead of behind it.
  if (page) el.scrollTop = Math.max(0, page.offsetTop - TOP_SPACE)
}

function setupLazyLoading() {
  lazyObserver?.disconnect()
  if (!scrollerRef.value) return

  lazyObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const index = Number(entry.target.dataset.index)
        const item = props.items[index]
        if (item) loadItem(item)
      }
    },
    // Resolve the pages just above and below the viewport so scrolling stays
    // ahead of the reader without touching the rest of the chapter.
    { root: scrollerRef.value, rootMargin: '800px 0px 800px 0px', threshold: 0 }
  )

  pages = Array.from(scrollerRef.value.querySelectorAll('.comic-page'))
  pages.forEach((page) => lazyObserver.observe(page))
}

onMounted(async () => {
  await nextTick()
  setupLazyLoading()
  scrollToIndex(props.startIndex)
  lastIndex = props.startIndex
  emit('progress', props.startIndex)
})

onBeforeUnmount(() => {
  lazyObserver?.disconnect()
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
})
</script>

<style scoped>
.comic-reader {
  flex: 1;
  position: relative;
  overflow-y: auto;
  overflow-x: hidden;
  /* Let the page gradient show through around the glass topbar and between
     pages; the images themselves cover the strip, so a black backdrop only
     adds a visible slab above the first page. */
  background: transparent;
  /* Keep the viewport anchored while a page grows from placeholder to image. */
  overflow-anchor: auto;
  overscroll-behavior: contain;
}

/* Must match TOP_SPACE in the script. */
.comic-spacer {
  height: 84px;
  flex: none;
}

.comic-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 40vh;
  width: 100%;
}

.comic-image {
  display: block;
  width: 100%;
  height: auto;
  /* Seamless strip: no gaps between consecutive pages. */
  margin: 0;
  user-select: none;
}

.page-hint {
  color: #b0b0b8;
  font-size: 13px;
  background: none;
  border: none;
  padding: 12px 16px;
  cursor: pointer;
}
</style>
