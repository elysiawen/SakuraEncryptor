/**
 * remoteClient — the protocol layer shared by the AList and OpenList clients.
 *
 * Both backends expose the same `/api` surface (auth/login, me, fs/list,
 * fs/get, fs/mkdir, fs/rename, fs/remove, fs/form), so the request shapes live
 * here once. This module holds no session state and imports no Vue: every call
 * receives the server/token it should use, while `providerSession.js` owns the
 * per-provider session state and storage keys.
 */

import { t } from '../i18n/index.js'

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
                ? t('errors.otpRequired')
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
    if (json.code !== 200) throw new Error(json.message || t('errors.renameFailed'))
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
    if (json.code !== 200) throw new Error(json.message || t('errors.mkdirFailed'))
    return json.data
}

/** Multipart upload is worth its overhead from this size on. */
export const MULTIPART_MIN_BYTES = 32 * 1024 * 1024
/** Flow control answers 429/409; give the server pipeline a moment and retry. */
export const MULTIPART_MAX_RETRIES = 6
export const MULTIPART_FALLBACK_STATUS = new Set([403, 404, 405])

/** How often the server's own upload task is polled, in milliseconds. */
const TASK_POLL_MS = 700
/** Task states that mean "no further progress is coming". */
const TASK_FAILED_STATES = new Set(['failed', 'canceled', 'cancelled', 'errored'])
const TASK_DONE_STATES = new Set(['succeeded', ...TASK_FAILED_STATES])

function clamp01(value) {
    const number = Number(value)
    if (!Number.isFinite(number)) return 0
    return Math.min(1, Math.max(0, number))
}

/** Human size, for the one message that talks about the file itself. */
function formatBytes(bytes) {
    const value = Number(bytes) || 0
    if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GB`
    if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`
    return `${Math.max(1, Math.round(value / 1024))} KB`
}

/**
 * Upload a file.
 *
 * Progress is reported as `{ sent, stored, phase }`, every ratio in 0..1:
 *
 *  - `sent`   how much of the file the server has taken delivery of;
 *  - `stored` how much it reports having written through to the storage
 *             backend, or `null` while that is unknowable;
 *  - `phase`  `'uploading'` (the body is moving) or `'storing'` (delivered,
 *             and the server is still writing to the cloud).
 *
 * The two differ because AList relays the upload to the driver: on a throttled
 * server the cloud write outlives the transfer that fed it, so the transfer
 * finishing says nothing about the file being there yet.
 *
 * When the caller injects a chunked implementation through
 * [options.uploadMultipart] and the file is large enough, that path runs first;
 * a 403/404/405 answer or a missing upload_id means the server does not support
 * it and the form upload takes over. Every other error is surfaced as-is.
 *
 * @param {RemoteContext} ctx
 * @param {object} [options]
 * @param {boolean} [options.asTask]            ask the server to queue the job
 * @param {(progress: {sent: number, stored: number|null, phase: string}) => void} [options.onProgress]
 * @param {boolean} [options.supportsMultipart] backend capability flag
 * @param {Function} [options.uploadMultipart]  injected chunked implementation
 */
export async function uploadFile(ctx, absPath, file, options = {}) {
    const { asTask = true, onProgress, supportsMultipart = false, uploadMultipart } = options
    if (supportsMultipart && uploadMultipart && file.size >= MULTIPART_MIN_BYTES) {
        try {
            return await uploadMultipart(ctx, absPath, file, { onProgress })
        } catch (err) {
            if (!err.multipartUnsupported) throw err
            // Older/disabled server: use the form API below.
        }
    }
    return uploadForm(ctx, absPath, file, { onProgress, asTask })
}

/**
 * Classic `PUT /api/fs/form` upload, with the two halves reported separately.
 *
 * `XMLHttpRequest` rather than `fetch` because `upload.onprogress` is the only
 * way a browser says how much of a request body has gone out — `fetch` reports
 * nothing until the whole thing is done, which is what left the bar parked.
 *
 * `As-Task: true` then asks the server to do the storage write as a background
 * task and hand back its id, and that task is what makes the second half
 * observable: the server's own progress towards the cloud.
 */
async function uploadForm(ctx, absPath, file, { onProgress, asTask = true } = {}) {
    const normalizedPath = normalizeUploadPath(absPath)
    const formData = new FormData()
    formData.append('file', file, file.name)

    let sentAny = false
    const report = (progress) => {
        if (progress.sent > 0) sentAny = true
        onProgress?.(progress)
    }

    let json
    try {
        json = await sendForm(ctx, normalizedPath, formData, { asTask, onProgress: report })
    } catch (err) {
        // Status 0 means nothing came back at all — typically a CORS preflight
        // that refused one of the headers. Not a byte has been sent in that
        // case, so dropping `As-Task` and trying again costs nothing.
        if (sentAny || !asTask || err.status !== 0) throw err
        json = await sendForm(ctx, normalizedPath, formData, { asTask: false, onProgress: report })
    }

    onProgress?.({ sent: 1, stored: null, phase: 'uploading' })

    const taskId = json?.data?.task?.id
    if (!taskId) {
        // Synchronous write: the answer only exists once the file is stored.
        onProgress?.({ sent: 1, stored: 1, phase: 'storing' })
        return json.data
    }

    onProgress?.({ sent: 1, stored: null, phase: 'storing' })
    await followUploadTask(ctx, taskId, onProgress)
    return json.data
}

