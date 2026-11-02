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
 *
 * An upload has two transfer halves and they are reported separately:
 *
 *  - `sentBytes`   bytes the server has taken delivery of (our own socket);
 *  - `storedBytes` bytes the server reports having written to the storage
 *                  backend, or `null` when it cannot tell us.
 *
 * The second one is the slow half on a rate-limited server, because AList
 * relays the body to the driver instead of storing it locally — which is why
 * "upload finished" and "file is in the cloud" are not the same moment.
 */
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import { t } from './i18n.js'
import os from 'node:os'
import path from 'node:path'
import { MULTIPART_MIN_BYTES } from './remote-client.js'
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
   * @param {import('./transfer-history.js').TransferHistory} [options.history]
   * @param {import('./pending-uploads.js').PendingUploads} [options.pending]
   * @param {(jobs: object[]) => void} [options.onUpdate]
   * @param {string} [options.tempDir] where containers are staged before upload
   * @param {string} [options.payloadDir] where a chunked upload's container is
   *   kept while it can still be resumed; must outlive the process
   */
  constructor({
    cloud,
    history,
    pending,
    onUpdate,
    maxConcurrent = DEFAULT_CONCURRENT,
    tempDir = '',
    payloadDir = '',
  } = {}) {
    this.cloud = cloud
    this.history = history
    // Not `pending`, which is the ready list in every other queue here: this one
    // is the on-disk record of uploads the server is still writing.
    this.pendingUploads = pending
    this.onUpdate = onUpdate
    this.maxConcurrent = Math.max(1, maxConcurrent)
    this.tempDir = tempDir || path.join(os.tmpdir(), 'ske-upload')
    this.payloadDir = payloadDir || path.join(os.tmpdir(), 'ske-upload-payloads')
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
      /** Which account owns the file, and what it is called there. */
      profileId: job.profileId || '',
      remoteName: job.remoteName || '',
      status: job.status,
      phase: job.phase,
      totalBytes: job.totalBytes,
      uploadBytes: job.uploadBytes,
      encryptRatio: job.encryptRatio,
      sentBytes: job.sentBytes,
      storedBytes: job.storedBytes,
      error: job.error,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }))
  }

  /**
   * Put settled rows back after a restart. See [DownloadQueue.restore].
   */
  restore(records = []) {
    for (const record of records) {
      if (!record?.id || this.jobs.has(record.id)) continue
      this.jobs.set(record.id, {
        id: record.id,
        sessionId: '',
        dir: record.dir || '/',
        name: record.name || '',
        srcPath: '',
        encrypt: !!record.encrypt,
        profileId: record.profileId || '',
        remoteName: record.remoteName || '',
        // A settled row is not going to upload anything: nothing to talk to.
        target: null,
        status: ['done', 'failed', 'cancelled'].includes(record.status) ? record.status : 'failed',
        phase: record.phase || 'uploading',
        totalBytes: record.totalBytes || 0,
        uploadBytes: record.uploadBytes || 0,
        encryptRatio: record.encryptRatio || 0,
        sentBytes: record.sentBytes || 0,
        storedBytes: Number.isFinite(record.storedBytes) ? record.storedBytes : null,
        error: record.error || '',
        startedAt: record.startedAt || 0,
        finishedAt: record.finishedAt || 0,
      })
    }
    if (records.length) this.#scheduleEmit()
  }

  /**
   * What survives a restart.
   *
   * Deliberately not the job: `srcPath` is a local path the history never
   * shows, and `target` carries a password. Listing the fields is what keeps a
   * later addition to the job from being persisted by accident.
   */
  #record(job) {
    return {
      kind: 'upload',
      id: job.id,
      name: job.name,
      dir: job.dir,
      encrypt: job.encrypt,
      // Enough to find the file on the server again: the account, and the name
      // it was stored under. Without them a finished row cannot be cleaned up.
      profileId: job.profileId || '',
      remoteName: job.remoteName || '',
      status: job.status,
      phase: job.phase,
      totalBytes: job.totalBytes,
      uploadBytes: job.uploadBytes,
      encryptRatio: job.encryptRatio,
      sentBytes: job.sentBytes,
      storedBytes: job.storedBytes,
      error: job.error,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }
  }

  #remember(job) {
    this.history?.remember(this.#record(job))
  }

  /**
   * Keep the on-disk row in step with the job.
   *
   * Written before the body moves as well as when the task id arrives: the
   * first version says "this file was never delivered" when the next launch
   * finds it, the second is what makes the upload recoverable at all.
   *
   * Two shapes come out of here. A chunked upload is tracked as `chunk` from the
   * start, because the container itself is kept and a restart sends the rest of
   * it; everything else becomes a `task` once the server hands back an id.
   */
  #track(job) {
    if (!this.pendingUploads || !job.target) return
    this.pendingUploads.upsert({
      id: job.id,
      profileId: job.target.profileId || '',
      dir: job.dir,
      name: job.name,
      encrypt: job.encrypt,
      mode: job.payloadPath ? 'chunk' : 'task',
      taskId: job.taskId || '',
      remoteName: job.remoteName || '',
      payloadPath: job.payloadPath || '',
      phase: job.phase,
      totalBytes: job.totalBytes,
      uploadBytes: job.uploadBytes,
      sentBytes: job.sentBytes,
      startedAt: job.startedAt,
      updatedAt: Date.now(),
    })
  }

  /**
   * Whether this upload will be chunked, in which case its container has to be
   * kept: the server only has some of the chunks after a crash, not the file.
   */
  #isChunked(job, target) {
    return Boolean(target?.client?.supportsMultipartUpload) && job.uploadBytes >= MULTIPART_MIN_BYTES
  }

  /**
   * Park the container where a later launch can read it.
   *
   * An encrypted container lives in `tempDir` and is ours, so a rename is
   * enough; a plain upload's source belongs to the user and is copied instead.
   */
  async #keepPayload(sourcePath, job, { move = false } = {}) {
    await fsp.mkdir(this.payloadDir, { recursive: true })
    const target = path.join(this.payloadDir, `${job.id}.payload`)
    if (move) {
      try {
        await fsp.rename(sourcePath, target)
        return target
      } catch {
        // Different volume: fall through to a copy.
      }
    }
    await fsp.copyFile(sourcePath, target)
    return target
  }

  /**
   * Put a still-running server task back into the queue.
   *
   * The job never runs `#run`: there is nothing left to encrypt or send — the
   * only thing missing is someone watching the server finish. It arrives as
   * `running` with a controller of its own, because cancelling it still has to
   * reach the server.
   *
   * @returns {{ job: object, signal: AbortSignal }}
   */
  adopt(entry) {
    const job = {
      id: entry.id,
      sessionId: '',
      dir: entry.dir || '/',
      name: entry.name || '',
      srcPath: '',
      encrypt: !!entry.encrypt,
      target: null,
      profileId: entry.profileId || '',
      taskId: entry.taskId || '',
      remoteName: entry.remoteName || '',
      status: 'running',
      phase: 'storing',
      totalBytes: entry.totalBytes || 0,
      uploadBytes: entry.uploadBytes || 0,
      encryptRatio: 1,
      sentBytes: entry.uploadBytes || 0,
      storedBytes: null,
      error: '',
      startedAt: entry.startedAt || Date.now(),
      finishedAt: 0,
    }
    this.jobs.set(job.id, job)

    const controller = new AbortController()
    this.controllers.set(job.id, controller)
    this.#scheduleEmit()
    return { job, signal: controller.signal }
  }

  /**
   * Put a chunked upload back into the queue after a restart.
   *
   * Nothing is on the server that can be watched: only some of the chunks made
   * it, so the job resumes by sending the rest of the container [entry] points
   * at. It arrives as `running` with a controller of its own, because
   * cancelling it still has to stop the transfer.
   *
   * @returns {{ job: object, signal: AbortSignal }}
   */
  adoptChunk(entry) {
    const job = {
      id: entry.id,
      sessionId: '',
      dir: entry.dir || '/',
      name: entry.name || '',
      srcPath: '',
      encrypt: !!entry.encrypt,
      target: null,
      profileId: entry.profileId || '',
      taskId: '',
      remoteName: entry.remoteName || entry.name || '',
      payloadPath: entry.payloadPath || '',
      status: 'running',
      phase: 'uploading',
      totalBytes: entry.totalBytes || 0,
      uploadBytes: entry.uploadBytes || entry.totalBytes || 0,
      encryptRatio: 1,
      sentBytes: 0,
      storedBytes: null,
      error: '',
      startedAt: entry.startedAt || Date.now(),
      finishedAt: 0,
    }
    this.jobs.set(job.id, job)

    const controller = new AbortController()
    this.controllers.set(job.id, controller)
    this.#scheduleEmit()
    return { job, signal: controller.signal }
  }

  /**
   * Send the rest of a chunked upload whose container survived the restart.
   *
   * The server only reuses a half-finished session when the init request
   * carries a matching content hash; this client does not compute one, so the
   * second attempt usually opens a new session and the chunks go out again from
   * the first. Either way the upload continues by itself, which is what the row
   * and the kept container are for.
   */
  async resumeChunk(job, client, { signal } = {}) {
    try {
      await client.upload(joinStoredPath(job.dir, job.remoteName), job.payloadPath, {
        signal,
        onProgress: (progress) => {
          if (!progress) return
          if (progress.phase) job.phase = progress.phase
          if (Number.isFinite(progress.sent)) {
            job.sentBytes = Math.min(job.uploadBytes, Math.max(job.sentBytes, progress.sent))
          }
          if (progress.stored === null) job.storedBytes = null
          else if (Number.isFinite(progress.stored)) {
            job.storedBytes = Math.min(job.uploadBytes, Math.max(job.storedBytes || 0, progress.stored))
          }
          this.#scheduleEmit()
        },
      })
      await this.finishRestored(job, signal?.aborted ? 'cancelled' : 'done')
    } catch (err) {
      await this.finishRestored(job, signal?.aborted ? 'cancelled' : 'failed', err.message)
    }
  }

  /** Close out a row that cannot be resumed at all, so the user is told why. */
  failPending(entry, message) {
    const now = Date.now()
    const job = {
      id: entry.id,
      sessionId: '',
      dir: entry.dir || '/',
      name: entry.name || '',
      srcPath: '',
      encrypt: !!entry.encrypt,
      target: null,
      taskId: entry.taskId || '',
      remoteName: entry.remoteName || '',
      payloadPath: '',
      status: 'failed',
      phase: 'uploading',
      totalBytes: entry.totalBytes || 0,
      uploadBytes: entry.uploadBytes || 0,
      encryptRatio: 1,
      sentBytes: entry.sentBytes || 0,
      storedBytes: null,
      error: message,
      startedAt: entry.startedAt || now,
      finishedAt: now,
    }
    this.jobs.set(job.id, job)
    this.pendingUploads?.remove(entry.id)
    this.#remember(job)
    this.#scheduleEmit()
  }

  /** Progress reported by someone else's watcher (a resumed upload). */
  touch() {
    this.#scheduleEmit()
  }

  /**
   * Drop one settled row, and its line in the history with it.
   *
   * The file itself is untouched — that is what `cloud.removeStored` is for, and
   * keeping the two apart is what lets the page offer "record only".
   *
   * @returns {boolean} whether a row was there to drop
   */
  forget(id) {
    const job = this.jobs.get(id)
    if (!job) return false
    if (job.status === 'queued' || job.status === 'running') return false
    this.jobs.delete(id)
    this.history?.forget('upload', [id])
    this.#scheduleEmit()
    return true
  }

  /**
   * Drop kept containers nothing points at any more.
   *
   * A crash between "the upload finished" and "the row was removed" leaves one
   * behind, and it can be gigabytes; this runs once per launch, before the
   * queue has any job of its own.
   *
   * @returns {Promise<number>} how many files were removed
   */
  async sweepPayloads() {
    let names = []
    try {
      names = await fsp.readdir(this.payloadDir)
    } catch {
      return 0
    }
    const kept = new Set(
      (this.pendingUploads?.entries() || []).map((entry) => entry.payloadPath).filter(Boolean),
    )
    let removed = 0
    for (const name of names) {
      if (!name.endsWith('.payload')) continue
      const full = path.join(this.payloadDir, name)
      if (kept.has(full)) continue
      if (this.jobs.has(name.slice(0, -'.payload'.length))) continue
      await fsp.rm(full, { force: true }).catch(() => {})
      removed += 1
    }
    return removed
  }

  /** Close out a resumed job the way `#run` would have. */
  finishRestored(job, status, error = '') {
    this.controllers.delete(job.id)
    job.status = status
    job.error = error
    job.finishedAt = Date.now()
    if (status === 'done') {
      job.sentBytes = job.uploadBytes
      job.storedBytes = job.uploadBytes
    }
    // The kept container has done its job either way; a failed job is not
    // retried from it, and leaving it behind would only fill the disk.
    const cleanup = job.payloadPath
      ? fsp.rm(job.payloadPath, { force: true }).catch(() => {})
      : Promise.resolve()
    job.payloadPath = ''
    this.pendingUploads?.remove(job.id)
    this.#remember(job)
    this.#scheduleEmit()
    return cleanup
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
    if (!list.length) throw new Error(t('errors.noFiles'))

    // Resolve once per batch: it validates the session and fails before any
    // job is created, so the UI never shows rows that can only fail.
    const target = this.cloud.uploadTarget(sessionId, plainPath)
    if (encrypt && !target.password) {
      throw new Error(t('errors.profileNoMediaPasswordUpload'))
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
        /** Server-side task id, once AList has queued the storage write. */
        taskId: '',
        /** Chunked uploads: the name on the server and the kept container. */
        remoteName: '',
        payloadPath: '',
        status: 'queued',
        phase: 'encrypting',
        totalBytes: 0, // source file size
        uploadBytes: 0, // container size, i.e. what actually goes over the wire
        encryptRatio: 0,
        sentBytes: 0,
        storedBytes: null, // null: the server does not report the cloud write
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
      if (!stat.isFile()) throw new Error(t('errors.notAFile'))
      job.totalBytes = stat.size
      job.uploadBytes = stat.size

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
            job.encryptRatio = Math.min(1, processed / Math.max(1, stat.size))
            this.#scheduleEmit()
          },
        })
        job.encryptRatio = 1
        sourcePath = temp
        // The container carries a header, the preview envelope and a 16-byte tag
        // per MiB, so the transfer is measured against its own size instead of
        // the source's — otherwise the bar would finish slightly past 100%.
        job.uploadBytes = (await fsp.stat(temp)).size
      }

      // Enough to find this file on the server later: the account that owns it
      // and the name it was stored under (a ciphertext token when encrypting).
      job.profileId = target?.profileId || job.profileId || ''
      job.remoteName = uploadName

      // A chunked upload is the only kind the next launch can finish on its
      // own, and only while the container still exists — `tempDir` is not a
      // promise, so the bytes move to [payloadDir] before the first chunk goes
      // out. A plain upload's source is copied, never moved.
      if (this.#isChunked(job, target)) {
        job.payloadPath = await this.#keepPayload(sourcePath, job, { move: job.encrypt })
        // Send from the kept copy, so what a restart would resend is exactly
        // what this attempt sent.
        sourcePath = job.payloadPath
      }

      job.phase = 'uploading'
      this.#scheduleEmit()
      // Written before the body moves: for a chunked upload this is the row
      // that makes a crash survivable, for the rest it says "not delivered yet".
      this.#track(job)

      await target.client.upload(joinStoredPath(target.dir, uploadName), sourcePath, {
        signal: controller.signal,
        onProgress: (progress) => {
          if (!progress) return
          // A downgraded retry re-sends the whole file: start the two counters
          // over instead of leaving the bar parked where the last attempt died.
          if (progress.restart) {
            job.sentBytes = 0
            job.storedBytes = null
          }
          if (progress.phase) job.phase = progress.phase
          // From here the server owns the write, and this id is what lets the
          // next launch pick it up.
          if (progress.taskId) job.taskId = progress.taskId
          if (progress.taskId || progress.restart) this.#track(job)
          if (Number.isFinite(progress.sent)) {
            job.sentBytes = Math.min(job.uploadBytes, Math.max(job.sentBytes, progress.sent))
          }
          // `null` means "the server does not say", which is not zero: the UI
          // shows the waiting state rather than a stalled dark segment.
          if (progress.stored === null) job.storedBytes = null
          else if (Number.isFinite(progress.stored)) {
            job.storedBytes = Math.min(job.uploadBytes, Math.max(job.storedBytes || 0, progress.stored))
          }
          this.#scheduleEmit()
        },
      })

      if (controller.signal.aborted) throw new Error(t('errors.cancelled'))
      job.sentBytes = job.uploadBytes
      job.storedBytes = job.uploadBytes
      job.status = 'done'
    } catch (err) {
      job.status = controller.signal.aborted ? 'cancelled' : 'failed'
      job.error = err.message
    } finally {
      this.controllers.delete(job.id)
      if (temp) await fsp.rm(temp, { force: true }).catch(() => {})
      if (job.payloadPath) {
        await fsp.rm(job.payloadPath, { force: true }).catch(() => {})
        job.payloadPath = ''
      }
      // Resolved either way — done, failed or cancelled: nothing is in flight
      // on the server that the next launch should look for.
      this.pendingUploads?.remove(job.id)
      // The credentials have served their purpose; do not keep them alive in a
      // finished row.
      job.target = null
      target.password = ''
      job.finishedAt = Date.now()
      this.#remember(job)
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
    for (const [id, job] of this.jobs) {
      if (['done', 'failed', 'cancelled'].includes(job.status)) {
        this.jobs.delete(id)
        cleared.push(id)
      }
    }
    // The history is what makes them come back on the next launch.
    this.history?.forget('upload', cleared)
    this.#scheduleEmit()
    return this.snapshot()
  }

  cancelAll() {
    for (const id of this.jobs.keys()) this.cancel(id)
  }
}
