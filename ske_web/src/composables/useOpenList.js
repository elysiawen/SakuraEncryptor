/**
 * useOpenList — OpenList backend entry point.
 *
 * OpenList is a community fork of AList and keeps the same `/api` surface, so
 * the request shapes live in `remoteClient.js` and the login / restore /
 * virtual-root logic in `providerSession.js`. This module exists so OpenList
 * keeps its own entry point and owns what the fork adds: the chunked upload
 * wired in from `openListUpload.js`, plus its own `openlist_*` session keys.
 *
 * The provider is chosen by `useRemoteStorage.js`.
 *
 * Server URL and credentials are provided at runtime (login screen).
 */

import { createProviderSession } from './providerSession.js'
import { uploadMultipart } from './openListUpload.js'

/** OpenList keeps its session under `openlist_*` keys. */
const session = createProviderSession({
    keyPrefix: 'openlist',
    label: 'OpenList',
    supportsMultipart: true,
    uploadMultipart,
})

/**
 * Upload a file.
 *
 * Large files go through OpenList's chunked upload (`openListUpload.js`); the
 * shared layer falls back to the classic form upload when the server does not
 * have the feature.
 *
 * @param {object} [options]
 * @param {(ratio: number) => void} [options.onProgress] 0..1, per file
 */
export function uploadFile(path, file, asTask = false, options = {}) {
    return session.uploadFile(path, file, asTask, options)
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
