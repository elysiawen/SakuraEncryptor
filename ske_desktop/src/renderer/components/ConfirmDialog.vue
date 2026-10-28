<!--
  Destructive confirmation (delete). The file views have no undo, so the action
  is spelled out here instead of happening straight from the context menu.

  Enter is deliberately not wired to confirm: the shell suppresses the default
  submit, and a stray Return on a delete dialog should do nothing.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="title"
    :description="description"
    :busy="busy"
    :error="error"
    @cancel="cancel"
  >
    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">取消</button>
      <button class="danger" type="button" :disabled="busy" @click="emit('confirm')">
        {{ busy ? '处理中…' : confirmText }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import AppDialog from './AppDialog.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  confirmText: { type: String, default: '删除' },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['cancel', 'confirm'])

function cancel() {
  if (props.busy) return
  emit('cancel')
}
</script>
