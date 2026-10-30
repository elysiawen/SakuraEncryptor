/**
 * Upload queue: encrypt locally, then push the container to AList.
 *
 * Same shape as the encryption and download queues — `add` returns instantly,
 * a couple of workers run, progress is pushed to the renderer — because the UI
 * already knows how to read that.
 *
 * Order matters: a plaintext file never reaches the server. With `encrypt` on,
 * the container is written to a temp file first and only that file is uploaded;
 * the temp file is removed whichever way the job ends. With `encrypt` off the
 * user asked for a plain upload, which is exactly what the web client offers
 * for trees shared with clients that cannot decrypt.
 */
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { encryptName } from './ske-names.js'
import { encryptFile } from './ske-writer.js'
import { makeThumbnail } from './thumbnails.js'

/**
 * Two at a time, like the other queues: AES-GCM saturates a core and the rest
 * is network, so more workers mostly make the progress bars lie.
 */
const DEFAULT_CONCURRENT = 2
const EMIT_INTERVAL_MS = 150

/** Stored path for an upload: no double slash when the folder is the root. */
function joinStoredPath(base, name) {
  const left = String(base || '').replace(/\/+$/, '')
  const right = String(name || '').replace(/^\/+/, '')
  return `${left}/${right}`.replace(/\/{2,}/g, '/') || '/'
}

export class UploadQueue {
  /**
   * @param {object} options
   * @param {import('./cloud.js').CloudService} options.cloud
   * @param {(jobs: object[]) => void} [options.onUpdate]
   * @param {string} [options.tempDir] where containers are staged before upload
   */
  constructor({ cloud, onUpdate, maxConcurrent = DEFAULT_CONCURRENT, tempDir = '' } = {}) {
    this.cloud = cloud
    this.onUpdate = onUpdate
    this.maxConcurrent = Math.max(1, maxConcurrent)
    this.tempDir = tempDir || path.join(os.tmpdir(), 'ske-upload')
    this.jobs = new Map()
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

  /** Public shape: file names and progress, never a password or a local path. */
  snapshot() {
    return [...this.jobs.values()].map((job) => ({
      id: job.id,
      name: job.name,
      dir: job.dir,
      encrypt: job.encrypt,
      status: job.status,
      phase: job.phase,
      totalBytes: job.totalBytes,
      doneBytes: job.doneBytes,
      error: job.error,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }))
  }

  /**
   * Queue a batch for one folder.
   *
   * @param {{ sessionId: string, path?: string, files?: ({ path: string, name?: string } | string)[], encrypt?: boolean }} input
   */
  add({ sessionId, path: plainPath = '/', files = [], encrypt = true } = {}) {
    const list = (Array.isArray(files) ? files : [])
      .map((file) => (typeof file === 'string' ? { path: file } : file))
      .filter((file) => file?.path)
    if (!list.length) throw new Error('没有要上传的文件')

    // Resolve once per batch: it validates the session and fails before any
    // job is created, so the UI never shows rows that can only fail.
    const target = this.cloud.uploadTarget(sessionId, plainPath)
    if (encrypt && !target.password) {
      throw new Error('该配置缺少媒体加密密码，无法加密上传')
    }

    const created = list.map((file) => {
      const job = {
        id: crypto.randomBytes(6).toString('hex'),
        sessionId,
        dir: plainPath,
        name: file.name || path.basename(file.path),
        srcPath: file.path,
        encrypt: !!encrypt,
        target: { ...target },
        status: 'queued',
        phase: 'encrypting',
        totalBytes: 0,
        doneBytes: 0,
        error: '',
        startedAt: 0,
        finishedAt: 0,
      }
      this.jobs.set(job.id, job)
      this.pending.push(job)
      return job
    })

    this.#scheduleEmit()
    this.#pump()

    return { jobs: created.map((job) => ({ id: job.id, name: job.name, status: job.status })) }
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

    const controller = new AbortController()
    this.controllers.set(job.id, controller)
    this.#scheduleEmit()

    const target = job.target
    let temp = ''
    let sourcePath = job.srcPath
    let uploadName = job.name

    try {
      const stat = await fsp.stat(job.srcPath)
      if (!stat.isFile()) throw new Error('要上传的不是文件')
      job.totalBytes = stat.size

      if (job.encrypt) {
        job.phase = 'encrypting'
        uploadName = `${encryptName(job.name, target.nameKey)}.ske`
        await fsp.mkdir(this.tempDir, { recursive: true })
        temp = path.join(this.tempDir, `${job.id}.ske`)

        // Same preview the local encryptor embeds, so an uploaded image gets a
        // tile without any extra round trip.
        const thumbnail = await makeThumbnail(job.srcPath)
        await encryptFile({
          src: job.srcPath,
          dst: temp,
          password: target.password,
          thumbnail,
          signal: controller.signal,
          onProgress: (processed) => {
            // Encryption is the first half of the bar; the container is slightly
            // larger than its source, so the upload half is measured separately.
            job.doneBytes = Math.round((processed / Math.max(1, stat.size)) * stat.size * 0.5)
            this.#scheduleEmit()
          },
        })
        job.doneBytes = Math.round(stat.size * 0.5)
        sourcePath = temp
      }

      job.phase = 'uploading'
      this.#scheduleEmit()

      // No byte-level progress here: the body is a Blob handed to the runtime,
      // which does not report how much of it has been sent. The bar holds at the
      // halfway mark and fills in when the server answers.
      await target.client.upload(joinStoredPath(target.dir, uploadName), sourcePath, {
        signal: controller.signal,
      })

      if (controller.signal.aborted) throw new Error('已取消')
      job.doneBytes = stat.size
      job.status = 'done'
    } catch (err) {
      job.status = controller.signal.aborted ? 'cancelled' : 'failed'
      job.error = err.message
    } finally {
      this.controllers.delete(job.id)
      if (temp) await fsp.rm(temp, { force: true }).catch(() => {})
      // The credentials have served their purpose; do not keep them alive in a
      // finished row.
      job.target = null
      target.password = ''
      job.finishedAt = Date.now()
      this.#scheduleEmit()
    }
  }

  cancel(id) {
    const job = this.jobs.get(id)
    if (!job) return false

    if (job.status === 'queued') {
      job.status = 'cancelled'
      job.finishedAt = Date.now()
      this.pending = this.pending.filter((item) => item.id !== id)
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
    for (const [id, job] of this.jobs) {
      if (['done', 'failed', 'cancelled'].includes(job.status)) this.jobs.delete(id)
    }
    this.#scheduleEmit()
    return this.snapshot()
  }

  cancelAll() {
    for (const id of this.jobs.keys()) this.cancel(id)
  }
}
