<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>播放诊断</h2>
        <p>直接对分块解密端点做一次真实播放与跳转，用于验证当前机器的解码能力。</p>
      </div>
      <button class="ghost" type="button" @click="$router.back()">返回</button>
    </div>

    <section class="card">
      <div class="field">
        <label>加密文件</label>
        <div class="row">
          <button class="ghost" type="button" @click="pickFile">选择文件</button>
          <input v-model="target" class="grow" placeholder="本地绝对路径，或 https:// 开头的远端地址" />
        </div>
      </div>

      <div class="field">
        <label>密码</label>
        <input v-model="password" type="password" placeholder="加密时使用的密码" @keyup.enter="openMedia" />
      </div>

      <div class="field">
        <label>解密后的文件名</label>
        <input v-model="displayName" placeholder="例如 movie.mp4 —— 决定播放器使用的 MIME 类型" />
      </div>

      <button class="primary" type="button" :disabled="busy" @click="openMedia">
        {{ busy ? '解密中…' : '打开并播放' }}
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
        <h3 style="margin: 0">Range / Seek 诊断</h3>
        <button class="ghost" type="button" :disabled="!media || running" @click="runDiagnostics">
          {{ running ? '测试中…' : '开始测试' }}
        </button>
      </div>
      <pre class="log">{{ logText || '打开文件后运行诊断，可验证分块解密与任意跳转是否正常。' }}</pre>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { toast } from '../state/session.js'

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
    toast('请先填写文件与密码', 'error')
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
    append(`打开失败：${res.error}`)
    toast(res.error, 'error')
    return
  }

  media.value = res
  append(
    res.encrypted
      ? `已建立解密会话：${formatSize(res.size)}，MIME ${res.mime}`
      : `该文件不是 .ske 容器，按明文直通：${formatSize(res.size)}，MIME ${res.mime}`,
  )
}

function waitForEvent(el, event, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      el.removeEventListener(event, onEvent)
      reject(new Error(`${event} 超时`))
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
  append('=== Range / Seek 诊断 ===')

  try {
    if (el.readyState < 1) await waitForEvent(el, 'loadedmetadata')
    append(`duration = ${Number.isFinite(el.duration) ? el.duration.toFixed(2) + 's' : '未知'}`)
    append(`videoWidth×Height = ${el.videoWidth}×${el.videoHeight}`)
    append(`canPlayType("${media.value.mime}") = ${JSON.stringify(el.canPlayType(media.value.mime) || '')}`)

    const duration = el.duration
    if (!Number.isFinite(duration) || duration <= 0) {
      append('无法获知时长，跳过 seek 测试（容器或编码不被当前机器支持）。')
      append('结论：需要外部播放器后端。')
      return
    }

    for (const ratio of [0.25, 0.5, 0.9]) {
      const at = duration * ratio
      const res = await seekTo(el, at)
      append(
        res.ok
          ? `seek → ${at.toFixed(2)}s 成功，用时 ${res.ms.toFixed(0)}ms（实际 ${res.at.toFixed(2)}s）`
          : `seek → ${at.toFixed(2)}s 失败或超时`,
      )
    }

    const samples = []
    for (let i = 0; i < 8; i += 1) {
      const res = await seekTo(el, Math.random() * duration * 0.95)
      if (res.ok) samples.push(res.ms)
    }
    if (samples.length) {
      const avg = samples.reduce((a, b) => a + b, 0) / samples.length
      append(`随机 seek ${samples.length}/8 次成功，平均 ${avg.toFixed(0)}ms，最慢 ${Math.max(...samples).toFixed(0)}ms`)
    } else {
      append('随机 seek 全部失败。')
    }

    append(
      samples.length
        ? '结论：原生 <video> 可播放该容器，分块解密与跳转正常。'
        : '结论：原生 <video> 对该容器支持不足，需要 remux 或外接播放器后端。',
    )
  } catch (err) {
    append(`诊断中断：${err.message}`)
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
