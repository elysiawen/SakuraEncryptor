<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>{{ $t('diagnostics.title') }}</h2>
        <p>{{ $t('diagnostics.subtitle') }}</p>
      </div>
      <button class="ghost" type="button" @click="$router.back()">{{ $t('diagnostics.back') }}</button>
    </div>

    <section class="card">
      <div class="field">
        <label>{{ $t('diagnostics.file') }}</label>
        <div class="row">
          <button class="ghost" type="button" @click="pickFile">
            {{ $t('diagnostics.pickFile') }}
          </button>
          <input v-model="target" class="grow" :placeholder="$t('diagnostics.targetPlaceholder')" />
        </div>
      </div>

      <div class="field">
        <label>{{ $t('diagnostics.password') }}</label>
        <input
          v-model="password"
          type="password"
          :placeholder="$t('diagnostics.passwordPlaceholder')"
          @keyup.enter="openMedia"
        />
      </div>

      <div class="field">
        <label>{{ $t('diagnostics.displayName') }}</label>
        <input v-model="displayName" :placeholder="$t('diagnostics.displayNamePlaceholder')" />
      </div>

      <button class="primary" type="button" :disabled="busy" @click="openMedia">
        {{ busy ? $t('diagnostics.decrypting') : $t('diagnostics.open') }}
      </button>
    </section>

    <section v-if="media" class="card">
      <video ref="videoEl" controls playsinline :src="media.url" />
      <div class="meta">
        <span>{{ media.name }}</span>
        <span class="mono">{{ media.mime }}</span>
        <span class="mono">{{ formatSize(media.size) }}</span>
      </div>
    </section>

    <section class="card">
      <div class="row" style="justify-content: space-between; margin-bottom: 10px">
        <h3 style="margin: 0">{{ $t('diagnostics.rangeTitle') }}</h3>
        <button class="ghost" type="button" :disabled="!media || running" @click="runDiagnostics">
          {{ running ? $t('diagnostics.testing') : $t('diagnostics.runTest') }}
        </button>
      </div>
      <pre class="log">{{ logText || $t('diagnostics.logEmpty') }}</pre>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { toast } from '../state/session.js'
import { t } from '../i18n.js'

const target = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)
const running = ref(false)
const media = ref(null)
const videoEl = ref(null)
const logLines = ref([])

const logText = computed(() => logLines.value.join('\n'))

function append(line) {
  logLines.value = [...logLines.value, line]
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

async function pickFile() {
  const res = await window.ske.dialog.pickFile()
  if (!res?.ok || !res.path) return
  target.value = res.path
  if (!displayName.value) {
    displayName.value = res.path.split(/[\\/]/).pop().replace(/\.ske$/i, '')
  }
}

async function openMedia() {
  if (busy.value) return
  if (!target.value || !password.value) {
    toast(t('diagnostics.needInput'), 'error')
    return
  }

  busy.value = true
  logLines.value = []

  if (media.value) {
    await window.ske.media.close(media.value.id)
    media.value = null
  }

  const res = await window.ske.media.open({
    target: target.value.trim(),
    password: password.value,
    name: displayName.value.trim() || target.value.trim(),
  })
  busy.value = false

  if (!res.ok) {
    append(t('diagnostics.openFailed', { error: res.error }))
    toast(res.error, 'error')
    return
  }

  media.value = res
  append(
    res.encrypted
      ? t('diagnostics.sessionOpened', { size: formatSize(res.size), mime: res.mime })
      : t('diagnostics.plainPassthrough', { size: formatSize(res.size), mime: res.mime }),
  )
}

function waitForEvent(el, event, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      el.removeEventListener(event, onEvent)
      reject(new Error(t('diagnostics.timeout', { event })))
    }, timeout)
    function onEvent() {
      clearTimeout(timer)
      el.removeEventListener(event, onEvent)
      resolve()
    }
    el.addEventListener(event, onEvent, { once: true })
  })
}

function seekTo(el, time) {
  return new Promise((resolve) => {
    const started = performance.now()
    const timer = setTimeout(() => {
      el.removeEventListener('seeked', onSeeked)
      resolve({ ok: false, ms: performance.now() - started })
    }, 8000)
    function onSeeked() {
      clearTimeout(timer)
      el.removeEventListener('seeked', onSeeked)
      resolve({ ok: true, ms: performance.now() - started, at: el.currentTime })
    }
    el.addEventListener('seeked', onSeeked, { once: true })
    el.currentTime = time
  })
}

async function runDiagnostics() {
  const el = videoEl.value
  if (!el || !media.value) return

  running.value = true
  append('')
  append(`=== ${t('diagnostics.rangeTitle')} ===`)

  try {
    if (el.readyState < 1) await waitForEvent(el, 'loadedmetadata')
    append(
      `duration = ${Number.isFinite(el.duration) ? el.duration.toFixed(2) + 's' : t('diagnostics.unknown')}`,
    )
    append(`videoWidth×Height = ${el.videoWidth}×${el.videoHeight}`)
    append(`canPlayType("${media.value.mime}") = ${JSON.stringify(el.canPlayType(media.value.mime) || '')}`)

    const duration = el.duration
    if (!Number.isFinite(duration) || duration <= 0) {
      append(t('diagnostics.noDuration'))
      append(t('diagnostics.needExternal'))
      return
    }

    for (const ratio of [0.25, 0.5, 0.9]) {
      const at = duration * ratio
      const res = await seekTo(el, at)
      append(
        res.ok
          ? t('diagnostics.seekOk', {
              at: at.toFixed(2),
              ms: res.ms.toFixed(0),
              actual: res.at.toFixed(2),
            })
          : t('diagnostics.seekFail', { at: at.toFixed(2) }),
      )
    }

    const samples = []
    for (let i = 0; i < 8; i += 1) {
      const res = await seekTo(el, Math.random() * duration * 0.95)
      if (res.ok) samples.push(res.ms)
    }
    if (samples.length) {
      const avg = samples.reduce((a, b) => a + b, 0) / samples.length
      append(
        t('diagnostics.randomOk', {
          count: samples.length,
          avg: avg.toFixed(0),
          max: Math.max(...samples).toFixed(0),
        }),
      )
    } else {
      append(t('diagnostics.randomFail'))
    }

    append(samples.length ? t('diagnostics.verdictOk') : t('diagnostics.verdictBad'))
  } catch (err) {
    append(t('diagnostics.interrupted', { error: err.message }))
  } finally {
    running.value = false
  }
}

onBeforeUnmount(() => {
  if (media.value) window.ske.media.close(media.value.id)
})
</script>

<style scoped>
video {
  width: 100%;
  max-height: 46vh;
  background: #000;
  border-radius: var(--radius);
  border: 1px solid var(--line);
}

.meta {
  display: flex;
  gap: 14px;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--muted);
  flex-wrap: wrap;
}

.log {
  margin: 0;
  min-height: 160px;
  max-height: 320px;
  overflow: auto;
  background: var(--bg-elevated);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 13px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-dim);
  white-space: pre-wrap;
  font-family: var(--mono);
}
</style>
