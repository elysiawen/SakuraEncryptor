<template>
  <DefaultLayout show-breadcrumbs>
    <template #default>
      <LoadingState v-if="source.restoring" />
      <EmptyState
        v-else-if="!filesState.isReady"
        icon="📁"
        :title="gate.title"
        :description="gate.description"
        :action-text="gate.action"
        @action="onReconnect"
      />
      <FileBrowser v-else mode="local" :local-state="filesState" />

      <!-- Fallback for browsers without showDirectoryPicker: those files carry
           no folder handle, so a reload can only ask again. -->
      <input
        ref="folderInput"
        type="file"
        class="hidden-input"
        webkitdirectory
        directory
        multiple
        @change="onFallbackPicked"
      />
    </template>
  </DefaultLayout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DefaultLayout from '../layouts/DefaultLayout.vue'
import FileBrowser from '../components/file-browser/FileBrowser.vue'
import LoadingState from '../components/common/LoadingState.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { useLocalFiles } from '../composables/useLocalFiles.js'
import {
  LOCAL_STATUS,
  pickLocalDirectory,
  reconnectLocalDirectory,
  restoreLocalDirectory,
  supportsDirectoryHandles,
  useLocalDirectory,
} from '../composables/useLocalDirectory.js'

const { t } = useI18n()
const { state: filesState, registerFiles } = useLocalFiles()
const { state: source } = useLocalDirectory()
const folderInput = ref(null)

/**
 * What the user sees before a folder is connected.
 *
 * A reload wipes the Service Worker's file map, which used to render as an
 * empty directory — the source was disconnected, not empty. Say so, and offer
 * the single click that brings it back.
 */
const gate = computed(() => {
  switch (source.status) {
    case LOCAL_STATUS.NEEDS_PERMISSION:
      return {
        title: t('local.needsPermissionTitle'),
        description: t('local.needsPermissionBody', { name: source.folderName }),
        action: t('local.reauthorize'),
      }
    case LOCAL_STATUS.DENIED:
      return {
        title: t('local.deniedTitle'),
        description: t('local.deniedBody'),
        action: t('local.repickFolder'),
      }
    case LOCAL_STATUS.ERROR:
      return {
        title: t('local.errorTitle'),
        description: source.message || t('local.errorFallback'),
        action: t('local.repickFolder'),
      }
    case LOCAL_STATUS.UNSUPPORTED:
      return {
        title: t('local.unsupportedTitle'),
        description: t('local.unsupportedBody'),
        action: t('local.pickFolder'),
      }
    default:
      return {
        title: t('local.disconnectedTitle'),
        description: supportsDirectoryHandles
          ? t('local.disconnectedRestorable')
          : t('local.disconnectedPlain'),
        action: t('local.pickFolder'),
      }
  }
})

onMounted(async () => {
  if (!filesState.isReady) await restoreLocalDirectory()
})

async function onReconnect() {
  if (supportsDirectoryHandles) {
    try {
      if (source.status === LOCAL_STATUS.NEEDS_PERMISSION) {
        await reconnectLocalDirectory()
        return
      }
      await pickLocalDirectory()
    } catch (err) {
      // Dismissing the picker is not a failure.
      if (err?.name !== 'AbortError') {
        source.message = err.message || t('errors.readFolderFailed')
        source.status = LOCAL_STATUS.ERROR
      }
    }
    return
  }
  folderInput.value?.click()
}

async function onFallbackPicked(event) {
  const files = event.target.files
  if (!files?.length) return
  await registerFiles(files)
  source.status = LOCAL_STATUS.RESTORED
}
</script>

<style scoped>
.hidden-input {
  display: none;
}
</style>
