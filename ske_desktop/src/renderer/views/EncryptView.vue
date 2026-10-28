<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>加密工作台</h2>
        <p>文件在本地完成加密，文件名同样会被加密；拖进来即可开始。</p>
      </div>
      <button v-if="finishedCount" class="ghost" type="button" @click="clear">清理已完成</button>
    </div>

    <section class="card settings">
      <div class="field">
        <label>输出目录</label>
        <div class="row">
          <input class="grow" :value="outputDir" readonly placeholder="选择一个输出目录" />
          <button class="ghost" type="button" @click="pickOutput">选择</button>
        </div>
      </div>

      <div class="field">
        <label>加密密码</label>
        <input
          v-model="password"
          type="password"
          autocomplete="new-password"
          placeholder="留空则使用当前配置的媒体密码"
        />
      </div>

      <label class="check">
        <input v-model="thumbnails" type="checkbox" @change="saveThumbSetting" />
        <span>生成预览图（需要系统 ffmpeg，缺失时自动跳过）</span>
      </label>
    </section>

    <section class="dropzone" :class="{ ready: !!outputDir }" @dragover.prevent @drop.prevent="onDrop">
      <div class="drop-inner">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round">
          <path d="M12 16V5" />
          <path d="M8 9l4-4 4 4" />
          <path d="M4 16v2.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V16" />
        </svg>
        <strong>拖放文件或文件夹到这里</strong>
        <span>{{ outputDir ? '加密后写入上面选择的目录' : '先选择输出目录，然后拖入文件' }}</span>
        <div class="row" style="margin-top: 6px">
          <button class="ghost" type="button" @click="pickFiles">选择文件</button>
          <button class="ghost" type="button" @click="pickFolder">选择文件夹</button>
        </div>
      </div>
    </section>

    <section class="card">
      <div class="row between">
        <h3 style="margin: 0">任务队列</h3>
        <span class="badge">
          {{ runningCount }} 进行中 · {{ jobs.items.length }} 总计
        </span>
      </div>

      <p v-if="!jobs.items.length" class="hint" style="margin: 12px 0 0">
        队列为空。单个大文件按 1 MiB 分块流式处理，内存占用不会随文件大小增长。
      </p>

      <ul v-else class="jobs">
        <li v-for="job in jobs.items" :key="job.id" class="job">
          <div class="job-head">
            <span class="job-name" :title="job.name">{{ job.name }}</span>
            <span class="badge" :class="statusClass(job)">{{ statusLabel(job) }}</span>
            <button
              v-if="isActive(job)"
              class="ghost tiny"
              type="button"
              @click="cancel(job)"
            >
              取消
            </button>
          </div>

          <div class="bar">
            <div class="fill" :class="{ failed: job.status === 'failed' }" :style="{ width: percent(job) + '%' }" />
          </div>

          <div class="job-meta">
            <span class="mono">{{ percent(job) }}%</span>
            <span v-if="job.totalFiles > 1">{{ job.doneFiles }}/{{ job.totalFiles }} 个文件</span>
            <span v-if="job.currentFile" class="current" :title="job.currentFile">{{ job.currentFile }}</span>
            <span v-if="job.error" class="err">{{ job.error }}</span>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { attempt, patchSettings, session, toast } from '../state/session.js'
import {
  activeCount,
  addJob,
  cancelJob,
  clearFinished,
  jobs,
  percent,
  subscribeJobs,
  unsubscribeJobs,
} from '../state/jobs.js'

const password = ref('')
const thumbnails = ref(session.settings?.encrypt?.thumbnails ?? true)
const outputDir = computed(() => session.settings?.encrypt?.outputDir || '')

const runningCount = computed(() => activeCount())
const finishedCount = computed(
  () => jobs.items.filter((job) => ['done', 'failed', 'cancelled'].includes(job.status)).length,
)

onMounted(subscribeJobs)
onUnmounted(unsubscribeJobs)

