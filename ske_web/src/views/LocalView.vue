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
        title: '本地目录需要重新授权',
        description: `浏览器不会跨刷新保留文件夹访问权限。点一下即可重新连接「${source.folderName}」，无需再去找目录。`,
        action: '重新授权',
      }
    case LOCAL_STATUS.DENIED:
      return {
        title: '本地目录的访问权限已被拒绝',
        description: '请在浏览器的网站设置里允许读取该文件夹，或重新选择一个。',
        action: '重新选择文件夹',
      }
    case LOCAL_STATUS.ERROR:
      return {
        title: '无法恢复本地目录',
        description: source.message || '请重新选择文件夹。',
        action: '重新选择文件夹',
      }
    case LOCAL_STATUS.UNSUPPORTED:
      return {
        title: '本地目录未连接',
        description: '当前浏览器不支持记住文件夹（Firefox / Safari），刷新后需要重新选择；Chrome / Edge 可以自动恢复。',
        action: '选择文件夹',
      }
    default:
      return {
        title: '本地目录未连接',
        description: supportsDirectoryHandles
          ? '选择一个文件夹即可浏览。之后刷新会自动尝试恢复上次的目录。'
          : '当前浏览器不支持记住文件夹，请选择文件夹后继续。',
        action: '选择文件夹',
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
        source.message = err.message || '无法读取该文件夹'
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
