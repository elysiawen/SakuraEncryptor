/**
 * Renderer-side state for the built-in server.
 *
 * The main process owns the real lifecycle; this module only mirrors the latest
 * snapshot (pushed via `embedded:update` while a download or a start is in
 * flight) and wraps the calls so every view shares one error surface.
 */
import { reactive } from 'vue'
import { session, toast } from './session.js'

const api = window.ske

export const embedded = reactive({
  /** Latest snapshot from main; null until the first load finishes. */
  state: null,
  busy: false,
  error: '',
  storages: [],
  /** Driver names the local server can mount. */
  drivers: [],
  account: { username: '' },
  revealedPassword: '',
  logs: '',
})

function unwrap(res, fallback) {
  if (!res?.ok) throw new Error(res?.error || fallback)
  return res
}

export const STATUS_LABELS = {
  stopped: '已停止',
  downloading: '下载中',
  starting: '启动中',
  running: '运行中',
  error: '出错',
}

export function statusLabel(state) {
  if (!state) return '未启用'
  return STATUS_LABELS[state.status] || '未启用'
}

/** One sentence for the badge's neighbours. */
export function statusDetail(state) {
  if (!state) return '正在读取状态…'
  if (state.status === 'running') return `${state.backendLabel} 运行中 · ${state.origin}`
  if (state.status === 'error') return state.error || '启动失败'
  if (state.status === 'stopped') {
    return state.enabled ? '已开启，但服务器没有在运行' : '未运行，开启后即可使用'
  }
  return '正在准备服务器…'
}

export function progressPercent(state) {
  const progress = state?.progress
  if (!progress || progress.phase !== 'download' || !progress.total) return 0
  return Math.min(100, Math.round((progress.received / progress.total) * 100))
}

export function progressText(state) {
  const progress = state?.progress
  if (!progress) return ''
  if (progress.phase === 'extract') return '正在解压安装包…'
  if (progress.phase === 'done') return '安装完成'
  if (progress.phase === 'download') {
    return `下载中 ${progressPercent(state)}%（${formatSize(progress.received)} / ${formatSize(progress.total)}）`
  }
  return ''
}

