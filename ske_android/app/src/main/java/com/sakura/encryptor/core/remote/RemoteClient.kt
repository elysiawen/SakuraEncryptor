package com.sakura.encryptor.core.remote

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

import com.sakura.encryptor.core.alist.FileEntry
import com.sakura.encryptor.core.alist.GetData
import com.sakura.encryptor.core.alist.GetRequest
import com.sakura.encryptor.core.alist.ListData
import com.sakura.encryptor.core.alist.ListRequest
import com.sakura.encryptor.core.alist.LoginData
import com.sakura.encryptor.core.alist.LoginRequest
import com.sakura.encryptor.core.alist.MkdirRequest
import com.sakura.encryptor.core.alist.ProgressRequestBody
import com.sakura.encryptor.core.alist.RemoveRequest
import com.sakura.encryptor.core.alist.RenameRequest
import com.sakura.encryptor.core.alist.UploadTaskInfo
import com.sakura.encryptor.core.alist.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Raised for any failed remote call.
 *
 * [code] is the envelope `code` the server answered with, or -1 for transport,
 * parsing and local validation failures.
 */
class RemoteException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 402 means this account has TOTP enabled and a code has to be supplied. */
    val needsOtp: Boolean get() = code == 402

    /** Set when a multipart upload is refused by a server that does not have it. */
    var multipartUnsupported: Boolean = false
}

/**
 * Transport shared by every remote backend.
 *
 * AList and its forks expose the same `/api` surface, so everything except the
 * optional chunked upload lives here. Subclasses only name themselves and
 * override what their server genuinely does differently; nothing in this
 * class may branch on the backend.
 */
