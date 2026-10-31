package com.sakura.encryptor.core.upload

import android.net.Uri
import com.sakura.encryptor.core.alist.AListRepository
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.local.CryptoTaskRunner
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
}

/**
 * A queued or finished "encrypt + upload" job.
 *
 * Rows live for the process only: the picked documents are content URIs whose
 * read grant belongs to the current activity, so pretending the job survives a
 * restart would only produce failures the user cannot fix.
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
    internal val uri: Uri,
    internal val parentPath: String,
)

/**
 * Upload queue for the cloud browser.
 *
 * Mirrors the desktop `UploadQueue`: jobs are queued per file, two run at once,
 * progress is pushed, and cancelling a running job aborts its HTTP call.
 */
class UploadManager(
    private val repository: AListRepository,
    private val vault: VaultSession,
    private val runner: CryptoTaskRunner,
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
            throw IllegalStateException("缺少加密主密码，无法加密上传")
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
    }

    fun delete(id: String) {
        val task = currentTask(id) ?: return
        if (task.status == UploadStatus.RUNNING || task.status == UploadStatus.QUEUED) return
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.status.isFinished } }
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
                runner.encryptToCache(task.uri, password!!) { done, total ->
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
                runner.copyToCache(task.uri)
            }
            payload = built

            if (sourceTotal <= 0) sourceTotal = built.length()
            val size = sourceTotal
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
                throw CancellationException("上传已取消")
            }

            repository.upload(
                parentPath = task.parentPath,
                fileName = remoteName,
                file = built,
                onProgress = { sent, length ->
                    val bodySize = if (length > 0) length else built.length()
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
                    report(UploadPhase.UPLOADING, done, size)
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
                        error = if (cancelled) null else (e.message ?: "上传失败"),
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
        } finally {
            payload?.delete()
            calls.remove(id)
            jobs.remove(id)
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
    }
}

private val UploadStatus.isFinished: Boolean
    get() = this == UploadStatus.DONE ||
        this == UploadStatus.FAILED ||
        this == UploadStatus.CANCELLED
