/**
 * Download queue mirrors the main process, never owns it.
 *
 * Progress arrives as pushed snapshots rather than polling: a 4 GiB container
 * would otherwise need an IPC round-trip per percent. The first snapshot can
 * arrive before a view mounts, so the state module subscribes once for the
 * whole app and views simply read it.
 */
import { reactive } from 'vue'
import { t } from '../i18n.js'
import { patchSettings, session } from './session.js'

export const downloads = reactive({
  items: [],
  loading: false,
})

let unsubscribe = null

export function subscribeDownloads() {
  const bridge = window.ske?.downloads
  if (!bridge || unsubscribe) return
  unsubscribe = bridge.onUpdate((snapshot) => {
    downloads.items = snapshot
  })
}

export async function refreshDownloads() {
  const bridge = window.ske?.downloads
  if (!bridge) return
  downloads.loading = true
  try {
    const res = await bridge.list()
    if (res?.ok) downloads.items = res.downloads
  } finally {
    downloads.loading = false
  }
}

/**
 * Ask where to put plaintext once, then remember it.
 *
 * Downloading is the only flow that writes decrypted bytes to disk, so the
 * first one asks instead of silently picking a folder the user never saw.
 */
async function ensureDestDir() {
  const saved = session.settings?.download?.dir
  if (saved) return saved

  const picked = await window.ske.dialog.pickDirectory()
  if (!picked?.ok || !picked.path) throw new Error(t('errors.downloadCancelledNoDir'))

  await patchSettings({ download: { dir: picked.path } })
  return picked.path
}

export async function enqueueDownload({ mode = 'cloud', sessionId = '', path, name }) {
  const destDir = await ensureDestDir()
  const res = await window.ske.downloads.add({ mode, sessionId, path, name, destDir })
  if (!res?.ok) throw new Error(res?.error || t('errors.enqueueDownloadFailed'))
  if (res.downloads) downloads.items = res.downloads
  return res.download
}

export async function cancelDownload(id) {
  const res = await window.ske.downloads.cancel(id)
  if (!res?.ok) throw new Error(res?.error || t('errors.cancelFailed'))
  if (res.downloads) downloads.items = res.downloads
}

export async function clearDownloads() {
  const res = await window.ske.downloads.clear()
  if (!res?.ok) throw new Error(res?.error || t('errors.cleanFailed'))
  if (res.downloads) downloads.items = res.downloads
}

export async function revealDownload(id) {
  const res = await window.ske.downloads.reveal(id)
  if (!res?.ok) throw new Error(res?.error || t('errors.openFolderFailed'))
}

/** Finished rows: drop the record only, or the downloaded file with it too. */
export async function forgetDownload(id) {
  const res = await window.ske.downloads.forget(id)
  if (!res?.ok) throw new Error(res?.error || t('errors.cleanFailed'))
  if (res.downloads) downloads.items = res.downloads
}

export async function deleteDownloadFile(id) {
  const res = await window.ske.downloads.deleteFile(id)
  if (!res?.ok) throw new Error(res?.error || t('errors.deleteFileFailed'))
  if (res.downloads) downloads.items = res.downloads
}
