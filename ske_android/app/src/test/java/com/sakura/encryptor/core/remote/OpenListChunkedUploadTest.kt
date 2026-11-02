package com.sakura.encryptor.core.remote

import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.openlist.OpenListApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * OpenList's chunked upload against a mock server.
 *
 * This is the path a resumed upload takes after a restart: the container is
 * re-sent chunk by chunk, and whatever the server says it already holds is
 * skipped. Both halves are pinned here — a fresh session sends everything, a
 * resumed one sends only the missing chunks.
 */
class OpenListChunkedUploadTest {

    private val dir: File = Files.createTempDirectory("ske-chunked").toFile()
    private val client = OpenListApi(
        OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
    )
    private var mock: MockMultipart? = null

    @After
    fun tearDown() {
        mock?.stop()
        dir.deleteRecursively()
    }

    /** 32 MB + 1 KB: four chunks at the server's 10 MB size. */
    private fun payload(): File = File(dir, "big.ske").apply {
        RandomAccessFile(this, "rw").use { it.setLength(32L * 1024 * 1024 + 1024) }
    }

    @Test
    fun `only the chunked backends use chunked uploads`() {
        val big = 32L * 1024 * 1024
        assertTrue(client.usesChunkedUpload(big))
        assertFalse(client.usesChunkedUpload(big - 1))
        // AList has no `/api/fs/multipart`: it always takes one request.
        assertFalse(AListApi(OkHttpClient()).usesChunkedUpload(big * 2))
    }

    @Test
    fun `a fresh session sends every chunk and merges`() = runBlocking {
        val mock = MockMultipart().start()
        this@OpenListChunkedUploadTest.mock = mock
        val file = payload()
        val progress = mutableListOf<Long>()

        client.upload(
            server = mock.base,
            token = "t",
            dirPath = "/movies",
            fileName = "movie.ske",
            file = file,
            onProgress = { report -> report.sent?.let { progress += it } },
        )

        assertEquals(file.length().toString(), mock.initFileSize)
        assertEquals(listOf(0, 1, 2, 3), mock.chunks.toList())
        assertEquals(1, mock.completed.get())
        assertEquals(file.length(), progress.last())
    }

    @Test
    fun `a resumed session skips the chunks the server already holds`() = runBlocking {
        val mock = MockMultipart(received = listOf(listOf(0, 1)), receivedBytes = 20L * 1024 * 1024).start()
        this@OpenListChunkedUploadTest.mock = mock
        val file = payload()

        client.upload(
            server = mock.base,
            token = "t",
            dirPath = "/movies",
            fileName = "movie.ske",
            file = file,
        )

        assertEquals("已收到的分片不再重发", listOf(2, 3), mock.chunks.toList())
        assertEquals(1, mock.completed.get())
    }

    /** Minimal OpenList: init → chunk → complete (and abort on failure). */
    private class MockMultipart(
        private val received: List<List<Int>> = emptyList(),
        private val receivedBytes: Long = 0,
        private val chunkSize: Long = 10L * 1024 * 1024,
    ) {
        val chunks = CopyOnWriteArrayList<Int>()
        val completed = AtomicInteger()
        val aborted = AtomicInteger()
        @Volatile
        var initFileSize: String? = null

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private var worker: Thread? = null

        val base: String get() = "http://127.0.0.1:${socket.localPort}"

        fun start(): MockMultipart = apply {
            worker = thread(isDaemon = true, name = "mock-openlist") { acceptLoop() }
        }

        fun stop() {
            runCatching { socket.close() }
            worker?.join(1_000)
        }

        private fun acceptLoop() {
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (_: IOException) {
                    return
                }
                runCatching { connection.use { handle(it) } }
            }
        }

        private fun handle(connection: Socket) {
            val input = BufferedInputStream(connection.getInputStream())
            val requestLine = readLine(input) ?: return
            val path = requestLine.split(' ').getOrNull(1).orEmpty()

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            input.readNBytes(length)

            val response = when {
                path.startsWith("/api/fs/multipart/init") -> {
                    initFileSize = headers["x-file-size"]
                    val size = initFileSize?.toLongOrNull() ?: 0
                    val total = ((size + chunkSize - 1) / chunkSize).toInt()
                    val ranges = received.joinToString(",") { "[${it.joinToString(",")}]" }
                    val data = """{"upload_id":"u-1","chunk_size":$chunkSize,""" +
                        """"total_chunks":$total,"received":[$ranges],"received_bytes":$receivedBytes}"""
                    """{"code":200,"message":"success","data":$data}"""
                }

                path.startsWith("/api/fs/multipart/chunk") -> {
                    val index = headers["x-chunk-index"]?.toIntOrNull() ?: -1
                    chunks += index
                    """{"code":200,"message":"success","data":{"received_bytes":${(index + 1) * chunkSize}}}"""
                }

                path.startsWith("/api/fs/multipart/complete") -> {
                    completed.incrementAndGet()
                    """{"code":200,"message":"success"}"""
                }

                path.startsWith("/api/fs/multipart/abort") -> {
                    aborted.incrementAndGet()
                    """{"code":200}"""
                }

                else -> """{"code":404,"message":"unexpected path"}"""
            }

            writeResponse(connection.getOutputStream(), response)
        }

        private fun readLine(input: InputStream): String? {
            val buffer = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (buffer.isEmpty()) null else buffer.toString()
                if (byte == '\n'.code) return buffer.toString().trimEnd('\r')
                buffer.append(byte.toChar())
            }
        }

        private fun writeResponse(output: OutputStream, body: String) {
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray())
            output.write(bytes)
            output.flush()
        }
    }
}
