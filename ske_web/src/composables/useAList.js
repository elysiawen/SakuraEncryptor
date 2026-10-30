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

/** Largest page size AList accepts (anything above 500 is clamped). */
const LIST_MAX_PER_PAGE = 500

/** One `/api/fs/list` request. */
async function listPage(absPath, page, perPage, refresh) {
    const body = { path: absPath, page, per_page: perPage }
    if (refresh) body.refresh = true
    const res = await fetch(`${state.server}/api/fs/list`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify(body),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'Failed to list directory')
    return json.data || {}
}

/**
 * List a directory.
 * For sub-accounts with base_path: root '/' returns a VIRTUAL listing
 * constructed from the permissions array (matching AList's own web UI behavior).
 * AList's API auto-scopes root for sub-accounts, hiding the storage layer,
 * so we reconstruct it manually.
 *
 * AList defaults `per_page` to 200 and silently drops the rest of the folder,
 * so a plain request truncates any directory with more than 200 entries. This
 * walks the pages with the largest accepted page size until `total` is reached.
 */
export async function listDir(path = '/', refresh = false) {
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
    const content = []
    const seen = new Set()
    // Adds only unseen entries, so a server that repeats a page cannot make the
    // loop run to its guard.
    const append = (list) => {
        let added = 0
        for (const entry of list) {
            if (seen.has(entry.name)) continue
            seen.add(entry.name)
            // `/api/fs/list` already carries the preview URL. Drivers return
            // either an absolute CDN link or a site-relative `/p/...` path.
            if (entry.thumb) entry.thumb = absoluteUrl(entry.thumb)
            content.push(entry)
            added += 1
        }
        return added
    }

    // The page size has to stay identical across requests: AList computes the
    // offset as (page - 1) * per_page with the per_page it actually applied, so
    // switching sizes mid-listing would skip or repeat entries.
    let perPage = LIST_MAX_PER_PAGE
    let total = NaN
    let first = {}
    for (let page = 1; page <= 200; page += 1) {
        const data = await listPage(absPath, page, perPage, page === 1 && refresh)
        if (page === 1) first = data
        const chunk = Array.isArray(data.content) ? data.content : []
        const added = append(chunk)
        const reported = Number(data.total)
        if (Number.isFinite(reported)) total = reported

        if (chunk.length === 0 || added === 0) break
        if (Number.isFinite(total) && content.length >= total) break
        // The server applied a smaller page size than requested; match it so the
        // next offset lines up with what it will return.
        if (chunk.length < perPage) perPage = chunk.length
    }

    if (!Number.isFinite(total) || total < content.length) total = content.length
    return {
        ...first,
        content,
        total,
        has_more: content.length < total,
    }
}

/** Rename a file or folder. */
export async function renameItem(path, newName) {
    if (!state.server) restoreSession()
    const absPath = getAbsPath(path)
    const res = await fetch(`${state.server}/api/fs/rename`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ path: absPath, name: newName }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '重命名失败')
    return json.data
}

/** Create a directory. */
export async function makeDir(path) {
    if (!state.server) restoreSession()
    const absPath = getAbsPath(path)
    const res = await fetch(`${state.server}/api/fs/mkdir`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ path: absPath }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '创建文件夹失败')
    return json.data
}

/** Upload a file using AList form upload API. */
export async function uploadFile(path, file, asTask = false) {
    if (!state.server) restoreSession()
    const absPath = getAbsPath(path)
    const normalizedPath = normalizeUploadPath(absPath)
    const formData = new FormData()
    formData.append('file', file, file.name)

    const res = await fetch(`${state.server}/api/fs/form`, {
        method: 'PUT',
        headers: {
            Authorization: state.token,
            'File-Path': normalizedPath,
            ...(asTask ? { 'As-Task': 'true' } : {}),
        },
        body: formData,
    })

    let json = null
    try {
        json = await res.json()
    } catch {
        // Fall through to the generic error below.
    }

    if (!res.ok || json?.code !== 200) {
        throw new Error(json?.message || '上传文件失败')
    }
    return json.data
}

function normalizeUploadPath(path) {
    const trimmed = (path || '').trim()
    if (!trimmed || trimmed === '/') {
        throw new Error('上传文件路径不能为空')
    }
    const withLeadingSlash = trimmed.startsWith('/') ? trimmed : `/${trimmed}`
    return encodeURI(withLeadingSlash)
}

/** Delete files or folders. */
export async function deleteItems(dir, names) {
    if (!state.server) restoreSession()
    const absDir = getAbsPath(dir)
    const res = await fetch(`${state.server}/api/fs/remove`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: state.token,
        },
        body: JSON.stringify({ dir: absDir, names }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '删除失败')
    return json.data
}

/**
 * Make a value AList returned (URL or site-relative path) loadable by the page.
 *
 * `thumb` is driver-dependent: some return a full CDN URL, the local driver
 * returns a `/p/...` path, and anything without a preview returns nothing.
 */
function absoluteUrl(value) {
    const raw = (value || '').trim()
    if (!raw) return ''
    if (/^https?:\/\//i.test(raw)) return raw
    if (raw.startsWith('//')) return `${location.protocol}${raw}`
    return `${state.server}${raw.startsWith('/') ? '' : '/'}${raw}`
}

/** Get a direct (signed) download URL, size, and server thumbnail for a file. */
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
        size: json.data.size,
        // Driver-generated thumbnail (images, and videos when AList has ffmpeg).
        // Empty for storages that cannot produce one, which includes every
        // encrypted container: AList cannot see inside a .ske body.
        thumb: absoluteUrl(json.data.thumb)
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
