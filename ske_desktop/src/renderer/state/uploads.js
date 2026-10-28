/**
 * Upload queue mirrors the main process, never owns it.
 *
 * Same contract as downloads: the main process pushes snapshots, the state
 * module subscribes once for the whole app, and views read it. Progress is
 * pushed rather than polled because the queue can be encrypting a 4 GiB video
 * while the user browses another folder.
 */
import { reactive } from 'vue'

export const uploads = reactive({
  items: [],
  loading: false,
})

let unsubscribe = null

export function subscribeUploads() {
  const bridge = window.ske?.uploads
  if (!bridge || unsubscribe) return
  unsubscribe = bridge.onUpdate((snapshot) => {
    uploads.items = snapshot
  })
}

export async function refreshUploads() {
  const bridge = window.ske?.uploads
  if (!bridge) return
  uploads.loading = true
  try {
    const res = await bridge.list()
    if (res?.ok) uploads.items = res.uploads
  } finally {
    uploads.loading = false
  }
}

/**
 * @param {{ sessionId: string, path: string, files: { path: string, name?: string }[], encrypt?: boolean }} input
 */
export async function enqueueUploads({ sessionId, path, files, encrypt = true }) {
  // Reactive proxies cannot cross IPC: structured clone rejects a Vue proxy
  // with a bare "An object could not be cloned", which reads like a main
  // process bug. Rebuild the list as plain records first.
  const plainFiles = (files || []).map((file) => ({
    path: file.path,
    name: file.name,
    size: file.size,
  }))

  const res = await window.ske.uploads.add({ sessionId, path, files: plainFiles, encrypt })
  if (!res?.ok) throw new Error(res?.error || '无法加入上传队列')
  if (res.uploads) uploads.items = res.uploads
  return res.jobs
}

export async function cancelUpload(id) {
  const res = await window.ske.uploads.cancel(id)
  if (!res?.ok) throw new Error(res?.error || '取消失败')
  if (res.uploads) uploads.items = res.uploads
}

export async function clearUploads() {
  const res = await window.ske.uploads.clear()
  if (!res?.ok) throw new Error(res?.error || '清理失败')
  if (res.uploads) uploads.items = res.uploads
}

/** Rows that still have work to do. */
export function activeUploads() {
  return uploads.items.filter((item) => item.status === 'queued' || item.status === 'running')
}
