/**
 * Shared protocol layer for the two remote backends.
 *
 * AList and OpenList expose the same `/api` surface (`auth/login`, `fs/list`,
 * `fs/get`, `fs/mkdir`, `fs/rename`, `fs/remove`, `fs/form`), so everything in
 * here is identical for both. Each backend still has its own module and its own
 * entry point; `alist.js` and `openlist.js` only override what actually differs
 * (OpenList's chunked upload). Nothing backend-specific may leak into this file:
 * no provider flag, no conditional on the backend name.
 *
 * The shapes match `ske_web/src/composables`, including the 500-per-page
 * listing limit both servers clamp to.
 */
import { createReadStream, openAsBlob } from 'node:fs'
import fsp from 'node:fs/promises'
import http from 'node:http'
import https from 'node:https'
import path from 'node:path'
import { t } from './i18n.js'

/** Both servers report failures as `{ code, message }` with HTTP 200. */
export class RemoteError extends Error {
  constructor(message, code) {
    super(message)
    this.name = 'RemoteError'
    this.code = code
    // 402 is the shared "this account uses 2FA, send an otp_code" answer.
    if (code === 402) this.needsOtp = true
  }
}

/** Servers clamp anything above this, so asking for more is silently truncated. */
export const MAX_PER_PAGE = 500
/** Safety valve: 500 × 200 = 100k entries in one directory. */
const MAX_PAGES = 200
const REQUEST_TIMEOUT_MS = 30_000

/** Chunked upload is worth its overhead from this size on (OpenList only). */
export const MULTIPART_MIN_BYTES = 32 * 1024 * 1024
/** Flow control answers 429/409; give the pipeline a moment and retry. */
export const MULTIPART_MAX_RETRIES = 6
/** Say "this server does not have the feature" rather than "upload failed". */
export const MULTIPART_FALLBACK_STATUS = new Set([403, 404, 405])
/** Raw-body upload missing (older build): the form endpoint is the way in. */
const UPLOAD_FALLBACK_STATUS = new Set([404, 405, 501])

/**
 * How often the server's own upload task is polled.
 *
 * The write to the storage backend takes far longer than the body transfer on
 * a throttled server, so this is what makes the second half of the bar move.
 * Fast enough to look live, slow enough to be one tiny request per file.
 */
const TASK_POLL_MS = 600
/** Task states that mean "no further progress is coming". */
const TASK_FAILED_STATES = new Set(['failed', 'canceled', 'cancelled', 'errored'])
const TASK_DONE_STATES = new Set(['succeeded', ...TASK_FAILED_STATES])

/**
 * Absolute form of a value the server returned.
 *
 * `thumb` is driver-dependent: a CDN link for cloud storages, a site-relative
 * `/p/...` path for the local driver, an empty string when nothing can be
 * generated. Absolutising here means callers never have to know which.
 */
function absoluteUrl(server, value) {
  const raw = String(value || '').trim()
  if (!raw) return ''
  if (/^https?:\/\//i.test(raw)) return raw
  if (raw.startsWith('//')) return `https:${raw}`
  return `${server}${raw.startsWith('/') ? '' : '/'}${raw}`
}

export async function readJson(res) {
  try {
    return await res.json()
  } catch {
    return null
  }
}

/**
 * Abortable delay for the chunked upload's flow-control retries.
 *
 * The listener is detached when the timer fires, not only when it aborts: this
 * sits inside poll loops (`#followUploadTask` wakes up every 600 ms for as long
 * as the storage write takes), and a `{ once: true }` listener that is never
 * reached stays on the signal — a minute-long upload would pile up a hundred
 * of them and trip EventTarget's warning.
 */
export function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new Error(t('errors.cancelled')))
      return
    }
    let timer = null
    const onAbort = () => {
      clearTimeout(timer)
      reject(new Error(t('errors.cancelled')))
    }
    timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Force a leading slash and refuse the root outright. An empty path means the
 * destination was never resolved, and sending it only produces the server's
 * complaint instead of a message pointing at the real problem.
 */
