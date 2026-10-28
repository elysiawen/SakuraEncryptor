<!--
  Right-click menu shared by the file views.

  The menu itself is dumb: the caller decides the items and where they belong.
  A full-viewport transparent layer closes it on any click, which is simpler and
  more reliable than a document listener racing the click that opened it.
-->
<template>
  <Teleport to="body">
    <div
      v-if="show"
      class="ctx-layer"
      @mousedown.self="close"
      @contextmenu.prevent="close"
    >
      <div class="ctx-menu" :style="{ left: `${x}px`, top: `${y}px` }">
        <button
          v-for="item in items"
          :key="item.label"
          class="ctx-item"
          :class="{ danger: item.danger }"
          type="button"
          :disabled="item.disabled"
          @click="run(item)"
        >
          <span class="ctx-icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="ctx-label">{{ item.label }}</span>
        </button>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { onBeforeUnmount, onMounted } from 'vue'

const props = defineProps({
  show: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  /** [{ label, icon, disabled?, action() }] */
  items: { type: Array, default: () => [] },
})

const emit = defineEmits(['close'])

function close() {
  emit('close')
}

function run(item) {
  if (item.disabled) return
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
  min-width: 152px;
  padding: 4px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
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

.ctx-label {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
