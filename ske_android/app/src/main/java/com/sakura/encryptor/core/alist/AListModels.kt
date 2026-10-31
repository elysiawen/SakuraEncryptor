package com.sakura.encryptor.core.alist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** AList REST envelope: `{ code, message, data }`. */
@Serializable
data class AListEnvelope<T>(
    val code: Int,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    // Sent only when the account uses TOTP; null keeps the field out of the body.
    @SerialName("otp_code") val otpCode: String? = null,
)

/** First-run setup: creates the single admin account of a fresh server. */
@Serializable
data class SetupRequest(val username: String, val password: String)

@Serializable
data class LoginData(val token: String)

@Serializable
data class UserInfo(
    @SerialName("base_path") val basePath: String? = null,
    val permissions: List<Permission>? = null,
)

@Serializable
data class Permission(
    val path: String? = null,
    val permission: Int? = null,
)

@Serializable
data class ListRequest(
    val path: String,
    val page: Int = 1,
    /** AList defaults this to 200 and clamps it at 500; -1 means "everything". */
    @SerialName("per_page") val perPage: Int = 0,
    val refresh: Boolean = false,
)

@Serializable
data class ListData(
    val content: List<FileEntry>? = null,
    val total: Long? = null,
    val provider: String? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
)

/** One entry returned by `/api/fs/list`. */
@Serializable
data class FileEntry(
    val name: String,
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    val modified: String? = null,
    val sign: String? = null,
    val thumb: String? = null,
    val type: Int? = null,
)

@Serializable
data class GetRequest(val path: String)

@Serializable
data class GetData(
    val name: String? = null,
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    @SerialName("raw_url") val rawUrl: String? = null,
    val sign: String? = null,
)

@Serializable
data class MkdirRequest(val path: String)

@Serializable
data class RenameRequest(val path: String, val name: String)

@Serializable
data class RemoveRequest(val dir: String, val names: List<String>)

// ---------------------------------------------------------------------------
// Storage management (embedded server only)
// ---------------------------------------------------------------------------

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

/** A cloud entry with its encrypted name already decrypted for display. */
data class DecryptedEntry(
    val raw: FileEntry,
    /** Plain name when the token could be decrypted, otherwise the raw token. */
    val displayName: String,
    val nameDecrypted: Boolean,
) {
    val isDir: Boolean get() = raw.isDir
    val size: Long get() = raw.size
    val isSke: Boolean get() = !raw.isDir && raw.name.endsWith(".ske", ignoreCase = true)

    /**
     * Storage-generated preview url (AList `thumb`), or null when there is none.
     *
     * A container is excluded on purpose: the server only sees ciphertext, so its
     * preview has to come from the encrypted envelope instead.
     */
    val remoteThumb: String? get() = if (isDir || isSke) null else raw.thumb
}
