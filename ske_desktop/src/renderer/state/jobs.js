/**
 * Encryption queue state.
 *
 * Progress is pushed from the main process rather than polled, so a long encode
 * costs one message per ~150 ms instead of an IPC round-trip per tick.
 */
import { reactive } from 'vue'

export const jobs = reactive({
  items: [],
  loaded: false,
})

let unsubscribe = null

export function subscribeJobs() {
  if (unsubscribe) return unsubscribe
  if (!window.ske?.jobs) return null

  unsubscribe = window.ske.jobs.onUpdate((items) => {
    jobs.items = items
    jobs.loaded = true
  })

  window.ske.jobs.list().then((res) => {
    if (res?.ok) {
      jobs.items = res.jobs
      jobs.loaded = true
    }
  })

  return unsubscribe
}

export function unsubscribeJobs() {
  unsubscribe?.()
  unsubscribe = null
}

export async function addJob({ srcPath, destDir, password, thumbnails }) {
  const res = await window.ske.jobs.add({ srcPath, destDir, password, thumbnails })
  if (!res?.ok) throw new Error(res?.error || '无法加入队列')
  return res.job
}

export async function cancelJob(id) {
  const res = await window.ske.jobs.cancel(id)
  if (!res?.ok) throw new Error(res?.error || '无法取消')
}

export async function clearFinished() {
  const res = await window.ske.jobs.clear()
  if (res?.ok) jobs.items = res.jobs
}

export function percent(job) {
  if (job.status === 'done') return 100
  if (!job.totalBytes) return 0
  return Math.min(100, Math.round((job.doneBytes / job.totalBytes) * 100))
}

export function activeCount(items = jobs.items) {
  return items.filter((job) => ['scanning', 'queued', 'running'].includes(job.status)).length
}
