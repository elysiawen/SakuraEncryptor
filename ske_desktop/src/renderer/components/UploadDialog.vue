<!--
  Confirmation for an upload batch.

  Two things are worth stating before files leave the machine: where they are
  going, and whether they stay encrypted on the way. The web client offers the
  same switch; here it defaults to on, because the folder being browsed is
  normally the encrypted tree.
-->
<template>
  <AppDialog
    :visible="visible"
    title="上传文件"
    :description="`将上传到「${folder}」`"
    :width="460"
    :busy="busy"
    :error="error"
    @cancel="cancel"
    @submit="submit"
  >
    <label class="dlg-check">
      <input v-model="encrypt" type="checkbox" />
      <span>加密上传（生成 .ske 容器）</span>
    </label>

    <p class="hint">
      {{
        encrypt
          ? '文件先在本机加密成容器再上传，云端只见到密文。'
          : '关闭后上传原始文件，云端目录里会存明文。'
      }}
    </p>

    <div class="files">
      <div v-for="file in files" :key="file.path" class="file">
        <span class="name">{{ file.name }}</span>
        <span class="size mono">{{ formatSize(file.size) }}</span>
      </div>
    </div>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">取消</button>
      <button class="primary" type="submit" :disabled="busy || !files.length">
        {{ busy ? '加入中…' : `上传 ${files.length} 个文件` }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import { formatSize } from '../state/cloud.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** Plain-text folder name, for the description line only. */
  folder: { type: String, default: '/' },
  files: { type: Array, default: () => [] },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['cancel', 'submit'])

const encrypt = ref(true)

// Each batch starts from the safe choice, even if the last one was turned off.
watch(
  () => props.visible,
  (visible) => {
    if (visible) encrypt.value = true
  },
)

function cancel() {
  if (props.busy) return
  emit('cancel')
}

function submit() {
  if (props.busy || !props.files.length) return
  emit('submit', { encrypt: encrypt.value })
}
</script>

<style scoped>
.hint {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.files {
  max-height: 220px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}

.file {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 6px;
  font-size: 12px;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.size {
  flex: 0 0 auto;
  font-size: 11.5px;
  color: var(--muted);
}
</style>
