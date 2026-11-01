<!--
  Editing one mount.

  Same reason as adding: a driver's form can be twenty fields long, and inline
  it pushed every other mount off the list. This one is also where a broken
  driver spec must never turn into a blank form — saving that would wipe the
  stored config.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="$t('embedded.editTitle')"
    :description="summary"
    :width="580"
    :busy="busy"
    :layer="66"
    @cancel="close"
    @submit="submit"
  >
    <div class="field">
      <label for="edit-mount">{{ $t('embedded.mountPath') }}</label>
      <input id="edit-mount" v-model="mountPath" type="text" placeholder="/media" />
      <div class="meta">{{ $t('embedded.mountPathHintEdit') }}</div>
    </div>

    <p v-if="loading" class="note">{{ $t('embedded.loadingDriver') }}</p>

    <div v-else-if="loadError" class="load-error">
      <span>{{ $t('embedded.driverLoadFailed', { driver, error: loadError }) }}</span>
      <button class="ghost sm" type="button" @click="load">{{ $t('common.retry') }}</button>
    </div>

    <StorageFields
      v-else-if="fields.length"
      :fields="fields"
      :model="values"
      :uid="`edit-${item?.id ?? 'x'}`"
    />

    <p v-else class="note">{{ $t('embedded.editNoFields') }}</p>

    <template #footer>
      <button class="ghost" type="button" @click="close">{{ $t('common.cancel') }}</button>
      <button class="primary" type="button" :disabled="busy || !canSave" @click="submit">
        {{ $t('embedded.save') }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import StorageFields from './StorageFields.vue'
import { t } from '../i18n.js'
import { toast } from '../state/session.js'
import { embedded, fetchDriverInfo, updateEmbeddedStorage } from '../state/embedded.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** The row from `storages()`: `{ id, driver, mountPath, values }`. */
  item: { type: Object, default: null },
})

const emit = defineEmits(['close'])

const busy = computed(() => embedded.busy)
const driver = computed(() => props.item?.driver || '')
const summary = computed(() => (props.item ? `${props.item.driver} · ${props.item.mountPath}` : ''))

const mountPath = ref('')
const fields = ref([])
const values = reactive({})
const loading = ref(false)
const loadError = ref('')

const canSave = computed(() => {
  if (!props.item || !mountPath.value.trim()) return false
  if (loading.value || loadError.value) return false
  return fields.value.every((field) => {
    if (!field.required || field.type === 'bool') return true
    const value = values[field.name]
    return String(value ?? '').trim() !== ''
  })
})

/**
 * Fill the path and the driver's fields from the row.
 *
 * Replies are dropped when the row changed meanwhile: two edits opened in a row
 * must not mix their field definitions.
 */
async function load() {
  const item = props.item
  if (!item) return

  fields.value = []
  loadError.value = ''
  Object.keys(values).forEach((key) => delete values[key])
  mountPath.value = item.mountPath || ''

  loading.value = true
  try {
    const info = await fetchDriverInfo(item.driver)
    if (props.item?.id !== item.id) return
    fields.value = info.additional
    for (const field of info.additional) {
      const current = item.values?.[field.name]
      // Strings only: the form holds text, `coerceField` in main restores the
      // JSON type the Go struct expects.
      values[field.name] = current === undefined || current === null ? field.default : String(current)
    }
  } catch (err) {
    if (props.item?.id !== item.id) return
    loadError.value = err.message || t('embedded.noResponse')
  } finally {
    if (props.item?.id === item.id) loading.value = false
  }
}

function close() {
  emit('close')
}

async function submit() {
  if (busy.value || !canSave.value) return
  const ok = await updateEmbeddedStorage({
    id: props.item.id,
    mountPath: mountPath.value.trim(),
    values: { ...values },
  })
  if (!ok) return
  toast(t('embedded.updated'), 'ok')
  emit('close')
}

// Getter sources, not a getter returning an array: the array is a new object on
// every evaluation, and Vue compares getter results by identity.
watch(
  [() => props.visible, () => props.item?.id],
  ([visible]) => {
    if (visible) load()
  },
  { immediate: true },
)
</script>
