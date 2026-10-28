<template>
  <transition name="alert-slide">
    <div v-if="banner" class="upstream-alert">
      <n-alert type="warning" closable :bordered="false" @close="dismissBanner">
        <template #header>{{ banner.title }}</template>
        {{ banner.message }}
        <p v-if="banner.link" class="download">
          下载地址：<a :href="banner.link" target="_blank" rel="noopener noreferrer">{{ banner.link }}</a>
        </p>
        <div v-if="banner.canAdd" class="actions">
          <n-button type="warning" strong :loading="adding" @click="addDrive">添加到插件</n-button>
        </div>
        <p v-if="banner.note" class="note">{{ banner.note }}</p>
      </n-alert>
    </div>
  </transition>
</template>

<script setup>
import { useUpstreamAlerts } from '../../composables/useUpstreamAlerts.js'

const { banner, dismissBanner, adding, addDrive } = useUpstreamAlerts()
</script>

<style scoped>
.upstream-alert {
  position: fixed;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 4000;
  width: min(560px, calc(100vw - 24px));
  pointer-events: auto;
}

/* Frosted glass: the banner floats over artwork, video and the top bar, so a
   blurred backdrop keeps the warning readable without a hard opaque slab. */
.upstream-alert :deep(.n-alert) {
  background: rgba(38, 28, 10, 0.52) !important;
  border: 1px solid rgba(245, 158, 11, 0.32) !important;
  border-radius: 14px !important;
  backdrop-filter: blur(18px) saturate(160%);
  -webkit-backdrop-filter: blur(18px) saturate(160%);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
  color: #f3e8d5 !important;
}

.upstream-alert :deep(.n-alert .n-alert-body__title) {
  color: #fde9c3 !important;
}

.upstream-alert :deep(.note) {
  margin: 6px 0 0;
  color: #d9cdfb;
  font-size: 12px;
}

/* The banner is the only place this action exists, so it has to look like the
   thing to click rather than a secondary chip: solid amber on the dark glass,
   a touch taller than default, and a soft glow to pull the eye away from the
   long explanation above it. */
.upstream-alert :deep(.actions) {
  margin-top: 10px;
}

.upstream-alert :deep(.actions .n-button) {
  --n-height: 34px !important;
  --n-color: #f59e0b !important;
  --n-color-hover: #fbbf24 !important;
  --n-color-pressed: #d97706 !important;
  --n-color-focus: #fbbf24 !important;
  --n-text-color: #2b1a00 !important;
  --n-text-color-hover: #2b1a00 !important;
  --n-text-color-pressed: #2b1a00 !important;
  --n-text-color-focus: #2b1a00 !important;
  --n-border: none !important;
  --n-border-hover: none !important;
  --n-border-focus: none !important;
  --n-border-pressed: none !important;
  padding: 0 20px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 6px 18px rgba(245, 158, 11, 0.3);
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.upstream-alert :deep(.actions .n-button:hover) {
  box-shadow: 0 8px 24px rgba(245, 158, 11, 0.45);
  transform: translateY(-1px);
}

.upstream-alert :deep(.download) {
  margin: 6px 0 0;
  font-size: 12px;
  word-break: break-all;
}

.upstream-alert :deep(.download a) {
  color: #fde9c3;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.upstream-alert :deep(.download a:hover) {
  color: #fff;
}

.upstream-alert :deep(.n-base-close) {
  color: rgba(253, 233, 195, 0.7);
}

.upstream-alert :deep(.n-base-close:hover) {
  color: #fde9c3;
}

/* Without backdrop-filter the tint alone would be too thin to read against a
   bright frame, so lean opaque instead. */
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .upstream-alert :deep(.n-alert) {
    background: rgba(30, 22, 8, 0.94) !important;
  }
}

.alert-slide-enter-active,
.alert-slide-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.alert-slide-enter-from,
.alert-slide-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px);
}
</style>
