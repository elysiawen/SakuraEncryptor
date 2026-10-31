/**
 * remoteClient — the protocol layer shared by the AList and OpenList clients.
 *
 * Both backends expose the same `/api` surface (auth/login, me, fs/list,
 * fs/get, fs/mkdir, fs/rename, fs/remove, fs/form), so the request shapes live
 * here once. This module holds no session state and imports no Vue: every call
 * receives the server/token it should use, while `providerSession.js` owns the
 * per-provider session state and storage keys.
 */

/** Error carrying the API `code`; 402 means a TOTP code is required. */
export class RemoteError extends Error {
    constructor(code, message) {
        super(message)
        this.name = 'RemoteError'
        this.code = code
        this.needsOtp = code === 402
    }
}

/** Strip trailing slashes so paths can be appended directly. */
export function normalizeBaseUrl(server) {
    return (server || '').replace(/\/+$/, '')
}

/**
 * A request context: where to send the call and which token to present.
 * @typedef {{ server: string, token: string }} RemoteContext
 */

/** Read a response body, tolerating non-JSON replies. */
export async function readJson(res) {
    try {
        return await res.json()
    } catch {
        return null
    }
}

/**
 * Make a value the API returned (URL or site-relative path) loadable by the page.
 *
 * `thumb` is driver-dependent: some return a full CDN URL, the local driver
 * returns a `/p/...` path, and anything without a preview returns nothing.
 */
