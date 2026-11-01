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
      <button class="ghost" type="button" :disabled="busy" @click="cancel">
        {{ $t('common.cancel') }}
      </button>
      <button class="danger" type="button" :disabled="busy" @click="emit('confirm')">
        {{ busy ? $t('common.processing') : confirmLabel }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed } from 'vue'
import AppDialog from './AppDialog.vue'
import { t } from '../i18n.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  /** Empty means "the default destructive verb" — resolved per language here. */
  confirmText: { type: String, default: '' },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const confirmLabel = computed(() => props.confirmText || t('common.delete'))

const emit = defineEmits(['cancel', 'confirm'])

function cancel() {
  if (props.busy) return
  emit('cancel')
}
</script>
