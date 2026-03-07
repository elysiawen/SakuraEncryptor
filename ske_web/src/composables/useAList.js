/**
 * useAList — AList REST API composable
 *
 * Wraps authentication and file-listing calls to an AList instance.
 * Server URL and credentials are provided at runtime (login screen).
 */

const state = {
    server: '',
    token: '',
    basePath: '',
}

/** Authenticate with AList.  Returns the JWT token. */
export async function login(server, username, password) {
    state.server = server.replace(/\/+$/, '')
    const res = await fetch(`${state.server}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'AList login failed')
    state.token = json.data.token
    
    // Fetch user profile to get base_path (crucial for sub-accounts)
    try {
        const meRes = await fetch(`${state.server}/api/me`, {
            method: 'GET',
            headers: { 'Authorization': state.token },
        })
        const meJson = await meRes.json()
        if (meJson.code === 200 && meJson.data.base_path) {
            state.basePath = meJson.data.base_path.replace(/\/+$/, '')
        }
    } catch (e) {
        console.warn('[AList] Failed to fetch user profile, assuming root /', e)
        state.basePath = ''
    }

    sessionStorage.setItem('alist_server', state.server)
    sessionStorage.setItem('alist_token', state.token)
    sessionStorage.setItem('alist_base_path', state.basePath)
    return state.token
}

/** Restore session from sessionStorage (page refresh). */
export function restoreSession() {
    state.server = sessionStorage.getItem('alist_server') || ''
    state.token = sessionStorage.getItem('alist_token') || ''
    state.basePath = sessionStorage.getItem('alist_base_path') || ''
    return !!(state.server && state.token)
}

/** Helper to join paths correctly ensuring basePath is prepended if needed */
function getAbsPath(path) {
    if (!state.basePath) return path
    if (path.startsWith(state.basePath)) return path
    const p = path.startsWith('/') ? path : '/' + path
    return (state.basePath + p).replace(/\/+$/, '') || '/'
}

/** List a directory.  Returns { content: FileEntry[], provider: string }. */
export async function listDir(path = '/') {
    if (!state.server) restoreSession()
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
    sessionStorage.removeItem('alist_server')
    sessionStorage.removeItem('alist_token')
    sessionStorage.removeItem('alist_base_path')
}
