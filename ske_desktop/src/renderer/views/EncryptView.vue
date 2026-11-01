<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>{{ $t('encrypt.title') }}</h2>
        <p v-if="mode === 'files'">{{ $t('encrypt.subtitleFiles') }}</p>
        <p v-else>{{ $t('encrypt.subtitleText') }}</p>
      </div>
      <div class="row">
        <div class="segmented" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'files'"
            :class="{ on: mode === 'files' }"
            @click="mode = 'files'"
          >
            {{ $t('encrypt.tabFiles') }}
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'text'"
            :class="{ on: mode === 'text' }"
            @click="mode = 'text'"
          >
            {{ $t('encrypt.tabText') }}
          </button>
        </div>
        <button
          v-if="mode === 'files' && finishedCount"
          class="ghost"
          type="button"
          @click="clear"
        >
          {{ $t('encrypt.clearFinished') }}
        </button>
      </div>
    </div>

    <section v-if="mode === 'files'" class="card settings">
      <div class="field">
        <label>{{ $t('encrypt.outputDir') }}</label>
        <div class="row">
          <input
            class="grow"
            :value="outputDir"
            readonly
            :placeholder="$t('encrypt.outputDirPlaceholder')"
          />
          <button class="ghost" type="button" @click="pickOutput">
            {{ $t('encrypt.choose') }}
          </button>
        </div>
      </div>

      <div class="field">
        <label>{{ $t('encrypt.password') }}</label>
        <input
          v-model="password"
          type="password"
          autocomplete="new-password"
          :placeholder="$t('encrypt.passwordPlaceholder')"
        />
      </div>
    </section>

    <section v-if="mode === 'files'" class="dropzone" :class="{ ready: !!outputDir }" @dragover.prevent @drop.prevent="onDrop">
      <div class="drop-inner">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round">
          <path d="M12 16V5" />
          <path d="M8 9l4-4 4 4" />
          <path d="M4 16v2.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V16" />
        </svg>
        <strong>{{ $t('encrypt.dropTitle') }}</strong>
        <span>{{ outputDir ? $t('encrypt.dropReady') : $t('encrypt.dropIdle') }}</span>
        <div class="row" style="margin-top: 6px">
          <button class="ghost" type="button" @click="pickFiles">
            {{ $t('encrypt.pickFiles') }}
          </button>
          <button class="ghost" type="button" @click="pickFolder">
            {{ $t('encrypt.pickFolder') }}
          </button>
        </div>
      </div>
    </section>

    <section v-if="mode === 'files'" class="card">
      <div class="row between">
        <h3 style="margin: 0">{{ $t('encrypt.queue') }}</h3>
        <span class="badge">
          {{ $t('encrypt.queueCount', { running: runningCount, total: jobs.items.length }) }}
        </span>
      </div>

      <p v-if="!jobs.items.length" class="hint" style="margin: 12px 0 0">
        {{ $t('encrypt.queueEmpty') }}
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
              {{ $t('common.cancel') }}
            </button>
          </div>

          <div class="bar">
            <div class="fill" :class="{ failed: job.status === 'failed' }" :style="{ width: percent(job) + '%' }" />
          </div>

          <div class="job-meta">
            <span class="mono">{{ percent(job) }}%</span>
            <span v-if="job.totalFiles > 1">
              {{ $t('encrypt.fileProgress', { done: job.doneFiles, total: job.totalFiles }) }}
            </span>
            <span v-if="job.currentFile" class="current" :title="job.currentFile">{{ job.currentFile }}</span>
            <span v-if="job.error" class="err">{{ job.error }}</span>
          </div>
        </li>
      </ul>
    </section>

    <section v-else class="card">
      <div class="field">
        <label>{{ $t('encrypt.password') }}</label>
        <input
          v-model="password"
          type="password"
          autocomplete="off"
          :placeholder="$t('encrypt.passwordPlaceholder')"
        />
        <span class="meta">{{ $t('encrypt.passwordHintText') }}</span>
      </div>

      <div class="field">
        <label>{{ $t('encrypt.content') }}</label>
        <textarea
          v-model="textInput"
          rows="7"
          spellcheck="false"
          :placeholder="$t('encrypt.contentPlaceholder')"
        />
      </div>

      <div class="row">
        <button
          class="primary"
          type="button"
          :disabled="converting"
          @click="convert('encrypt')"
        >
          {{ $t('encrypt.encrypt') }}
        </button>
        <button
          class="ghost"
          type="button"
          :disabled="converting"
          @click="convert('decrypt')"
        >
          {{ $t('encrypt.decrypt') }}
        </button>
        <span v-if="converting" class="meta">{{ $t('common.processing') }}</span>
      </div>

      <p v-if="textError" class="text-error">{{ textError }}</p>

      <div v-if="textOutput" class="text-result">
        <div class="row between">
          <label>{{ $t('encrypt.result') }}</label>
          <div class="row">
            <span class="meta">{{ $t('encrypt.chars', { count: textOutput.length }) }}</span>
            <button class="ghost tiny" type="button" @click="copyResult">
              {{ $t('encrypt.copy') }}
            </button>
          </div>
        </div>
        <textarea class="result mono" :value="textOutput" rows="6" readonly />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { attempt, patchSettings, session, toast } from '../state/session.js'
