/**
 * Encryption job queue.
 *
 * Two jobs run at a time by design: AES-GCM is fast, and the bottleneck is
 * normally disk or ffmpeg, so more parallelism mostly makes the UI jittery.
 *
 * Long directories are scanned first so the progress bar has a real denominator
 * instead of jumping around as files are discovered.
 */
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { encryptName } from './ske-names.js'
import { encryptFile } from './ske-writer.js'
import { makeThumbnail } from './thumbnails.js'

/**
 * Two jobs at a time is the sweet spot on desktop: AES-GCM saturates a core but
 * the rest of the time is disk and ffmpeg, and more workers mostly make the UI
 * jittery. Exposed as an option so tests can pin it to 1.
 */
const DEFAULT_CONCURRENT = 2
const EMIT_INTERVAL_MS = 150

/** Build the output plan for one source (file or folder). */
async function buildPlan(srcPath, destDir, nameKey) {
  const stat = await fsp.stat(srcPath)

  if (stat.isFile()) {
    return {
      kind: 'file',
      dirs: [],
      files: [
        {
          src: srcPath,
          dst: path.join(destDir, `${encryptName(path.basename(srcPath), nameKey)}.ske`),
          size: stat.size,
        },
      ],
      totalBytes: stat.size,
    }
  }

  if (!stat.isDirectory()) throw new Error('只支持文件或文件夹')

  const files = []
  const dirs = []
  // The folder itself is part of the encrypted tree, so `电影/2024` keeps its
  // shape after encryption instead of collapsing into the output directory.
  const outRoot = path.join(destDir, encryptName(path.basename(srcPath), nameKey))
  dirs.push(outRoot)

  async function walk(dir, outDir) {
    const entries = await fsp.readdir(dir, { withFileTypes: true })
    for (const entry of entries) {
      const abs = path.join(dir, entry.name)
      const token = encryptName(entry.name, nameKey)

      if (entry.isDirectory()) {
        const next = path.join(outDir, token)
        dirs.push(next)
        await walk(abs, next)
      } else if (entry.isFile()) {
        const info = await fsp.stat(abs)
        files.push({ src: abs, dst: path.join(outDir, `${token}.ske`), size: info.size })
      }
    }
  }

  await walk(srcPath, outRoot)

  return {
    kind: 'folder',
    dirs,
    files,
    totalBytes: files.reduce((sum, file) => sum + file.size, 0),
  }
}

export class JobQueue {
  /** @param {{ onUpdate?: (jobs: object[]) => void, maxConcurrent?: number }} options */
  constructor({ onUpdate, maxConcurrent = DEFAULT_CONCURRENT } = {}) {
    this.jobs = new Map()
    this.pending = []
    this.controllers = new Map()
    this.running = 0
    this.maxConcurrent = Math.max(1, maxConcurrent)
    this.onUpdate = onUpdate
    this.emitTimer = null
  }

  #scheduleEmit() {
    if (this.emitTimer) return
    this.emitTimer = setTimeout(() => {
      this.emitTimer = null
      this.onUpdate?.(this.snapshot())
    }, EMIT_INTERVAL_MS)
  }

  /** Public shape: never leak the password to the renderer. */
  snapshot() {
    return [...this.jobs.values()].map((job) => ({
      id: job.id,
      kind: job.kind,
      name: job.name,
      status: job.status,
      totalBytes: job.totalBytes,
      doneBytes: job.doneBytes,
      totalFiles: job.totalFiles,
      doneFiles: job.doneFiles,
      currentFile: job.currentFile,
      error: job.error,
      destDir: job.destDir,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }))
  }

  /**
   * Queue one source. Returns immediately; scanning happens in the background
   * and the job moves from `scanning` to `queued` when it is ready.
   */
  add({ srcPath, destDir, password, nameKey, thumbnails = true }) {
    if (!srcPath || !destDir) throw new Error('缺少源路径或输出目录')
    if (!password) throw new Error('缺少加密密码')

    const job = {
      id: crypto.randomBytes(6).toString('hex'),
      kind: 'file',
      name: path.basename(srcPath) || srcPath,
      srcPath,
      destDir,
      password,
      nameKey,
      thumbnails,
      status: 'scanning',
      totalBytes: 0,
      doneBytes: 0,
      totalFiles: 0,
      doneFiles: 0,
      baseBytes: 0,
      currentFile: '',
      error: '',
      plan: null,
      startedAt: 0,
      finishedAt: 0,
    }

    this.jobs.set(job.id, job)
    this.#scheduleEmit()

    // Scan off the caller's stack so `add` stays instant for the UI.
    this.#scan(job).catch((err) => {
      job.status = 'failed'
      job.error = err.message
      job.finishedAt = Date.now()
      this.#scheduleEmit()
    })

    return { id: job.id, name: job.name, status: job.status }
  }

  async #scan(job) {
    try {
      const plan = await buildPlan(job.srcPath, job.destDir, job.nameKey)

      // Cancelling during the scan must win: otherwise the finished scan would
      // resurrect a job the user already dismissed.
      if (job.status === 'cancelled') return

      job.plan = plan
      job.kind = plan.kind
      job.totalBytes = plan.totalBytes
      job.totalFiles = plan.files.length
      job.status = 'queued'
      this.pending.push(job)
      this.#scheduleEmit()
      this.#pump()
    } catch (err) {
      if (job.status === 'cancelled') return
      job.status = 'failed'
      job.error = err.message
      job.finishedAt = Date.now()
      this.#scheduleEmit()
    }
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

    try {
      // Create every folder first so empty directories survive encryption.
      for (const dir of job.plan.dirs) {
        await fsp.mkdir(dir, { recursive: true })
      }

      for (const file of job.plan.files) {
        if (controller.signal.aborted) throw new Error('已取消')

        job.currentFile = path.basename(file.src)
        this.#scheduleEmit()

        const thumbnail = job.thumbnails ? await makeThumbnail(file.src) : null

        await encryptFile({
          src: file.src,
          dst: file.dst,
          password: job.password,
          thumbnail,
          signal: controller.signal,
          onProgress: (processed) => {
            job.doneBytes = job.baseBytes + processed
            this.#scheduleEmit()
          },
        })

        job.baseBytes += file.size
        job.doneBytes = job.baseBytes
        job.doneFiles += 1
        this.#scheduleEmit()
      }

      job.status = 'done'
      job.currentFile = ''
    } catch (err) {
      job.status = controller.signal.aborted ? 'cancelled' : 'failed'
      job.error = err.message
    } finally {
      this.controllers.delete(job.id)
      // The password has served its purpose; do not keep it in memory longer
      // than the job needs it.
      job.password = ''
      job.nameKey = null
      job.plan = null
      job.finishedAt = Date.now()
      this.#scheduleEmit()
    }
  }

  cancel(id) {
    const job = this.jobs.get(id)
    if (!job) return false

    if (job.status === 'scanning' || job.status === 'queued') {
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
      if (['done', 'failed', 'cancelled'].includes(job.status)) {
        this.jobs.delete(id)
      }
    }
    this.#scheduleEmit()
    return this.snapshot()
  }

  cancelAll() {
    for (const id of this.jobs.keys()) this.cancel(id)
  }
}
