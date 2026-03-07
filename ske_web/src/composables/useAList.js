/**
 * useAList — AList REST API composable
 *
 * Wraps authentication and file-listing calls to an AList instance.
 * Server URL and credentials are provided at runtime (login screen).
 */

const state = {
    server: '',
    token: '',
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
    sessionStorage.setItem('alist_server', state.server)
    sessionStorage.setItem('alist_token', state.token)
    return state.token
}

/** Restore session from sessionStorage (page refresh). */
export function restoreSession() {
    state.server = sessionStorage.getItem('alist_server') || ''
    state.token = sessionStorage.getItem('alist_token') || ''
    return !!(state.server && state.token)
}

/** List a directory.  Returns { content: FileEntry[], provider: string }. */
export async function listDir(path = '/') {
    if (!state.server) restoreSession()
    const res = await fetch(`${state.server}/api/fs/list`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ path, refresh: false }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'Failed to list directory')
    return json.data
}

/** Get a direct (signed) download URL and size for a file. */
export async function getFileInfo(path) {
    if (!state.server) restoreSession()
    const res = await fetch(`${state.server}/api/fs/get`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ path }),
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
    sessionStorage.removeItem('alist_server')
    sessionStorage.removeItem('alist_token')
}