export function absoluteUrl(server, value) {
    const raw = (value || '').trim()
    if (!raw) return ''
    if (/^https?:\/\//i.test(raw)) return raw
    if (raw.startsWith('//')) return `${location.protocol}${raw}`
    return `${server}${raw.startsWith('/') ? '' : '/'}${raw}`
}

/**
 * Fetch the user profile (base_path and permission paths).
 *
 * @returns {Promise<{detectedBasePath: string, permissionPaths: string[]}>}
 */
export async function me(server, token) {
    let detectedBasePath = ''
    let permissionPaths = []
    for (const url of [`${server}/api/me`, `${server}/api/auth/me`]) {
        const r = await fetch(url, { headers: { 'Authorization': token } })
        const j = await r.json()
        if (j.code === 200 && j.data) {
            // Detect base_path
            if (j.data.base_path && j.data.base_path !== '/') {
                detectedBasePath = j.data.base_path.replace(/\/+$/, '')
                if (!detectedBasePath.startsWith('/'))
                    detectedBasePath = '/' + detectedBasePath
            }
            // Extract permission paths (top-level accessible directories)
            if (j.data.permissions && Array.isArray(j.data.permissions)) {
                permissionPaths = j.data.permissions
                    .map(p => p.path)
                    .filter(p => p && p !== '/')
            }
            break
        }
    }
    return { detectedBasePath, permissionPaths }
}

/**
 * Authenticate against the backend.  Returns the token and the profile.
 *
 * A 402 answer means the account has TOTP enabled: [RemoteError.needsOtp] lets
 * the caller ask for the code and retry with [otpCode].
 *
 * @param {string} label provider name used in the fallback error text
 */
export async function login(server, username, password, otpCode = '', label = 'AList') {
    const baseUrl = normalizeBaseUrl(server)
    const res = await fetch(`${baseUrl}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            username,
            password,
            ...(otpCode ? { otp_code: otpCode } : {}),
        }),
    })
    const json = await res.json()
    if (json.code !== 200) {
        // 402 means the account has TOTP enabled: the caller asks for a code
        // and retries.
        throw new RemoteError(
            json.code,
            json.code === 402
                ? '该账号已开启两步验证，请输入动态验证码'
                : (json.message || `${label} login failed`)
        )
    }
    const token = json.data.token

    // Fetch user profile to get base_path and permissions
    let detectedBasePath = ''
    let permissionPaths = []
    try {
        ({ detectedBasePath, permissionPaths } = await me(baseUrl, token))
    } catch (e) {
        console.warn(`[${label}] Could not detect user profile`, e)
    }

    return { server: baseUrl, token, detectedBasePath, permissionPaths }
}

/** Largest page size the API accepts (anything above 500 is clamped). */
const LIST_MAX_PER_PAGE = 500

/** One `/api/fs/list` request. */
async function listPage(ctx, absPath, page, perPage, refresh) {
    const body = { path: absPath, page, per_page: perPage }
    if (refresh) body.refresh = true
    const res = await fetch(`${ctx.server}/api/fs/list`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: ctx.token,
        },
        body: JSON.stringify(body),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'Failed to list directory')
    return json.data || {}
}

/**
 * List a directory, walking every page.
 *
 * The API defaults `per_page` to 200 and silently drops the rest of the
 * folder, so a plain request truncates any directory with more than 200
 * entries. This walks the pages with the largest accepted page size until
 * `total` is reached.
 */
export async function list(ctx, absPath, refresh = false) {
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
            if (entry.thumb) entry.thumb = absoluteUrl(ctx.server, entry.thumb)
            content.push(entry)
            added += 1
        }
        return added
    }

    // The page size has to stay identical across requests: the API computes the
    // offset as (page - 1) * per_page with the per_page it actually applied, so
    // switching sizes mid-listing would skip or repeat entries.
    let perPage = LIST_MAX_PER_PAGE
    let total = NaN
    let first = {}
    for (let page = 1; page <= 200; page += 1) {
        const data = await listPage(ctx, absPath, page, perPage, page === 1 && refresh)
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
export async function rename(ctx, absPath, newName) {
    const res = await fetch(`${ctx.server}/api/fs/rename`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: ctx.token,
        },
        body: JSON.stringify({ path: absPath, name: newName }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '重命名失败')
    return json.data
}

/** Create a directory. */
export async function mkdir(ctx, absPath) {
    const res = await fetch(`${ctx.server}/api/fs/mkdir`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: ctx.token,
        },
        body: JSON.stringify({ path: absPath }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '创建文件夹失败')
    return json.data
}

/** Multipart upload is worth its overhead from this size on. */
export const MULTIPART_MIN_BYTES = 32 * 1024 * 1024
/** Flow control answers 429/409; give the server pipeline a moment and retry. */
export const MULTIPART_MAX_RETRIES = 6
export const MULTIPART_FALLBACK_STATUS = new Set([403, 404, 405])

/**
 * Upload a file.
 *
 * Defaults to the classic `PUT /api/fs/form` request. When the caller injects
 * a chunked implementation through [options.uploadMultipart] and the file is
 * large enough, that path runs first; a 403/404/405 answer or a missing
 * upload_id means the server does not support it and the form upload takes
 * over. Every other error is surfaced as-is.
 *
 * @param {RemoteContext} ctx
 * @param {object} [options]
 * @param {boolean} [options.asTask]            ask the server to queue the job
 * @param {(ratio: number) => void} [options.onProgress] 0..1, per file
 * @param {boolean} [options.supportsMultipart] backend capability flag
 * @param {Function} [options.uploadMultipart]  injected chunked implementation
 */
export async function uploadFile(ctx, absPath, file, options = {}) {
    const { asTask = false, onProgress, supportsMultipart = false, uploadMultipart } = options
    if (supportsMultipart && uploadMultipart && file.size >= MULTIPART_MIN_BYTES) {
        try {
            return await uploadMultipart(ctx, absPath, file, { onProgress })
        } catch (err) {
            if (!err.multipartUnsupported) throw err
            // Older/disabled server: use the form API below.
        }
    }
    return uploadForm(ctx, absPath, file, asTask)
}

/** Classic `PUT /api/fs/form` upload. */
async function uploadForm(ctx, absPath, file, asTask = false) {
    const normalizedPath = normalizeUploadPath(absPath)
    const formData = new FormData()
    formData.append('file', file, file.name)

    const res = await fetch(`${ctx.server}/api/fs/form`, {
        method: 'PUT',
        headers: {
            Authorization: ctx.token,
            'File-Path': normalizedPath,
            ...(asTask ? { 'As-Task': 'true' } : {}),
        },
        body: formData,
    })

    const json = await readJson(res)
    if (!res.ok || json?.code !== 200) {
        throw new Error(json?.message || '上传文件失败')
    }
    return json.data
}

export function normalizeUploadPath(path) {
    const trimmed = (path || '').trim()
    if (!trimmed || trimmed === '/') {
        throw new Error('上传文件路径不能为空')
    }
    const withLeadingSlash = trimmed.startsWith('/') ? trimmed : `/${trimmed}`
    return encodeURI(withLeadingSlash)
}

/** Delete files or folders. */
export async function remove(ctx, absDir, names) {
    const res = await fetch(`${ctx.server}/api/fs/remove`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: ctx.token,
        },
        body: JSON.stringify({ dir: absDir, names }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '删除失败')
    return json.data
}

/** Get a direct (signed) download URL, size, and server thumbnail for a file. */
export async function get(ctx, absPath) {
    const res = await fetch(`${ctx.server}/api/fs/get`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: ctx.token,
        },
        body: JSON.stringify({ path: absPath }),
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || 'Failed to get file info')
    return {
        url: json.data.raw_url,
        size: json.data.size,
        // Driver-generated thumbnail (images, and videos when the backend has
        // ffmpeg). Empty for storages that cannot produce one, which includes
        // every encrypted container: the backend cannot see inside a .ske body.
        thumb: absoluteUrl(ctx.server, json.data.thumb)
    }
}
