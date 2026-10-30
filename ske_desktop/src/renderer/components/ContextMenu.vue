<!--
  Right-click menu shared by the file views.

  The menu itself is dumb: the caller decides the items and where they belong.
  A full-viewport transparent layer closes it on any click, which is simpler and
  more reliable than a document listener racing the click that opened it.
-->
<template>
  <Teleport to="body">
    <Transition name="ctx">
      <div
        v-if="show"
        class="ctx-layer"
        @mousedown.self="close"
        @contextmenu.prevent="close"
      >
        <div class="ctx-menu" :style="{ left: `${x}px`, top: `${y}px` }">
          <template v-for="(item, index) in items" :key="item.separator ? `sep-${index}` : item.label">
            <!-- Group divider: the caller decides where the categories break. -->
            <div v-if="item.separator" class="ctx-sep" role="separator" />

            <div
              v-else
              class="ctx-row"
              @mouseenter="openSub(item)"
              @mouseleave="scheduleCloseSub"
            >
            <button
              class="ctx-item"
              :class="{ danger: item.danger, 'sub-open': subOpen === item.label }"
              type="button"
              :disabled="item.disabled"
              :aria-haspopup="item.children?.length ? 'menu' : undefined"
              :aria-expanded="item.children?.length ? subOpen === item.label : undefined"
              @click="run(item)"
            >
              <span class="ctx-icon" aria-hidden="true">{{ item.icon }}</span>
              <span class="ctx-label">{{ item.label }}</span>
              <!-- Drawn rather than typed: `›` renders thin and light at this
                   size, which made the second level easy to miss. -->
              <span v-if="item.children?.length" class="ctx-arrow" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 5.5 15.5 12 9 18.5" />
                </svg>
              </span>
            </button>

            <!-- Submenu: the caller supplies the entries, this only positions them. -->
            <div
              v-if="item.children?.length && subOpen === item.label"
              class="ctx-sub"
              :class="{ flip: subFlip }"
              role="menu"
            >
              <button
                v-for="child in item.children"
                :key="child.label"
                class="ctx-item"
                :class="{ danger: child.danger }"
                type="button"
                :disabled="child.disabled"
                @click="run(child)"
              >
                <span class="ctx-icon" aria-hidden="true">{{ child.icon }}</span>
                <span class="ctx-label">{{ child.label }}</span>
              </button>
            </div>
            </div>
          </template>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  show: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  /**
   * [{ label, icon, disabled?, danger?, action(), children? }]
   *
   * A `{ separator: true }` entry draws a divider between groups.
   */
  items: { type: Array, default: () => [] },
})

const emit = defineEmits(['close'])

/** Label of the entry whose submenu is showing. */
const subOpen = ref('')
/** Submenus open to the left when the menu already sits near the right edge. */
const subFlip = ref(false)
let closeTimer = 0

// A submenu belongs to one opening of the menu: reopening it must not show a
// stale one from a previous right-click.
watch(
  () => props.show,
  (show) => {
    if (!show) subOpen.value = ''
  },
)

function close() {
  emit('close')
}

function openSub(item) {
  clearTimeout(closeTimer)
  if (item.disabled || !item.children?.length) return
  subOpen.value = item.label
  // 152 px is this menu's width, ~176 the submenu's: beyond that it has to go
  // the other way or it would be clipped by the window edge.
  subFlip.value = props.x + 152 + 176 > window.innerWidth
}

/**
 * Leaving a row closes its submenu, but not instantly: the pointer has to
 * cross a couple of pixels to get from the row into the submenu.
 */
function scheduleCloseSub() {
  clearTimeout(closeTimer)
  closeTimer = setTimeout(() => {
    subOpen.value = ''
  }, 140)
}

function run(item) {
  if (item.disabled) return
  // A parent is a doorway, not an action: clicking it just toggles the submenu.
  if (item.children?.length) {
    subOpen.value = subOpen.value === item.label ? '' : item.label
    return
  }
  close()
  item.action?.()
}

function onKey(event) {
  if (event.key === 'Escape' && props.show) close()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped>
.ctx-layer {
  position: fixed;
  inset: 0;
  /* Above the page and its dialogs, below the lock screen (100) and toasts (200). */
  z-index: 70;
}

.ctx-menu {
  position: absolute;
  /* A touch wider than the old 152 px so an entry with a submenu arrow does
     not ellipsise the very label that announces the second level. */
  min-width: 164px;
  padding: 4px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  /* Grows out of the pointer, not the middle of its own box. */
  transform-origin: top left;
}

.ctx-enter-active,
.ctx-leave-active {
  transition: opacity 0.12s ease;
}

.ctx-enter-active .ctx-menu,
.ctx-leave-active .ctx-menu {
  transition: opacity 0.12s ease, transform 0.14s cubic-bezier(0.22, 0.61, 0.36, 1);
}

.ctx-enter-from,
.ctx-leave-to {
  opacity: 0;
}

.ctx-enter-from .ctx-menu,
.ctx-leave-to .ctx-menu {
  opacity: 0;
  transform: scale(0.94) translateY(-2px);
}

.ctx-row {
  position: relative;
}

/* Category break: a hairline with a little air on both sides, so the eye reads
   groups rather than one long list. */
.ctx-sep {
  height: 1px;
  margin: 4px 6px;
  background: var(--line);
}

/* Opens beside its row; flips left when the window edge is near. It stays a
   child of the row so the pointer never leaves the hover area on the way in. */
.ctx-sub {
  position: absolute;
  top: -4px;
  left: 100%;
  z-index: 1;
  min-width: 168px;
  padding: 4px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
}

.ctx-sub.flip {
  left: auto;
  right: 100%;
}

.ctx-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 9px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  color: var(--text-dim);
  text-align: left;
}

.ctx-item:hover:not(:disabled) {
  background: var(--panel-hover);
  color: var(--text);
}

.ctx-item:disabled {
  color: var(--muted-strong);
}

/* Destructive entries read red before the pointer is even near them.
   `button.danger` in the global sheet is an outlined button; that border must
   not leak in here, or the row shows a red ring inside the menu. */
.ctx-item.danger:not(:disabled) {
  border-color: transparent;
  color: var(--danger);
}

.ctx-item.danger:hover:not(:disabled) {
  background: color-mix(in srgb, var(--danger) 14%, transparent);
  color: var(--danger);
}

.ctx-icon {
  width: 16px;
  text-align: center;
  font-size: 12px;
  line-height: 1;
}

.ctx-arrow {
  flex: 0 0 14px;
  display: grid;
  place-items: center;
  width: 14px;
  height: 14px;
  color: var(--text-dim);
}

.ctx-arrow svg {
  width: 12px;
  height: 12px;
}

/* The row that owns the open submenu stays lit, so the arrow and the panel are
   visibly the same thing. */
.ctx-item.sub-open:not(:disabled) {
  background: var(--panel-hover);
  color: var(--text);
}

.ctx-item:hover:not(:disabled) .ctx-arrow,
.ctx-item.sub-open:not(:disabled) .ctx-arrow {
  color: var(--text);
}

.ctx-label {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
