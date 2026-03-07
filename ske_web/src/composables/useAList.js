/**
 * useAList — AList REST API composable
 *
 * Wraps authentication and file-listing calls to an AList instance.
 * Server URL and credentials are provided at runtime (login screen).
 */

const state = {
    server: '',
    token: '',
    basePath: '',            // manual override from login form
    detectedBasePath: '',    // auto-detected from /api/me
    permissionPaths: [],     // e.g. ["/R2", "/移动家庭云"]
}

/** Authenticate with AList.  Returns the JWT token. */
export async function login(server, username, password, manualBasePath = '') {
    state.server = server.replace(/\/+$/, '')
    const res = await fetch(`${state.server}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'AList login failed')
    state.token = json.data.token
    
    // Fetch user profile to get base_path and permissions
    state.detectedBasePath = ''
    state.permissionPaths = []
    try {
        for (const url of [`${state.server}/api/me`, `${state.server}/api/auth/me`]) {
            const r = await fetch(url, { headers: { 'Authorization': state.token } })
            const j = await r.json()
            if (j.code === 200 && j.data) {
                // Detect base_path
                if (j.data.base_path && j.data.base_path !== '/') {
                    state.detectedBasePath = j.data.base_path.replace(/\/+$/, '')
                    if (!state.detectedBasePath.startsWith('/'))
                        state.detectedBasePath = '/' + state.detectedBasePath
                }
                // Extract permission paths (top-level accessible directories)
                if (j.data.permissions && Array.isArray(j.data.permissions)) {
                    state.permissionPaths = j.data.permissions
                        .map(p => p.path)
                        .filter(p => p && p !== '/')
                }
                break
            }
        }
    } catch (e) {
        console.warn('[AList] Could not detect user profile', e)
    }

    // Manual override
    let manual = manualBasePath || ''
    if (manual && !manual.startsWith('/')) manual = '/' + manual
    state.basePath = manual.replace(/\/+$/, '')

    sessionStorage.setItem('alist_server', state.server)
    sessionStorage.setItem('alist_token', state.token)
    sessionStorage.setItem('alist_base_path', state.basePath)
    sessionStorage.setItem('alist_detected_base_path', state.detectedBasePath)
    sessionStorage.setItem('alist_permission_paths', JSON.stringify(state.permissionPaths))
    return state.token
}

/** Restore session from sessionStorage (page refresh). */
export function restoreSession() {
    state.server = sessionStorage.getItem('alist_server') || ''
    state.token = sessionStorage.getItem('alist_token') || ''
    state.basePath = sessionStorage.getItem('alist_base_path') || ''
    state.detectedBasePath = sessionStorage.getItem('alist_detected_base_path') || ''
    try {
        state.permissionPaths = JSON.parse(sessionStorage.getItem('alist_permission_paths') || '[]')
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
 * constructed from the permissions array (matching AList's own web UI behavior).
 * AList's API auto-scopes root for sub-accounts, hiding the storage layer,
 * so we reconstruct it manually.
 */
export async function listDir(path = '/') {
    if (!state.server) restoreSession()

    // Virtual root for sub-accounts: construct from permissions
    if (path === '/' && !state.basePath && state.detectedBasePath && state.permissionPaths.length > 0) {
        console.log('[AList] Using virtual root from permissions:', state.permissionPaths)
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

    const absPath = getAbsPath(path)
    const res = await fetch(`${state.server}/api/fs/list`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ path: absPath, refresh: false }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'Failed to list directory')
    return json.data
}

/** Get a direct (signed) download URL and size for a file. */
export async function getFileInfo(path) {
    if (!state.server) restoreSession()
    const absPath = getAbsPath(path)
    const res = await fetch(`${state.server}/api/fs/get`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ path: absPath }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'Failed to get file info')
    return {
        url: json.data.raw_url,
        size: json.data.size
    }
}

/** Get the current AList server base URL. */
export function getServer() {
    if (!state.server) restoreSession()
    return state.server
}

/** Clear session. */
export function logout() {
    state.server = ''
    state.token = ''
    state.basePath = ''
    state.detectedBasePath = ''
    state.permissionPaths = []
    sessionStorage.removeItem('alist_server')
    sessionStorage.removeItem('alist_token')
    sessionStorage.removeItem('alist_base_path')
    sessionStorage.removeItem('alist_detected_base_path')
    sessionStorage.removeItem('alist_permission_paths')
}
