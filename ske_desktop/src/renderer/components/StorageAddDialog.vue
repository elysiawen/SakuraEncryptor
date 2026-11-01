<!--
  Adding one mount.

  Opens on top of the storage list instead of living inside it: a driver's form
  can be twenty fields long, and inline it pushed the list off the screen.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="$t('embedded.addTitle')"
    :description="$t('embedded.addDesc')"
    :width="580"
    :busy="busy"
    :layer="66"
    @cancel="close"
    @submit="submit"
  >
    <div class="field">
      <label for="add-driver">{{ $t('embedded.driver') }}</label>
      <select id="add-driver" v-model="driver">
        <option value="">{{ $t('embedded.driverPick') }}</option>
        <option v-for="name in drivers" :key="name" :value="name">{{ name }}</option>
      </select>
    </div>

    <div class="field">
      <label for="add-mount">{{ $t('embedded.mountPath') }}</label>
      <input id="add-mount" v-model="mountPath" type="text" placeholder="/media" />
      <div class="meta">{{ $t('embedded.mountPathHint') }}</div>
    </div>

    <!-- The driver's own fields come from the server. Three states matter here:
         loading, failed (with the reason and a retry), and "this driver needs
         nothing" — otherwise an empty form looks like the driver has no
         settings at all, which is what a swallowed error looks like. -->
    <p v-if="loading" class="note">{{ $t('embedded.loadingDriver') }}</p>

    <div v-else-if="loadError" class="load-error">
      <span>{{ $t('embedded.driverLoadFailed', { driver, error: loadError }) }}</span>
      <button class="ghost sm" type="button" @click="loadFields(driver)">
        {{ $t('common.retry') }}
      </button>
    </div>

    <StorageFields v-else-if="fields.length" :fields="fields" :model="values" uid="add" />

    <p v-else-if="driver" class="note">{{ $t('embedded.driverNoFields') }}</p>

    <p class="hint">{{ $t('embedded.addNote') }}</p>

    <template #footer>
      <button class="ghost" type="button" @click="close">{{ $t('common.cancel') }}</button>
      <button class="primary" type="button" :disabled="busy || !canAdd" @click="submit">
        {{ $t('embedded.add') }}
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
import {
  createEmbeddedStorage,
  embedded,
  fetchDriverInfo,
  loadDrivers,
} from '../state/embedded.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const busy = computed(() => embedded.busy)
const drivers = computed(() => embedded.drivers)

const driver = ref('')
const mountPath = ref('')
const fields = ref([])
const values = reactive({})
const loading = ref(false)
const loadError = ref('')

/** Required fields must not be blank, or the server rejects the whole mount. */
const canAdd = computed(() => {
  if (!driver.value || !mountPath.value.trim()) return false
  if (loading.value || loadError.value) return false
  return fields.value.every((field) => {
    if (!field.required || field.type === 'bool') return true
    const value = values[field.name]
    return String(value ?? '').trim() !== ''
  })
})

function reset() {
  driver.value = ''
  mountPath.value = ''
  fields.value = []
  loading.value = false
  loadError.value = ''
  Object.keys(values).forEach((key) => delete values[key])
}

function close() {
  reset()
  emit('close')
}

/**
 * Ask the server what this driver wants filled in.
 *
 * Driven by a watcher rather than the select's `change` event: the fields must
 * follow the value no matter how it changed, and the reply must be dropped if
 * the user picked another driver meanwhile.
 */
async function loadFields(name) {
  fields.value = []
  loadError.value = ''
  Object.keys(values).forEach((key) => delete values[key])
  if (!name) {
    loading.value = false
    return
  }

  loading.value = true
  try {
    const info = await fetchDriverInfo(name)
    if (driver.value !== name) return
    fields.value = info.additional
    // Start from the driver's own defaults so nothing is submitted as an empty
    // string where the Go side expects a number or a bool.
    for (const field of info.additional) values[field.name] = field.default
  } catch (err) {
    if (driver.value !== name) return
    loadError.value = err.message || t('embedded.noResponse')
  } finally {
    if (driver.value === name) loading.value = false
  }
}

async function submit() {
  if (busy.value || !canAdd.value) return
  const ok = await createEmbeddedStorage({
    driver: driver.value,
    mountPath: mountPath.value.trim(),
    values: { ...values },
  })
  if (!ok) return
  toast(t('embedded.added'), 'ok')
  // `createEmbeddedStorage` already refreshed the shared list, so closing is
  // enough: the new mount is waiting in the list behind this dialog.
  close()
}

watch(driver, (name) => loadFields(name))

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) {
      reset()
      return
    }
    // Always re-read, even when the list looks populated: AList and OpenList do
    // not ship the same drivers, and a name cached from the other backend makes
    // `driver/info` answer "driver not found".
    await loadDrivers()
  },
  { immediate: true },
)
</script>