abstract class RemoteClient(
    protected val client: OkHttpClient,
    internal val json: Json = DEFAULT_JSON,
) {

    companion object {
        internal val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            isLenient = true
        }
        internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        internal val OCTET_MEDIA = "application/octet-stream".toMediaType()
        internal const val OK = 200

        /** Multipart is worth its overhead from this size on. */
        internal const val MULTIPART_MIN_BYTES = 32L * 1024 * 1024

        /** The servers clamp `per_page` above 500. */
        private const val MAX_PER_PAGE = 500

        /** Guard against a server that ignores paging and never reports a total. */
        private const val MAX_LIST_PAGES = 200

        /** How often the server's own storage write is polled. */
        private const val TASK_POLL_MS = 600L

        /** Task states that mean the write is over; anything else keeps polling. */
        private val TASK_DONE_STATES = setOf("succeeded")
        private val TASK_FAILED_STATES = setOf("failed", "canceled", "cancelled", "errored")
    }

    /** Backend name used in error copy. */
    protected abstract val label: String

    /**
     * Whether [upload] takes the chunked path for a container of [size].
     *
     * The queue asks before it starts, because a chunked upload keeps its
     * payload on disk and can be picked up again after a restart, while a
     * single request cannot.
     */
    fun usesChunkedUpload(size: Long): Boolean = supportsMultipart && size >= MULTIPART_MIN_BYTES

    /** Whether this backend serves the `/api/fs/multipart` endpoints at all. */
    protected open val supportsMultipart: Boolean get() = false

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    suspend fun login(
        server: String,
        username: String,
        password: String,
        otpCode: String = "",
    ): String {
        val body = json.encodeToString(
            LoginRequest.serializer(),
            LoginRequest(username, password, otpCode.takeIf { it.isNotBlank() }),
        )
        val data = requestJson("POST", url(server, "/api/auth/login"), token = null, body = body)
        return json.decodeFromJsonElement(LoginData.serializer(), data).token
    }

    /** Best-effort profile probe; returns null when the server does not expose it. */
    suspend fun me(server: String, token: String): UserInfo? {
        for (endpoint in listOf("/api/me", "/api/auth/me")) {
            try {
                val data = requestJson("GET", url(server, endpoint), token = token, body = null)
                if (data !is JsonNull) return json.decodeFromJsonElement(UserInfo.serializer(), data)
            } catch (_: RemoteException) {
                // Try the next endpoint.
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // File system
    // -----------------------------------------------------------------------

    /**
     * List a directory, all of it.
     *
     * The servers default `per_page` to 200 and silently drop the rest, so a
     * single request truncates any folder with more than 200 entries. This
     * walks the pages with the largest accepted page size until `total` is
     * reached.
     *
     * The page size must stay constant across requests: the server computes the
     * offset from the `per_page` it actually applied, so changing it mid-listing
     * would skip or repeat entries.
     */
    suspend fun list(server: String, token: String, path: String, refresh: Boolean = false): ListData {
        val content = mutableListOf<FileEntry>()
        val seen = HashSet<String>()
        // Drops duplicates as well: a server that ignores paging repeats the
        // first page, which would otherwise loop until the page guard.
        fun append(entries: List<FileEntry>): Int {
            var added = 0
            for (entry in entries) {
                if (seen.add("${entry.name}|${entry.isDir}")) {
                    // The listing already carries the preview link, so a grid of
                    // plain files needs no `/api/fs/get` per tile.
                    content += entry.copy(thumb = absoluteThumb(server, entry.thumb))
                    added++
                }
            }
            return added
        }

        var perPage = MAX_PER_PAGE
        var total: Long? = null
        var first = ListData()
        var page = 1
        while (page <= MAX_LIST_PAGES) {
            val data = listPage(
                server = server,
                token = token,
                path = path,
                page = page,
                perPage = perPage,
                refresh = page == 1 && refresh,
            )
            if (page == 1) first = data

            val chunk = data.content.orEmpty()
            val added = append(chunk)
            data.total?.let { total = it }

            if (chunk.isEmpty() || added == 0) break
            val known = total
            if (known != null && content.size >= known) break
            // The server applied a smaller page size than requested; match it so
            // the next offset lines up with what it will return.
            if (chunk.size < perPage) perPage = chunk.size
            page++
        }

        val resolvedTotal = maxOf(total ?: 0L, content.size.toLong())
        return first.copy(
            content = content,
            total = resolvedTotal,
            hasMore = content.size.toLong() < resolvedTotal,
        )
    }

    private suspend fun listPage(
        server: String,
        token: String,
        path: String,
        page: Int,
        perPage: Int,
        refresh: Boolean,
    ): ListData {
        val body = json.encodeToString(
            ListRequest.serializer(),
            ListRequest(path = path, page = page, perPage = perPage, refresh = refresh),
        )
        val data = requestJson("POST", url(server, "/api/fs/list"), token, body)
        return if (data is JsonNull) ListData() else json.decodeFromJsonElement(ListData.serializer(), data)
    }

    suspend fun get(server: String, token: String, path: String): GetData {
        val body = json.encodeToString(GetRequest.serializer(), GetRequest(path))
        val data = requestJson("POST", url(server, "/api/fs/get"), token, body)
        if (data is JsonNull) throw RemoteException(-1, t(R.string.remote_server_returned_file_info, path))
        return json.decodeFromJsonElement(GetData.serializer(), data)
    }

    suspend fun mkdir(server: String, token: String, path: String) {
        val body = json.encodeToString(MkdirRequest.serializer(), MkdirRequest(path))
        requestJson("POST", url(server, "/api/fs/mkdir"), token, body)
    }

    suspend fun rename(server: String, token: String, path: String, newName: String) {
        val body = json.encodeToString(RenameRequest.serializer(), RenameRequest(path, newName))
        requestJson("POST", url(server, "/api/fs/rename"), token, body)
    }

    suspend fun remove(server: String, token: String, dir: String, names: List<String>) {
        val body = json.encodeToString(RemoveRequest.serializer(), RemoveRequest(dir, names))
        requestJson("POST", url(server, "/api/fs/remove"), token, body)
    }

    /**
     * Upload [file] as [fileName] into [dirPath].
     *
     * Backends that serve the chunked endpoint switch to it from 32 MB on; when
     * the server refuses it (403/404/405 or a missing `upload_id`) the classic
     * form upload runs instead. Any other failure must surface to the caller.
     */
    suspend fun upload(
        server: String,
        token: String,
        dirPath: String,
        fileName: String,
        file: File,
        asTask: Boolean = false,
        onProgress: ((bytesWritten: Long, contentLength: Long) -> Unit)? = null,
        onTaskId: ((String) -> Unit)? = null,
        onCall: ((Call) -> Unit)? = null,
    ) {
        val remotePath = normalizeUploadPath(dirPath, fileName)

        if (usesChunkedUpload(file.length())) {
            try {
                uploadMultipart(server, token, remotePath, file, onProgress, onCall)
                return
            } catch (e: RemoteException) {
                // Older server, or the driver does not support chunking.
                if (!e.multipartUnsupported) throw e
            }
        }

        // `As-Task` is what makes the second half observable — and, with the id
        // handed to [onTaskId], recoverable after this process is gone.
        if (asTask && uploadAsTask(server, token, remotePath, file, onProgress, onTaskId, onCall)) {
            return
        }

        // Fallback path. `As-Task` is asked for here too: AList and OpenList
        // both accept it on the form endpoint (the web client relies on it for
        // every upload), so a server without the raw-body endpoint still gets a
        // chance at a resumable task.
        val taskId = uploadForm(
            server, token, remotePath, fileName, file, asTask, onProgress, onCall,
        )
        if (taskId != null) {
            onTaskId?.invoke(taskId)
            waitForUploadTask(server, token, taskId, file.length(), onProgress)
        }
    }

    /**
     * `PUT /api/fs/put` with `As-Task: true`.
     *
     * The raw-body endpoint answers with a task id before the storage write is
     * over, which is what both other clients use on AList. A server that does
     * not serve it says so with 403/404/405; the caller then falls back to the
     * form upload above, which asks for the same task.
     *
     * @return false when this endpoint is unavailable, true when the upload was
     *   handled (synchronously or through a task).
     */
    private suspend fun uploadAsTask(
        server: String,
        token: String,
        remotePath: String,
        file: File,
        onProgress: ((Long, Long) -> Unit)?,
        onTaskId: ((String) -> Unit)?,
        onCall: ((Call) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        val size = file.length()
        val fileBody = file.asRequestBody(OCTET_MEDIA)
        val body = if (onProgress != null) ProgressRequestBody(fileBody, onProgress) else fileBody
        val request = Request.Builder()
            .url(url(server, "/api/fs/put"))
            .put(body)
            .header("Authorization", token)
            .header("File-Path", remotePath)
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", size.toString())
            .header("As-Task", "true")
            .build()

        val raw = sendRaw(request, onCall)
        if (raw.httpCode in 403..405) return@withContext false

        val envelope = readEnvelope(raw)
        if (!envelope.ok) {
            throw RemoteException(
                envelope.code,
                envelope.message ?: t(R.string.remote_upload_failed_http, raw.httpCode),
            )
        }

        val taskId = taskIdOf(envelope.data)
        if (taskId == null) {
            // Synchronous write: the answer only exists once the file is stored.
            onProgress?.invoke(size, size)
            return@withContext true
        }

        // Told before the wait starts, so a launch that dies mid-write still
        // leaves the id somewhere the next one can find it.
        onTaskId?.invoke(taskId)
        waitForUploadTask(server, token, taskId, size, onProgress)
        true
    }

    /**
     * Watch one upload task until the server is done with it.
     *
     * [strict] separates the two callers. During a live upload a task that
     * cannot be read means "no news worth failing over" — the bytes were
     * delivered either way. After a restart it means we genuinely do not know
     * whether the file landed, and reporting success would be a lie.
     */
    suspend fun waitForUploadTask(
        server: String,
        token: String,
        taskId: String,
        size: Long,
        onProgress: ((Long, Long) -> Unit)? = null,
        strict: Boolean = false,
    ) {
        var completed = false
        try {
            while (true) {
                delay(TASK_POLL_MS)
                val task = findUploadTask(server, token, taskId)
                if (task == null) {
                    if (strict) throw RemoteException(-1, t(R.string.upload_task_gone))
                    return
                }

                val percent = task.progress.coerceIn(0.0, 100.0)
                if (size > 0) onProgress?.invoke((size * percent / 100.0).toLong(), size)

                val state = task.state?.lowercase().orEmpty()
                if (state in TASK_FAILED_STATES) {
                    throw RemoteException(-1, task.error ?: t(R.string.upload_upload_failed))
                }
                if (state in TASK_DONE_STATES || percent >= 100.0) {
                    completed = true
                    onProgress?.invoke(size, size)
                    return
                }
            }
        } catch (e: CancellationException) {
            // The storage write keeps running after the user stops it unless the
            // server is told, and a cancelled coroutine cannot make that call.
            taskAction("cancel", server, token, taskId)
            throw e
        } finally {
            // Finished rows pile up in the server's task list otherwise.
            if (completed) taskAction("delete", server, token, taskId)
        }
    }

    /** Stop a task the server is running on its own; best effort. */
    suspend fun cancelUploadTask(server: String, token: String, taskId: String) {
        taskAction("cancel", server, token, taskId)
    }

    /**
     * Chunked upload hook. The default refuses the feature, which makes [upload]
     * fall back to the form request.
     */
    protected open suspend fun uploadMultipart(
        server: String,
        token: String,
        remotePath: String,
        file: File,
        onProgress: ((Long, Long) -> Unit)?,
        onCall: ((Call) -> Unit)?,
    ) {
        throw RemoteException(-1, t(R.string.remote_support_chunked_upload, label)).also { it.multipartUnsupported = true }
    }

    /** Direct download URL plus the signed size for a remote file. */
    suspend fun resolveRawUrl(server: String, token: String, path: String): Pair<String, Long> {
        val info = get(server, token, path)
        val raw = info.rawUrl ?: throw RemoteException(-1, t(R.string.remote_server_returned_download_url, path))
        return raw to info.size
    }

    // -----------------------------------------------------------------------
    // Upload internals
    // -----------------------------------------------------------------------

    /**
     * `PUT /api/fs/form`, the classic multipart upload.
     *
     * @return the server-side task id when `As-Task` was honoured, null when the
     *   server wrote synchronously (or was not asked for a task).
     */
    private suspend fun uploadForm(
        server: String,
        token: String,
        remotePath: String,
        fileName: String,
        file: File,
        asTask: Boolean,
        onProgress: ((Long, Long) -> Unit)?,
        onCall: ((Call) -> Unit)?,
    ): String? = withContext(Dispatchers.IO) {
        val fileBody = file.asRequestBody(OCTET_MEDIA)
        val partBody = if (onProgress != null) ProgressRequestBody(fileBody, onProgress) else fileBody
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, partBody)
            .build()
        val request = Request.Builder()
            .url(url(server, "/api/fs/form"))
            .put(body)
            .header("Authorization", token)
            .header("File-Path", remotePath)
            .apply { if (asTask) header("As-Task", "true") }
            .build()

        // Form uploads report their own progress through the request body, so
        // the envelope here is only checked for success.
        val raw = sendRaw(request, onCall)
        val envelope = readEnvelope(raw)
        if (!envelope.ok) {
            throw RemoteException(
                envelope.code,
                envelope.message ?: t(R.string.remote_upload_failed_http, raw.httpCode),
            )
        }
        taskIdOf(envelope.data)
    }

    // -----------------------------------------------------------------------
    // Server-side upload tasks
    // -----------------------------------------------------------------------

    /** `data.task.id` from an `As-Task` answer, or null for a synchronous write. */
    private fun taskIdOf(data: JsonElement?): String? {
        val obj = data as? JsonObject ?: return null
        val task = obj["task"] as? JsonObject ?: return null
        return (task["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** One poll of `/api/task/upload/info`; null when the task cannot be read. */
    private suspend fun findUploadTask(
        server: String,
        token: String,
        taskId: String,
    ): UploadTaskInfo? {
        val query = "/api/task/upload/info?tid=${encodeQuery(taskId)}"
        val data = runCatching { requestJson("POST", url(server, query), token, "{}") }.getOrNull()
            ?: return null

        val candidates = when (data) {
            is JsonArray -> data.mapNotNull { toUploadTask(it) }
            else -> listOfNotNull(toUploadTask(data))
        }
        // Documented as an array of tasks; a build that answers with the task
        // itself has no id to compare against, so it is taken as-is.
        return candidates.firstOrNull { it.id == taskId }
            ?: candidates.firstOrNull { it.id.isEmpty() }
    }

    private fun toUploadTask(element: JsonElement?): UploadTaskInfo? {
        val obj = element as? JsonObject ?: return null
        val task = obj["task"] as? JsonObject ?: obj
        return runCatching {
            json.decodeFromJsonElement(UploadTaskInfo.serializer(), task)
        }.getOrNull()
    }

    /**
     * Cancel or delete a task server-side.
     *
     * Never throws: this runs while an upload is already failing or being
     * cancelled, where a second error would only hide the first one.
     */
    private suspend fun taskAction(name: String, server: String, token: String, taskId: String) {
        withContext(NonCancellable) {
            runCatching {
                val query = "/api/task/upload/$name?tid=${encodeQuery(taskId)}"
                requestJson("POST", url(server, query), token, "{}")
            }
        }
    }

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    protected data class RawResponse(val httpCode: Int, val text: String)

    protected data class Envelope(
        val httpCode: Int,
        val code: Int,
        val message: String?,
        val data: JsonElement?,
    ) {
        val ok: Boolean get() = httpCode in 200..299 && code == OK
    }

    internal fun url(server: String, path: String): String = server.trimEnd('/') + path

    /**
     * Absolute form of a preview link the server returned.
     *
     * Drivers hand out either a CDN url or a site-relative `/p/...` path, and an
     * empty string when nothing can be generated. Only the grid consumes this,
     * so normalising here keeps every caller free of the distinction.
     */
    protected fun absoluteThumb(server: String, value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            return raw
        }
        if (raw.startsWith("//")) return "https:$raw"
        return server.trimEnd('/') + if (raw.startsWith("/")) raw else "/$raw"
    }

    protected fun normalizeUploadPath(dir: String, fileName: String): String {
        val trimmed = dir.trim()
        if (trimmed.isEmpty() || trimmed == "/") throw RemoteException(-1, t(R.string.remote_upload_path_cannot_empty))
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return encodePath("$withSlash/$fileName")
    }

    /** Percent-encode each path segment but keep the slashes. */
    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    internal suspend fun requestJson(
        method: String,
        url: String,
        token: String?,
        body: String?,
    ): JsonElement = withContext(Dispatchers.IO) {
        val requestBody: RequestBody? = body?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder().url(url)
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(JSON_MEDIA))
            "PUT" -> builder.put(requestBody ?: ByteArray(0).toRequestBody(JSON_MEDIA))
            else -> throw RemoteException(-1, t(R.string.remote_unsupported_request_method, method))
        }
        if (token != null) builder.header("Authorization", token)

        val envelope = readEnvelope(sendRaw(builder.build(), null))
        if (!envelope.ok) {
            throw RemoteException(
                envelope.code,
                envelope.message ?: t(R.string.remote_request_failed_http, envelope.httpCode),
            )
        }
        envelope.data ?: JsonNull
    }

    protected fun sendRaw(request: Request, onCall: ((Call) -> Unit)?): RawResponse {
        val call = client.newCall(request)
        // The caller may need the handle to cancel a large upload.
        onCall?.invoke(call)
        return executeCall(call)
    }

    private fun executeCall(call: Call): RawResponse {
        val response = try {
            call.execute()
        } catch (e: Exception) {
            throw RemoteException(-1, t(R.string.remote_network_request_failed, e.message ?: e.javaClass.simpleName), e)
        }

        response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: Exception) {
                throw RemoteException(-1, t(R.string.remote_failed_read_response), e)
            }
            return RawResponse(resp.code, text)
        }
    }

    protected fun readEnvelope(raw: RawResponse): Envelope {
        val root = try {
            json.parseToJsonElement(raw.text)
        } catch (_: Exception) {
            return Envelope(raw.httpCode, -1, null, JsonNull)
        }
        val obj = root as? JsonObject ?: return Envelope(raw.httpCode, -1, null, JsonNull)
        val code = (obj["code"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: -1
        val message = (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        return Envelope(raw.httpCode, code, message, obj["data"] ?: JsonNull)
    }
}
