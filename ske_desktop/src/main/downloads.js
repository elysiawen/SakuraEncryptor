/**
 * Download queue: decrypt one container into a plaintext file on disk.
 *
 * Downloads reuse the media path on purpose. `MediaRegistry` already knows how
 * to turn a `.ske` container — a local path or a signed remote URL — into a
 * plaintext byte stream, so "download" is that same stream written to a file
 * instead of to an HTTP response. One decryption implementation, two consumers,
 * which is what keeps range seeking and downloads from drifting apart.
 *
 * Plaintext on disk is the one place the zero-knowledge design makes an
 * exception, and only when the user explicitly asks for it.
 */
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import { t } from './i18n.js'
import path from 'node:path'

/**
 * Two at a time for the same reason as encryption jobs: AES-GCM saturates a
 * core, and the rest of the time is network or disk. More workers mostly make
 * the progress bars lie.
 */
const DEFAULT_CONCURRENT = 2
const EMIT_INTERVAL_MS = 150

/** Pick a free name: `movie.mp4`, `movie (2).mp4`, … */
async function uniquePath(dir, name) {
  const { name: base, ext } = path.parse(name)
  let candidate = path.join(dir, name)
  let counter = 1

  for (;;) {
    try {
      await fsp.access(candidate)
    } catch {
      return candidate
    }
    counter += 1
    candidate = path.join(dir, `${base} (${counter})${ext}`)
  }
}

export class DownloadQueue {
  /**
   * @param {object} options
   * @param {import('./media-router.js').MediaRegistry} options.registry
   * @param {import('./cloud.js').CloudService} options.cloud
   * @param {import('./local-fs.js').LocalLibrary} options.localLibrary
   * @param {import('./transfer-history.js').TransferHistory} [options.history]
   * @param {(jobs: object[]) => void} [options.onUpdate]
   */
  constructor({
    registry,
    cloud,
    localLibrary,
    history,
    onUpdate,
    maxConcurrent = DEFAULT_CONCURRENT,
  } = {}) {
    this.registry = registry
    this.cloud = cloud
    this.localLibrary = localLibrary
    this.history = history
    this.onUpdate = onUpdate
    this.maxConcurrent = Math.max(1, maxConcurrent)
    this.items = new Map()
    this.pending = []
    this.controllers = new Map()
    this.running = 0
    this.emitTimer = null
  }

