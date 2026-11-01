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
    :title="$t('embedded.storageTitle')"
    :description="$t('embedded.storageDesc')"
    :width="660"
    :busy="busy"
    :dismissible="!adding && !editing"
    @cancel="close"
  >
    <!-- An exited server carries its own reason (exit code / crash), which is
         more useful than "it is not running". -->
    <p v-if="state?.status !== 'running'" class="hint">
      {{
        state?.status === 'error' && state?.error
          ? state.error
          : $t('embedded.storageOffline')
      }}
    </p>

    <template v-else>
      <div class="list-head">
        <span class="label">{{ $t('embedded.storageMounted', { count: storages.length }) }}</span>
        <button class="primary sm" type="button" @click="adding = true">
          {{ $t('embedded.storageAdd') }}
        </button>
      </div>

      <div class="storage-list">
        <div v-for="item in storages" :key="item.id" class="storage-row">
          <div class="storage-main">
            <div class="label mono">
              {{ item.mountPath }}
              <span class="badge" :class="{ warn: item.status !== 'work' }">
                {{
                  item.status === 'work'
                    ? $t('embedded.storageEnabled')
                    : $t('embedded.storageDisabled')
                }}
              </span>
              <span class="badge">{{ item.driver }}</span>
            </div>
            <div class="desc path" :title="item.rootPath">{{ item.rootPath || summarize(item) }}</div>
          </div>
          <div class="row">
            <button class="ghost sm" type="button" :disabled="busy" @click="editing = item">
              {{ $t('common.edit') }}
            </button>
            <button class="ghost sm" type="button" :disabled="busy" @click="toggleStorage(item)">
              {{
                item.status === 'work'
                  ? $t('embedded.storageDisable')
                  : $t('embedded.storageEnable')
              }}
            </button>
            <button
              class="ghost sm"
              type="button"
              :disabled="busy || item.mountPath === '/'"
              :title="item.mountPath === '/' ? $t('embedded.storageDefaultLocked') : ''"
              @click="removeStorage(item)"
            >
              {{
                pendingDelete === item.id
                  ? $t('embedded.storageDeleteConfirm')
                  : $t('common.delete')
              }}
            </button>
          </div>
        </div>
        <p v-if="!storages.length" class="desc">{{ $t('embedded.storageEmpty') }}</p>
      </div>

      <p class="hint">{{ $t('embedded.storageNote') }}</p>

      <!-- Both forms are their own modals: a driver can ask for twenty fields,
           and inline they pushed the list out of view. -->
      <StorageAddDialog :visible="adding" @close="adding = false" />
      <StorageEditDialog :visible="!!editing" :item="editing" @close="editing = null" />
    </template>

    <template #footer>
      <button class="ghost" type="button" @click="close">{{ $t('common.close') }}</button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import StorageAddDialog from './StorageAddDialog.vue'
import StorageEditDialog from './StorageEditDialog.vue'
import { t } from '../i18n.js'
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
  return item.driver
    ? t('embedded.storageUnnamed', { driver: item.driver })
    : t('embedded.storageUnconfigured')
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
