/**
 * Local library state. The local password is independent of any cloud profile.
 */
import { reactive } from 'vue'

export const local = reactive({
  unlocked: false,
  root: '',
  path: '/',
  entries: [],
  loading: false,
  error: '',
})

export async function refreshLocalStatus() {
  const res = await window.ske.local.status()
  if (!res?.ok) return
  local.unlocked = !!res.unlocked
  local.root = res.root || ''
}

export async function unlockLocal(password) {
  const res = await window.ske.local.unlock(password)
  if (!res?.ok) throw new Error(res?.error || '解锁失败')
  local.unlocked = true
  local.root = res.root || ''
  if (local.root) await loadLocal('/')
}

export async function lockLocal() {
  await window.ske.local.lock()
  local.unlocked = false
  local.root = ''
  local.path = '/'
  local.entries = []
}

/** Point the library at an absolute directory (picked, or handed over by the OS). */
export async function setLocalRoot(dir) {
  const res = await window.ske.local.setRoot(dir)
  if (!res?.ok) throw new Error(res?.error || '无法打开该目录')
  local.root = res.root
  await loadLocal('/')
  return res.root
}

export async function chooseRoot() {
  const picked = await window.ske.dialog.pickDirectory()
  if (!picked?.ok || !picked.path) return false

  await setLocalRoot(picked.path)
  return true
}

export async function loadLocal(path = '/') {
  if (!local.unlocked || !local.root) return
  local.loading = true
  local.error = ''
  try {
    const res = await window.ske.local.list(path)
    if (!res?.ok) throw new Error(res?.error || '无法读取目录')
    local.path = res.path
    local.entries = res.entries
  } catch (err) {
    local.error = err.message
    local.entries = []
  } finally {
    local.loading = false
  }
}

// ── File-manager writes ──
// Same operations as the cloud library, applied to the selected directory. The
// listing is re-read afterwards so encrypted names come back decrypted.

export async function makeFolder(path, name, encrypt = true) {
  const res = await window.ske.local.mkdir({ path, name, encrypt })
  if (!res?.ok) throw new Error(res?.error || '创建文件夹失败')
  await loadLocal(path)
  return res
}

export async function renameEntry(path, newName) {
  const res = await window.ske.local.rename({ path, newName })
  if (!res?.ok) throw new Error(res?.error || '重命名失败')
  await loadLocal(local.path)
  return res
}

export async function removeEntry(path) {
  const res = await window.ske.local.remove({ path })
  if (!res?.ok) throw new Error(res?.error || '删除失败')
  await loadLocal(local.path)
  return res
}

/**
 * Delete several entries in one go.
 *
 * The main process removes one path per call, so this loops and re-reads the
 * listing once afterwards. Failures are collected rather than thrown: a batch
 * that lost one file should still delete the others and tell the caller which
 * path fell over.
 */
export async function removeEntries(paths) {
  const failed = []
  for (const path of paths) {
    const res = await window.ske.local.remove({ path })
    if (!res?.ok) failed.push({ path, error: res?.error || '删除失败' })
  }
  await loadLocal(local.path)
  return failed
}

export function localCrumbs(path = local.path) {
  const parts = path.split('/').filter(Boolean)
  const list = [{ label: '根目录', path: '/' }]
  let acc = ''
  for (const part of parts) {
    acc += `/${part}`
    list.push({ label: part, path: acc })
  }
  return list
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
