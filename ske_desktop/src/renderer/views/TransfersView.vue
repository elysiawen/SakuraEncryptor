<!--
  Both directions in one place.

  A download and an upload are the same kind of work from the user's side —
  something is moving, and it can be watched or cancelled — so splitting them
  across two pages only hid half the queue. The finished group merges both and
  sorts by the moment each one ended, because "what did I just move" is asked
  about the pair, not about one direction.
-->
<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>传输</h2>
        <p>
          下载把云端或本地的容器解密到本机，上传把本机文件加密后推到云端。任务在后台继续，切换页面不影响进度。
        </p>
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
      v-if="!tabs.some((item) => item.count)"
      icon="⇅"
      title="没有传输任务"
      description="云端库和本地库的右键菜单可以下载或上传；任务会出现在这里，加密与解密都在本机完成。"
    />

    <template v-else>
      <!-- Three lists, one panel: the reference layout puts the categories in a
           column, here they sit in a row because a desktop window has width to
           spare and switching is the point. -->
      <div class="tabs" role="tablist">
        <button
          v-for="item in tabs"
          :key="item.key"
          class="tab"
          :class="{ on: tab === item.key }"
          type="button"
          role="tab"
          :aria-selected="tab === item.key"
          @click="tab = item.key"
        >
          <svg
            class="ti"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <template v-if="item.key === 'upload'">
              <path d="M12 16V4" />
              <path d="M8 8l4-4 4 4" />
              <path d="M5 20h14" />
            </template>
            <template v-else-if="item.key === 'download'">
              <path d="M12 4v12" />
              <path d="M8 12l4 4 4-4" />
              <path d="M5 20h14" />
            </template>
            <template v-else>
              <circle cx="12" cy="12" r="8.2" />
              <path d="M8.4 12.2l2.6 2.6 4.6-5" />
            </template>
          </svg>
          <span>{{ item.title }}</span>
          <span v-if="item.count" class="num">{{ item.count }}</span>
        </button>
      </div>

      <p v-if="!current.items.length" class="none">{{ current.empty }}</p>

      <div v-else class="list">
        <article v-for="job in current.items" :key="`${job.kind}-${job.id}`" class="item">
          <div class="head">
            <span class="name" :title="job.name">{{ job.name }}</span>
            <span class="badge" :class="badgeTone(job)">{{ statusText(job) }}</span>
          </div>

          <div v-if="current.key !== 'finished'" class="bar">
            <div
              class="fill"
              :class="{ failed: job.status === 'failed' }"
              :style="{ width: `${percent(job)}%` }"
            />
          </div>

          <div class="foot">
            <span class="kind">{{ job.kind === 'download' ? '下载' : '上传' }}</span>
            <span class="mono">
              {{ formatSize(job.doneBytes) }} / {{ formatSize(job.totalBytes) }}
            </span>

            <span v-if="job.error" class="err" :title="job.error">{{ job.error }}</span>
            <span v-else-if="job.kind === 'download' && job.outPath" class="path mono" :title="job.outPath">
              {{ job.outPath }}
            </span>
            <span v-else-if="job.kind === 'upload' && job.dir" class="path mono" :title="job.dir">
              {{ job.dir }}
            </span>

            <div class="actions">
              <button
                v-if="job.kind === 'download' && job.status === 'done'"
                class="ghost sm"
                type="button"
                @click="reveal(job)"
              >
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
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
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
import {
  cancelUpload,
  clearUploads,
  refreshUploads,
  subscribeUploads,
  uploads,
} from '../state/uploads.js'
import { formatSize } from '../state/cloud.js'

const destDir = computed(() => session.settings?.download?.dir || '')

const isActive = (job) => job.status === 'queued' || job.status === 'running'
const isFinished = (job) => ['done', 'failed', 'cancelled'].includes(job.status)

const activeDownloads = computed(() =>
  downloads.items.filter(isActive).map((job) => ({ ...job, kind: 'download' })),
)

const activeUploads = computed(() =>
  uploads.items.filter(isActive).map((job) => ({ ...job, kind: 'upload' })),
)

/**
 * Both directions, newest finish first.
 *
 * A download and an upload of the same name are different rows, hence the kind
 * in the key; a snapshot without a timestamp still sorts last rather than
 * jumping to the top.
 */
const finished = computed(() =>
  [
    ...downloads.items.filter(isFinished).map((job) => ({ ...job, kind: 'download' })),
    ...uploads.items.filter(isFinished).map((job) => ({ ...job, kind: 'upload' })),
  ].sort((a, b) => (b.finishedAt || b.startedAt || 0) - (a.finishedAt || a.startedAt || 0)),
)

const hasFinished = computed(() => finished.value.length > 0)

/**
 * Which list is open.
 *
 * Starts on whichever list has work in it — landing on an empty upload tab
 * right after finishing a download reads as if nothing happened — and falls
 * back to the finished list so an idle page still shows the history.
 */
const tab = ref(
  activeUploads.value.length ? 'upload' : activeDownloads.value.length ? 'download' : 'finished',
)

const tabs = computed(() => [
  {
    key: 'upload',
    title: '上传列表',
    items: activeUploads.value,
    count: activeUploads.value.length,
    empty: '暂无进行中的上传',
  },
  {
    key: 'download',
    title: '下载列表',
    items: activeDownloads.value,
    count: activeDownloads.value.length,
    empty: '暂无进行中的下载',
  },
  {
    key: 'finished',
    title: '完成列表',
    items: finished.value,
    count: finished.value.length,
    empty: '还没有结束的任务',
  },
])

const current = computed(() => tabs.value.find((item) => item.key === tab.value) || tabs.value[0])

onMounted(() => {
  // Already subscribed by the shell; these calls only refresh the snapshots a
  // page opened after the tasks started would otherwise miss.
  subscribeDownloads()
  subscribeUploads()
  refreshDownloads()
  refreshUploads()
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
      // Uploading has two visible stages; saying which one is running explains
      // why a big file sits at 50% while the network is idle. The transfer
      // itself has no percentage — only the encrypting half is measurable.
      if (job.kind === 'upload') {
        return job.phase === 'encrypting' ? '加密中' : '上传中'
      }
      return `${percent(job)}%`
    case 'done':
      return job.kind === 'download' ? '已下载' : '已上传'
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
    if (job.kind === 'download') await cancelDownload(job.id)
    else await cancelUpload(job.id)
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

/** Clear both sides at once: the group is one list to the user. */
async function clearFinished() {
  try {
    await clearDownloads()
    await clearUploads()
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

/* Category strip: pill-shaped tabs with the count sitting inside the label,
   so switching lists never shifts the layout. */
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--text-dim);
  font-size: 12.5px;
}

.tab:hover {
  background: var(--panel);
  color: var(--text);
}

.tab.on {
  background: var(--accent-soft);
  border-color: transparent;
  color: var(--accent-strong);
}

.ti {
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
}

.num {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--panel);
  color: var(--muted);
  font-size: 10.5px;
  line-height: 16px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.tab.on .num {
  background: color-mix(in srgb, var(--accent) 22%, transparent);
  color: var(--accent-strong);
}

.none {
  margin: 0;
  padding: 12px 14px;
  border: 1px dashed var(--line);
  border-radius: var(--radius-sm);
  font-size: 12px;
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

/* Direction tag: the same row shape serves both queues, so the only thing that
   tells them apart at a glance is this word. */
.kind {
  flex: 0 0 auto;
  padding: 1px 6px;
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 10.5px;
  color: var(--text-dim);
}

.err {
  color: var(--danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.path {
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
