<!--
  Single-field prompt used by the file views (新建文件夹 / 重命名).

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
      <button class="ghost" type="button" :disabled="busy" @click="cancel">取消</button>
      <button class="primary" type="submit" :disabled="busy || !text.trim()">
        {{ busy ? '处理中…' : confirmText }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  initialValue: { type: String, default: '' },
  /** Empty string hides the checkbox row entirely. */
  checkboxLabel: { type: String, default: '' },
  checkedByDefault: { type: Boolean, default: true },
  confirmText: { type: String, default: '确定' },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

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
