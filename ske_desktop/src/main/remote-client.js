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
import { openAsBlob } from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'

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

/** Abortable delay for the chunked upload's flow-control retries. */
export function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    if (!signal) return
    if (signal.aborted) {
      clearTimeout(timer)
      reject(new Error('已取消'))
      return
    }
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new Error('已取消'))
    }, { once: true })
  })
}

/**
 * Force a leading slash and refuse the root outright. An empty path means the
 * destination was never resolved, and sending it only produces the server's
 * complaint instead of a message pointing at the real problem.
 */
export function normalizeUploadPath(absPath) {
  const trimmed = String(absPath || '').trim()
  if (!trimmed || trimmed === '/') throw new Error('上传文件路径不能为空')
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
      if (err.name === 'TimeoutError') throw new RemoteError('连接服务器超时')
      throw new RemoteError(`无法连接服务器：${err.message}`)
    }

    if (!res.ok) throw new RemoteError(`服务器返回 HTTP ${res.status}`, res.status)

    let json
    try {
      json = await res.json()
    } catch {
      throw new RemoteError(`服务器返回的不是 JSON，请检查地址是否为 ${this.label} 服务`)
    }

    if (json.code !== 200) {
      throw new RemoteError(json.message || `${this.label} 返回 ${json.code}`, json.code)
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
        throw new RemoteError('该账号已开启两步验证，请输入动态验证码', 402)
      }
      throw err
    }
    if (!data.token) throw new RemoteError('登录响应中没有 token')
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
   * @param {string} absPath destination path including the file name
   * @param {string} filePath local source
   * @param {{ signal?: AbortSignal, onProgress?: (ratio: number) => void }} [options]
   */
  async upload(absPath, filePath, { signal, onProgress } = {}) {
    const stat = await fsp.stat(filePath)
    if (!stat.isFile()) throw new Error('要上传的不是文件')

    if (this.supportsMultipartUpload && stat.size >= MULTIPART_MIN_BYTES) {
      try {
        return await this.uploadMultipart(absPath, filePath, stat.size, { signal, onProgress })
      } catch (err) {
        if (signal?.aborted) throw new Error('已取消')
        if (!err.multipartUnsupported) throw err
        // Older/disabled server: use the form API below.
      }
    }
    return this.#uploadForm(absPath, filePath, { signal })
  }

  /** Chunked upload, overridden by backends that support it. */
  async uploadMultipart() {
    throw new Error(`${this.label} 客户端没有实现分片上传`)
  }

  /**
   * Classic `PUT /api/fs/form` upload.
   *
   * Letting the runtime build the multipart envelope and the `Content-Length`
   * matters: assembling it by hand worked against a local echo server but the
   * real server answered 「上传文件路径不能为空」. The file is opened as a
   * `Blob` rather than read into memory, so a multi-gigabyte container still
   * streams off disk.
   */
  async #uploadForm(absPath, filePath, { signal } = {}) {
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
      if (err.name === 'AbortError') throw new Error('已取消')
      throw new RemoteError(`上传失败：${err.message}`)
    }

    if (!res.ok) throw new RemoteError(`服务器返回 HTTP ${res.status}`, res.status)

    const json = await readJson(res)
    if (!json) throw new RemoteError(`服务器返回的不是 JSON，请检查地址是否为 ${this.label} 服务`)
    if (json.code !== 200) {
      // The server's own wording never says *which* path it rejected, so the
      // diagnostic carries what was actually sent.
      const detail = json.message || `${this.label} 返回 ${json.code}`
      throw new RemoteError(`${detail}（目标：${decodeURIComponent(target)}）`, json.code)
    }
    return true
  }
}
