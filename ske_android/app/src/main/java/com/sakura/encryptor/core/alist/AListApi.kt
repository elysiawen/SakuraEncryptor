package com.sakura.encryptor.core.alist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Raised for any non-200 AList response or transport failure. */
class AListException(val code: Int, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Thin AList REST client.
 *
 * Mirrors `ske_web/src/composables/useAList.js`: same endpoints, same JSON
 * shapes, same virtual-root behaviour for sub-accounts.
 */
class AListApi(
    private val client: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    },
) {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val OK = 200

        /** AList clamps `per_page` above 500, so this is the biggest usable page. */
        private const val MAX_PER_PAGE = 500
        /** Guard against a server that ignores paging and never reports a total. */
        private const val MAX_LIST_PAGES = 200
    }

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    suspend fun login(server: String, username: String, password: String): String {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
        val data = requestJson("POST", url(server, "/api/auth/login"), token = null, body = body)
        return json.decodeFromJsonElement(LoginData.serializer(), data).token
    }

    /** Best-effort profile probe; returns null when the server does not expose it. */
    suspend fun me(server: String, token: String): UserInfo? {
        for (endpoint in listOf("/api/me", "/api/auth/me")) {
            try {
                val data = requestJson("GET", url(server, endpoint), token = token, body = null)
                if (data !is JsonNull) return json.decodeFromJsonElement(UserInfo.serializer(), data)
            } catch (_: AListException) {
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
     * AList defaults `per_page` to 200 and silently drops the rest, so a single
     * request truncates any folder with more than 200 entries. This walks the
     * pages with the largest accepted page size until `total` is reached.
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
                if (seen.add(entry.name)) {
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
     * Upload [file] as [fileName] into [dirPath] via the AList form API.
     * Returns the uploaded file's remote path.
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
        withContext(Dispatchers.IO) {
            val fileBody = file.asRequestBody("application/octet-stream".toMediaType())
            val partBody = if (onProgress != null) {
                ProgressRequestBody(fileBody, onProgress)
            } else {
                fileBody
            }
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
            val call = client.newCall(request)
            // The caller may need the handle to cancel a large upload.
            onCall?.invoke(call)
            execute(call)
        }
    }

    /** Direct download URL plus the signed size for a remote file. */
    suspend fun resolveRawUrl(server: String, token: String, path: String): Pair<String, Long> {
        val info = get(server, token, path)
        val raw = info.rawUrl ?: throw AListException(-1, "Server returned no raw_url for $path")
        return raw to info.size
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun url(server: String, path: String): String = server.trimEnd('/') + path

    /**
     * Absolute form of a preview link AList returned.
     *
     * Drivers hand out either a CDN url or a site-relative `/p/...` path, and an
     * empty string when nothing can be generated. Only the grid consumes this,
     * so normalising here keeps every caller free of the distinction.
     */
    private fun absoluteThumb(server: String, value: String?): String? {
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

    private fun normalizeUploadPath(dir: String, fileName: String): String {
        val trimmed = dir.trim()
        if (trimmed.isEmpty() || trimmed == "/") throw AListException(-1, "上传路径不能为空")
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return encodePath("$withSlash/$fileName")
    }

    /** Percent-encode each path segment but keep the slashes. */
    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    /**
     * Executes [request] and returns the JSON `data` element.
     * @throws AListException on transport errors or a non-200 AList code.
     */
    private fun execute(request: Request): JsonElement = execute(client.newCall(request))

    private fun execute(call: Call): JsonElement {
        val response = try {
            call.execute()
        } catch (e: Exception) {
            throw AListException(-1, "网络请求失败：${e.message ?: e.javaClass.simpleName}", e)
        }

        response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: Exception) {
                throw AListException(-1, "读取响应失败", e)
            }

            val root = try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw AListException(-1, "服务器返回了非 JSON 响应 (HTTP ${resp.code})", e)
            }

            val code = root.jsonObject["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (!resp.isSuccessful || code != OK) {
                val message = root.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                    ?: "请求失败 (HTTP ${resp.code})"
                throw AListException(code, message)
            }
            return root.jsonObject["data"] ?: JsonNull
        }
    }

    private suspend fun requestJson(
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
            else -> throw AListException(-1, "Unsupported method $method")
        }
        if (token != null) builder.header("Authorization", token)
        execute(builder.build())
    }
}