export function formatSize(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(0)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

/** Mirrors the profile list too: the embedded profile lives in the vault. */
function adopt(res) {
  if (res?.profiles) {
    session.profiles = res.profiles
    session.activeId = res.activeId
  }
  if (res?.state) embedded.state = res.state
}

/**
 * Resolve once the built-in server is running.
 *
 * The window is interactive before the server is: main fires `restore()` and
 * then creates the window without waiting, so the first connect of an embedded
 * profile is answered with "connection refused" and the user has to press 重试.
 * Polling the snapshot here turns a warm start into a short "正在启动…", and
 * reports the real reason when it cannot come up at all.
 */
export async function waitUntilRunning({ timeout = 30_000, downloadTimeout = 10 * 60_000, interval = 250 } = {}) {
  if (!api?.embedded?.state) return

  let deadline = Date.now() + timeout
  for (;;) {
    let current = null
    try {
      const res = await api.embedded.state()
      if (res?.ok) current = res.state
    } catch {
      // A single failed snapshot is not a verdict; keep trying until the deadline.
    }
    if (current) embedded.state = current

    if (current?.status === 'running') return
    // Nothing is bringing it up: waiting would only delay the explanation.
    if (current && !current.enabled) throw new Error('内置服务器没有开启，到设置里开启后再试')
    if (current?.status === 'error') throw new Error(current.error || '内置服务器启动失败')
    // A first-run download is not a hang: give it its own budget.
    if (current?.status === 'downloading') deadline = Math.max(deadline, Date.now() + downloadTimeout)
    if (Date.now() >= deadline) throw new Error('内置服务器启动超时，请稍后再试')

    await new Promise((resolve) => setTimeout(resolve, interval))
  }
}

export async function loadEmbedded() {
  if (!api?.embedded) return
  try {
    const res = unwrap(await api.embedded.state(), '无法读取内置服务器状态')
    embedded.state = res.state
  } catch (err) {
    embedded.error = err.message
  }
}

/** Subscribe to status/progress pushes. Returns an unsubscribe function. */
export function bindEmbedded() {
  if (!api?.embedded?.onUpdate) return () => {}
  return api.embedded.onUpdate((state) => {
    embedded.state = state
  })
}

async function run(fn, { success, failure }) {
  if (embedded.busy) return null
  embedded.busy = true
  embedded.error = ''
  try {
    const res = await fn()
    const payload = unwrap(res, failure)
    adopt(payload)
    if (success) toast(success, 'ok')
    return payload
  } catch (err) {
    embedded.error = err.message
    toast(err.message, 'error')
    return null
  } finally {
    embedded.busy = false
  }
}

/**
 * Driver names are cached, and AList and OpenList do not ship the same set:
 * whatever is cached belongs to the backend that was running when it was read.
 */
function forgetDrivers() {
  embedded.drivers = []
}

export async function enableEmbedded(backendId) {
  const res = await run(() => api.embedded.enable(backendId), {
    success: '内置服务器已启动',
    failure: '开启内置服务器失败',
  })
  if (res) forgetDrivers()
  return res
}

export function disableEmbedded() {
  return run(() => api.embedded.disable(), { failure: '关闭内置服务器失败' })
}

export async function reinstallEmbedded(backendId) {
  const res = await run(() => api.embedded.reinstall(backendId), { failure: '重新下载失败' })
  if (res) forgetDrivers()
  return res
}

export async function setEmbeddedBackend(backendId) {
  const res = await run(() => api.embedded.setBackend(backendId), { failure: '切换后端失败' })
  if (res) forgetDrivers()
  return res
}

export function setEmbeddedPort(port) {
  return run(() => api.embedded.setPort(port), {
    success: '端口已更新',
    failure: '修改端口失败',
  })
}

export function setEmbeddedVersion(version) {
  return run(() => api.embedded.setVersion(version), { failure: '保存版本失败' })
}

export function setEmbeddedMirror(mirror) {
  return run(() => api.embedded.setMirror(mirror), { failure: '保存镜像失败' })
}

export function uninstallEmbedded(backendId) {
  return run(() => api.embedded.uninstall(backendId), {
    success: '已删除内置服务器及其数据',
    failure: '删除失败',
  })
}

export async function loadAccount() {
  try {
    const res = unwrap(await api.embedded.account(), '无法读取账号')
    embedded.account = res.account
    embedded.revealedPassword = ''
  } catch (err) {
    embedded.error = err.message
  }
}

export function updateEmbeddedAccount(patch) {
  return run(() => api.embedded.updateAccount(patch), {
    success: '账号已更新',
    failure: '修改账号失败',
  })
}

export async function revealEmbeddedPassword() {
  try {
    const res = unwrap(await api.embedded.revealPassword(), '无法读取密码')
    embedded.revealedPassword = res.password
    return res.password
  } catch (err) {
    embedded.error = err.message
    return ''
  }
}

export function randomEmbeddedPassword() {
  return api.embedded.randomPassword().then((res) => (res.ok ? res.password : ''))
}

export async function loadStorages() {
  try {
    const res = unwrap(await api.embedded.storages(), '无法读取存储列表')
    embedded.storages = res.storages
  } catch (err) {
    embedded.error = err.message
  }
}

/** Driver names the local server can mount; cached until the dialog reopens. */
export async function loadDrivers() {
  if (!api?.embedded?.drivers) return []
  try {
    const res = unwrap(await api.embedded.drivers(), '无法读取驱动列表')
    embedded.drivers = res.drivers
    return res.drivers
  } catch (err) {
    embedded.error = err.message
    return []
  }
}

/**
 * `fetch failed` is what Node says when the server is simply not there, and it
 * tells a user nothing. The server can die under the app (AList does), so the
 * one thing the message must do is point at that.
 */
function describeDriverError(err) {
  const message = err?.message || String(err || '')
  if (/fetch failed|ECONNREFUSED|socket hang up|ECONNRESET/i.test(message)) {
    return '内置服务器没有在运行（可能已退出），到设置里重新开启后再试'
  }
  return message || '内置服务器没有响应'
}

/**
 * Field definitions for one driver.
 *
 * Not cached in the shared state: the "add" and "edit" forms can be open on two
 * different drivers at once, so each keeps its own copy.
 *
 * Throws instead of returning an empty list: a blank form is indistinguishable
 * from "this driver needs no settings", and the user then submits a mount
 * without the credentials the server asked for.
 */
export async function fetchDriverInfo(driver) {
  if (!driver) return { common: [], additional: [] }
  try {
    const res = unwrap(await api.embedded.driverInfo(driver), '无法读取驱动配置')
    return res.info
  } catch (err) {
    const message = describeDriverError(err)
    embedded.error = message
    throw new Error(message)
  }
}

async function storageCall(fn, failure) {
  embedded.busy = true
  embedded.error = ''
  try {
    const res = unwrap(await fn(), failure)
    embedded.storages = res.storages
    return true
  } catch (err) {
    embedded.error = err.message
    toast(err.message, 'error')
    return false
  } finally {
    embedded.busy = false
  }
}

export function createEmbeddedStorage(payload) {
  return storageCall(() => api.embedded.createStorage(payload), '新增存储失败')
}

export function updateEmbeddedStorage(payload) {
  return storageCall(() => api.embedded.updateStorage(payload), '修改存储失败')
}

export function deleteEmbeddedStorage(id) {
  return storageCall(() => api.embedded.deleteStorage(id), '删除存储失败')
}

export function setEmbeddedStorageEnabled(id, enabled) {
  return storageCall(() => api.embedded.setStorageEnabled(id, enabled), '切换存储状态失败')
}

export async function loadEmbeddedLogs() {
  try {
    const res = unwrap(await api.embedded.logs(), '无法读取日志')
    embedded.logs = res.logs
  } catch (err) {
    embedded.error = err.message
  }
}
