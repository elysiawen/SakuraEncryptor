<!--
  Mounts of the local server.

  The driver list and each driver's fields come from the server itself
  (`/api/admin/driver/list` + `/driver/info`), so mounting a WebDAV, S3 or
  anything else the build supports is the same form as mounting a local folder —
  no driver is hard-coded here.
-->
<template>
  <AppDialog
    :visible="visible"
    title="内置服务器 · 存储"
    description="存储就是服务器能看到的目录或网盘。可以挂本地文件夹，也可以挂 WebDAV、S3 等任何一种驱动。"
    :width="660"
    :busy="busy"
    :dismissible="!adding && !editing"
    @cancel="close"
  >
    <!-- An exited server carries its own reason (退出码 / 崩溃), which is more
         useful than "it is not running". -->
    <p v-if="state?.status !== 'running'" class="hint">
      {{
        state?.status === 'error' && state?.error
          ? state.error
          : '服务器没有在运行，先在设置里开启，再回来管理存储。'
      }}
    </p>

    <template v-else>
      <div class="list-head">
        <span class="label">已挂载 {{ storages.length }} 个</span>
        <button class="primary sm" type="button" @click="adding = true">新增挂载</button>
      </div>

      <div class="storage-list">
        <div v-for="item in storages" :key="item.id" class="storage-row">
          <div class="storage-main">
            <div class="label mono">
              {{ item.mountPath }}
              <span class="badge" :class="{ warn: item.status !== 'work' }">
                {{ item.status === 'work' ? '启用中' : '已停用' }}
              </span>
              <span class="badge">{{ item.driver }}</span>
            </div>
            <div class="desc path" :title="item.rootPath">{{ item.rootPath || summarize(item) }}</div>
          </div>
          <div class="row">
            <button class="ghost sm" type="button" :disabled="busy" @click="editing = item">编辑</button>
            <button class="ghost sm" type="button" :disabled="busy" @click="toggleStorage(item)">
              {{ item.status === 'work' ? '停用' : '启用' }}
            </button>
            <button
              class="ghost sm"
              type="button"
              :disabled="busy || item.mountPath === '/'"
              :title="item.mountPath === '/' ? '默认存储不能删除，可以改它的配置' : ''"
              @click="removeStorage(item)"
            >
              {{ pendingDelete === item.id ? '确认删除' : '删除' }}
            </button>
          </div>
        </div>
        <p v-if="!storages.length" class="desc">还没有存储，点上面的“新增挂载”开始。</p>
      </div>

      <p class="hint">
        停用只是让挂载从列表里消失，不会动文件；默认那个挂在 <code>/</code> 的存储不能删除。
      </p>

      <!-- Both forms are their own modals: a driver can ask for twenty fields,
           and inline they pushed the list out of view. -->
      <StorageAddDialog :visible="adding" @close="adding = false" />
      <StorageEditDialog :visible="!!editing" :item="editing" @close="editing = null" />
    </template>

    <template #footer>
      <button class="ghost" type="button" @click="close">关闭</button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import StorageAddDialog from './StorageAddDialog.vue'
import StorageEditDialog from './StorageEditDialog.vue'
import {
  deleteEmbeddedStorage,
  embedded,
  loadDrivers,
  loadEmbedded,
  loadStorages,
  setEmbeddedStorageEnabled,
} from '../state/embedded.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const state = computed(() => embedded.state)
const busy = computed(() => embedded.busy)
const storages = computed(() => embedded.storages)
const drivers = computed(() => embedded.drivers)

/** The mount whose add/edit dialog sits on top; the list must not close behind it. */
const adding = ref(false)
const editing = ref(null)
const pendingDelete = ref(null)

const close = () => {
  editing.value = null
  pendingDelete.value = null
  adding.value = false
  emit('close')
}

async function toggleStorage(item) {
  await setEmbeddedStorageEnabled(item.id, item.status !== 'work')
}

async function removeStorage(item) {
  // The default mount backs every listing; deleting it would leave the app
  // browsing an empty server on next start.
  if (item.mountPath === '/') return
  if (pendingDelete.value !== item.id) {
    pendingDelete.value = item.id
    setTimeout(() => {
      if (pendingDelete.value === item.id) pendingDelete.value = null
    }, 4000)
    return
  }
  pendingDelete.value = null
  await deleteEmbeddedStorage(item.id)
}

/** Non-Local drivers have no obvious one-line summary; show their driver name. */
function summarize(item) {
  return item.driver ? `${item.driver} 存储` : '未配置'
}

/**
 * Two getter sources, not one getter returning an array.
 *
 * `() => [visible, status]` produces a new array on every evaluation, and Vue
 * compares getter results by identity — so the callback fired again every time
 * `loadEmbedded()` below replaced `embedded.state`, which turned into an endless
 * request loop: six `/driver/names` in one second, `/storage/list` four times in
 * three. AList died under that (its crash dumps show SQLite failing to open),
 * and the add dialog's own `/driver/info` drowned in the queue.
 */
watch(
  [() => props.visible, () => embedded.state?.status],
  async ([visible, status]) => {
    if (!visible) return
    await loadEmbedded()
    if (status === 'running' || embedded.state?.status === 'running') {
      await Promise.all([loadStorages(), drivers.value.length ? Promise.resolve() : loadDrivers()])
    }
  },
  { immediate: true },
)
</script>

<style scoped>
/* The list's own header: a count and the one way to add a mount. The form
   itself lives in StorageAddDialog, so this row stays one line tall. */
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

/* `.label` and `.desc` are styled globally only under `.card`/`.switch-row`, and
   a dialog body is neither: without these the mount path and its root path
   read as one block of plain text. */
.label {
  font-size: 12px;
  color: var(--text-dim);
}

.storage-main .label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text);
}

.desc {
  margin: 0;
  font-size: 11px;
  color: var(--muted);
}

.storage-main .desc {
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.storage-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.storage-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.storage-main {
  min-width: 0;
  flex: 1;
}

.storage-row .row {
  flex-wrap: wrap;
  justify-content: flex-end;
}
</style>
