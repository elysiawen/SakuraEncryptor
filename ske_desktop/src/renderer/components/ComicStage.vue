<template>
  <div class="comic-stage">
    <div
      ref="scrollRef"
      class="comic-scroll"
      :class="{ 'is-spread': layout === 'spread' }"
      @scroll.passive="onScroll"
      @wheel="onWheel"
      @pointerdown="markUserScroll"
    >
      <!-- Vertical strip: one column, width capped so a wide desktop window
           does not blow every page up to full width. -->
      <template v-if="layout === 'strip'">
        <div
          v-for="(item, i) in items"
          :key="pageKey(item, i)"
          :ref="(el) => setPageEl(i, el)"
          class="comic-page"
          :style="pageStyle(i)"
        >
          <img
            v-if="pages.get(i)?.url"
            :src="pages.get(i).url"
            :alt="item.name"
            draggable="false"
            @load="onPageLoaded(i, $event)"
          />
          <div v-else class="comic-hold">
            <span v-if="!failed.has(i)" class="spinner" />
            <button v-else class="pill-btn" type="button" @click="retry(i)">重试</button>
          </div>
        </div>
      </template>

      <!-- Two-page spread: pages side by side, each fitted to the window
           height, one spread per screen. -->
      <template v-else>
        <div
          v-for="(spread, s) in spreads"
          :key="`spread-${s}`"
          :ref="(el) => setSpreadEl(s, el)"
          class="comic-spread"
        >
          <div
            v-for="i in spread"
            :key="pageKey(items[i], i)"
            :ref="(el) => setPageEl(i, el)"
            class="comic-half"
            :style="pageStyle(i)"
          >
            <img
              v-if="pages.get(i)?.url"
              :src="pages.get(i).url"
              :alt="items[i]?.name"
              draggable="false"
              @load="onPageLoaded(i, $event)"
            />
            <div v-else class="comic-hold">
              <span v-if="!failed.has(i)" class="spinner" />
              <button v-else class="pill-btn" type="button" @click="retry(i)">重试</button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div v-if="indicator" class="comic-hud">{{ indicator }}</div>
  </div>
</template>

<script setup>
/**
 * Continuous comic reader, the desktop twin of the Android `ComicReader`.
 *
 * Two layouts share the same decrypt pipeline:
 *  - `strip`  — one centred column, page after page (webtoon style);
 *  - `spread` — two pages side by side, one spread per screen (manga style).
 *
 * Pages are decrypted lazily around the viewport: each `.ske` page costs a full
 * PBKDF2 derivation plus a block-by-block decrypt, so sessions for pages left
 * behind are closed again to release their plaintext blocks. The strip scrolls
 * freely like a web page; only the spread layout snaps one spread per screen.
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { openStripSession } from '../state/player.js'

const props = defineProps({
  items: { type: Array, default: () => [] },
  startIndex: { type: Number, default: 0 },
  /** 'strip' = centred column, 'spread' = two pages per screen. */
  layout: { type: String, default: 'strip' },
})

const scrollRef = ref(null)
/** index -> { id, url, shared } for the pages that currently hold a session. */
const pages = reactive(new Map())
/** index -> height / width, measured once a page has decoded. */
const ratios = reactive(new Map())
const failed = reactive(new Set())
const pending = new Set()
const activeIndex = ref(Math.max(0, props.startIndex))

const pageEls = []
const spreadEls = []
let frame = 0
let anchorIndex = Math.max(0, props.startIndex)
/** Once the reader scrolls, the entry anchor must stop pinning the scroll. */
let userScrolled = false
let wheelAccum = 0
let wheelLockUntil = 0
/** The window has already been shaped to the entry spread; later resizes win. */
let spreadFitted = false

/** Pages kept warm on both sides of what is visible. */
const LOAD_MARGIN = 1
/** Pages whose session is kept before it is released again. */
const RETAIN_MARGIN = 2
/** Fallback page shape while nothing has been measured: typical strip ratio. */
const FALLBACK_RATIO = 1.6
/** Wheel delta that counts as one notch in spread mode. */
const WHEEL_STEP = 40
const WHEEL_LOCK_MS = 320