export function normalizeUploadPath(absPath) {
  const trimmed = String(absPath || '').trim()
  if (!trimmed || trimmed === '/') throw new Error(t('errors.uploadPathEmpty'))
  return encodeURI(trimmed.startsWith('/') ? trimmed : `/${trimmed}`)
}

/** A Blob backed by the file on disk, without reading it into memory. */
export async function blobFor(filePath) {
  if (typeof openAsBlob === 'function') {
    try {
      return await openAsBlob(filePath)
    } catch {
      // Older runtimes without a working openAsBlob: fall back below.
    }
  }
  return new Blob([await fsp.readFile(filePath)])
}

/** Every JSON answer here is small; read it whole as text. */
async function readText(res) {
  let text = ''
  res.setEncoding('utf8')
  for await (const chunk of res) text += chunk
  return text
}

function parseJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
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
  return Boolean(task?.end_time) || progress >= 100
}

/** A status the server picked on its own, as opposed to a dead connection. */
function isRefusal(status) {
  return Number.isFinite(status) && status >= 300
}

/** Human size for the one message that talks about the file, not about bytes. */
function formatBytes(bytes) {
  const value = Number(bytes) || 0
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`
  return `${Math.max(1, Math.round(value / 1024))} KB`
}

export class RemoteClient {
  /**
   * @param {{
   *   server: string,
   *   username?: string,
   *   password?: string,
   *   otpCode?: string,
   *   token?: string,
   *   label?: string,
   * }} options `label` is only used in user-facing messages.
   */
  constructor({ server, username, password, otpCode = '', token = '', label = 'AList' }) {
    this.server = String(server || '').replace(/\/+$/, '')
    this.username = username || ''
    this.password = password || ''
    this.otpCode = otpCode || ''
    this.token = token
    this.label = label
  }

  /** Backends that implement [uploadMultipart] flip this on. */
  supportsMultipartUpload = false

  /**
   * How this particular server gets uploaded to.
   *
   * - `task`   raw body + `As-Task`: byte progress, then the server's own
   *            upload task for the storage half. Needs a build that takes the
   *            body before answering, which is what `WebPutAsTask` does.
   * - `stream` raw body, synchronous: byte progress, and the answer only
   *            arrives once the file is really in the storage backend.
   * - `form`   the multipart endpoint of the first version: no byte-level
   *            progress, but the most compatible of the three.
   *
   * A failed attempt only ever moves this downwards, so a server that does not
   * like one strategy pays for that discovery once — not once per file.
   */
  uploadMode = 'task'

  async #post(path, body) {
    let res
    try {
      res = await fetch(`${this.server}${path}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(this.token ? { Authorization: this.token } : {}),
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      })
    } catch (err) {
      if (err.name === 'TimeoutError') throw new RemoteError(t('errors.connectTimeout'))
      throw new RemoteError(t('errors.connectFailedReason', { message: err.message }))
    }

    if (!res.ok) throw new RemoteError(t('errors.httpStatus', { status: res.status }), res.status)

    let json
    try {
      json = await res.json()
    } catch {
      throw new RemoteError(t('errors.notJson', { label: this.label }))
    }

    if (json.code !== 200) {
      throw new RemoteError(
        json.message || t('errors.apiFailed', { label: this.label, code: json.code }),
        json.code,
      )
    }
    return json.data || {}
  }

  /**
   * Authenticate. Returns the JWT token.
   *
   * When 2FA is on and no code was supplied the server answers 402; the thrown
   * error carries `needsOtp` so the UI can ask and retry with a fresh client.
   */
  async login() {
    let data
    try {
      data = await this.#post('/api/auth/login', {
        username: this.username,
        password: this.password,
        ...(this.otpCode ? { otp_code: this.otpCode } : {}),
      })
    } catch (err) {
      if (err.code === 402) {
        throw new RemoteError(t('errors.otpRequired'), 402)
      }
      throw err
    }
    if (!data.token) throw new RemoteError(t('errors.loginNoToken'))
    this.token = data.token
    return this.token
  }

  /**
   * List a directory, walking every page.
   *
   * Both servers default to 200 entries per page and silently drop the rest, so
   * a single request truncates any large folder.
   */
  async list(absPath, { refresh = false } = {}) {
    const entries = []
    const seen = new Set()

    for (let page = 1; page <= MAX_PAGES; page += 1) {
      const data = await this.#post('/api/fs/list', {
        path: absPath,
        page,
        per_page: MAX_PER_PAGE,
        ...(refresh && page === 1 ? { refresh: true } : {}),
      })

      const content = data.content || []
      for (const entry of content) {
        // A server that repeats a page must not duplicate rows or loop forever.
        const key = `${entry.name}|${entry.is_dir}`
        if (seen.has(key)) continue
        seen.add(key)
        // The listing already carries the preview URL, so a grid of plain
        // images needs no `/api/fs/get` per tile.
        if (entry.thumb) entry.thumb = absoluteUrl(this.server, entry.thumb)
        entries.push(entry)
      }

      const total = Number(data.total)
      if (content.length === 0) break
      if (Number.isFinite(total) && entries.length >= total) break
      if (content.length < MAX_PER_PAGE) break
    }

    return entries
  }

  /** Signed direct link plus size. The link expires; never cache it for long. */
  async getFile(absPath) {
    const data = await this.#post('/api/fs/get', { path: absPath })
    return {
      rawUrl: data.raw_url || '',
      size: Number(data.size) || 0,
      sign: data.sign || '',
      // Storage-generated preview; empty when the driver cannot make one.
      thumb: absoluteUrl(this.server, data.thumb),
    }
  }

  /**
   * Create one directory.
   *
   * `absPath` is the full path of the *new* folder, matching how the web client
   * calls `/api/fs/mkdir`. An existing name is an error, which is what the UI
   * shows rather than silently reusing the folder.
   */
  async mkdir(absPath) {
    await this.#post('/api/fs/mkdir', { path: absPath })
    return true
  }

  /** Rename an entry in place: full source path plus the new *name*. */
  async rename(absPath, newName) {
    await this.#post('/api/fs/rename', { path: absPath, name: newName })
    return true
  }

  /** Delete entries by name inside one directory. */
  async remove(dirPath, names) {
    await this.#post('/api/fs/remove', { dir: dirPath, names })
    return true
  }

  /**
   * Upload one local file to `absPath`, using the chunked endpoint from 32 MB
   * on when the backend has one. Servers without the feature report
   * `multipartUnsupported` and fall back to the classic form upload.
   *
   * Progress is reported as `{ sent, stored, phase }`:
   *
   *  - `sent`   bytes the server has taken delivery of;
   *  - `stored` bytes it reports having written through to the storage
   *             backend, or `null` while that is unknowable;
   *  - `phase`  `'uploading'` (the body is moving) or `'storing'` (the body
   *             is delivered and the server is still writing to the cloud).
   *
   * Those two numbers are what the UI draws as one bar. They differ because
   * AList relays the upload to the driver: on a throttled server the cloud
   * write outlives the transfer that fed it, so the transfer finishing says
   * nothing about the file being there yet.
   *
   * @param {string} absPath destination path including the file name
   * @param {string} filePath local source
   * @param {{ signal?: AbortSignal, onProgress?: (progress: object) => void }} [options]
   */
  async upload(absPath, filePath, { signal, onProgress } = {}) {
    const stat = await fsp.stat(filePath)
    if (!stat.isFile()) throw new Error(t('errors.notAFile'))

    if (this.supportsMultipartUpload && stat.size >= MULTIPART_MIN_BYTES) {
      try {
        return await this.uploadMultipart(absPath, filePath, stat.size, { signal, onProgress })
      } catch (err) {
        if (signal?.aborted) throw new Error(t('errors.cancelled'))
        if (!err.multipartUnsupported) throw err
        // Older/disabled server: use the plain endpoints below.
      }
    }

    for (;;) {
      try {
        if (this.uploadMode === 'form') {
          return await this.#uploadForm(absPath, filePath, {
            signal,
            onProgress,
            size: stat.size,
          })
        }
        return await this.#uploadStream(absPath, filePath, stat.size, {
          signal,
          onProgress,
          asTask: this.uploadMode === 'task',
        })
      } catch (err) {
        if (signal?.aborted) throw new Error(t('errors.cancelled'))
        const next = this.#degrade(err, this.uploadMode)
        if (!next) throw err
        // Left in the main-process log on purpose: a silent downgrade would
        // hide a server that keeps refusing the better strategy, and that is
        // the one thing worth reporting.
        console.warn(`[upload] ${this.uploadMode} → ${next}: ${err.message}`)
        this.uploadMode = next
      }
    }
  }

  /**
   * Which simpler strategy to retry with, or `''` to hand the error over.
   *
   * Only two kinds of failure are worth another attempt: a connection that
   * died before the server said anything (some builds answer a raw-body upload
   * and hang up, which is indistinguishable from a broken network here), and
   * an endpoint this deployment does not serve. Everything else is a real
   * answer from the server and must reach the user unchanged.
   */
  #degrade(err, mode) {
    // Nothing is left below the form endpoint.
    if (mode === 'form') return ''
    if (err.fallbackToForm) return 'form'
    if (!err.network) return ''
    return mode === 'task' ? 'stream' : 'form'
  }

  /**
   * The error for a status the server chose itself.
   *
   * `fallbackToForm` is only set for statuses that mean "this endpoint is not
   * here". Everything else is a real answer, and 413 is the one that must never
   * be retried: it comes from the proxy in front of AList, so every other
   * strategy hits the same wall — after the whole file has crossed the wire
   * again.
   *
   * `via` is the `Server` header: without it the advice is "some proxy in
   * front of AList", with it the user is told which one said no.
   */
  #uploadRefusal(status, json, size, via = '') {
    if (status === 413) {
      const server = String(via || '').trim()
      const message = server
        ? t('errors.uploadTooLargeVia', { size: formatBytes(size), server })
        : t('errors.uploadTooLarge', { size: formatBytes(size) })
      return new RemoteError(message, status)
    }
    const err = new RemoteError(
      json?.message || t('errors.httpStatus', { status }),
      json?.code ?? status,
    )
    err.fallbackToForm = UPLOAD_FALLBACK_STATUS.has(status) || (status >= 300 && status < 400)
    return err
  }

  /**
   * `PUT /api/fs/put` with the file itself as the raw body.
   *
   * The only path that can measure the first half honestly: the container is
   * streamed off disk, so each chunk reported here has really been handed to
   * the server. With `asTask`, `As-Task: true` asks the server to do the
   * storage write as a background task and to hand back its id — and that task
   * is what makes the second half observable, since it carries the server's
   * own progress towards the cloud.
   */
  async #uploadStream(absPath, filePath, size, { signal, onProgress, asTask = true } = {}) {
    const url = new URL(`${this.server}/api/fs/put`)
    const transport = url.protocol === 'https:' ? https : http

    const req = transport.request(url, {
      method: 'PUT',
      headers: {
        ...(this.token ? { Authorization: this.token } : {}),
        // The path travels percent-encoded; the server unescapes it itself.
        'File-Path': normalizeUploadPath(absPath),
        'Content-Type': 'application/octet-stream',
        // Read by the server instead of a multipart part header.
        'Content-Length': String(size),
        ...(asTask ? { 'As-Task': 'true' } : {}),
      },
    })

    const source = createReadStream(filePath)
    let sent = 0

    // Cancelling has to stop reading the disk too: an aborted 4 GiB container
    // that keeps being read is not cancelled.
    const onAbort = () => {
      source.destroy()
      req.destroy()
    }
    signal?.addEventListener('abort', onAbort, { once: true })

    const responded = new Promise((resolve, reject) => {
      req.once('response', resolve)
      req.once('error', reject)
    })
    const flushed = new Promise((resolve) => {
      req.once('finish', resolve)
      // A peer that hangs up counts as "no more bytes are going out"; the
      // status code above decides whether that was acceptable.
      req.once('error', resolve)
    })

    // `data` respects the pipe's backpressure, so this counts bytes handed to
    // the socket rather than bytes read ahead from disk.
    source.on('data', (chunk) => {
      // `restart` tells the queue this attempt starts from zero: a downgraded
      // retry re-sends the whole file and the bar must not sit at 100%.
      onProgress?.({
        sent,
        stored: null,
        phase: 'uploading',
        restart: sent === 0,
      })
      sent += chunk.length
    })
    source.once('error', (err) => req.destroy(err))
    source.pipe(req)

    try {
      let response
      try {
        response = await responded
      } catch (err) {
        if (signal?.aborted) throw new Error(t('errors.cancelled'))
        const failure = new RemoteError(t('errors.uploadFailedReason', { message: err.message }))
        // Nothing came back: the connection, not the server, ended this.
        failure.network = true
        throw failure
      }

      let raw = ''
      try {
        raw = await readText(response)
      } catch (err) {
        if (signal?.aborted) throw new Error(t('errors.cancelled'))
        // A proxy that refuses a body it has been reading usually hangs up on
        // the way out too. The status is still the server's answer, so a dead
        // read must not turn a 413 into a "network error" that gets retried
        // with another strategy — that would only cost a second transfer.
        if (!isRefusal(response.statusCode)) {
          const failure = new RemoteError(t('errors.uploadFailedReason', { message: err.message }))
          failure.network = true
          throw failure
        }
      }

      const json = parseJson(raw)
      if (!json || json.code !== 200) {
        // The server has already answered; stop pushing the rest of the file.
        source.destroy()
        req.destroy()
        throw this.#uploadRefusal(response.statusCode, json, size, response.headers.server)
      }

      // Task mode answers as soon as the task exists, which can be before the
      // last chunk is flushed — wait for the send side before claiming delivery.
      await flushed
      onProgress?.({ sent: size, stored: null, phase: 'uploading' })

      const taskId = json?.data?.task?.id
      if (!taskId) {
        // Synchronous write: the response only exists once the file is in the
        // storage backend, so both numbers are the full size.
        onProgress?.({ sent: size, stored: size, phase: 'storing' })
        return { sent: size, stored: size }
      }

      // The task id travels with the progress so the queue can persist it:
      // if the app closes now, the server keeps writing and this id is the only
      // way to pick the upload back up on the next launch.
      onProgress?.({ sent: size, stored: null, phase: 'storing', taskId })
      await this.#followUploadTask(taskId, size, { signal, onProgress })
      return { sent: size, stored: size }
    } finally {
      signal?.removeEventListener('abort', onAbort)
      source.destroy()
    }
  }

  /**
   * Watch one upload task until the server is done with it.
   *
   * A task that cannot be read (older build, or a fork without the endpoint)
   * must not fail an upload the server already accepted: the caller keeps the
   * bytes it delivered and simply has no storage figure. Cancelling is the
   * opposite — it has to reach the server, or the cloud write keeps running
   * after the user stopped it.
   */
  async #followUploadTask(tid, size, { signal, onProgress, strict = false } = {}) {
    const query = `/api/task/upload/info?tid=${encodeURIComponent(tid)}`
    let completed = false

    try {
      for (;;) {
        await delay(TASK_POLL_MS, signal)

        let task
        try {
          const data = await this.#post(query, {})
          // Documented as an array; some builds answer with the task alone.
          task = Array.isArray(data) ? data.find((item) => item?.id === tid) : data?.task || data
        } catch (err) {
          if (signal?.aborted) throw err
          if (strict) throw new RemoteError(t('errors.uploadTaskGone'))
          return
        }
        if (!task) {
          if (strict) throw new RemoteError(t('errors.uploadTaskGone'))
          return
        }

        const progress = Math.max(0, Math.min(100, Number(task.progress) || 0))
        onProgress?.({
          sent: size,
          stored: Math.round((size * progress) / 100),
          phase: 'storing',
        })

        if (!isUploadTaskDone(task, progress)) continue
        completed = true
        if (task.error || TASK_FAILED_STATES.has(String(task.state || '').toLowerCase())) {
          throw new RemoteError(task.error || t('errors.uploadTaskFailed'))
        }
        onProgress?.({ sent: size, stored: size, phase: 'storing' })
        return
      }
    } catch (err) {
      if (signal?.aborted) this.#taskAction('cancel', tid)
      throw err
    } finally {
      // Finished rows pile up in the server's task list otherwise.
      if (completed) this.#taskAction('delete', tid)
    }
  }

  /**
   * Take over an upload task the server is still running.
   *
   * The request that created the task is long gone — the app may have been
   * closed and restarted while the storage backend was still writing — so the
   * task id is the only handle left. Unlike the polling inside [upload], a
   * vanished task is an error here: mid-upload "no answer" means `no news worth
   * failing over`, but after a restart it means we genuinely do not know
   * whether the file landed.
   *
   * @param {string} tid task id returned by `As-Task`
   * @param {{ size?: number, signal?: AbortSignal, onProgress?: Function }} [options]
   */
  async waitForUploadTask(tid, { size = 0, signal, onProgress } = {}) {
    await this.#followUploadTask(tid, size, { signal, onProgress, strict: true })
  }

  /** Best effort: a task that is already gone must not fail an upload. */
  #taskAction(name, tid) {
    this.#post(`/api/task/upload/${name}?tid=${encodeURIComponent(tid)}`, {}).catch(() => {})
  }

  /** Chunked upload, overridden by backends that support it. */
  async uploadMultipart() {
    throw new Error(t('errors.noMultipart', { label: this.label }))
  }

  /**
   * Classic `PUT /api/fs/form` upload.
   *
   * Letting the runtime build the multipart envelope and the `Content-Length`
   * matters: assembling it by hand worked against a local echo server but the
   * real server answered 「上传文件路径不能为空」. The file is opened as a
   * `Blob` rather than read into memory, so a multi-gigabyte container still
   * streams off disk.
   *
   * This is the fallback for servers without `/api/fs/put`, and it is
   * deliberately progress-free: the runtime writes the multipart body itself
   * and reports nothing about it. The bar therefore holds until the response
   * arrives, which is also the moment the file is in the storage backend.
   */
  async #uploadForm(absPath, filePath, { signal, onProgress, size = 0 } = {}) {
    const target = normalizeUploadPath(absPath)
    const filename = path.basename(absPath).replace(/["\r\n]/g, '')
    const form = new FormData()
    form.append('file', await blobFor(filePath), filename)

    let res
    try {
      res = await fetch(`${this.server}/api/fs/form`, {
        method: 'PUT',
        headers: {
          ...(this.token ? { Authorization: this.token } : {}),
          'File-Path': target,
        },
        body: form,
        signal,
      })
    } catch (err) {
      if (err.name === 'AbortError') throw new Error(t('errors.cancelled'))
      throw new RemoteError(t('errors.uploadFailedReason', { message: err.message }))
    }

    if (!res.ok) throw this.#uploadRefusal(res.status, null, size, res.headers.get('server'))

    const json = await readJson(res)
    if (!json) throw new RemoteError(t('errors.notJson', { label: this.label }))
    if (json.code !== 200) {
      // The server's own wording never says *which* path it rejected, so the
      // diagnostic carries what was actually sent.
      const detail =
        json.message || t('errors.apiFailed', { label: this.label, code: json.code })
      throw new RemoteError(
        t('errors.uploadFailedTarget', { detail, target: decodeURIComponent(target) }),
        json.code,
      )
    }

    // The form endpoint is synchronous: reaching here means the driver already
    // wrote the file, so both halves are complete.
    onProgress?.({ sent: size, stored: size, phase: 'storing' })
    return { sent: size, stored: size }
  }
}
