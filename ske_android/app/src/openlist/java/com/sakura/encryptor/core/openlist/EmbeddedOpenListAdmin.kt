package com.sakura.encryptor.core.openlist

import com.sakura.encryptor.core.alist.AListApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Role value OpenList uses for the built-in administrator. */
private const val ADMIN_ROLE = 2

/**
 * Admin surface of the AList API, used only by the embedded server.
 *
 * It lives in the `openlist` flavor: the server ships without a web admin, so
 * first-run setup and storage management are done from the app, and builds
 * without the server do not carry any of this.
 */
@Serializable
data class SetupRequest(val username: String, val password: String)

/**
 * One form field a driver exposes.
 *
 * The server derives these from the driver's Go struct (`json` tags and
 * `conf.Type*`), which is exactly what the official web admin renders its
 * storage form from. `type` is one of string / text / number / bool / select,
 * plus a few reflected Go kinds (int, uint, float…).
 */
@Serializable
data class DriverItem(
    val name: String,
    val type: String = "string",
    val default: String = "",
    val options: String = "",
    val required: Boolean = false,
    val help: String = "",
) {
    val isBoolean: Boolean get() = type.equals("bool", ignoreCase = true)

    val isNumber: Boolean
        get() = type.equals("number", ignoreCase = true) ||
            type.startsWith("int", ignoreCase = true) ||
            type.startsWith("uint", ignoreCase = true) ||
            type.startsWith("float", ignoreCase = true)

    /** `options` is a comma-separated list, already in display order. */
    val optionList: List<String>
        get() = options.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Serializable
data class DriverConfig(
    val name: String = "",
    /** Root the driver suggests when the storage does not set one. */
    @SerialName("default_root") val defaultRoot: String? = null,
)

/** Every field a driver needs, split into mount-level and driver-level halves. */
@Serializable
data class DriverInfo(
    val common: List<DriverItem> = emptyList(),
    val additional: List<DriverItem> = emptyList(),
    val config: DriverConfig = DriverConfig(),
)

/** A row of `/api/admin/storage/list`; also the payload of update. */
@Serializable
data class StorageInfo(
    val id: Int = 0,
    @SerialName("mount_path") val mountPath: String = "",
    val order: Int = 0,
    val driver: String = "",
    @SerialName("cache_expiration") val cacheExpiration: Int = 0,
    @SerialName("custom_cache_policies") val customCachePolicies: String = "",
    val status: String = "work",
    /** Driver-specific settings, carried as a JSON document in a string. */
    val addition: String = "",
    val remark: String = "",
    val modified: String = "",
    val disabled: Boolean = false,
    @SerialName("disable_index") val disableIndex: Boolean = false,
    @SerialName("enable_sign") val enableSign: Boolean = false,
    @SerialName("seed_policy") val seedPolicy: String = "inherit",
    @SerialName("order_by") val orderBy: String = "",
    @SerialName("order_direction") val orderDirection: String = "",
    @SerialName("extract_folder") val extractFolder: String = "",
    @SerialName("web_proxy") val webProxy: Boolean = false,
    @SerialName("webdav_policy") val webdavPolicy: String = "",
    @SerialName("proxy_range") val proxyRange: Boolean = false,
    @SerialName("down_proxy_url") val downProxyUrl: String = "",
    @SerialName("disable_proxy_sign") val disableProxySign: Boolean = false,
)

/** Whether the server already has an admin account, i.e. setup has run. */
suspend fun AListApi.initStatus(server: String): Boolean {
    val data = requestJson("GET", url(server, "/api/public/init_status"), token = null, body = null)
    if (data is JsonNull) return false
    return data.jsonObject["initialized"]?.jsonPrimitive?.booleanOrNull ?: false
}

/** Creates the first admin account; a second call is rejected with code 400. */
suspend fun AListApi.setup(server: String, username: String, password: String) {
    val body = json.encodeToString(SetupRequest.serializer(), SetupRequest(username, password))
    requestJson("POST", url(server, "/api/public/init/setup"), token = null, body = body)
}

/**
 * Rename the admin account and set a new password.
 *
 * OpenList keeps the account in its own database, and `user/update` wants the
 * whole record back: the row returned by the admin list is resent with only the
 * two fields changed, so base path, permissions and 2FA stay untouched.
 */
suspend fun AListApi.updateAdminUser(
    server: String,
    token: String,
    currentUsername: String,
    newUsername: String,
    newPassword: String,
) {
    val data = requestJson("GET", url(server, "/api/admin/user/list?page=1&per_page=100"), token, null)
    val users = ((data as? JsonObject)?.get("content") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    val admin = users.firstOrNull {
        it["username"]?.jsonPrimitive?.contentOrNull == currentUsername
    } ?: users.firstOrNull {
        it["role"]?.jsonPrimitive?.intOrNull == ADMIN_ROLE
    } ?: error("服务器上找不到内置管理员账号")

    val body = JsonObject(
        admin.toMutableMap().apply {
            put("username", JsonPrimitive(newUsername))
            put("password", JsonPrimitive(newPassword))
        },
    )
    requestJson("POST", url(server, "/api/admin/user/update"), token, body.toString())
}

/** Mount paths of every storage the signed-in admin can see. */
suspend fun AListApi.storageMountPaths(server: String, token: String): List<String> {
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
suspend fun AListApi.createLocalStorage(
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

/** Every registered driver keyed by name, each with its form definition. */
suspend fun AListApi.driverInfos(server: String, token: String): Map<String, DriverInfo> {
    val data = requestJson("GET", url(server, "/api/admin/driver/list"), token, null)
    val drivers = data as? JsonObject ?: return emptyMap()
    return drivers.entries.mapNotNull { (name, value) ->
        runCatching { name to json.decodeFromJsonElement(DriverInfo.serializer(), value) }
            .getOrNull()
    }.toMap()
}

/** All storages, enabled or not, ordered the way the server sorts them. */
suspend fun AListApi.storages(server: String, token: String): List<StorageInfo> {
    val data = requestJson("GET", url(server, "/api/admin/storage/list"), token, null)
    val content = (data as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
    return content.mapNotNull { item ->
        runCatching { json.decodeFromJsonElement(StorageInfo.serializer(), item) }.getOrNull()
    }
}

suspend fun AListApi.createStorage(server: String, token: String, storage: StorageInfo) {
    requestJson("POST", url(server, "/api/admin/storage/create"), token, storageBody(storage))
}

/** The server rejects a changed driver, so [storage] must keep its own. */
suspend fun AListApi.updateStorage(server: String, token: String, storage: StorageInfo) {
    requestJson("POST", url(server, "/api/admin/storage/update"), token, storageBody(storage, withId = true))
}

suspend fun AListApi.deleteStorage(server: String, token: String, id: Int) {
    requestJson("POST", url(server, "/api/admin/storage/delete?id=$id"), token, null)
}

/** Enabling or disabling an unchanged storage is an error, so callers check first. */
suspend fun AListApi.setStorageEnabled(server: String, token: String, id: Int, enabled: Boolean) {
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