const spreads = computed(() => {
  const out = []
  for (let i = 0; i < props.items.length; i += 2) {
    out.push(i + 1 < props.items.length ? [i, i + 1] : [i])
  }
  return out
})

const indicator = computed(() => {
  const total = props.items.length
  if (!total) return ''
  const start =
    props.layout === 'spread' ? Math.floor(activeIndex.value / 2) * 2 : activeIndex.value
  const end = Math.min(total - 1, props.layout === 'spread' ? start + 1 : start)
  return start === end
    ? `第 ${start + 1} / ${total} 页`
    : `第 ${start + 1}–${end + 1} / ${total} 页`
})

function pageKey(item, index) {
  return item?.path || item?.name || index
}

function setPageEl(index, el) {
  if (el) pageEls[index] = el
}

function setSpreadEl(index, el) {
  if (el) spreadEls[index] = el
}

function pageStyle(index) {
  const ratio = ratios.get(index) || FALLBACK_RATIO
  // Strip: matching the container to the decoded picture keeps the scroll
  // position stable when the page replaces its placeholder. Spread: it gives
  // each half its page's exact width so pages can sit flush together.
  return { aspectRatio: `1 / ${ratio}` }
}

/** Combined width/height ratio of a spread, or 0 while a page is unmeasured. */
function spreadAspect(group) {
  if (!group?.length) return 0
  let widthPerHeight = 0
  for (const index of group) {
    const ratio = ratios.get(index)
    if (!ratio) return 0
    widthPerHeight += 1 / ratio
  }
  return widthPerHeight
}

/**
 * Spreads are opened in a window shaped like the two pages: two tall columns
 * inside a wide window would otherwise waste half the screen on side bars.
 *
 * Done once per entry into spread mode. Afterwards the window belongs to the
 * reader: if they change its ratio, black bars are the honest result instead of
 * the app yanking the window back. Maximised/full-screen windows are skipped by
 * the main process.
 */
function fitWindowToSpread() {
  if (props.layout !== 'spread' || spreadFitted) return
  const el = scrollRef.value
  if (!el) return
  const aspect = spreadAspect(spreads.value[currentSpread()])
  if (!aspect) return
  spreadFitted = true
  const chrome = Math.max(0, window.innerHeight - el.clientHeight)
  window.ske?.window?.fitAspect?.(aspect, chrome)?.catch?.(() => {})
}

function markUserScroll() {
  userScrolled = true
}

/**
 * Any real movement of the strip means the entry page must stop being pinned.
 * Anchor corrections set the scroll position to the page's own offset, so
 * anything visibly off that point came from the user.
 */
function onScroll() {
  const el = scrollRef.value
  if (el && props.layout === 'strip') {
    const node = pageEls[anchorIndex]
    if (node && Math.abs(el.scrollTop - node.offsetTop) > 8) userScrolled = true
  }
  updateActive()
  scheduleSync()
}

function onPageLoaded(index, event) {
  const width = event.target.naturalWidth
  const height = event.target.naturalHeight
  if (width > 0 && height > 0) ratios.set(index, height / width)
  if (!userScrolled && props.layout === 'strip') keepAnchor()
  fitWindowToSpread()
  scheduleSync()
}

/** Keep the entry page under the top edge while pages above finish measuring. */
function keepAnchor() {
  const el = scrollRef.value
  const node = pageEls[anchorIndex]
  if (el && node) el.scrollTop = node.offsetTop
}

async function openPage(index) {
  if (pending.has(index) || pages.has(index)) return
  if (index < 0 || index >= props.items.length) return
  pending.add(index)
  try {
    const session = await openStripSession(props.items[index])
    pages.set(index, session)
  } catch {
    failed.add(index)
  } finally {
    pending.delete(index)
  }
}

function retry(index) {
  failed.delete(index)
  openPage(index)
}

function scheduleSync() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    sync()
  })
}

function currentSpread() {
  const el = scrollRef.value
  if (!el) return 0
  // Ref callbacks have not run yet on the first pass: fall back to the page
  // index so an early fit measures the entry spread, not the last one.
  if (!spreadEls.length) return Math.floor(activeIndex.value / 2)
  for (let i = 0; i < spreads.value.length; i += 1) {
    const node = spreadEls[i]
    if (!node) continue
    if (node.offsetTop + node.offsetHeight > el.scrollTop + 4) return i
  }
  return Math.max(0, spreads.value.length - 1)
}