import { t } from '../i18n.js'
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
const outputDir = computed(() => session.settings?.encrypt?.outputDir || '')

// Two workspaces in one page: file containers and short text tokens.
const mode = ref('files')
const textInput = ref('')
const textOutput = ref('')
const textError = ref('')
const converting = ref(false)

async function convert(direction) {
  if (converting.value) return
  const source = textInput.value.trim()
  if (!source) {
    textOutput.value = ''
    textError.value = t('encrypt.missingInput')
    return
  }

  converting.value = true
  textError.value = ''
  try {
    const res = await window.ske.text.convert({
      direction,
      text: source,
      password: password.value,
    })
    if (!res?.ok) throw new Error(res?.error || t('encrypt.convertFailed'))
    textOutput.value = res.text
  } catch (err) {
    textOutput.value = ''
    textError.value = err.message
  } finally {
    converting.value = false
  }
}

async function copyResult() {
  if (!textOutput.value) return
  await attempt(() => window.ske.clipboard.write(textOutput.value), {
    success: t('encrypt.copied'),
  })
}

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
      return t('encrypt.statusScanning')
    case 'queued':
      return t('encrypt.statusQueued')
    case 'running':
      return t('encrypt.statusRunning')
    case 'done':
      return t('encrypt.statusDone')
    case 'cancelled':
      return t('encrypt.statusCancelled')
    default:
      return t('encrypt.statusFailed')
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

async function enqueue(paths) {
  if (!outputDir.value) {
    toast(t('encrypt.needOutputDir'), 'error')
    return
  }
  if (!paths.length) return

  let queued = 0
  for (const srcPath of paths) {
    try {
      // Previews are not optional: the main process decides that, and a job
      // fails loudly if the bundled ffmpeg is unavailable.
      await addJob({
        srcPath,
        destDir: outputDir.value,
        password: password.value || '',
      })
      queued += 1
    } catch (err) {
      toast(err.message, 'error')
    }
  }

  if (queued) toast(t('encrypt.queued', { count: queued }), 'ok')
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
    toast(t('encrypt.needOutputDir'), 'error')
    return
  }

  // Electron 32 removed File.path; the bridged webUtils call is the only way
  // to turn a dropped File into a real path.
  const paths = [...event.dataTransfer.files]
    .map((file) => window.ske.files.pathForFile(file))
    .filter(Boolean)

  if (!paths.length) {
    toast(t('encrypt.dropUnreadable'), 'error')
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

.segmented {
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--bg-elevated);
}

.segmented button {
  border: 0;
  border-radius: 999px;
  padding: 5px 14px;
  font-size: 12px;
  color: var(--muted);
}

.segmented button.on {
  background: color-mix(in srgb, var(--accent) 18%, var(--panel));
  color: var(--accent-strong);
  font-weight: 600;
}

/* Textareas share the global field chrome in `styles.css`. */

.text-error {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--danger);
}

.text-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-soft);
}

.text-result label {
  font-size: 12px;
  color: var(--text-dim);
}

.text-result .result {
  font-family: var(--mono);
  font-size: 12.5px;
}
</style>
