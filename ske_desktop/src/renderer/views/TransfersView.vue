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
        <h2>{{ $t('transfers.title') }}</h2>
        <p>{{ $t('transfers.subtitle') }}</p>
      </div>
      <div class="tools">
        <span class="dir mono" :title="destDir || $t('transfers.notChosen')">
          {{ destDir || $t('transfers.notChosen') }}
        </span>
        <button class="ghost sm" type="button" @click="pickDir">
          {{ $t('transfers.pickDir') }}
        </button>
        <button class="ghost sm" type="button" :disabled="!hasFinished" @click="clearFinished">
          {{ $t('transfers.clearFinished') }}
        </button>
      </div>
    </div>

    <EmptyState
      v-if="!tabs.some((item) => item.count)"
      icon="⇅"
      :title="$t('transfers.emptyTitle')"
      :description="$t('transfers.emptyDesc')"
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
        <article
          v-for="(job, i) in current.items"
          :key="`${job.kind}-${job.id}`"
          class="item stagger-in"
          :style="{ '--i': i }"
        >
          <div class="head">
            <span class="name" :title="job.name">{{ job.name }}</span>
            <span class="badge" :class="badgeTone(job)">{{ statusText(job) }}</span>
          </div>

          <div v-if="current.key !== 'finished'" class="bar">
            <!-- A download is one stream; an upload is two: the bytes the
                 server took delivery of, and the bytes it managed to write
                 through to the storage backend. -->
            <template v-if="job.kind === 'download'">
              <div
                class="fill"
                :class="{ failed: job.status === 'failed' }"
                :style="{ width: `${percent(job)}%` }"
              />
            </template>
            <template v-else>
              <div
                v-if="job.phase === 'encrypting'"
                class="fill sent"
                :style="{ width: `${encryptPercent(job)}%` }"
              />
              <template v-else>
                <div class="fill sent" :style="{ width: `${sentPercent(job)}%` }" />
                <div
                  class="fill stored"
                  :class="{ failed: job.status === 'failed' }"
                  :style="{ width: `${storedPercent(job)}%` }"
                />
              </template>
              <div v-if="isStoringUnknown(job)" class="sweep" />
            </template>
          </div>

          <div class="foot">
            <span class="kind">
              {{ job.kind === 'download' ? $t('transfers.kindDownload') : $t('transfers.kindUpload') }}
            </span>
            <span v-if="job.kind === 'download'" class="mono">
              {{ formatSize(job.doneBytes) }} / {{ formatSize(job.totalBytes) }}
            </span>
            <span v-else class="mono">
              {{ formatSize(job.sentBytes) }} / {{ formatSize(uploadTotal(job)) }}
            </span>
            <span v-if="showsCloud(job)" class="mono cloud">
              {{ $t('transfers.cloudPercent', { percent: storedPercent(job) }) }}
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
                {{ $t('transfers.reveal') }}
              </button>
              <button
                v-if="job.status === 'queued' || job.status === 'running'"
                class="ghost sm"
                type="button"
                @click="cancel(job)"
              >
                {{ $t('common.cancel') }}
              </button>

              <!-- Finished rows keep the two actions apart: wanting the row gone
                   should not force deleting the file, and the other way round. -->
              <button
                v-if="deletable(job)"
                class="ghost sm danger-text"
                type="button"
                @click="askDeleteFile(job)"
              >
                {{
                  job.kind === 'download'
                    ? $t('transfers.deleteLocalFile')
                    : $t('transfers.deleteRemoteFile')
                }}
              </button>
              <button
                v-if="isFinished(job)"
                class="ghost sm"
                type="button"
                @click="forgetRow(job)"
              >
                {{ $t('transfers.deleteRecord') }}
              </button>
            </div>
          </div>
        </article>
      </div>
    </template>

    <ConfirmDialog
      :visible="removal.show"
      :title="
        removal.job?.kind === 'download'
          ? $t('transfers.deleteLocalFile')
          : $t('transfers.deleteRemoteFile')
      "
      :description="removalDescription"
      :busy="removal.busy"
      :error="removal.error"
      @cancel="removal.show = false"
      @confirm="confirmDeleteFile"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import { patchSettings, session, toast } from '../state/session.js'
