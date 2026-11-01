<!--
  Backend selection, port, version and download source.
  Everything here needs a running server or a fresh download, so it stays out of
  the settings list and opens as a dialog instead.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="$t('embedded.backendTitle')"
    :description="$t('embedded.backendDesc')"
    :width="560"
    :busy="busy"
    @cancel="close"
  >
    <div class="backend-grid">
      <button
        v-for="option in state?.backends || []"
        :key="option.id"
        type="button"
        class="backend"
        :class="{ active: state?.backend === option.id }"
        :disabled="busy"
        @click="pickBackend(option.id)"
      >
        <strong>{{ option.label }}</strong>
        <span>{{ option.blurb }}</span>
      </button>
    </div>
    <p class="hint">{{ $t('embedded.switchHint') }}</p>

    <div class="field">
      <label for="embedded-port">{{ $t('embedded.port') }}</label>
      <div class="row">
        <input
          id="embedded-port"
          v-model.number="portDraft"
          type="number"
          :min="state?.minPort || 1024"
          :max="state?.maxPort || 65535"
        />
        <button class="ghost sm" type="button" :disabled="busy" @click="savePort">
          {{ $t('embedded.save') }}
        </button>
      </div>
      <div class="meta">{{ $t('embedded.portHint') }}</div>
    </div>

    <div class="field">
      <label for="embedded-version">{{ $t('embedded.versionLabel') }}</label>
      <div class="row">
        <input id="embedded-version" v-model="versionDraft" type="text" placeholder="latest" />
        <button class="ghost sm" type="button" :disabled="busy" @click="saveVersion">
          {{ $t('embedded.save') }}
        </button>
        <button class="ghost sm" type="button" :disabled="busy" @click="reinstall">
          {{ $t('embedded.reinstall') }}
        </button>
      </div>
      <div class="meta">
        {{
          $t('embedded.installedTag', {
            value: state?.installed
              ? `${state.installed.tag} (${formatSize(state.installed.size)})`
              : $t('embedded.notDownloaded'),
          })
        }}
      </div>
    </div>

    <div class="field">
      <label for="embedded-mirror">{{ $t('embedded.mirrorLabel') }}</label>
      <div class="row">
        <input id="embedded-mirror" v-model="mirrorDraft" type="text" placeholder="https://ghproxy.example/" />
        <button class="ghost sm" type="button" :disabled="busy" @click="saveMirror">
          {{ $t('embedded.save') }}
        </button>
      </div>
      <div class="meta">{{ $t('embedded.mirrorHint') }}</div>
    </div>

    <div class="row tools">
      <button class="ghost sm" type="button" @click="openDir">
        {{ $t('embedded.openDataDir') }}
      </button>
      <button class="ghost sm" type="button" @click="toggleLogs">
        {{ showLogs ? $t('embedded.hideLogs') : $t('embedded.showLogs') }}
      </button>
    </div>
    <pre v-if="showLogs" class="logs">{{ logs || $t('embedded.noLogs') }}</pre>

    <div class="danger-zone">
      <div>
        <div class="label">{{ $t('embedded.deleteTitle') }}</div>
        <div class="desc">{{ $t('embedded.deleteDesc') }}</div>
      </div>
      <button class="danger sm" type="button" :disabled="busy" @click="removeServer">
        {{ dangerArmed ? $t('embedded.confirmDelete') : $t('common.delete') }}
      </button>
    </div>

    <template #footer>
      <button class="ghost" type="button" @click="close">{{ $t('common.done') }}</button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import {
  embedded,
  formatSize,
  loadEmbedded,
  loadEmbeddedLogs,
  reinstallEmbedded,
  setEmbeddedBackend,
  setEmbeddedMirror,
  setEmbeddedPort,
  setEmbeddedVersion,
  uninstallEmbedded,
} from '../state/embedded.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const state = computed(() => embedded.state)
const busy = computed(() => embedded.busy)
const logs = computed(() => embedded.logs)

const portDraft = ref(5244)
const versionDraft = ref('latest')
const mirrorDraft = ref('')
const showLogs = ref(false)
const dangerArmed = ref(false)

function close() {
  showLogs.value = false
  dangerArmed.value = false
  emit('close')
}

function syncDrafts() {
  const current = embedded.state
  if (!current) return
  portDraft.value = current.port
  versionDraft.value = current.version
  mirrorDraft.value = current.mirror
}

async function pickBackend(id) {
  if (state.value?.backend === id) return
  await setEmbeddedBackend(id)
}

async function savePort() {
  await setEmbeddedPort(Number(portDraft.value))
}

async function saveVersion() {
  await setEmbeddedVersion(versionDraft.value)
}

async function saveMirror() {
  await setEmbeddedMirror(mirrorDraft.value)
}

async function reinstall() {
  await reinstallEmbedded()
}

async function openDir() {
  await window.ske.embedded.openDir()
}

async function toggleLogs() {
  showLogs.value = !showLogs.value
  if (showLogs.value) await loadEmbeddedLogs()
}

async function removeServer() {
  if (!dangerArmed.value) {
    dangerArmed.value = true
    setTimeout(() => {
      dangerArmed.value = false
    }, 4000)
    return
  }
  dangerArmed.value = false
  await uninstallEmbedded()
  close()
}

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return
    await loadEmbedded()
    syncDrafts()
  },
  { immediate: true },
)

// Getter sources, not one getter returning an array: a fresh array counts as a
// change every time the state snapshot is replaced, so this would re-sync on
// every push (including the ones it does not care about).
watch([() => embedded.state?.port, () => embedded.state?.version, () => embedded.state?.mirror], syncDrafts)
</script>

<style scoped>
.backend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px;
  margin-bottom: 8px;
}

.backend {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  text-align: left;
  border: 1px solid var(--line, #333);
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
}

.backend.active {
  border-color: var(--accent, #e8a0bf);
  box-shadow: inset 0 0 0 1px var(--accent, #e8a0bf);
}

.backend span {
  font-size: 12px;
  opacity: 0.7;
  line-height: 1.5;
}

.tools {
  margin-top: 4px;
}

.logs {
  margin-top: 10px;
  max-height: 200px;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.35);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.danger-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
</style>
