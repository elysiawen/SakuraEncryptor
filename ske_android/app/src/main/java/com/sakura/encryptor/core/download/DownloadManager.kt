package com.sakura.encryptor.core.download

import android.content.Context
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.alist.AListSession
import com.sakura.encryptor.core.alist.DecryptedEntry
import com.sakura.encryptor.core.crypto.SkeFileCipher
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.data.db.DownloadStatus
import com.sakura.encryptor.data.db.DownloadTaskEntity
import com.sakura.encryptor.data.db.SakuraDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Download + decrypt" pipeline with pause / resume / cancel.
 *
 * The encrypted container is streamed into an app-private `.part` file, so a
 * paused task keeps its progress: the partial ciphertext is plain bytes, which
 * means an HTTP `Range` request can pick up exactly where it stopped. Only once
 * the container is complete is it decrypted and moved into the output folder.
 */
class DownloadManager(
    private val context: Context,
    private val database: SakuraDatabase,
    private val api: AListApi,
    private val session: AListSession,
    private val vault: VaultSession,
    private val client: OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = database.downloadDao().observeAll()

    /** Register a new queued task and return its id. */
    suspend fun enqueue(entry: DecryptedEntry, remotePath: String): String {
        val id = UUID.randomUUID().toString()
        database.downloadDao().upsert(
            DownloadTaskEntity(
                id = id,
                displayName = entry.displayName,
                remotePath = remotePath,
                server = session.server,
                totalBytes = entry.size,
                downloadedBytes = 0,
                status = DownloadStatus.PENDING.name,
                createdAt = System.currentTimeMillis(),
            )
        )
        return id
    }

    /** Begin (or resume) a task in the background. Safe to call repeatedly. */
    fun start(id: String) {
        if (jobs[id]?.isActive == true) return

        jobs[id] = scope.launch {
            try {
                execute(id)
            } catch (e: CancellationException) {
                // Pause / cancel already persisted the intended status.
                throw e
            } catch (e: Exception) {
                val current = runCatching { database.downloadDao().find(id)?.status }.getOrNull()
                val userStopped = current == DownloadStatus.PAUSED.name ||
                    current == DownloadStatus.CANCELLED.name
                if (!userStopped) {
                    val task = runCatching { database.downloadDao().find(id) }.getOrNull()
                    database.downloadDao().updateProgress(
                        id = id,
                        status = DownloadStatus.FAILED.name,
                        downloadedBytes = task?.downloadedBytes ?: 0,
                        totalBytes = task?.totalBytes ?: 0,
                        localUri = null,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            } finally {
                calls.remove(id)
                jobs.remove(id)
            }
        }
    }

    /** Stop the task but keep the partial file so it can be resumed. */
    fun pause(id: String) {
        scope.launch {
            val dao = database.downloadDao()
            val task = dao.find(id) ?: return@launch
            if (task.status != DownloadStatus.RUNNING.name && task.status != DownloadStatus.PENDING.name) {
                return@launch
            }

            val downloaded = partFile(id).takeIf { it.exists() }?.length() ?: task.downloadedBytes
            dao.updateProgress(
                id = id,
                status = DownloadStatus.PAUSED.name,
                downloadedBytes = downloaded,
                totalBytes = task.totalBytes,
                localUri = null,
                error = null,
            )

            // Abort the in-flight socket so the loop stops immediately.
            calls[id]?.cancel()
            jobs[id]?.cancel()
        }
    }

    /** Abort the task and discard its partial file. */
    fun cancel(id: String) {
        scope.launch {
            val dao = database.downloadDao()
            val task = dao.find(id) ?: return@launch

            dao.updateProgress(
                id = id,
                status = DownloadStatus.CANCELLED.name,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                localUri = null,
                error = null,
            )

            calls[id]?.cancel()
            jobs[id]?.cancel()
            partFile(id).delete()
        }
    }

    /** Re-run a failed or paused task, resuming from the partial file. */
    fun retry(id: String) {
        scope.launch {
            val dao = database.downloadDao()
            val task = dao.find(id) ?: return@launch
            dao.updateProgress(
                id = id,
                status = DownloadStatus.PENDING.name,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                localUri = null,
                error = null,
            )
            start(id)
        }
    }

    fun delete(id: String) {
        scope.launch {
            calls[id]?.cancel()
            jobs[id]?.cancel()
            partFile(id).delete()
            database.downloadDao().delete(id)
        }
    }

    suspend fun clearFinished() = database.downloadDao().clearFinished()

    /** Directory holding decrypted downloads, exposed for sharing / external players. */
    fun outputDirectory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "SakuraEncryptor").apply { mkdirs() }
    }

    fun outputFileFor(displayName: String): File {
        val safeName = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download" }
        return File(outputDirectory(), safeName)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /** Partial ciphertext lives here, keyed by task id, so it survives restarts. */
    private fun partFile(id: String): File {
        val dir = File(context.filesDir, "ske_downloads").apply { mkdirs() }
        return File(dir, "$id.part")
    }

    /** True when [file] starts with the `.ske` magic bytes. */
    private fun isSkeContainer(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(SkeFormat.MAGIC_SIZE)
            val read = input.read(magic)
            read == SkeFormat.MAGIC_SIZE && magic.contentEquals(SkeFormat.MAGIC)
        }
    }.getOrDefault(false)

    private suspend fun execute(id: String) = withContext(Dispatchers.IO) {
        val dao = database.downloadDao()
        val task = dao.find(id) ?: return@withContext

        val part = partFile(id)
        val alreadyDownloaded = part.takeIf { it.exists() }?.length() ?: 0L

        dao.updateProgress(
            id = id,
            status = DownloadStatus.RUNNING.name,
            downloadedBytes = alreadyDownloaded,
            totalBytes = task.totalBytes,
            localUri = null,
            error = null,
        )

        val (url, remoteSize) = api.resolveRawUrl(task.server, session.token, task.remotePath)
        val total = remoteSize.takeIf { it > 0 } ?: task.totalBytes

        if (total <= 0 || alreadyDownloaded < total) {
            var lastPersisted = alreadyDownloaded
            downloadToFile(id, url, part, alreadyDownloaded, total) { done ->
                if (done - lastPersisted >= PROGRESS_STEP || (total > 0 && done == total)) {
                    lastPersisted = done
                    dao.updateDownloadedBytes(id, done, total)
                }
            }
        }

        currentCoroutineContext().ensureActive()

        if (total > 0 && part.length() != total) {
            throw IOException("下载不完整：${part.length()} / $total 字节")
        }

        val plainTemp = File.createTempFile("dl_", ".plain", context.cacheDir)
        try {
            if (isSkeContainer(part)) {
                // Password is only required for actual containers.
                SkeFileCipher.decryptFile(part, plainTemp, vault.requirePassword())
            } else {
                part.copyTo(plainTemp, overwrite = true)
            }
            currentCoroutineContext().ensureActive()

            val output = outputFileFor(task.displayName)
            plainTemp.copyTo(output, overwrite = true)

            val finalSize = output.length()
            dao.updateProgress(
                id = id,
                status = DownloadStatus.DONE.name,
                downloadedBytes = finalSize,
                totalBytes = finalSize,
                localUri = output.absolutePath,
                error = null,
            )
            part.delete()
        } finally {
            plainTemp.delete()
        }
    }

    private suspend fun downloadToFile(
        id: String,
        url: String,
        target: File,
        alreadyDownloaded: Long,
        total: Long,
        onProgress: suspend (Long) -> Unit,
    ) {
        val builder = Request.Builder().url(url)
        if (alreadyDownloaded > 0) {
            builder.header("Range", "bytes=$alreadyDownloaded-")
        }

        val call = client.newCall(builder.build())
        calls[id] = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("服务器返回了空响应")

                // 206 means the resume was honoured; a 200 means the server sent
                // the whole file again, so we start the file over.
                val append = alreadyDownloaded > 0 && response.code == 206
                var done = if (append) alreadyDownloaded else 0L

                FileOutputStream(target, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            onProgress(done)
                        }
                    }
                }
            }
        } finally {
            calls.remove(id)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_STEP = 512 * 1024L
    }
}