  #scheduleEmit() {
    if (this.emitTimer) return
    this.emitTimer = setTimeout(() => {
      this.emitTimer = null
      this.onUpdate?.(this.snapshot())
    }, EMIT_INTERVAL_MS)
  }

  /** Public shape: paths the UI can show, no credentials. */
  snapshot() {
    return [...this.items.values()].map((job) => ({
      id: job.id,
      mode: job.mode,
      name: job.name,
      sourcePath: job.path,
      status: job.status,
      totalBytes: job.totalBytes,
      doneBytes: job.doneBytes,
      destDir: job.destDir,
      outPath: job.outPath,
      error: job.error,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }))
  }

  /**
   * Put settled rows back after a restart.
   *
   * They go into the same map as live jobs, which is what makes them behave
   * exactly like rows finished in this session — "show in folder" only needs
   * `outPath`, and nothing in the UI can tell the difference.
   */
  restore(records = []) {
    for (const record of records) {
      if (!record?.id || this.items.has(record.id)) continue
      this.items.set(record.id, {
        id: record.id,
        mode: record.mode === 'local' ? 'local' : 'cloud',
        sessionId: '',
        path: record.sourcePath || '',
        name: record.name || '',
        destDir: record.destDir || '',
        status: ['done', 'failed', 'cancelled'].includes(record.status) ? record.status : 'failed',
        totalBytes: record.totalBytes || 0,
        doneBytes: record.doneBytes || 0,
        outPath: record.outPath || '',
        error: record.error || '',
        startedAt: record.startedAt || 0,
        finishedAt: record.finishedAt || 0,
      })
    }
    if (records.length) this.#scheduleEmit()
  }

  /** What survives a restart: the row the user saw, and nothing else. */
  #record(job) {
    return {
      kind: 'download',
      id: job.id,
      name: job.name,
      mode: job.mode,
      sourcePath: job.path,
      destDir: job.destDir,
      outPath: job.outPath,
      status: job.status,
      totalBytes: job.totalBytes,
      doneBytes: job.doneBytes,
      error: job.error,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }
  }

  #remember(job) {
    this.history?.remember(this.#record(job))
  }

  add({ mode = 'cloud', sessionId = '', path: plainPath, name, destDir } = {}) {
    if (!plainPath) throw new Error(t('errors.pathMissing'))
    if (!destDir) throw new Error(t('errors.needDownloadDir'))

    const job = {
      id: crypto.randomBytes(6).toString('hex'),
      mode: mode === 'local' ? 'local' : 'cloud',
      sessionId,
      path: plainPath,
      name: name || path.posix.basename(plainPath),
      destDir,
      status: 'queued',
      totalBytes: 0,
      doneBytes: 0,
      outPath: '',
      error: '',
      startedAt: 0,
      finishedAt: 0,
    }

    this.items.set(job.id, job)
    this.pending.push(job)
    this.#scheduleEmit()
    this.#pump()

    return { id: job.id, name: job.name, status: job.status }
  }

  /** Resolve the container to a password plus a target `sources.js` understands. */
  async #source(job) {
    if (job.mode === 'local') {
      if (!this.localLibrary?.unlocked) throw new Error(t('errors.localLocked'))
      return {
        target: this.localLibrary.filePath(job.path),
        password: this.localLibrary.password,
      }
    }

    const session = this.cloud.get(job.sessionId)
    const { rawUrl } = await this.cloud.resolve(job.sessionId, job.path)
    return { target: rawUrl, password: session.skePassword }
  }

  #pump() {
    while (this.running < this.maxConcurrent && this.pending.length) {
      const job = this.pending.shift()
      if (job.status !== 'queued') continue
      this.running += 1
      this.#run(job)
        .catch(() => {})
        .finally(() => {
          this.running -= 1
          this.#pump()
        })
    }
  }

  async #run(job) {
    job.status = 'running'
    job.startedAt = Date.now()
    this.#scheduleEmit()

    const controller = new AbortController()
    this.controllers.set(job.id, controller)

    let mediaId = ''
    let outPath = ''
    let handle = null

    try {
      await fsp.mkdir(job.destDir, { recursive: true })

      const { target, password } = await this.#source(job)
      const info = await this.registry.open({ target, password, name: job.name })
      mediaId = info.id
      job.totalBytes = info.size

      outPath = await uniquePath(job.destDir, job.name)
      job.outPath = outPath
      this.#scheduleEmit()

      const media = this.registry.get(mediaId)
      handle = await fsp.open(outPath, 'w')

      if (info.size > 0) {
        for await (const chunk of media.readRange(0, info.size - 1)) {
          if (controller.signal.aborted) throw new Error(t('errors.cancelled'))
          await handle.write(chunk)
          job.doneBytes += chunk.length
          this.#scheduleEmit()
        }
      }

      job.status = 'done'
    } catch (err) {
      job.status = controller.signal.aborted ? 'cancelled' : 'failed'
      job.error = err.message
      // A half-written file is worse than none: the user cannot tell it apart
      // from a complete download.
      if (outPath) await fsp.rm(outPath, { force: true }).catch(() => {})
      job.outPath = ''
    } finally {
      await handle?.close().catch(() => {})
      if (mediaId) await this.registry.close(mediaId).catch(() => {})
      this.controllers.delete(job.id)
      job.finishedAt = Date.now()
      this.#remember(job)
      this.#scheduleEmit()
    }
  }

  cancel(id) {
    const job = this.items.get(id)
    if (!job) return false

    if (job.status === 'queued') {
      job.status = 'cancelled'
      job.finishedAt = Date.now()
      this.pending = this.pending.filter((item) => item.id !== id)
      this.#remember(job)
      this.#scheduleEmit()
      return true
    }

    const controller = this.controllers.get(id)
    if (controller) {
      controller.abort()
      return true
    }
    return false
  }

  /** Drop finished rows so the list does not grow without bound. */
  clearFinished() {
    const cleared = []
    for (const [id, job] of this.items) {
      if (['done', 'failed', 'cancelled'].includes(job.status)) {
        this.items.delete(id)
        cleared.push(id)
      }
    }
    // The history is what makes them come back on the next launch.
    this.history?.forget('download', cleared)
    this.#scheduleEmit()
    return this.snapshot()
  }

  /**
   * Drop one settled row and its history line, leaving the file on disk.
   *
   * @returns {boolean} whether a row was there to drop
   */
  forget(id) {
    const job = this.items.get(id)
    if (!job) return false
    if (job.status === 'queued' || job.status === 'running') return false
    this.items.delete(id)
    this.history?.forget('download', [id])
    this.#scheduleEmit()
    return true
  }

  /**
   * Delete the downloaded file, then the row.
   *
   * Only a finished download has a path worth deleting: a failed one already
   * removed its half-written file, and `outPath` was cleared with it.
   *
   * @returns {Promise<boolean>} whether a row was dropped
   */
  async deleteFile(id) {
    const job = this.items.get(id)
    if (!job) return false
    if (job.status !== 'done' || !job.outPath) return false

    await fsp.rm(job.outPath, { force: true }).catch((err) => {
      // A file the user already moved away is not an error worth a dialog.
      if (err.code !== 'ENOENT') throw err
    })
    return this.forget(id)
  }

  cancelAll() {
    for (const id of this.items.keys()) this.cancel(id)
  }
}
