/**
 * Minimal AList API client.
 *
 * Only the endpoints the desktop client needs are implemented: auth, listing,
 * download resolution, upload, and the three file-manager writes behind the
 * context menu. The request shapes match `ske_web/src/composables/useAList.js`
 * so both frontends talk to the same server behaviour, including the
 * 500-per-page limit.
 */
import { openAsBlob } from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'

/** AList reports failures as `{ code, message }` with HTTP 200. */
export class AListError extends Error {
  constructor(message, code) {
    super(message)
    this.name = 'AListError'
    this.code = code
  }
}

/** AList clamps anything above this, so asking for more is silently truncated. */
const MAX_PER_PAGE = 500
/** Safety valve: 500 × 200 = 100k entries in one directory. */
const MAX_PAGES = 200
const REQUEST_TIMEOUT_MS = 30_000

export class AListClient {
  constructor({ server, username, password, token = '' }) {
    this.server = String(server || '').replace(/\/+$/, '')
    this.username = username || ''
    this.password = password || ''
    this.token = token
  }

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
      if (err.name === 'TimeoutError') throw new AListError('连接服务器超时')
      throw new AListError(`无法连接服务器：${err.message}`)
    }

    if (!res.ok) throw new AListError(`服务器返回 HTTP ${res.status}`, res.status)

    let json
    try {
      json = await res.json()
    } catch {
      throw new AListError('服务器返回的不是 JSON，请检查地址是否为 AList 服务')
    }

    if (json.code !== 200) {
      throw new AListError(json.message || `AList 返回 ${json.code}`, json.code)
    }
    return json.data || {}
  }

  async login() {
    const data = await this.#post('/api/auth/login', {
      username: this.username,
      password: this.password,
    })
    if (!data.token) throw new AListError('登录响应中没有 token')
    this.token = data.token
    return this.token
  }

  /**
   * List a directory, walking every page.
   *
   * AList defaults to 200 entries per page and silently drops the rest, so a
   * single request truncates any large folder. This is the bug the Android
   * client also had to work around.
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
    }
  }

  /**
   * Create one directory.
   *
   * `absPath` is the full path of the *new* folder, matching how the web client
   * calls `/api/fs/mkdir`. AList treats an existing name as an error, which is
   * what the UI shows rather than silently reusing the folder.
   */
  async mkdir(absPath) {
    await this.#post('/api/fs/mkdir', { path: absPath })
    return true
  }

  /**
   * Rename an entry in place.
   *
   * The request shape is the web client's: the full source path plus the new
   * *name*, not the destination path.
   */
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
   * Upload one local file to `absPath`.
   *
   * The same request the web client makes: `PUT /api/fs/form` with a `FormData`
   * body, letting the runtime build the multipart envelope and the
   * `Content-Length`. Assembling that body by hand worked against a local echo
   * server but the real server answered 「上传文件路径不能为空」 — when the
   * only remaining difference is who writes the envelope, the runtime wins.
   *
   * The file is opened as a `Blob` rather than read into memory, so a
   * multi-gigabyte container still streams off disk.
   *
   * @param {string} absPath destination path including the file name
   * @param {string} filePath local source
   * @param {{ signal?: AbortSignal }} [options]
   */
  async upload(absPath, filePath, { signal } = {}) {
    const stat = await fsp.stat(filePath)
    if (!stat.isFile()) throw new Error('要上传的不是文件')

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
      throw new AListError(`上传失败：${err.message}`)
    }

    if (!res.ok) throw new AListError(`服务器返回 HTTP ${res.status}`, res.status)

    let json
    try {
      json = await res.json()
    } catch {
      throw new AListError('服务器返回的不是 JSON，请检查地址是否为 AList 服务')
    }
    if (json.code !== 200) {
      // The server's own wording ("上传文件路径不能为空") never says *which*
      // path it rejected, so the diagnostic carries what was actually sent.
      const detail = json.message || `AList 返回 ${json.code}`
      throw new AListError(`${detail}（目标：${decodeURIComponent(target)}）`, json.code)
    }
    return true
  }
}

/**
 * Mirrors `normalizeUploadPath` in the web client: force a leading slash, and
 * refuse the root outright. An empty path means the destination was never
 * resolved, and sending it only produces the server's complaint instead of a
 * message pointing at the real problem.
 */
function normalizeUploadPath(absPath) {
  const trimmed = String(absPath || '').trim()
  if (!trimmed || trimmed === '/') throw new Error('上传文件路径不能为空')
  return encodeURI(trimmed.startsWith('/') ? trimmed : `/${trimmed}`)
}

/** A Blob backed by the file on disk, without reading it into memory. */
async function blobFor(filePath) {
  if (typeof openAsBlob === 'function') {
    try {
      return await openAsBlob(filePath)
    } catch {
      // Older runtimes without a working openAsBlob: fall back below.
    }
  }
  return new Blob([await fsp.readFile(filePath)])
}
