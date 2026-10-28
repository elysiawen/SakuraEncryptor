<!--
  Custom window chrome.

  The shell window is frameless, so this strip is the title bar: the whole bar
  is a drag region (Windows treats it as a caption, which keeps edge-snapping and
  double-click-to-maximise working), and the buttons opt out of it. Caption
  controls are 36×28 hit areas with a rounded hover, which reads lighter than the
  native 46 px square blocks and matches the rest of the app.
-->
<template>
  <header class="titlebar">
    <div class="brand">
      <span class="dot" aria-hidden="true" />
      <span class="wordmark">Sakura Encryptor</span>
    </div>

    <div class="controls">
      <button class="ctl" type="button" title="最小化" @click="minimize">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <path d="M6 12h12" />
        </svg>
      </button>

      <button class="ctl" type="button" :title="maximized ? '还原' : '最大化'" @click="toggleMaximize">
        <svg v-if="maximized" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
          <rect x="5" y="8" width="11" height="11" rx="1.4" />
          <path d="M9 8V6.4A1.4 1.4 0 0 1 10.4 5H18a1.4 1.4 0 0 1 1.4 1.4V14" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
          <rect x="6" y="6" width="12" height="12" rx="1.5" />
        </svg>
      </button>

      <button class="ctl close" type="button" title="关闭" @click="closeWindow">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <path d="M7 7l10 10M17 7L7 17" />
        </svg>
      </button>
    </div>
  </header>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

const maximized = ref(false)
let frame = 0

async function readState() {
  const res = await window.ske?.window?.state?.()
  if (res?.ok) maximized.value = !!res.maximized
}

// A double-click on the drag region maximises through the system, so the size
// change — not a click handler — is what tells us to swap the icon.
function onResize() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    readState()
  })
}

onMounted(() => {
  readState()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  if (frame) cancelAnimationFrame(frame)
  window.removeEventListener('resize', onResize)
})

function minimize() {
  window.ske?.window?.minimize()
}

async function toggleMaximize() {
  const res = await window.ske?.window?.toggleMaximize()
  if (res?.ok) maximized.value = !!res.maximized
}

function closeWindow() {
  window.ske?.window?.close()
}
</script>

<style scoped>
.titlebar {
  position: relative;
  /* Above the lock screen (90): a frameless window still needs a way to be
     moved and closed while the app is locked. */
  z-index: 120;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: 0 0 40px;
  height: 40px;
  /* 20 px lines the wordmark up with the sidebar's nav labels below it. */
  padding: 0 6px 0 20px;
  background: var(--bg);
  border-bottom: 1px solid var(--line-soft);
  -webkit-app-region: drag;
  user-select: none;
}

/* The brand lives in the caption, not in the sidebar: one instance, and the top
   strip stops being an empty 40 px band. */
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.dot {
  width: 9px;
  height: 9px;
  flex: 0 0 9px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 12px var(--accent);
}

.wordmark {
  /* Full product name, one notch smaller than a one-word mark would need: it has
     to stay quiet next to the navigation below it. */
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--text);
  white-space: nowrap;
}

.controls {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
  /* Buttons must stay clickable inside the drag region. */
  -webkit-app-region: no-drag;
}

.ctl {
  display: grid;
  place-items: center;
  width: 36px;
  height: 28px;
  padding: 0;
  border-radius: 6px;
  color: var(--muted);
  border-color: transparent;
}

.ctl svg {
  width: 15px;
  height: 15px;
}

.ctl:hover:not(:disabled) {
  background: var(--panel-hover);
  color: var(--text);
}

.ctl.close:hover:not(:disabled) {
  background: var(--danger);
  color: #fff;
}
</style>
