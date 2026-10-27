package com.sakura.encryptor.core.media

import com.sakura.encryptor.core.crypto.SkeFileCipher
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.player.CipherBlockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The preview path of a grid tile reads the v003 envelope through a
 * [CipherBlockSource], i.e. an HTTP range request for cloud files and an SAF
 * descriptor for local ones. These tests exercise that byte plumbing without a
 * device, using the same vectors the crypto tests use.
 */
class PreviewEnvelopeTest {

    private val password = "sakura-test-密码-123"

    /** Serves an in-memory container, like a byte-range capable server would. */
    private class ByteArraySource(private val bytes: ByteArray) : CipherBlockSource {
        override fun open() = Unit

        override val cipherSize: Long get() = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() = Unit
    }

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Missing test resource: $name")
        return stream.use { it.readBytes() }
    }

    private fun head(source: CipherBlockSource): ByteArray =
        source.readRange(0, SkeFormat.PROBE_SIZE)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `reads the preview of a cli v003 file through a range source`() {
        val source = ByteArraySource(resource("vectors/cli_v003.ske"))

        val preview = EnvelopeReader.read(head(source), source, password)

        assertNotNull(preview)
        assertEquals(564, preview!!.size)
        assertEquals(0xFF, preview[0].toInt() and 0xFF)
        assertEquals(0xD8, preview[1].toInt() and 0xFF)
        assertEquals(
            "5dcc94925985cd211e78f7833c484c7b72c376ee502be9d1ca71fd3944e26760",
            sha256Hex(preview),
        )
    }

    @Test
    fun `wrong password yields no preview instead of throwing`() {
        val source = ByteArraySource(resource("vectors/cli_v003.ske"))

        assertNull(EnvelopeReader.read(head(source), source, "not-the-password"))
    }

    @Test
    fun `v002 container has no preview`() {
        val source = ByteArraySource(resource("vectors/cli_empty.ske"))

        assertNull(EnvelopeReader.read(head(source), source, password))
    }

    @Test
    fun `v003 container without a preview yields null`() {
        val dir = Files.createTempDirectory("ske-preview").toFile()
        val plain = File(dir, "notes.txt").apply { writeBytes(ByteArray(2_000) { 7 }) }
        val encrypted = File(dir, "notes.txt.ske")
        SkeFileCipher.encryptFile(plain, encrypted, "pw")

        val source = ByteArraySource(encrypted.readBytes())
        assertNull(EnvelopeReader.read(head(source), source, "pw"))
    }

    @Test
    fun `truncated probe is ignored`() {
        val source = ByteArraySource(resource("vectors/cli_v003.ske"))

        assertNull(EnvelopeReader.read(ByteArray(10), source, password))
    }
}
