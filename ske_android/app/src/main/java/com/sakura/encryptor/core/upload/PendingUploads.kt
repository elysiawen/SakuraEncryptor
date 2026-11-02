package com.sakura.encryptor.core.upload

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How a row can be picked back up. */
@Serializable
enum class PendingMode {
    /** The server took every byte and is writing it to storage: poll the task. */
    TASK,

    /** Chunked upload: the payload is kept on disk and the chunks are re-sent. */
    CHUNK,
}

/**
 * One upload that has not reached the cloud yet.
 *
 * A [PendingMode.TASK] row is recoverable through its [taskId] alone: the whole
 * container is already on the server. A [PendingMode.CHUNK] row is recoverable
 * through [payloadPath] — the container is still on this device, because a
 * chunked upload keeps it there until the merge succeeds.
 */
@Serializable
data class PendingUpload(
    val id: String,
    /** Server address the task belongs to; a task id means nothing elsewhere. */
    val server: String = "",
    val dir: String = "/",
    val name: String = "",
    val encrypt: Boolean = false,
    val mode: PendingMode = PendingMode.TASK,
    /** Task id, for [PendingMode.TASK] rows. */
    val taskId: String = "",
    /** Remote file name (already encrypted), needed to reopen a chunked upload. */
    val remoteName: String = "",
    /** Container kept in `filesDir`, for [PendingMode.CHUNK] rows. */
    val payloadPath: String = "",
    /** Size of the container on the wire, i.e. what the task progress is out of. */
    val totalBytes: Long = 0,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
private data class PendingFile(
    val version: Int = 1,
    val records: List<PendingUpload> = emptyList(),
)

/**
 * Uploads that have not reached the cloud yet, kept on disk.
 *
 * The Android counterpart of the desktop `pending-uploads.json`: a container
 * handed to AList is not yet a file in the cloud, because the server keeps
 * writing to the storage backend after the response. That write outlives the
 * app, and the task id is the only handle left on it — so it is written here
 * the moment AList returns it, and removed once the task resolves.
 *
 * Rows written before a chunked upload starts are the other shape: no task id
 * yet, but the container itself is kept under `filesDir`, which is what lets
 * the next launch send the remaining chunks.
 *
 * Deliberately not Room: this is a handful of rows that are only read once per
 * launch, and the desktop build keeps the same file for the same reason. Rows
 * live in `filesDir` (not `cacheDir`), because the system may clear the cache
 * between launches.
 *
 * No credential and no local path is ever written: the server address is enough
 * to sign back in, and the payload is gone by the time a row matters.
 */
class PendingUploads(
    private val file: File,
    private val limit: Int = DEFAULT_LIMIT,
    // coerceInputValues: a file written by a newer build (unknown mode) must not
    // wipe the whole list on the next launch.
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    },
) {

    private val lock = Any()
    private var records: List<PendingUpload> = emptyList()

    /** Read the file; a missing or damaged one simply means "nothing to resume". */
    fun load(): PendingUploads {
        val stored = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            json.decodeFromString(PendingFile.serializer(), file.readText(Charsets.UTF_8)).records
        }.getOrElse { error ->
            Log.w(TAG, "cannot read ${file.name}: ${error.message}")
            emptyList()
        }
        synchronized(lock) {
            records = stored.filter { it.id.isNotBlank() }
        }
        return this
    }

    fun entries(): List<PendingUpload> = synchronized(lock) { records }

    /** Add a row, or replace the one with the same id. */
    fun upsert(entry: PendingUpload) {
        if (entry.id.isBlank()) return
        synchronized(lock) {
            val index = records.indexOfFirst { it.id == entry.id }
            records = if (index >= 0) {
                records.toMutableList().apply { this[index] = entry }
            } else {
                (listOf(entry) + records).take(limit)
            }
        }
        flush()
    }

    fun remove(id: String) {
        val changed = synchronized(lock) {
            val remaining = records.filterNot { it.id == id }
            val changed = remaining.size != records.size
            records = remaining
            changed
        }
        if (changed) flush()
    }

    /**
     * Write now, atomically.
     *
     * Synchronous on purpose: the row that matters most is the one written the
     * instant before the app is killed, and a rename is cheap at this size.
     */
    fun flush() {
        val snapshot = synchronized(lock) { records }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(
                json.encodeToString(PendingFile.serializer(), PendingFile(records = snapshot)),
                Charsets.UTF_8,
            )
            if (!tmp.renameTo(file)) {
                // Windows-style rename-over-existing, and some Android volumes.
                file.delete()
                tmp.renameTo(file)
            }
        } catch (error: Exception) {
            Log.w(TAG, "cannot write ${file.name}: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "pending-uploads"

        /** Hard cap, so a crash loop cannot grow the file without bound. */
        const val DEFAULT_LIMIT = 200
    }
}
