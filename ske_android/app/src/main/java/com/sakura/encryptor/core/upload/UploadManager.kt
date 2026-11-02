package com.sakura.encryptor.core.upload

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

import android.net.Uri
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.local.CryptoTaskRunner
import com.sakura.encryptor.core.remote.RemoteRepository
import com.sakura.encryptor.core.session.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class UploadStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED,
}

enum class UploadPhase {
    ENCRYPTING,
    UPLOADING,

    /** The bytes are on the server, which is still writing them to the cloud. */
    STORING,
}

/**
 * A queued or finished "encrypt + upload" job.
 *
 * A live row belongs to the process: the picked document is a content URI whose
 * read grant dies with the activity. What outlives a restart is the row that
 * has a [taskId] — the server is still writing on its own clock, and the next
 * launch can pick that task up (see [UploadManager.restorePending]).
 */
data class UploadTask(
    val id: String,
    val displayName: String,
    val dir: String,
    val encrypt: Boolean,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val status: UploadStatus = UploadStatus.QUEUED,
    val phase: UploadPhase = UploadPhase.ENCRYPTING,
    val error: String? = null,
    val createdAt: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /** Server-side task id, once AList has queued the storage write. */
    val taskId: String = "",
    internal val uri: Uri? = null,
    internal val parentPath: String = "",
    /** Name on the server: the encrypted token, not the display name. */
    internal val remoteName: String = "",
)

/**
 * Upload queue for the cloud browser.
 *
 * Mirrors the desktop `UploadQueue`: jobs are queued per file, two run at once,
 * progress is pushed, and cancelling a running job aborts its HTTP call.
 *
 * The upload itself cannot survive a restart — the picked document is a content
 * URI whose read grant ends with the activity — but the *storage write* can.
 * AList takes the container with `As-Task` and hands back a task id; that id is
 * written to [pending] so the next launch can watch the same task finish
 * instead of re-uploading a file the server already has.
 */
