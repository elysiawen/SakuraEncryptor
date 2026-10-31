package com.sakura.encryptor.core.alist

import com.sakura.encryptor.core.remote.RemoteClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Thin AList client.
 *
 * AList has no chunked upload endpoint, so the shared form upload from
 * [RemoteClient] is its only upload path. On top of that this exposes the
 * admin surface the embedded OpenList server relies on: first-run setup and
 * storage management, since that server ships without a web admin.
 */
class AListApi(
    client: OkHttpClient,
    json: Json = RemoteClient.DEFAULT_JSON,
) : RemoteClient(client, json) {

    override val label: String = "AList"

    // -----------------------------------------------------------------------
    // First-run setup (used by the embedded server)
    // -----------------------------------------------------------------------

    /** Whether the server already has an admin account, i.e. setup has run. */
    suspend fun initStatus(server: String): Boolean {
        val data = requestJson("GET", url(server, "/api/public/init_status"), token = null, body = null)
        if (data is JsonNull) return false
        return data.jsonObject["initialized"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /** Creates the first admin account; a second call is rejected with code 400. */
    suspend fun setup(server: String, username: String, password: String) {
        val body = json.encodeToString(SetupRequest.serializer(), SetupRequest(username, password))
        requestJson("POST", url(server, "/api/public/init/setup"), token = null, body = body)
    }

    /** Mount paths of every storage the signed-in admin can see. */
    suspend fun storageMountPaths(server: String, token: String): List<String> {
        val data = requestJson("GET", url(server, "/api/admin/storage/list"), token, null)
        val content = ((data as? JsonObject)?.get("content") as? JsonArray) ?: return emptyList()
        return content.mapNotNull { item ->
            (item as? JsonObject)?.get("mount_path")?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * Mount [rootPath] at [mountPath] with the built-in Local driver.
     *
     * `addition` is a JSON document carried as a string, exactly like the web
     * admin sends it; only the root path is required by the driver.
     */
    suspend fun createLocalStorage(
        server: String,
        token: String,
        mountPath: String,
        rootPath: String,
    ) {
        createStorage(
            server = server,
            token = token,
            storage = StorageInfo(
                mountPath = mountPath,
                driver = "Local",
                cacheExpiration = 30,
                status = "work",
                addition = buildJsonObject { put("root_folder_path", rootPath) }.toString(),
                webdavPolicy = "native_proxy",
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Storage management (the embedded server has no web admin of its own)
    // -----------------------------------------------------------------------

    /** Every registered driver keyed by name, each with its form definition. */
    suspend fun driverInfos(server: String, token: String): Map<String, DriverInfo> {
        val data = requestJson("GET", url(server, "/api/admin/driver/list"), token, null)
        val drivers = data as? JsonObject ?: return emptyMap()
        return drivers.entries.mapNotNull { (name, value) ->
            runCatching { name to json.decodeFromJsonElement(DriverInfo.serializer(), value) }
                .getOrNull()
        }.toMap()
    }

    /** All storages, enabled or not, ordered the way the server sorts them. */
    suspend fun storages(server: String, token: String): List<StorageInfo> {
        val data = requestJson("GET", url(server, "/api/admin/storage/list"), token, null)
        val content = (data as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(StorageInfo.serializer(), item) }.getOrNull()
        }
    }

    suspend fun createStorage(server: String, token: String, storage: StorageInfo) {
        requestJson("POST", url(server, "/api/admin/storage/create"), token, storageBody(storage))
    }

    /** The server rejects a changed driver, so [storage] must keep its own. */
    suspend fun updateStorage(server: String, token: String, storage: StorageInfo) {
        requestJson("POST", url(server, "/api/admin/storage/update"), token, storageBody(storage, withId = true))
    }

    suspend fun deleteStorage(server: String, token: String, id: Int) {
        requestJson("POST", url(server, "/api/admin/storage/delete?id=$id"), token, null)
    }

    /** Enabling or disabling an unchanged storage is an error, so callers check first. */
    suspend fun setStorageEnabled(server: String, token: String, id: Int, enabled: Boolean) {
        val action = if (enabled) "enable" else "disable"
        requestJson("POST", url(server, "/api/admin/storage/$action?id=$id"), token, null)
    }

    /** The full `model.Storage` document; omitted keys would reset server-side fields. */
    private fun storageBody(storage: StorageInfo, withId: Boolean = false): String = buildJsonObject {
        if (withId) put("id", storage.id)
        put("mount_path", storage.mountPath)
        put("order", storage.order)
        put("driver", storage.driver)
        put("cache_expiration", storage.cacheExpiration)
        put("custom_cache_policies", storage.customCachePolicies)
        put("status", storage.status.ifEmpty { "work" })
        put("addition", storage.addition)
        put("remark", storage.remark)
        put("disabled", storage.disabled)
        put("disable_index", storage.disableIndex)
        put("enable_sign", storage.enableSign)
        put("seed_policy", storage.seedPolicy.ifEmpty { "inherit" })
        put("order_by", storage.orderBy)
        put("order_direction", storage.orderDirection)
        put("extract_folder", storage.extractFolder)
        put("web_proxy", storage.webProxy)
        put("webdav_policy", storage.webdavPolicy)
        put("proxy_range", storage.proxyRange)
        put("down_proxy_url", storage.downProxyUrl)
        put("disable_proxy_sign", storage.disableProxySign)
    }.toString()
}