/** Visible page range, or the anchor page before anything has been measured. */
function visibleRange() {
  const el = scrollRef.value
  if (!el) return null

  if (props.layout === 'spread') {
    const group = spreads.value[currentSpread()]
    if (!group?.length) return null
    return { first: group[0], last: group[group.length - 1] }
  }

  const top = el.scrollTop
  const bottom = top + el.clientHeight
  let first = -1
  let last = -1
  for (let i = 0; i < props.items.length; i += 1) {
    const node = pageEls[i]
    if (!node) continue
    const nodeTop = node.offsetTop
    const nodeBottom = nodeTop + node.offsetHeight
    if (nodeBottom > top && nodeTop < bottom) {
      if (first < 0) first = i
      last = i
    }
  }
  return first < 0 ? null : { first, last }
}

async function sync() {
  const lastIndex = props.items.length - 1
  if (lastIndex < 0) return

  const range = visibleRange()
  const first = range ? range.first : Math.min(activeIndex.value, lastIndex)
  const last = range ? range.last : first
  const loadMargin = props.layout === 'spread' ? 2 : LOAD_MARGIN
  const retainMargin = props.layout === 'spread' ? 4 : RETAIN_MARGIN

  const loadFrom = Math.max(0, first - loadMargin)
  const loadTo = Math.min(lastIndex, last + loadMargin)
  const retainFrom = Math.max(0, first - retainMargin)
  const retainTo = Math.min(lastIndex, last + retainMargin)

  for (let i = loadFrom; i <= loadTo; i += 1) {
    if (!failed.has(i)) await openPage(i)
  }

  for (const [index, page] of [...pages]) {
    if (index >= retainFrom && index <= retainTo) continue
    if (!page.shared) window.ske?.media?.close(page.id).catch(() => {})
    pages.delete(index)
  }
}

function topIndex() {
  if (props.layout === 'spread') {
    const group = spreads.value[currentSpread()]
    return group?.[0] ?? Math.max(0, props.startIndex)
  }
  const el = scrollRef.value
  if (!el) return Math.max(0, props.startIndex)
  const top = el.scrollTop + 6
  for (let i = 0; i < props.items.length; i += 1) {
    const node = pageEls[i]
    if (!node) continue
    if (node.offsetTop + node.offsetHeight > top) return i
  }
  return Math.max(0, props.items.length - 1)
}

function updateActive() {
  activeIndex.value = topIndex()
}

function scrollToPage(index, smooth = true) {
  const el = scrollRef.value
  const node = pageEls[index]
  if (!el || !node) return
  el.scrollTo({ top: node.offsetTop, behavior: smooth ? 'smooth' : 'auto' })
}

function scrollToSpread(index, smooth = true) {
  const el = scrollRef.value
  const node = spreadEls[index]
  if (!el || !node) return
  el.scrollTo({ top: node.offsetTop, behavior: smooth ? 'smooth' : 'auto' })
}

/** Advance exactly one spread (spread mode is the only snapping layout). */
function stepSpread(direction) {
  const target = Math.max(0, Math.min(spreads.value.length - 1, currentSpread() + direction))
  scrollToSpread(target)
}

function onWheel(event) {
  // The strip scrolls freely: leave wheel and trackpad movement to the browser.
  if (props.layout !== 'spread') return
  event.preventDefault()
  const now = performance.now()
  // One notch (or one trackpad swipe) is one spread; inertia is swallowed until
  // the next notch starts, so a flick cannot skip half the folder.
  if (now < wheelLockUntil) return
  wheelAccum += event.deltaY
  if (Math.abs(wheelAccum) < WHEEL_STEP) return
  const direction = wheelAccum > 0 ? 1 : -1
  wheelAccum = 0
  wheelLockUntil = now + WHEEL_LOCK_MS
  stepSpread(direction)
}

