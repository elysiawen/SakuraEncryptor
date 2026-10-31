package com.sakura.encryptor.core.openlist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenList request/response models that AList does not have.
 *
 * The plain file-system DTOs (`FileEntry`, `ListData`, `GetData`, …) are
 * identical on both servers and are shared from `core.alist`; only the
 * multipart session shapes are defined here.
 */
@Serializable
data class MultipartInitData(
    @SerialName("upload_id") val uploadId: String? = null,
    @SerialName("chunk_size") val chunkSize: Long? = null,
    @SerialName("total_chunks") val totalChunks: Int? = null,
    /** Inclusive [start, end] chunk ranges the server already holds. */
    val received: List<List<Long>>? = null,
    @SerialName("received_bytes") val receivedBytes: Long? = null,
)

/** `/api/fs/multipart/chunk` response. */
@Serializable
data class MultipartChunkData(
    @SerialName("received_bytes") val receivedBytes: Long? = null,
)
