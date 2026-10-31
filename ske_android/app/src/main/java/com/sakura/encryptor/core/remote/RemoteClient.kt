package com.sakura.encryptor.core.remote

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
import com.sakura.encryptor.core.alist.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
    }

    /** Backend name used in error copy. */
    protected abstract val label: String

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
        if (data is JsonNull) throw RemoteException(-1, "服务器没有返回 $path 的文件信息")
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
        onCall: ((Call) -> Unit)? = null,
    ) {
        val remotePath = normalizeUploadPath(dirPath, fileName)

        if (supportsMultipart && file.length() >= MULTIPART_MIN_BYTES) {
            try {
                uploadMultipart(server, token, remotePath, file, onProgress, onCall)
                return
            } catch (e: RemoteException) {
                // Older server, or the driver does not support chunking.
                if (!e.multipartUnsupported) throw e
            }
        }

        uploadForm(server, token, remotePath, fileName, file, asTask, onProgress, onCall)
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
        throw RemoteException(-1, "$label 不支持分片上传").also { it.multipartUnsupported = true }
    }

    /** Direct download URL plus the signed size for a remote file. */
    suspend fun resolveRawUrl(server: String, token: String, path: String): Pair<String, Long> {
        val info = get(server, token, path)
        val raw = info.rawUrl ?: throw RemoteException(-1, "服务器没有返回 $path 的下载地址")
        return raw to info.size
    }

    // -----------------------------------------------------------------------
    // Upload internals
    // -----------------------------------------------------------------------

    private suspend fun uploadForm(
        server: String,
        token: String,
        remotePath: String,
        fileName: String,
        file: File,
        asTask: Boolean,
        onProgress: ((Long, Long) -> Unit)?,
        onCall: ((Call) -> Unit)?,
    ) = withContext(Dispatchers.IO) {
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
                envelope.message ?: "上传失败 (HTTP ${raw.httpCode})",
            )
        }
    }

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
        if (trimmed.isEmpty() || trimmed == "/") throw RemoteException(-1, "上传路径不能为空")
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
            else -> throw RemoteException(-1, "不支持的请求方法 $method")
        }
        if (token != null) builder.header("Authorization", token)

        val envelope = readEnvelope(sendRaw(builder.build(), null))
        if (!envelope.ok) {
            throw RemoteException(
                envelope.code,
                envelope.message ?: "请求失败 (HTTP ${envelope.httpCode})",
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
            throw RemoteException(-1, "网络请求失败：${e.message ?: e.javaClass.simpleName}", e)
        }

        response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: Exception) {
                throw RemoteException(-1, "读取响应失败", e)
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