function onKeydown(event) {
  const target = event.target
  if (target?.closest?.('input, textarea, select, [contenteditable="true"]')) return
  let direction = 0
  let screenful = false
  switch (event.key) {
    case 'ArrowDown':
    case 'ArrowRight':
      direction = 1
      break
    case 'ArrowUp':
    case 'ArrowLeft':
      direction = -1
      break
    case 'PageDown':
      direction = 1
      screenful = true
      break
    case 'PageUp':
      direction = -1
      screenful = true
      break
    case ' ':
      direction = event.shiftKey ? -1 : 1
      screenful = true
      break
    default:
      return
  }
  event.preventDefault()
  if (props.layout === 'spread') {
    stepSpread(direction)
    return
  }
  const el = scrollRef.value
  if (!el) return
  // Strip mode scrolls, it does not jump pages.
  const delta = screenful ? el.clientHeight * 0.9 : 72
  el.scrollBy({ top: direction * delta, behavior: 'smooth' })
}

/**
 * A spread is exactly one screen tall, so every spread's top shifts when the
 * window is resized (including our own fit). Pin the spread being read instead
 * of letting the scroll position drift to a neighbour.
 */
function onWindowResize() {
  if (props.layout === 'spread') {
    scrollToSpread(Math.floor(activeIndex.value / 2), false)
  }
  scheduleSync()
}

watch(
  () => props.layout,
  async () => {
    await nextTick()
    spreadFitted = false
    if (props.layout === 'spread') {
      scrollToSpread(Math.floor(activeIndex.value / 2), false)
      fitWindowToSpread()
    } else {
      scrollToPage(activeIndex.value, false)
    }
    updateActive()
    scheduleSync()
  },
)

onMounted(async () => {
  const lastIndex = Math.max(0, props.items.length - 1)
  activeIndex.value = Math.min(Math.max(0, props.startIndex), lastIndex)
  anchorIndex = activeIndex.value
  await nextTick()

  if (props.layout === 'spread') {
    scrollToSpread(Math.floor(anchorIndex / 2), false)
  } else {
    keepAnchor()
  }

  // Decrypt the entry page (and the one below) straight away instead of
  // greeting the reader with a screen full of spinners.
  const entry = props.layout === 'spread' ? Math.floor(anchorIndex / 2) * 2 : anchorIndex
  for (let i = entry; i <= Math.min(lastIndex, entry + 2); i += 1) {
    await openPage(i)
  }

  await nextTick()
  if (props.layout === 'spread') scrollToSpread(Math.floor(anchorIndex / 2), false)
  else if (!userScrolled) keepAnchor()
  updateActive()
  scheduleSync()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onWindowResize)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onWindowResize)
  if (frame) cancelAnimationFrame(frame)
  for (const [, page] of pages) {
    if (!page.shared) window.ske?.media?.close(page.id).catch(() => {})
  }
  pages.clear()
})

defineExpose({ topIndex })
</script>

<style scoped>
.comic-stage {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  background: #000;
}

.comic-scroll {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.28) transparent;
}

/* ---- strip: one centred column ------------------------------------------ */
.comic-page {
  width: min(100%, 820px);
  margin: 0 auto;
  background: #000;
  display: grid;
  place-items: center;
  overflow: hidden;
}

.comic-page img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

/* ---- spread: two pages per screen --------------------------------------- */
.comic-scroll.is-spread {
  scrollbar-width: none;
}

.comic-spread {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: #000;
}

/* Each half takes exactly its page's width (aspect ratio passed in from the
   measured picture), so the two pages meet in the middle: no black band between
   them. Whatever width is left over falls on the outer sides. */
.comic-half {
  flex: 0 0 auto;
  height: 100%;
  max-width: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.comic-half:only-child {
  max-width: 100%;
}

.comic-half img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

/* ---- shared ------------------------------------------------------------- */
.comic-hold {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  min-height: 120px;
}

.comic-hud {
  position: absolute;
  right: 14px;
  bottom: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.86);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
  user-select: none;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.2);
  border-top-color: #60a5fa;
  border-radius: 50%;
  animation: comic-spin 0.75s linear infinite;
}

@keyframes comic-spin {
  to {
    transform: rotate(360deg);
  }
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
