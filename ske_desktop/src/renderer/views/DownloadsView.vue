<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>下载</h2>
        <p>把云端或本地的 .ske 容器解密成普通文件。任务在后台继续，切换页面不影响进度。</p>
      </div>
      <div class="tools">
        <span class="dir mono" :title="destDir || '尚未选择'">
          {{ destDir || '尚未选择下载目录' }}
        </span>
        <button class="ghost sm" type="button" @click="pickDir">选择目录</button>
        <button class="ghost sm" type="button" :disabled="!hasFinished" @click="clearFinished">
          清除已完成
        </button>
      </div>
    </div>

    <EmptyState
      v-if="!downloads.items.length"
      icon="⤓"
      title="没有下载任务"
      description="在云端库或本地库里点文件名右侧的下载按钮，任务会出现在这里。下载会写出明文文件，请确认目标位置合适。"
    />

    <div v-else class="list">
      <article v-for="job in downloads.items" :key="job.id" class="item">
        <div class="head">
          <span class="name" :title="job.name">{{ job.name }}</span>
          <span class="badge" :class="badgeTone(job)">{{ statusText(job) }}</span>
        </div>

        <div class="bar">
          <div class="fill" :class="{ failed: job.status === 'failed' }" :style="{ width: `${percent(job)}%` }" />
        </div>

        <div class="foot">
          <span class="mono">
            {{ formatSize(job.doneBytes) }} / {{ formatSize(job.totalBytes) }}
          </span>
          <span v-if="job.error" class="err">{{ job.error }}</span>
          <span v-else-if="job.status === 'done'" class="ok-path mono" :title="job.outPath">
            {{ job.outPath }}
          </span>

          <div class="actions">
            <button v-if="job.status === 'done'" class="ghost sm" type="button" @click="reveal(job)">
              打开所在文件夹
            </button>
            <button
              v-if="job.status === 'queued' || job.status === 'running'"
              class="ghost sm"
              type="button"
              @click="cancel(job)"
            >
              取消
            </button>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import EmptyState from '../components/EmptyState.vue'
import { patchSettings, session, toast } from '../state/session.js'
import {
  cancelDownload,
  clearDownloads,
  downloads,
  refreshDownloads,
  revealDownload,
  subscribeDownloads,
} from '../state/downloads.js'
import { formatSize } from '../state/cloud.js'

const destDir = computed(() => session.settings?.download?.dir || '')
const hasFinished = computed(() =>
  downloads.items.some((job) => ['done', 'failed', 'cancelled'].includes(job.status)),
)

onMounted(() => {
  subscribeDownloads()
  refreshDownloads()
})

function percent(job) {
  if (!job.totalBytes) return job.status === 'done' ? 100 : 0
  return Math.min(100, Math.round((job.doneBytes / job.totalBytes) * 100))
}

function statusText(job) {
  switch (job.status) {
    case 'queued':
      return '排队中'
    case 'running':
      return `${percent(job)}%`
    case 'done':
      return '已完成'
    case 'cancelled':
      return '已取消'
    default:
      return '失败'
  }
}

function badgeTone(job) {
  if (job.status === 'done') return 'ok'
  if (job.status === 'failed') return 'warn'
  return ''
}

async function pickDir() {
  const picked = await window.ske.dialog.pickDirectory()
  if (!picked?.ok || !picked.path) return
  try {
    await patchSettings({ download: { dir: picked.path } })
    toast('下载目录已更新', 'ok')
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function cancel(job) {
  try {
    await cancelDownload(job.id)
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function reveal(job) {
  try {
    await revealDownload(job.id)
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function clearFinished() {
  try {
    await clearDownloads()
  } catch (err) {
    toast(err.message, 'error')
  }
}
</script>

<style scoped>
.tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sm {
  padding: 5px 10px;
  font-size: 12px;
}

.dir {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--muted);
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item {
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--panel);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.name {
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar {
  height: 4px;
  border-radius: 999px;
  background: var(--line-strong);
  overflow: hidden;
}

.fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
  transition: width 0.2s ease;
}

.fill.failed {
  background: var(--danger);
}

.foot {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--muted);
}

.err {
  color: var(--danger);
}

.ok-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 46%;
}

.actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
</style>
