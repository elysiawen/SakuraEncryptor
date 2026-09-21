package com.sakura.encryptor.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved cloud profile.
 *
 * Each profile carries its own AList account *and* its own encryption master
 * password — different passwords can therefore drive different encrypted
 * trees, and switching profiles switches everything at once.
 *
 * Passwords are stored only as Keystore-sealed blobs (or not at all).
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val server: String = "",
    val username: String = "",
    val basePath: String = "",
    /** Sealed AList login password; null means "ask on connect". */
    val sealedAlistPassword: String? = null,
    /** Sealed encryption master password; null means "ask on switch". */
    val sealedMasterPassword: String? = null,
    val lastUsedAt: Long = 0,
) {
    val hasServer: Boolean get() = server.isNotBlank()

    /** True when the AList session can be restored without prompting. */
    val canAutoLogin: Boolean get() = hasServer && !sealedAlistPassword.isNullOrEmpty()

    /** True when the encryption password is stored and can unlock automatically. */
    val canAutoUnlock: Boolean get() = !sealedMasterPassword.isNullOrEmpty()
}

enum class DownloadStatus {
    PENDING,
    RUNNING,

    /** Interrupted by the user; the partial ciphertext is kept for resuming. */
    PAUSED,

    DONE,
    FAILED,
    CANCELLED,
}

/** A queued or finished "download + decrypt" job. */
@Entity(tableName = "downloads")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val remotePath: String,
    val server: String,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: String = DownloadStatus.PENDING.name,
    /** Absolute path of the decrypted output, once finished. */
    val localUri: String? = null,
    val mime: String? = null,
    val error: String? = null,
    val createdAt: Long = 0,
)

/** Resume position for the media player. */
@Entity(tableName = "history")
data class PlayHistoryEntity(
    @PrimaryKey val key: String,
    val displayName: String,
    val remotePath: String,
    val server: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)
