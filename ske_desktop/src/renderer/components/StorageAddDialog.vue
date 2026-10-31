<!--
  Adding one mount.

  Opens on top of the storage list instead of living inside it: a driver's form
  can be twenty fields long, and inline it pushed the list off the screen.
-->
<template>
  <AppDialog
    :visible="visible"
    title="新增挂载"
    description="先选驱动，再填它需要的字段。挂载路径决定它在浏览页里出现的位置。"
    :width="580"
    :busy="busy"
    :layer="66"
    @cancel="close"
    @submit="submit"
  >
    <div class="field">
      <label for="add-driver">驱动</label>
      <select id="add-driver" v-model="driver">
        <option value="">选择驱动…</option>
        <option v-for="name in drivers" :key="name" :value="name">{{ name }}</option>
      </select>
    </div>

    <div class="field">
      <label for="add-mount">挂载路径</label>
      <input id="add-mount" v-model="mountPath" type="text" placeholder="/media" />
      <div class="meta">浏览页里看到的目录，通常以 / 开头</div>
    </div>

    <!-- The driver's own fields come from the server. Three states matter here:
         loading, failed (with the reason and a retry), and "this driver needs
         nothing" — otherwise an empty form looks like the driver has no
         settings at all, which is what a swallowed error looks like. -->
    <p v-if="loading" class="note">正在读取该驱动的配置…</p>

    <div v-else-if="loadError" class="load-error">
      <span>「{{ driver }}」的配置读取失败：{{ loadError }}</span>
      <button class="ghost sm" type="button" @click="loadFields(driver)">重试</button>
    </div>

    <StorageFields v-else-if="fields.length" :fields="fields" :model="values" uid="add" />

    <p v-else-if="driver" class="note">这个驱动不需要额外配置，填好挂载路径即可。</p>

    <p class="hint">
      提交后服务器会立即加载这个挂载；名字或配置填错时，删除再重来即可，不会影响已有文件。
    </p>

    <template #footer>
      <button class="ghost" type="button" @click="close">取消</button>
      <button class="primary" type="button" :disabled="busy || !canAdd" @click="submit">添加</button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import StorageFields from './StorageFields.vue'
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
    loadError.value = err.message || '内置服务器没有响应'
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
  toast('挂载已添加', 'ok')
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