/**
 * One `PUT /api/fs/form` attempt, reporting how much of the body has gone out.
 *
 * `XMLHttpRequest` rather than `fetch` because `upload.onprogress` is the only
 * way a browser says how much of a request body has left — `fetch` stays silent
 * until the whole thing is done, which is what left the bar parked at zero.
 *
 * An error with `status === 0` means no response at all (network, or a refused
 * preflight); every other number came from the server.
 */
function sendForm(ctx, normalizedPath, formData, { asTask, onProgress }) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest()
        xhr.open('PUT', `${ctx.server}/api/fs/form`)
        xhr.setRequestHeader('Authorization', ctx.token)
        xhr.setRequestHeader('File-Path', normalizedPath)
        if (asTask) xhr.setRequestHeader('As-Task', 'true')

        // The totals include the multipart framing, so this is the body the
        // server is actually receiving — the same thing the bar is about.
        xhr.upload.onprogress = (event) => {
            if (!event.lengthComputable) return
            onProgress?.({
                sent: clamp01(event.loaded / Math.max(1, event.total)),
                stored: null,
                phase: 'uploading',
            })
        }

        xhr.onload = () => {
            let body = null
            try {
                body = JSON.parse(xhr.responseText)
            } catch {
                body = null
            }
            if (xhr.status < 200 || xhr.status >= 300 || body?.code !== 200) {
                reject(uploadError(xhr.status, body, formData.get('file')?.size || 0))
                return
            }
            resolve(body)
        }

        xhr.onerror = () => {
            const err = new Error(t('errors.remoteUploadFailed'))
            err.status = 0
            reject(err)
        }

        xhr.send(formData)
    })
}

/**
 * The error for a status the server chose itself.
 *
 * 413 comes from the proxy in front of AList, not from AList: the name of the
 * limit is worth saying, because the fix is on the server, not here.
 */
function uploadError(status, json, size) {
    if (status === 413) {
        return new Error(t('errors.uploadTooLarge', { size: formatBytes(size) }))
    }
    return new RemoteError(
        json?.code ?? status,
        json?.message || t('errors.remoteUploadFailed'),
    )
}

/**
 * Watch one upload task until the server is done with it.
 *
 * A task that cannot be read (older build, or a fork without the endpoint) must
 * not fail an upload the server already accepted: the bytes are delivered, we
 * simply have no storage figure to show.
 */
async function followUploadTask(ctx, tid, onProgress) {
    const query = `${ctx.server}/api/task/upload/info?tid=${encodeURIComponent(tid)}`
    const headers = { Authorization: ctx.token, 'Content-Type': 'application/json' }

    for (;;) {
        await new Promise((resolve) => setTimeout(resolve, TASK_POLL_MS))

        let data
        try {
            const res = await fetch(query, { method: 'POST', headers, body: '{}' })
            const json = await readJson(res)
            if (json?.code !== 200) return
            data = json.data
        } catch {
            return
        }

        // Documented as an array; some builds answer with the task alone.
        const task = Array.isArray(data) ? data.find((item) => item?.id === tid) : data?.task || data
        if (!task) return

        const progress = clamp01(Number(task.progress) / 100)
        onProgress?.({ sent: 1, stored: progress, phase: 'storing' })

        if (!isUploadTaskDone(task, progress)) continue

        if (task.error || TASK_FAILED_STATES.has(String(task.state || '').toLowerCase())) {
            throw new Error(task.error || t('errors.uploadTaskFailed'))
        }
        onProgress?.({ sent: 1, stored: 1, phase: 'storing' })

        // Finished rows pile up in the server's task list otherwise.
        fetch(`${ctx.server}/api/task/upload/delete?tid=${encodeURIComponent(tid)}`, {
            method: 'POST',
            headers,
            body: '{}',
        }).catch(() => {})
        return
    }
}

/**
 * Is this upload task finished?
 *
 * `state` is the field that says so, but its vocabulary is not documented and
 * differs between forks, so `end_time` (null while running, per the API docs)
 * and a 100% progress are accepted as well.
 */
function isUploadTaskDone(task, progress) {
    const state = String(task?.state || '').toLowerCase()
    if (TASK_DONE_STATES.has(state)) return true
    return Boolean(task?.end_time) || progress >= 1
}

export function normalizeUploadPath(path) {
    const trimmed = (path || '').trim()
    if (!trimmed || trimmed === '/') {
        throw new Error(t('errors.uploadPathEmpty'))
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
    if (json.code !== 200) throw new Error(json.message || t('errors.deleteFailed'))
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
