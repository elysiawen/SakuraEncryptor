package com.sakura.encryptor.core.download

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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

    /** Directory holding decrypted downloads when the public folder is unusable. */
    private fun privateOutputDirectory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, PUBLIC_DIR).apply { mkdirs() }
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    /**
     * Copy the decrypted bytes into the user-visible Downloads folder and return
     * where they landed.
     *
     * Android 10+ goes through MediaStore, which needs no permission and puts the
     * file somewhere a file manager actually shows (`Download/SakuraEncryptor/`).
     * Scoped storage forbids the raw `File` write the older branch still uses, and
     * that branch also needs WRITE_EXTERNAL_STORAGE — when it is missing the
     * download falls back to the app-private folder instead of failing outright,
     * which is also where every pre-Q device without the grant ends up.
     */
    private fun storeDecrypted(displayName: String, source: File): String {
        val safeName = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return storeViaMediaStore(safeName, source)
        }
        return if (hasLegacyWritePermission()) {
            storeInPublicFolderLegacy(safeName, source)
        } else {
            storePrivately(safeName, source)
        }
    }

    /** MediaStore path: the provider owns the file, so no permission is needed. */
    private fun storeViaMediaStore(name: String, source: File): String {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueDisplayName(resolver, relativePath, name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            // IS_PENDING hides the entry until the bytes are all there — without
            // it another app can open the file mid-copy and see a truncated one.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在下载目录创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法写入下载目录")
        } catch (e: Exception) {
            // An empty stub would otherwise sit in the user's Downloads forever.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    /** Pre-Q path: a plain file write into the shared Downloads folder. */
    private fun storeInPublicFolderLegacy(name: String, source: File): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLIC_DIR,
        )
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建目录：${dir.absolutePath}")
        }
        val target = File(dir, uniqueFileName(dir, name))
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    private fun storePrivately(name: String, source: File): String {
        val target = File(privateOutputDirectory(), name)
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    private fun hasLegacyWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun uniqueDisplayName(resolver: ContentResolver, relativePath: String, name: String): String {
        if (!entryExists(resolver, relativePath, name)) return name
        val (stem, ext) = splitExtension(name)
        var index = 1
        while (entryExists(resolver, relativePath, "$stem ($index)$ext")) index++
        return "$stem ($index)$ext"
    }

    private fun entryExists(resolver: ContentResolver, relativePath: String, name: String): Boolean =
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relativePath, name),
            null,
        )?.use { it.count > 0 } == true

    private fun uniqueFileName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val (stem, ext) = splitExtension(name)
        var index = 1
        while (File(dir, "$stem ($index)$ext").exists()) index++
        return "$stem ($index)$ext"
    }

    /** Leading-dot names count as having no extension, so `.ske` is never split. */
    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
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

            val localUri = storeDecrypted(task.displayName, plainTemp)
            val finalSize = plainTemp.length()
            dao.updateProgress(
                id = id,
                status = DownloadStatus.DONE.name,
                downloadedBytes = finalSize,
                totalBytes = finalSize,
                localUri = localUri,
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

        /** Folder inside the shared Downloads directory that the app writes to. */
        private const val PUBLIC_DIR = "SakuraEncryptor"
    }
}
