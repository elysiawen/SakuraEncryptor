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
    :title="$t('browse.uploadTitle')"
    :description="$t('browse.uploadTo', { folder })"
    :width="460"
    :busy="busy"
    :error="error"
    @cancel="cancel"
    @submit="submit"
  >
    <label class="dlg-check">
      <input v-model="encrypt" type="checkbox" />
      <span>{{ $t('browse.uploadEncrypt') }}</span>
    </label>

    <p class="hint">
      {{ encrypt ? $t('browse.uploadEncryptHint') : $t('browse.uploadPlainHint') }}
    </p>

    <div class="files">
      <div v-for="file in files" :key="file.path" class="file">
        <span class="name">{{ file.name }}</span>
        <span class="size mono">{{ formatSize(file.size) }}</span>
      </div>
    </div>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">
        {{ $t('common.cancel') }}
      </button>
      <button class="primary" type="submit" :disabled="busy || !files.length">
        {{ busy ? $t('browse.uploadAdding') : $t('browse.uploadCount', { count: files.length }) }}
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
