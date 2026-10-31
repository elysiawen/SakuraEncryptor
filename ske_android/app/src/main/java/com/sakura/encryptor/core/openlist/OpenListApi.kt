package com.sakura.encryptor.core.openlist

import com.sakura.encryptor.core.remote.RemoteClient
import com.sakura.encryptor.core.remote.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * OpenList client.
 *
 * OpenList is a community fork of AList and keeps the same `/api` surface, so
 * everything but the resumable upload below lives in [RemoteClient]. Only the
 * extras are here: the `/api/fs/multipart` endpoints and their retry rules.
 */
class OpenListApi(
    client: OkHttpClient,
    json: Json = RemoteClient.DEFAULT_JSON,
) : RemoteClient(client, json) {

    private companion object {
        /** Flow control answers 429/409; give the server pipeline a moment. */
        private const val MULTIPART_MAX_RETRIES = 6

        /** Server-chosen size when the init response omits `chunk_size`. */
        private const val DEFAULT_CHUNK_BYTES = 10L * 1024 * 1024
    }

    override val label: String = "OpenList"

    override val supportsMultipart: Boolean = true

    /**
     * init → sequential chunks → complete.
     *
     * A resumed session carries inclusive [start, end] ranges the server already
     * holds, so those are skipped. Sequential sending avoids the 409 "chunk
     * already in flight" answer that parallel sends can trigger.
     */
    override suspend fun uploadMultipart(
        server: String,
        token: String,
        remotePath: String,
        file: File,
        onProgress: ((Long, Long) -> Unit)?,
        onCall: ((Call) -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        val total = file.length()
        val initRequest = Request.Builder()
            .url(url(server, "/api/fs/multipart/init"))
            .post(ByteArray(0).toRequestBody(RemoteClient.OCTET_MEDIA))
            .header("Authorization", token)
            .header("File-Path", remotePath)
            .header("X-File-Size", total.toString())
            .build()

        val initRaw = sendRaw(initRequest, onCall)
        val initEnvelope = readEnvelope(initRaw)
        if (!initEnvelope.ok) {
            val error = RemoteException(
                initEnvelope.code,
                initEnvelope.message ?: "分片上传初始化失败 (HTTP ${initRaw.httpCode})",
            )
            error.multipartUnsupported = initRaw.httpCode in 403..405
            throw error
        }
        val init = json.decodeFromJsonElement(
            MultipartInitData.serializer(),
            initEnvelope.data ?: JsonNull,
        )
        val uploadId = init.uploadId?.takeIf { it.isNotBlank() }
            ?: throw RemoteException(-1, "分片上传响应缺少 upload_id").also { it.multipartUnsupported = true }
        val chunkSize = init.chunkSize?.takeIf { it > 0 } ?: DEFAULT_CHUNK_BYTES
        val totalChunks = init.totalChunks
            ?.takeIf { it > 0 }
            ?: ((total + chunkSize - 1) / chunkSize).toInt()

        val receivedChunks = HashSet<Int>()
        init.received.orEmpty().forEach { range ->
            val from = range.getOrNull(0)?.toInt() ?: return@forEach
            val to = range.getOrNull(1)?.toInt() ?: return@forEach
            for (index in from..to) {
                if (index in 0 until totalChunks) receivedChunks += index
            }
        }
        var receivedBytes = init.receivedBytes ?: 0L
        onProgress?.invoke(receivedBytes, total)

        try {
            for (index in 0 until totalChunks) {
                if (index in receivedChunks) continue

                val start = index.toLong() * chunkSize
                val length = minOf(chunkSize, total - start)
                if (length <= 0) break

                val chunkRequest = Request.Builder()
                    .url(url(server, "/api/fs/multipart/chunk"))
                    .put(FileRangeRequestBody(file, start, length))
                    .header("Authorization", token)
                    .header("X-Upload-Id", uploadId)
                    .header("X-Chunk-Index", index.toString())
                    .build()

                val chunkData = sendChunk(chunkRequest, index, onCall)
                receivedBytes = maxOf(receivedBytes, chunkData?.receivedBytes ?: (start + length))
                onProgress?.invoke(receivedBytes, total)
            }

            val completeRequest = Request.Builder()
                .url(url(server, "/api/fs/multipart/complete"))
                .post(ByteArray(0).toRequestBody(RemoteClient.OCTET_MEDIA))
                .header("Authorization", token)
                .header("X-Upload-Id", uploadId)
                .build()
            val complete = readEnvelope(sendRaw(completeRequest, onCall))
            if (!complete.ok) {
                throw RemoteException(complete.code, complete.message ?: "分片上传合并失败")
            }
            if (onProgress != null) onProgress(total, total)
        } catch (e: Throwable) {
            // Never leave a half-written session (and its temp chunks) behind.
            withContext(NonCancellable) {
                runCatching {
                    sendRaw(
                        Request.Builder()
                            .url(url(server, "/api/fs/multipart/abort"))
                            .post(ByteArray(0).toRequestBody(RemoteClient.OCTET_MEDIA))
                            .header("Authorization", token)
                            .header("X-Upload-Id", uploadId)
                            .build(),
                        null,
                    )
                }
            }
            throw e
        }
    }

    private suspend fun sendChunk(
        request: Request,
        index: Int,
        onCall: ((Call) -> Unit)?,
    ): MultipartChunkData? {
        var attempt = 0
        while (true) {
            val raw = sendRaw(request, onCall)
            val envelope = readEnvelope(raw)
            if (envelope.ok) {
                return envelope.data
                    ?.takeIf { it !is JsonNull }
                    ?.let { runCatching { json.decodeFromJsonElement(MultipartChunkData.serializer(), it) }.getOrNull() }
            }
            // 429 = receiving window full, 409 = the same chunk is in flight.
            if (raw.httpCode == 429 || raw.httpCode == 409) {
                attempt++
                if (attempt >= MULTIPART_MAX_RETRIES) {
                    throw RemoteException(-1, "分片 $index 上传失败：服务器持续繁忙")
                }
                delay(300L + attempt * 300L)
                continue
            }
            throw RemoteException(
                envelope.code,
                envelope.message ?: "分片 $index 上传失败 (HTTP ${raw.httpCode})",
            )
        }
    }

    /** Sends exactly [length] bytes of [file] starting at [start]. */
    private class FileRangeRequestBody(
        private val file: File,
        private val start: Long,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            FileInputStream(file).use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val step = input.skip(start - skipped)
                    if (step <= 0) throw IOException("无法定位分片起点")
                    skipped += step
                }

                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("文件读取提前结束")
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
}
