/**
 * Cloud browsing state.
 *
 * The renderer never sees a password or the name key: it asks for a plaintext
 * listing and gets names, sizes and ready-made preview URLs back.
 */
import { reactive } from 'vue'
import { activeProfile, toast } from './session.js'

export const cloud = reactive({
  sessionId: '',
  profileId: null,
  connected: false,
  connecting: false,
  loading: false,
  path: '/',
  entries: [],
  error: '',
})

function reset() {
  cloud.sessionId = ''
  cloud.profileId = null
  cloud.connected = false
  cloud.path = '/'
  cloud.entries = []
  cloud.error = ''
}

/** Connect to the active profile, reusing the session when it already matches. */
export async function ensureConnected({ force = false } = {}) {
  const profile = activeProfile.value
  if (!profile) {
    reset()
    return false
  }
  if (!force && cloud.connected && cloud.profileId === profile.id) return true

  if (cloud.sessionId) {
    window.ske.cloud.disconnect(cloud.sessionId).catch(() => {})
  }
  reset()

  cloud.connecting = true
  try {
    const res = await window.ske.cloud.connect(profile.id)
    if (!res?.ok) throw new Error(res?.error || '连接失败')

    cloud.sessionId = res.session.sessionId
    cloud.profileId = profile.id
    cloud.connected = true
    await load('/')
    return true
  } catch (err) {
    cloud.error = err.message
    cloud.connected = false
    return false
  } finally {
    cloud.connecting = false
  }
}

export async function load(path = cloud.path) {
  if (!cloud.connected) return
  cloud.loading = true
  cloud.error = ''
  try {
    const res = await window.ske.cloud.list(cloud.sessionId, path)
    if (!res?.ok) throw new Error(res?.error || '无法读取目录')
    cloud.path = res.path || '/'
    cloud.entries = res.entries || []
  } catch (err) {
    cloud.error = err.message
    cloud.entries = []
  } finally {
    cloud.loading = false
  }
}

export async function disconnect() {
  if (cloud.sessionId) {
    await window.ske.cloud.disconnect(cloud.sessionId).catch(() => {})
  }
  reset()
}

// ── File-manager writes ──
// Each one re-lists afterwards: an encrypted name is a token the renderer never
// sees, so the authoritative display name has to come back from the server.

export async function makeFolder(path, name, encrypt = true) {
  const res = await window.ske.cloud.mkdir({ sessionId: cloud.sessionId, path, name, encrypt })
  if (!res?.ok) throw new Error(res?.error || '创建文件夹失败')
  await load(path)
  return res
}

export async function renameEntry(path, newName) {
  const res = await window.ske.cloud.rename({ sessionId: cloud.sessionId, path, newName })
  if (!res?.ok) throw new Error(res?.error || '重命名失败')
  await load(cloud.path)
  return res
}

export async function removeEntry(path) {
  const res = await window.ske.cloud.remove({ sessionId: cloud.sessionId, path })
  if (!res?.ok) throw new Error(res?.error || '删除失败')
  await load(cloud.path)
  return res
}

/**
 * Delete several entries in one go.
 *
 * The server deletes one path per call, so this loops and re-lists once
 * afterwards. Failures are collected rather than thrown: a batch that lost one
 * file should still delete the others and tell the caller which path fell over.
 */
export async function removeEntries(paths) {
  const sessionId = cloud.sessionId
  const failed = []
  for (const path of paths) {
    const res = await window.ske.cloud.remove({ sessionId, path })
    if (!res?.ok) failed.push({ path, error: res?.error || '删除失败' })
  }
  await load(cloud.path)
  return failed
}

/** Breadcrumb segments, from the root down to the current folder. */
export function crumbs(path = cloud.path) {
  const parts = path.split('/').filter(Boolean)
  const list = [{ label: '根目录', path: '/' }]
  let acc = ''
  for (const part of parts) {
    acc += `/${part}`
    list.push({ label: part, path: acc })
  }
  return list
}

export function parentOf(path = cloud.path) {
  const parts = path.split('/').filter(Boolean)
  parts.pop()
  return parts.length ? `/${parts.join('/')}` : '/'
}

export function formatSize(bytes) {
  if (!bytes) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

export function playable(entry) {
  return !entry.isDir && !entry.locked && ['video', 'audio', 'image'].includes(entry.kind)
}

export function requirePlayable(entry) {
  if (playable(entry)) return true
  toast(entry.locked ? '该文件无法解密，请检查媒体加密密码' : '暂不支持播放该类型的文件', 'error')
  return false
}
