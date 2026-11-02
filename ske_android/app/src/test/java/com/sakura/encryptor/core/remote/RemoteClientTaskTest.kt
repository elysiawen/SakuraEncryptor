package com.sakura.encryptor.core.remote

import com.sakura.encryptor.core.alist.AListApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The server-side half of an upload, against a mock AList.
 *
 * No Android is involved on the success path, so this runs as a plain JVM test:
 * it pins down what the `As-Task` request carries, how the task id is read back
 * from the answer, and that a finished task is deleted from the server's list.
 * The mock speaks just enough HTTP for OkHttp, with no test-only dependency.
 */
class RemoteClientTaskTest {

    private val dir: File = Files.createTempDirectory("ske-remote-task").toFile()
    private val client = AListApi(OkHttpClient())
    private var mock: Mock? = null

    @After
    fun tearDown() {
        mock?.stop()
        dir.deleteRecursively()
    }

    private fun payload(bytes: Int = 4096): File =
        File(dir, "payload.ske").apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    @Test
    fun `task mode asks for As-Task and follows the task to the end`() = runBlocking {
        val mock = Mock().start()
        this@RemoteClientTaskTest.mock = mock
        val file = payload()
        var taskId: String? = null
        val progress = mutableListOf<Long>()

        client.upload(
            server = mock.base,
            token = "t",
            dirPath = "/movies",
            fileName = "movie.ske",
            file = file,
            asTask = true,
            onProgress = { report ->
                report.sent?.let { progress += it }
                report.taskId?.let { taskId = it }
            },
        )

        assertEquals("t-1", taskId)
        assertEquals("true", mock.putHeaders["as-task"])
        assertEquals(file.length().toString(), mock.putHeaders["content-length"])
        assertTrue("正文逐字节到达", mock.putBody.contentEquals(file.readBytes()))
        assertEquals("完成的任务从服务器列表里删掉", 1, mock.deletes.get())
        assertTrue("轮询过任务进度", mock.polls.get() >= 2)
        assertEquals("进度最终写满", file.length(), progress.last())
    }

    @Test
    fun `a synchronous write has no task and is not polled`() = runBlocking {
        val mock = Mock(taskId = null).start()
        this@RemoteClientTaskTest.mock = mock
        var taskId: String? = null

        client.upload(
            server = mock.base,
            token = "t",
            dirPath = "/movies",
            fileName = "movie.ske",
            file = payload(),
            asTask = true,
            onProgress = { report -> report.taskId?.let { taskId = it } },
        )

        assertNull(taskId)
        assertEquals(0, mock.polls.get())
    }

    @Test
    fun `a live upload survives a task the server cannot answer for`() = runBlocking {
        val mock = Mock(known = false).start()
        this@RemoteClientTaskTest.mock = mock

        // Lenient: the bytes were delivered, so no storage figure and no error.
        client.waitForUploadTask(mock.base, "t", "t-1", 100)

        assertTrue(mock.polls.get() > 0)
    }

    @Test
    fun `a server without the raw-body endpoint still gets a task from the form`() = runBlocking {
        val mock = Mock(putStatus = 404).start()
        this@RemoteClientTaskTest.mock = mock
        var taskId: String? = null

        client.upload(
            server = mock.base,
            token = "t",
            dirPath = "/movies",
            fileName = "movie.ske",
            file = payload(),
            asTask = true,
            onProgress = { report -> report.taskId?.let { taskId = it } },
        )

        assertEquals(1, mock.formCalls.get())
        assertEquals("true", mock.formHeaders["as-task"])
        assertEquals("t-1", taskId)
        assertTrue("回落后仍然轮询任务", mock.polls.get() >= 2)
        assertEquals(1, mock.deletes.get())
    }

    @Test
    fun `cancelling a task reaches the server`() = runBlocking {
        val mock = Mock().start()
        this@RemoteClientTaskTest.mock = mock

        client.cancelUploadTask(mock.base, "t", "t-1")

        assertEquals(1, mock.cancels.get())
    }

    /** Minimal AList: `fs/put`, `fs/form`, the task info endpoint and the actions. */
    private class Mock(
        private val taskId: String? = "t-1",
        private val known: Boolean = true,
        private val putStatus: Int = 200,
    ) {
        val polls = AtomicInteger()
        val deletes = AtomicInteger()
        val cancels = AtomicInteger()
        val formCalls = AtomicInteger()
        val putHeaders = mutableMapOf<String, String>()
        val formHeaders = mutableMapOf<String, String>()
        @Volatile
        var putBody: ByteArray = ByteArray(0)

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private var worker: Thread? = null

        val base: String get() = "http://127.0.0.1:${socket.localPort}"

        fun start(): Mock = apply {
            worker = thread(isDaemon = true, name = "mock-alist") { acceptLoop() }
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
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1).orEmpty()

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
            val body = input.readNBytes(length)

            val response = when {
                path.startsWith("/api/fs/put") -> {
                    for (name in listOf("as-task", "content-length", "file-path")) {
                        headers[name]?.let { putHeaders[name] = it }
                    }
                    putBody = body
                    if (putStatus != 200) {
                        writeResponse(connection.getOutputStream(), """{"code":$putStatus}""", putStatus)
                        return
                    }
                    val data = taskId?.let { """{"task":{"id":"$it"}}""" } ?: "{}"
                    """{"code":200,"message":"success","data":$data}"""
                }

                path.startsWith("/api/fs/form") -> {
                    formCalls.incrementAndGet()
                    for (name in listOf("as-task", "file-path")) {
                        headers[name]?.let { formHeaders[name] = it }
                    }
                    val data = taskId?.let { """{"task":{"id":"$it"}}""" } ?: "{}"
                    """{"code":200,"message":"success","data":$data}"""
                }

                path.startsWith("/api/task/upload/info") -> {
                    val round = polls.incrementAndGet()
                    if (!known) {
                        """{"code":500,"message":"task not found"}"""
                    } else {
                        // 40% first, then done: one poll of real progress.
                        val progress = if (round < 2) 40 else 100
                        val state = if (round < 2) "running" else "succeeded"
                        val data = """[{"id":"t-1","state":"$state","progress":$progress}]"""
                        """{"code":200,"message":"success","data":$data}"""
                    }
                }

                path.startsWith("/api/task/upload/delete") -> {
                    deletes.incrementAndGet()
                    """{"code":200}"""
                }

                path.startsWith("/api/task/upload/cancel") -> {
                    cancels.incrementAndGet()
                    """{"code":200}"""
                }

                else -> """{"code":404,"message":"unexpected ${parts.firstOrNull()}"}"""
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

        private fun writeResponse(output: OutputStream, body: String, status: Int = 200) {
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray())
            output.write(bytes)
            output.flush()
        }
    }
}