class UploadManager(
    private val repository: RemoteRepository,
    private val vault: VaultSession,
    private val runner: CryptoTaskRunner,
    private val pending: PendingUploads,
    /** Where a chunked upload keeps its container between launches. */
    private val payloadDir: File,
    maxConcurrent: Int = DEFAULT_CONCURRENT,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limit = Semaphore(maxConcurrent.coerceAtLeast(1))
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()

    private val _tasks = MutableStateFlow<List<UploadTask>>(emptyList())
    val tasks: StateFlow<List<UploadTask>> = _tasks.asStateFlow()

    /** Emitted once a task reaches DONE so the browser can refresh its listing. */
    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val completed: SharedFlow<Unit> = _completed.asSharedFlow()

    /**
     * Queue [uris] for upload into [plainPath].
     *
     * [names] are the user-edited remote names; blank entries fall back to the
     * picked document's own name.
     */
    fun enqueue(
        uris: List<Uri>,
        names: List<String> = emptyList(),
        encrypt: Boolean,
        plainPath: String,
    ): List<UploadTask> {
        if (uris.isEmpty()) return emptyList()
        if (encrypt && !vault.isUnlocked) {
            throw IllegalStateException(t(R.string.upload_master_password_missing_cannot_encrypt_upload))
        }

        val now = System.currentTimeMillis()
        val created = uris.mapIndexed { index, uri ->
            val edited = names.getOrNull(index)?.trim().orEmpty()
            val name = edited.ifEmpty {
                runner.displayNameOf(uri) ?: "file_${now + index}"
            }
            UploadTask(
                id = UUID.randomUUID().toString(),
                displayName = name,
                dir = plainPath.ifBlank { "/" },
                encrypt = encrypt,
                totalBytes = runner.sizeOf(uri),
                createdAt = now + index,
                uri = uri,
                parentPath = plainPath,
            )
        }

        _tasks.update { current -> current + created }
        created.forEach { task ->
            val job = scope.launch(start = CoroutineStart.LAZY) { run(task.id) }
            jobs[task.id] = job
            job.start()
        }
        return created
    }

    /** Abort a queued or running job. Finished rows are left untouched. */
    fun cancel(id: String) {
        val task = currentTask(id) ?: return
        if (task.status.isFinished) return

        update(id) {
            it.copy(status = UploadStatus.CANCELLED, finishedAt = System.currentTimeMillis(), error = null)
        }
        // The abort is what stops a large body from being sent; marking the row
        // first means the run loop reports it as cancelled rather than failed.
        calls.remove(id)?.cancel()
        jobs.remove(id)?.cancel()
        if (task.taskId.isNotBlank()) {
            // The server keeps writing to the storage backend unless it is told,
            // and an adopted row has no request of ours left to abort.
            pending.remove(id)
            scope.launch { repository.cancelUploadTask(task.taskId) }
        }
    }

    fun delete(id: String) {
        val task = currentTask(id) ?: return
        if (task.status == UploadStatus.RUNNING || task.status == UploadStatus.QUEUED) return
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.status.isFinished } }
    }

    /**
     * Pick up uploads the server is still writing after a restart.
     *
     * Only rows that carry a task id and belong to the signed-in server can be
     * resumed: a task id means nothing on another account, and a row without
     * one describes a body that never finished crossing, which the server
     * forgets. The latter is dropped, the former is left for the launch that
     * signs back into that account.
     *
     * @return how many tasks were adopted.
     */
    fun restorePending(): Int {
        if (!repository.isLoggedIn) return 0
        sweepOrphanPayloads()
        val server = repository.server
        var adopted = 0
        for (entry in pending.entries()) {
            if (entry.server != server) continue
            if (jobs.containsKey(entry.id) || currentTask(entry.id) != null) continue
            when (entry.mode) {
                PendingMode.TASK -> {
                    if (entry.taskId.isBlank()) {
                        // Without a task id the server kept nothing of this
                        // body: there is nothing left to pick up.
                        pending.remove(entry.id)
                        continue
                    }
                    adoptTask(entry)
                }

                PendingMode.CHUNK -> {
                    if (!adoptChunked(entry)) continue
                }
            }
            adopted += 1
        }
        return adopted
    }

    private fun adoptTask(entry: PendingUpload) {
        val task = UploadTask(
            id = entry.id,
            displayName = entry.name,
            dir = entry.dir,
            encrypt = entry.encrypt,
            totalBytes = entry.totalBytes,
            doneBytes = 0,
            status = UploadStatus.RUNNING,
            phase = UploadPhase.STORING,
            createdAt = entry.startedAt,
            startedAt = entry.startedAt,
            taskId = entry.taskId,
            uri = null,
            parentPath = entry.dir,
            remoteName = entry.remoteName,
        )
        _tasks.update { current -> current + task }
        jobs[entry.id] = scope.launch { watch(task) }
    }

    /**
     * Bring a chunked upload back: the container is on disk, so the remaining
     * chunks can be sent again.
     *
     * OpenList only reuses an interrupted session when the request carries a
     * matching content hash, which this client does not compute — so the second
     * attempt usually starts a new session and re-sends from the first chunk.
     * The task still continues by itself, which is the point: nothing is lost
     * and the user does not have to pick the file again.
     *
     * @return false when the row cannot be resumed and has been dropped.
     */
    private fun adoptChunked(entry: PendingUpload): Boolean {
        // Only ever read a container this app put there itself: a row is a plain
        // JSON file, and nothing else should be uploadable through it.
        val payload = entry.payloadPath.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.parentFile?.absolutePath == payloadDir.absolutePath }
        if (payload == null || !payload.isFile) {
            // The bytes are gone: the row can only describe a failure now.
            pending.remove(entry.id)
            _tasks.update { current ->
                current + UploadTask(
                    id = entry.id,
                    displayName = entry.name,
                    dir = entry.dir,
                    encrypt = entry.encrypt,
                    totalBytes = entry.totalBytes,
                    doneBytes = 0,
                    status = UploadStatus.FAILED,
                    phase = UploadPhase.UPLOADING,
                    error = t(R.string.upload_payload_missing),
                    createdAt = entry.startedAt,
                    startedAt = entry.startedAt,
                    finishedAt = System.currentTimeMillis(),
                )
            }
            return false
        }

        val task = UploadTask(
            id = entry.id,
            displayName = entry.name,
            dir = entry.dir,
            encrypt = entry.encrypt,
            totalBytes = entry.totalBytes,
            doneBytes = 0,
            status = UploadStatus.RUNNING,
            phase = UploadPhase.UPLOADING,
            createdAt = entry.startedAt,
            startedAt = entry.startedAt,
            uri = null,
            parentPath = entry.dir,
            remoteName = entry.remoteName,
        )
        _tasks.update { current -> current + task }
        jobs[entry.id] = scope.launch { resumeChunked(task, payload) }
        return true
    }

    /** Send the rest of an adopted chunked upload, then close the row out. */
    private suspend fun resumeChunked(task: UploadTask, payload: File) {
        val id = task.id
        try {
            repository.upload(
                parentPath = task.parentPath,
                fileName = task.remoteName,
                file = payload,
                asTask = false,
                onProgress = { sent, total ->
                    update(id) { current ->
                        if (current.status != UploadStatus.RUNNING) {
                            current
                        } else {
                            current.copy(
                                phase = UploadPhase.UPLOADING,
                                doneBytes = sent.coerceAtLeast(0),
                                totalBytes = if (total > 0) total else current.totalBytes,
                            )
                        }
                    }
                },
            ).getOrThrow()

            update(id) { current ->
                if (current.status.isFinished) {
                    current
                } else {
                    current.copy(
                        status = UploadStatus.DONE,
                        doneBytes = current.totalBytes,
                        finishedAt = System.currentTimeMillis(),
                        error = null,
                    )
                }
            }
            pending.remove(id)
            payload.delete()
            _completed.tryEmit(Unit)
        } catch (e: CancellationException) {
            update(id) {
                if (it.status.isFinished) {
                    it
                } else {
                    it.copy(status = UploadStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                }
            }
            pending.remove(id)
            payload.delete()
            throw e
        } catch (e: Throwable) {
            update(id) {
                if (it.status.isFinished) {
                    it
                } else {
                    it.copy(
                        status = UploadStatus.FAILED,
                        error = e.message ?: t(R.string.upload_upload_failed),
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
            pending.remove(id)
            payload.delete()
        } finally {
            jobs.remove(id)
        }
    }

    /**
     * Move the container into `filesDir` and return the kept copy.
     *
     * Same volume, so this is a rename in practice; the copy path only exists
     * for the odd device where `filesDir` and `cacheDir` are split.
     */
    private fun keepPayload(source: File, id: String): File {
        payloadDir.mkdirs()
        val target = File(payloadDir, "$id.payload")
        if (source.absolutePath == target.absolutePath) return target
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return target
    }

    /** Remove containers no pending row points at any more. */
    private fun sweepOrphanPayloads() {
        val files = payloadDir.listFiles { file -> file.name.endsWith(PAYLOAD_SUFFIX) } ?: return
        if (files.isEmpty()) return
        val kept = pending.entries().mapNotNull { it.payloadPath.takeIf(String::isNotBlank) }.toSet()
        for (file in files) {
            if (file.absolutePath in kept) continue
            if (currentTask(file.name.removeSuffix(PAYLOAD_SUFFIX)) != null) continue
            file.delete()
        }
    }

    /** Poll one adopted task: nothing is left on our side but the server's write. */
    private suspend fun watch(task: UploadTask) {
        val id = task.id
        try {
            repository.waitForUploadTask(task.taskId, task.totalBytes) { stored, total ->
                update(id) { current ->
                    if (current.status != UploadStatus.RUNNING) {
                        current
                    } else {
                        current.copy(
                            phase = UploadPhase.STORING,
                            doneBytes = stored.coerceAtLeast(0),
                            totalBytes = if (total > 0) total else current.totalBytes,
                        )
                    }
                }
            }.getOrThrow()

            update(id) { current ->
                if (current.status.isFinished) {
                    current
                } else {
                    current.copy(
                        status = UploadStatus.DONE,
                        phase = UploadPhase.STORING,
                        doneBytes = current.totalBytes,
                        finishedAt = System.currentTimeMillis(),
                        error = null,
                    )
                }
            }
            pending.remove(id)
            _completed.tryEmit(Unit)
        } catch (e: CancellationException) {
            update(id) {
                if (it.status.isFinished) {
                    it
                } else {
                    it.copy(status = UploadStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                }
            }
            throw e
        } catch (e: Throwable) {
            update(id) {
                if (it.status.isFinished) {
                    it
                } else {
                    it.copy(
                        status = UploadStatus.FAILED,
                        error = e.message ?: t(R.string.upload_upload_failed),
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
            pending.remove(id)
        } finally {
            jobs.remove(id)
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun currentTask(id: String): UploadTask? =
        _tasks.value.firstOrNull { it.id == id }

    private fun update(id: String, transform: (UploadTask) -> UploadTask) {
        _tasks.update { list ->
            list.map { task -> if (task.id == id) transform(task) else task }
        }
    }

    private suspend fun run(id: String) = limit.withPermit {
        val task = currentTask(id) ?: return@withPermit
        if (task.status == UploadStatus.CANCELLED) return@withPermit
        // Adopted rows have no document left to read; they are handled by
        // [watch] instead, which only polls the server.
        val uri = task.uri ?: return@withPermit

        update(id) {
            it.copy(status = UploadStatus.RUNNING, startedAt = System.currentTimeMillis(), error = null)
        }

        var payload: File? = null
        val throttle = ProgressThrottle()

        fun report(phase: UploadPhase, done: Long, total: Long) {
            if (!throttle.shouldEmit(done, total, phase)) return
            update(id) { current ->
                if (current.status != UploadStatus.RUNNING) {
                    current
                } else {
                    current.copy(
                        phase = phase,
                        doneBytes = done.coerceAtLeast(0),
                        totalBytes = if (total > 0) total else current.totalBytes,
                    )
                }
            }
        }

        try {
            val password = if (task.encrypt) vault.requirePassword() else null
            val remoteName = if (task.encrypt) {
                SkeCrypto.encryptName(task.displayName, SkeCrypto.deriveNameKey(password!!)) +
                    SkeFormat.SKE_EXT
            } else {
                // A separator typed into the name field must not become a sub-path.
                task.displayName.replace('/', '_').replace('\\', '_')
            }

            var sourceTotal = task.totalBytes

            val built = if (task.encrypt) {
                runner.encryptToCache(uri, password!!) { done, total ->
                    if (total > 0) sourceTotal = total
                    val fraction = if (total > 0) {
                        (done.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    report(
                        UploadPhase.ENCRYPTING,
                        (sourceTotal * 0.5f * fraction).toLong(),
                        sourceTotal,
                    )
                }
            } else {
                runner.copyToCache(uri)
            }
            payload = built

            if (sourceTotal <= 0) sourceTotal = built.length()
            val size = sourceTotal

            // A chunked upload is the only kind this app can pick back up by
            // itself: the server keeps a half-finished session, not the file, so
            // the container has to outlive the process. Moving it out of
            // `cacheDir` (which the system may clear) is what makes the row in
            // [pending] usable on the next launch.
            var body = built
            if (repository.usesChunkedUpload(size)) {
                body = keepPayload(built, id)
                payload = body
                pending.upsert(
                    PendingUpload(
                        id = id,
                        server = repository.server,
                        dir = task.parentPath,
                        name = task.displayName,
                        encrypt = task.encrypt,
                        mode = PendingMode.CHUNK,
                        remoteName = remoteName,
                        payloadPath = body.absolutePath,
                        totalBytes = size,
                        startedAt = currentTask(id)?.startedAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            update(id) { current ->
                if (current.status != UploadStatus.RUNNING) {
                    current
                } else {
                    current.copy(
                        phase = UploadPhase.UPLOADING,
                        totalBytes = size,
                        doneBytes = if (task.encrypt) size / 2 else 0,
                    )
                }
            }

            currentCoroutineContext().ensureActive()
            if (currentTask(id)?.status == UploadStatus.CANCELLED) {
                throw CancellationException(t(R.string.upload_upload_cancelled))
            }

            repository.upload(
                parentPath = task.parentPath,
                fileName = remoteName,
                file = body,
                asTask = true,
                onProgress = { sent, length ->
                    val bodySize = if (length > 0) length else body.length()
                    val fraction = if (bodySize > 0) {
                        (sent.toFloat() / bodySize).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val done = if (task.encrypt) {
                        size / 2 + (size * 0.5f * fraction).toLong()
                    } else {
                        (size * fraction).toLong()
                    }
                    report(currentTask(id)?.phase ?: UploadPhase.UPLOADING, done, size)
                },
                onTaskId = { taskId ->
                    // From here the server owns the write. This row is the only
                    // handle on the task once this process is gone.
                    update(id) { it.copy(taskId = taskId, phase = UploadPhase.STORING) }
                    pending.upsert(
                        PendingUpload(
                            id = id,
                            server = repository.server,
                            dir = task.parentPath,
                            name = task.displayName,
                            encrypt = task.encrypt,
                            mode = PendingMode.TASK,
                            taskId = taskId,
                            remoteName = remoteName,
                            totalBytes = size,
                            startedAt = currentTask(id)?.startedAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                onCall = { call -> calls[id] = call },
            ).getOrThrow()

            currentCoroutineContext().ensureActive()
            update(id) { current ->
                if (current.status == UploadStatus.CANCELLED) {
                    current
                } else {
                    current.copy(
                        status = UploadStatus.DONE,
                        phase = UploadPhase.UPLOADING,
                        doneBytes = size,
                        totalBytes = size,
                        error = null,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
            _completed.tryEmit(Unit)
        } catch (e: CancellationException) {
            update(id) {
                if (it.status.isFinished) {
                    it
                } else {
                    it.copy(status = UploadStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                }
            }
            throw e
        } catch (e: Throwable) {
            val cancelled = currentTask(id)?.status == UploadStatus.CANCELLED
            update(id) {
                if (it.status.isFinished) {
                    it
                } else {
                    it.copy(
                        status = if (cancelled) UploadStatus.CANCELLED else UploadStatus.FAILED,
                        error = if (cancelled) null else (e.message ?: t(R.string.upload_upload_failed)),
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
        } finally {
            payload?.delete()
            calls.remove(id)
            jobs.remove(id)
            // Resolved either way — done, failed or cancelled: nothing is in
            // flight on the server that the next launch should look for.
            pending.remove(id)
        }
    }

    private class ProgressThrottle {
        private var lastPercent = -1
        private var lastPhase: UploadPhase? = null
        private var lastDone = 0L

        fun shouldEmit(done: Long, total: Long, phase: UploadPhase): Boolean {
            val percent = if (total > 0) ((done * 100) / total).toInt() else -1
            val advanced = done - lastDone >= PROGRESS_STEP
            if (phase == lastPhase && percent == lastPercent && !advanced) return false
            lastPhase = phase
            lastPercent = percent
            lastDone = done
            return true
        }
    }

    companion object {
        private const val DEFAULT_CONCURRENT = 2
        private const val PROGRESS_STEP = 256 * 1024L

        /** Extension of a container kept for a resumable upload. */
        private const val PAYLOAD_SUFFIX = ".payload"
    }
}

private val UploadStatus.isFinished: Boolean
    get() = this == UploadStatus.DONE ||
        this == UploadStatus.FAILED ||
        this == UploadStatus.CANCELLED
