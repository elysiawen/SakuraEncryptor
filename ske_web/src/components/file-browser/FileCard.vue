<template>
  <div
    class="file-card glass-card"
    @click="$emit('click', item)"
    @contextmenu.stop.prevent="$emit('contextmenu', $event, item)"
    @touchstart.passive="onTouchStart"
    @touchend.passive="onTouchEnd"
    @touchmove.passive="onTouchCancel"
  >
    <FileIcon :item="item" />
    <div class="file-info">
      <div class="file-name truncate" :title="item.decName">{{ item.decName || item.encName }}</div>
      <div class="file-meta">
        <span v-if="!item.is_dir && item.size">{{ formatSize(item.size) }}</span>
        <FileTag :type="tagType" />
        <FileTag :type="encryptionTagType" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import FileIcon from '../common/FileIcon.vue'
import FileTag from '../common/FileTag.vue'
import { getFileTagType, formatSize, isEncryptedFileName } from '../../composables/useFileDetection.js'

const props = defineProps({
  item: { type: Object, required: true },
})

const emit = defineEmits(['click', 'contextmenu'])

const tagType = computed(() => getFileTagType(props.item))

// Encrypted folders are detected by their name decrypting successfully (the
// listing computes that for every entry); plaintext filenames additionally
// fall back to the .ske marker.
const encryptionTagType = computed(() => {
  const item = props.item
  const encrypted = item.encrypted ?? isEncryptedFileName(item.encName || item.name)
  return encrypted ? 'encrypted' : 'plain'
})

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
.file-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  cursor: pointer;
  transition: all var(--transition-fast);
  overflow: hidden;
}

.file-card:hover {
  transform: translateY(-2px);
}

.file-info {
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.file-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
}

.file-meta .truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

@media (max-width: 600px) {
  .file-card {
    padding: 12px 14px;
    gap: 10px;
  }

  .file-name {
    font-size: 13px;
  }
}
</style>
