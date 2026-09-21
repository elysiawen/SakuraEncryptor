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

    suspend fun list(server: String, token: String, path: String, refresh: Boolean = false): ListData {
        val body = json.encodeToString(ListRequest.serializer(), ListRequest(path, refresh))
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
    ) {
        val remotePath = normalizeUploadPath(dirPath, fileName)
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val request = Request.Builder()
                .url(url(server, "/api/fs/form"))
                .put(body)
                .header("Authorization", token)
                .header("File-Path", remotePath)
                .apply { if (asTask) header("As-Task", "true") }
                .build()
            execute(request)
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
    private fun execute(request: Request): JsonElement {
        val response = try {
            client.newCall(request).execute()
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