function isActive(job) {
  return ['scanning', 'queued', 'running'].includes(job.status)
}

function statusLabel(job) {
  switch (job.status) {
    case 'scanning':
      return '扫描中'
    case 'queued':
      return '等待中'
    case 'running':
      return '加密中'
    case 'done':
      return '完成'
    case 'cancelled':
      return '已取消'
    default:
      return '失败'
  }
}

function statusClass(job) {
  if (job.status === 'done') return 'ok'
  if (job.status === 'failed') return 'warn'
  return ''
}

async function pickOutput() {
  const res = await window.ske.dialog.pickDirectory()
  if (!res?.ok || !res.path) return
  await attempt(() => patchSettings({ encrypt: { outputDir: res.path } }))
}

async function saveThumbSetting() {
  await attempt(() => patchSettings({ encrypt: { thumbnails: thumbnails.value } }))
}

async function enqueue(paths) {
  if (!outputDir.value) {
    toast('请先选择输出目录', 'error')
    return
  }
  if (!paths.length) return

  let queued = 0
  for (const srcPath of paths) {
    try {
      await addJob({
        srcPath,
        destDir: outputDir.value,
        password: password.value || '',
        thumbnails: thumbnails.value,
      })
      queued += 1
    } catch (err) {
      toast(err.message, 'error')
    }
  }

  if (queued) toast(`已加入 ${queued} 个任务`, 'ok')
}

async function pickFiles() {
  // Encrypting takes plaintext sources, so this must not use the `.ske` picker.
  const res = await window.ske.dialog.pickSourceFiles()
  if (res?.ok && res.paths?.length) enqueue(res.paths)
}

async function pickFolder() {
  const res = await window.ske.dialog.pickDirectory()
  if (res?.ok && res.path) enqueue([res.path])
}

function onDrop(event) {
  if (!outputDir.value) {
    toast('请先选择输出目录', 'error')
    return
  }

  // Electron 32 removed File.path; the bridged webUtils call is the only way
  // to turn a dropped File into a real path.
  const paths = [...event.dataTransfer.files]
    .map((file) => window.ske.files.pathForFile(file))
    .filter(Boolean)

  if (!paths.length) {
    toast('无法读取拖入的文件路径', 'error')
    return
  }
  enqueue(paths)
}

async function cancel(job) {
  await attempt(() => cancelJob(job.id))
}

async function clear() {
  await attempt(clearFinished)
}
</script>

<style scoped>
.settings {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 4px 20px;
  align-items: start;
}

.settings .check {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-dim);
  margin-top: 2px;
}

.settings .check input {
  width: auto;
  accent-color: var(--accent);
}

.dropzone {
  border: 1px dashed var(--line);
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
  padding: 38px 24px;
  text-align: center;
  margin: 14px 0;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.dropzone.ready:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  background: var(--panel-hover);
}

.drop-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
}

.drop-inner svg {
  width: 28px;
  height: 28px;
  color: var(--accent);
  opacity: 0.85;
}

.drop-inner strong {
  font-size: 13.5px;
}

.drop-inner span {
  font-size: 12px;
  color: var(--muted);
}

.between {
  justify-content: space-between;
}

.jobs {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.job {
  border-top: 1px solid var(--line-soft);
  padding-top: 12px;
}

.job:first-child {
  border-top: 0;
  padding-top: 0;
}

.job-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.job-name {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tiny {
  padding: 3px 9px;
  font-size: 11.5px;
}

.bar {
  height: 5px;
  border-radius: 999px;
  background: var(--bg-elevated);
  overflow: hidden;
  margin: 9px 0 7px;
}

.fill {
  height: 100%;
  background: linear-gradient(90deg, #1d4ed8, var(--accent));
  transition: width 0.2s ease;
}

.fill.failed {
  background: var(--danger);
}

.job-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--muted);
}

.job-meta .current {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 40%;
}

.job-meta .err {
  color: var(--danger);
}
</style>
