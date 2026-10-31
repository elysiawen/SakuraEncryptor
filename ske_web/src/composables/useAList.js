/**
 * useAList — AList backend entry point.
 *
 * AList and OpenList share the same `/api` surface and the same session
 * plumbing, so the request shapes live in `remoteClient.js` and the login /
 * restore / virtual-root logic in `providerSession.js`. This module exists so
 * AList keeps its own entry point: anything AList-specific belongs here and
 * only here. Today that is just the form-only upload.
 *
 * The provider is chosen by `useRemoteStorage.js`.
 *
 * Server URL and credentials are provided at runtime (login screen).
 */

import { createProviderSession } from './providerSession.js'

/** AList keeps its session under `alist_*` keys. */
const session = createProviderSession({
    keyPrefix: 'alist',
    label: 'AList',
})

/** Upload a file using the AList form upload API (no chunked endpoint). */
export function uploadFile(path, file, asTask = false) {
    return session.uploadFile(path, file, asTask)
}

export const {
    login,
    restoreSession,
    listDir,
    renameItem,
    makeDir,
    deleteItems,
    getFileInfo,
    getServer,
    logout,
} = session
