<!--
  Single-field prompt used by the file views (new folder / rename).

  Keeping it local to the renderer is deliberate: the name typed here is
  plaintext, and the main process is what turns it into a stored token. The
  shell, the buttons and the way out live in AppDialog.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="title"
    :description="description"
    :busy="busy"
    :error="error"
    @cancel="cancel"
    @submit="submit"
  >
    <input
      ref="field"
      v-model="text"
      type="text"
      :placeholder="placeholder"
      autocomplete="off"
    />

    <label v-if="checkboxLabel" class="dlg-check">
      <input v-model="checked" type="checkbox" />
      <span>{{ checkboxLabel }}</span>
    </label>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">
        {{ $t('common.cancel') }}
      </button>
      <button class="primary" type="submit" :disabled="busy || !text.trim()">
        {{ busy ? $t('common.processing') : confirmLabel }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import { t } from '../i18n.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  initialValue: { type: String, default: '' },
  /** Empty string hides the checkbox row entirely. */
  checkboxLabel: { type: String, default: '' },
  checkedByDefault: { type: Boolean, default: true },
  /** Empty means "the generic affirmation" — resolved per language here. */
  confirmText: { type: String, default: '' },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const confirmLabel = computed(() => props.confirmText || t('common.ok'))

const emit = defineEmits(['cancel', 'submit'])

const text = ref('')
const checked = ref(true)
const field = ref(null)

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return
    text.value = props.initialValue || ''
    checked.value = props.checkedByDefault
    await nextTick()
    field.value?.focus()
    field.value?.select()
  },
  { immediate: true },
)

function cancel() {
  if (props.busy) return
  emit('cancel')
}

function submit() {
  if (props.busy) return
  const value = text.value.trim()
  if (!value) return
  emit('submit', { text: value, checked: checked.value })
}
</script>
