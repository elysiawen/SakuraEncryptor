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
data class LoginRequest(val username: String, val password: String)

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
data class ListRequest(val path: String, val refresh: Boolean = false)

@Serializable
data class ListData(
    val content: List<FileEntry>? = null,
    val total: Long? = null,
    val provider: String? = null,
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
}
