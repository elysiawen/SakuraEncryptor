/**
 * useFileDownload — Shared helpers for building plaintext download targets.
 *
 * Encrypted files must be fetched through the Service Worker's decrypt proxy
 * (see useDecryptProxy), otherwise the user only gets the `.ske` container.
 * The `download=1` flag switches the proxy into whole-file streaming mode,
 * because the playback path caps every response at 5 MiB.
 *
 * Plaintext files are downloaded straight from their upstream URL.
 */
import { buildDecryptProxyUrl } from './useDecryptProxy.js'

export { buildDecryptProxyUrl }

export const DOWNLOAD_PARAM = 'download=1'

/** Whether a stored filename denotes an encrypted (.ske) file. */
export function isEncryptedFileName(name) {
    return !!name && name.endsWith('.ske')
}

/** Append the whole-file download flag, preserving any existing query string. */
export function toDownloadUrl(url) {
    if (!url) return ''
    const sep = url.includes('?') ? '&' : '?'
    return `${url}${sep}${DOWNLOAD_PARAM}`
}
