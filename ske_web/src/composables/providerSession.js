/**
 * providerSession — one cloud session, two providers.
 *
 * `useAList.js` and `useOpenList.js` used to carry the same state machine twice:
 * sessionStorage keys aside, the login flow, the virtual root for sub-accounts,
 * the manual base-path override and the logout cleanup were identical. This
 * factory owns that once; each provider module creates one instance with its
 * own key prefix and label.
 *
 * The provider modules stay separate on purpose: they are the entry points
 * `useRemoteStorage.js` dispatches through, and either one can gain behaviour
 * the other does not have (OpenList's chunked upload is wired in that way).
 */

import {
    login as remoteLogin,
    normalizeBaseUrl,
    list,
    get,
    mkdir,
    rename,
    remove,
    uploadFile as remoteUploadFile,
} from './remoteClient.js'

/**
 * @param {{
 *   keyPrefix: string,
 *   label: string,
 *   supportsMultipart?: boolean,
 *   uploadMultipart?: Function,
 * }} options
 */
export function createProviderSession({
    keyPrefix,
    label,
    supportsMultipart = false,
    uploadMultipart,
}) {
    const key = (name) => `${keyPrefix}_${name}`

    const state = {
        server: '',
        token: '',
        basePath: '',            // manual override from login form
        detectedBasePath: '',    // auto-detected from /api/me
        permissionPaths: [],     // e.g. ["/R2", "/移动家庭云"]
    }

    /** The server and token this session currently points at. */
    function remoteContext() {
        return { server: state.server, token: state.token }
    }

    /** Authenticate. Returns the JWT token. */
    async function login(server, username, password, manualBasePath = '', otpCode = '') {
        state.server = normalizeBaseUrl(server)
        const session = await remoteLogin(server, username, password, otpCode, label)
        state.token = session.token
        state.detectedBasePath = session.detectedBasePath
        state.permissionPaths = session.permissionPaths

        // Manual override
        let manual = manualBasePath || ''
        if (manual && !manual.startsWith('/')) manual = '/' + manual
        state.basePath = manual.replace(/\/+$/, '')

        sessionStorage.setItem(key('server'), state.server)
        sessionStorage.setItem(key('token'), state.token)
        sessionStorage.setItem(key('base_path'), state.basePath)
        sessionStorage.setItem(key('detected_base_path'), state.detectedBasePath)
        sessionStorage.setItem(key('permission_paths'), JSON.stringify(state.permissionPaths))
        return state.token
    }

    /** Restore session from sessionStorage (page refresh). */
    function restoreSession() {
        state.server = sessionStorage.getItem(key('server')) || ''
        state.token = sessionStorage.getItem(key('token')) || ''
        state.basePath = sessionStorage.getItem(key('base_path')) || ''
        state.detectedBasePath = sessionStorage.getItem(key('detected_base_path')) || ''
        try {
            state.permissionPaths = JSON.parse(sessionStorage.getItem(key('permission_paths')) || '[]')
        } catch { state.permissionPaths = [] }
        return !!(state.server && state.token)
    }

    /**
     * For manual basePath only: prepend to all paths.
     * No auto-prepending for detectedBasePath — we use virtual root instead.
     */
    function getAbsPath(path) {
        if (state.basePath) {
            if (path.startsWith(state.basePath)) return path
            const p = path.startsWith('/') ? path : '/' + path
            return (state.basePath + p).replace(/\/+$/, '') || '/'
        }
        return path
    }

    /**
     * List a directory.
     * For sub-accounts with base_path: root '/' returns a VIRTUAL listing
     * constructed from the permissions array (matching the provider's own web UI).
     * The API auto-scopes root for sub-accounts, hiding the storage layer,
     * so we reconstruct it manually.
     */
    async function listDir(path = '/', refresh = false) {
        if (!state.server) restoreSession()

        // Virtual root for sub-accounts: construct from permissions
        if (path === '/' && !state.basePath && state.detectedBasePath && state.permissionPaths.length > 0) {
            console.log(`[${label}] Using virtual root from permissions:`, state.permissionPaths)
            const virtualContent = state.permissionPaths.map(p => {
                // Extract top-level folder name from path like "/R2" or "/移动家庭云"
                const name = p.replace(/^\//, '').split('/')[0]
                return {
                    name,
                    is_dir: true,
                    size: 0,
                    modified: '',
                }
            })
            // Deduplicate by name
            const seen = new Set()
            const unique = virtualContent.filter(e => {
                if (seen.has(e.name)) return false
                seen.add(e.name)
                return true
            })
            return { content: unique, provider: 'virtual' }
        }

        return list(remoteContext(), getAbsPath(path), refresh)
    }

    /** Rename a file or folder. */
    async function renameItem(path, newName) {
        if (!state.server) restoreSession()
        return rename(remoteContext(), getAbsPath(path), newName)
    }

    /** Create a directory. */
    async function makeDir(path) {
        if (!state.server) restoreSession()
        return mkdir(remoteContext(), getAbsPath(path))
    }

    /**
     * Upload a file.
     *
     * @param {(ratio: number) => void} [options.onProgress] 0..1, per file
     */
    async function uploadFile(path, file, asTask = false, options = {}) {
        if (!state.server) restoreSession()
        return remoteUploadFile(remoteContext(), getAbsPath(path), file, {
            asTask,
            onProgress: options.onProgress,
            supportsMultipart,
            uploadMultipart,
        })
    }

    /** Delete files or folders. */
    async function deleteItems(dir, names) {
        if (!state.server) restoreSession()
        return remove(remoteContext(), getAbsPath(dir), names)
    }

    /** Get a direct (signed) download URL, size, and server thumbnail for a file. */
    async function getFileInfo(path) {
        if (!state.server) restoreSession()
        return get(remoteContext(), getAbsPath(path))
    }

    /** Get the current server base URL. */
    function getServer() {
        if (!state.server) restoreSession()
        return state.server
    }

    /** Clear session. */
    function logout() {
        state.server = ''
        state.token = ''
        state.basePath = ''
        state.detectedBasePath = ''
        state.permissionPaths = []
        sessionStorage.removeItem(key('server'))
        sessionStorage.removeItem(key('token'))
        sessionStorage.removeItem(key('base_path'))
        sessionStorage.removeItem(key('detected_base_path'))
        sessionStorage.removeItem(key('permission_paths'))
    }

    return {
        login,
        restoreSession,
        listDir,
        renameItem,
        makeDir,
        uploadFile,
        deleteItems,
        getFileInfo,
        getServer,
        logout,
    }
}
