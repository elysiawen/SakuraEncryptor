<template>
  <header class="media-topbar glass-card">
    <n-button quaternary size="small" @click="$emit('back')">
      <span class="desktop-text">← 返回</span>
      <span class="mobile-text">←</span>
    </n-button>
    <div class="media-title truncate">{{ title }}</div>
    <n-button v-if="downloadUrl" quaternary size="small" @click="handleDownload">
      <span class="desktop-text">⬇️ 下载</span>
      <span class="mobile-text">⬇️</span>
    </n-button>
    <div v-else style="width: 60px;"></div>
    <slot name="extra" />
  </header>
</template>

<script setup>
import { useDownloadManager } from '../../composables/useDownloadManager.js'

const props = defineProps({
  title: { type: String, default: '' },
  downloadUrl: { type: String, default: '' },
  downloadName: { type: String, default: '' },
  downloadSize: { type: Number, default: 0 },
})

defineEmits(['back'])

const { addTask } = useDownloadManager()

function handleDownload() {
  if (!props.downloadUrl) return
  addTask({
    url: props.downloadUrl,
    name: props.downloadName || 'download',
    total: props.downloadSize || 0,
  })
}
</script>

<style scoped>
.media-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  margin: 16px 16px 0;
  border-radius: var(--radius-md);
  gap: 16px;
}

.media-title {
  flex: 1;
  text-align: center;
  font-size: 15px;
  font-weight: 600;
  min-width: 0;
}

@media (max-width: 600px) {
  .media-topbar {
    margin: 8px 8px 0;
    padding: 8px 12px;
  }

  .media-title {
    font-size: 13px;
  }
}
</style>
