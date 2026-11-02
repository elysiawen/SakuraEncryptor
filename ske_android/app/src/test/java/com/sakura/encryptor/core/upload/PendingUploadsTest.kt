package com.sakura.encryptor.core.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PendingUploadsTest {

    private val dir: File = Files.createTempDirectory("ske-pending-uploads").toFile()
    private val file: File = File(dir, "pending_uploads.json")

    private fun entry(id: String, taskId: String = "t-$id", progressBytes: Long = 512) = PendingUpload(
        id = id,
        server = "https://cloud.example",
        dir = "/movies",
        name = "$id.mp4",
        encrypt = true,
        taskId = taskId,
        totalBytes = progressBytes,
        startedAt = 1_000,
        updatedAt = 2_000,
    )

    @Test
    fun `rows survive a new instance over the same file`() {
        PendingUploads(file).also { it.load() }.apply {
            upsert(entry("a"))
            upsert(entry("b"))
        }

        val reopened = PendingUploads(file).load()

        assertEquals(listOf("b", "a"), reopened.entries().map { it.id })
        assertEquals("t-a", reopened.entries().first { it.id == "a" }.taskId)
        assertEquals(512L, reopened.entries().first { it.id == "a" }.totalBytes)
    }

    @Test
    fun `same task keeps one row and the newest state`() {
        val store = PendingUploads(file).load()
        store.upsert(entry("a", progressBytes = 10))
        store.upsert(entry("a", progressBytes = 99))

        assertEquals(1, store.entries().size)
        assertEquals(99L, store.entries().single().totalBytes)
    }

    @Test
    fun `removing a row is written through`() {
        PendingUploads(file).load().apply {
            upsert(entry("a"))
            upsert(entry("b"))
            remove("a")
        }

        assertEquals(listOf("b"), PendingUploads(file).load().entries().map { it.id })
    }

    @Test
    fun `a row without a task id is reported as it is`() {
        // The manager drops these; the store must not invent a task id.
        val store = PendingUploads(file).load()
        store.upsert(entry("a", taskId = ""))

        assertEquals("", store.entries().single().taskId)
    }

    @Test
    fun `a chunked row keeps its payload and remote name`() {
        val store = PendingUploads(file).load()
        store.upsert(
            entry("chunk").copy(
                mode = PendingMode.CHUNK,
                taskId = "",
                remoteName = "token.ske",
                payloadPath = File(dir, "chunk.payload").absolutePath,
            ),
        )

        val restored = PendingUploads(file).load().entries().single()
        assertEquals(PendingMode.CHUNK, restored.mode)
        assertEquals("token.ske", restored.remoteName)
        assertTrue(restored.payloadPath.endsWith("chunk.payload"))
    }

    @Test
    fun `rows written before chunked uploads still load as task rows`() {
        // The shape this file had when only server tasks were resumable.
        file.writeText(
            """
            {"version":1,"records":[{"id":"old","server":"https://cloud.example",
            "dir":"/movies","name":"old.mp4","encrypt":true,"taskId":"t-old",
            "totalBytes":2048,"startedAt":1,"updatedAt":2}]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val restored = PendingUploads(file).load().entries().single()

        assertEquals(PendingMode.TASK, restored.mode)
        assertEquals("t-old", restored.taskId)
        assertEquals("", restored.payloadPath)
    }

    @Test
    fun `a damaged file does not break the launch`() {
        file.writeText("not json at all", Charsets.UTF_8)

        val store = PendingUploads(file).load()

        assertTrue(store.entries().isEmpty())
        // And it must still be usable afterwards.
        store.upsert(entry("a"))
        assertEquals(1, PendingUploads(file).load().entries().size)
    }

    @Test
    fun `missing file is an empty list`() {
        assertTrue(PendingUploads(File(dir, "absent.json")).load().entries().isEmpty())
    }

    @Test
    fun `the file is capped so a crash loop cannot grow it`() {
        val store = PendingUploads(file, limit = 3).load()
        repeat(6) { index -> store.upsert(entry("row-$index")) }

        assertEquals(3, store.entries().size)
        assertEquals(3, PendingUploads(file, limit = 3).load().entries().size)
    }
}