import {
  cancelDownload,
  clearDownloads,
  deleteDownloadFile,
  downloads,
  forgetDownload,
  refreshDownloads,
  revealDownload,
  subscribeDownloads,
} from '../state/downloads.js'
import {
  cancelUpload,
  clearUploads,
  deleteUploadFile,
  forgetUpload,
  refreshUploads,
  subscribeUploads,
  uploads,
} from '../state/uploads.js'
import { formatSize } from '../state/cloud.js'
import { t } from '../i18n.js'

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
    title: t('transfers.listUploads'),
    items: activeUploads.value,
    count: activeUploads.value.length,
    empty: t('transfers.emptyUploads'),
  },
  {
    key: 'download',
    title: t('transfers.listDownloads'),
    items: activeDownloads.value,
    count: activeDownloads.value.length,
    empty: t('transfers.emptyDownloads'),
  },
  {
    key: 'finished',
    title: t('transfers.listFinished'),
    items: finished.value,
    count: finished.value.length,
    empty: t('transfers.emptyFinished'),
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

/** Denominator for an upload: the container, which is what actually moves. */
function uploadTotal(job) {
  return job.uploadBytes || job.totalBytes || 0
}

function ratioBytes(bytes, job) {
  const total = uploadTotal(job)
  if (!total) return 0
  return Math.min(100, Math.round(((bytes || 0) / total) * 100))
}

/** Delivered to AList — the light blue. */
function sentPercent(job) {
  if (job.status === 'done') return 100
  return ratioBytes(job.sentBytes, job)
}

/** Written through to the storage backend — the solid blue on top of it. */
function storedPercent(job) {
  if (job.status === 'done') return 100
  return job.storedBytes === null ? 0 : ratioBytes(job.storedBytes, job)
}

function encryptPercent(job) {
  return Math.min(100, Math.round((job.encryptRatio || 0) * 100))
}

/**
 * Delivered, but the server has not said how far the cloud write got.
 *
 * Most backends can only be watched through their own upload task; when that
 * is unavailable the bar keeps that part honest by sweeping rather than
 * inventing a percentage.
 */
function isStoringUnknown(job) {
  return (
    job.status === 'running' &&
    job.phase === 'storing' &&
    job.storedBytes === null &&
    sentPercent(job) > 0
  )
}

function showsCloud(job) {
  return job.kind === 'upload' && job.status === 'running' && job.storedBytes !== null
}

function statusText(job) {
  switch (job.status) {
    case 'queued':
      return t('transfers.statusQueued')
    case 'running':
      // Uploading has three visible stages, and saying which one is running is
      // the point: the file is on the server long before it is on the cloud,
      // and only the second number explains why the network looks idle.
      if (job.kind === 'upload') {
        if (job.phase === 'encrypting') return t('transfers.statusEncrypting')
        if (job.phase === 'storing') {
          return job.storedBytes === null
            ? t('transfers.statusStoringPending')
            : t('transfers.statusStoring', { percent: storedPercent(job) })
        }
        return t('transfers.statusUploading', { percent: sentPercent(job) })
      }
      return `${percent(job)}%`
    case 'done':
      return job.kind === 'download' ? t('transfers.statusDownloaded') : t('transfers.statusUploaded')
    case 'cancelled':
      return t('transfers.statusCancelled')
    default:
      return t('transfers.statusFailed')
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
    toast(t('transfers.dirUpdated'), 'ok')
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

/**
 * Whether the file behind a settled row can be deleted from here.
 *
 * A download needs the path it actually wrote; an upload needs the name it was
 * stored under, which rows recorded before that existed do not have — those can
 * still be forgotten, just not deleted from the cloud.
 */
function deletable(job) {
  if (job.status !== 'done') return false
  return job.kind === 'download' ? Boolean(job.outPath) : Boolean(job.remoteName)
}

const removal = reactive({ show: false, job: null, busy: false, error: '' })

const removalDescription = computed(() => {
  const job = removal.job
  if (!job) return ''
  return job.kind === 'download'
    ? t('transfers.deleteLocalFileDesc', { name: job.name })
    : t('transfers.deleteRemoteFileDesc', { name: job.name, dir: job.dir })
})

function askDeleteFile(job) {
  removal.job = job
  removal.error = ''
  removal.busy = false
  removal.show = true
}

async function confirmDeleteFile() {
  const job = removal.job
  if (!job) return

  removal.busy = true
  try {
    if (job.kind === 'download') await deleteDownloadFile(job.id)
    else await deleteUploadFile(job.id)
    removal.show = false
    toast(t('transfers.deletedFile'), 'ok')
  } catch (err) {
    // The row stays: it is the only handle on a file the storage refused.
    removal.error = err.message
  } finally {
    removal.busy = false
  }
}

/** Drop the row only; the file stays wherever it is. */
async function forgetRow(job) {
  try {
    if (job.kind === 'download') await forgetDownload(job.id)
    else await forgetUpload(job.id)
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
  transition: border-color 0.16s ease;
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

/* Two layers on one track: the pale part is what the server has taken delivery
   of, the solid part is what it has written through to the storage backend.
   The solid one sits on top and grows from the left, so the pale tail is
   exactly "already in AList, not in the cloud yet". */
.bar {
  position: relative;
  height: 5px;
  border-radius: 999px;
  background: var(--line-strong);
  overflow: hidden;
}

.fill {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  border-radius: 999px;
  background: var(--accent);
  transition: width 0.2s ease;
}

.fill.sent {
  background: color-mix(in srgb, var(--accent) 38%, transparent);
}

.fill.failed {
  background: var(--danger);
}

/* Delivered, but the cloud write cannot be measured: a moving band says "still
   working" without inventing a percentage nobody reports. */
.sweep {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 45%;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--accent) 90%, transparent),
    transparent
  );
  animation: sweep 1.5s linear infinite;
}

@keyframes sweep {
  from {
    left: -45%;
  }
  to {
    left: 100%;
  }
}

.cloud {
  color: var(--accent-strong);
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

/* Deleting a file is the one action here that cannot be undone, so it reads
   red even before the dialog explains it. */
.danger-text {
  color: var(--danger);
}

.danger-text:hover {
  border-color: color-mix(in srgb, var(--danger) 45%, transparent);
}
</style>
