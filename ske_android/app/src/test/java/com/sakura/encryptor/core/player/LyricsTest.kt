package com.sakura.encryptor.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LyricsTest {

    // -----------------------------------------------------------------------
    // LRC parsing
    // -----------------------------------------------------------------------

    @Test
    fun `parses timestamps into milliseconds`() {
        val lines = LrcParser.parse(
            """
            [ti:demo]
            [00:01.50]first
            [00:12]second
            [01:02.25]third
            """.trimIndent()
        )

        assertEquals(3, lines.size)
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals("first", lines[0].text)
        assertEquals(12_000L, lines[1].timeMs)
        assertEquals(62_250L, lines[2].timeMs)
    }

    @Test
    fun `expands lines carrying several timestamps`() {
        val lines = LrcParser.parse("[00:05.00][00:09.00]chorus")
        assertEquals(2, lines.size)
        assertEquals(5_000L, lines[0].timeMs)
        assertEquals(9_000L, lines[1].timeMs)
        assertEquals("chorus", lines[0].text)
        assertEquals("chorus", lines[1].text)
    }

    @Test
    fun `falls back to unsynced text`() {
        val lines = LrcParser.parse("just\nplain\nlyrics")
        assertEquals(3, lines.size)
        assertFalse(LrcParser.isSynced(lines))
        assertEquals(-1L, lines[0].timeMs)
    }

    @Test
    fun `synced parser reports synced`() {
        assertTrue(LrcParser.isSynced(LrcParser.parse("[00:01.00]x")))
    }

    // -----------------------------------------------------------------------
    // ID3v2 extraction
    // -----------------------------------------------------------------------

    @Test
    fun `extracts id3v2 USLT lyrics`() {
        val lyrics = "[00:01.00]line one\n[00:02.00]line two"
        val data = buildId3v2WithUslt(lyrics)

        val extracted = LyricsExtractor.extract(data)
        assertEquals(lyrics, extracted)

        val parsed = LrcParser.parse(extracted!!)
        assertEquals(2, parsed.size)
        assertEquals("line one", parsed[0].text)
    }

    @Test
    fun `returns null for data without lyrics`() {
        assertNull(LyricsExtractor.extract(ByteArray(64) { 1 }))
        assertNull(LyricsExtractor.extract(ByteArray(0)))
    }

    @Test
    fun `ignores unrelated id3 frames`() {
        val artist = "TPE1".toByteArray(Charsets.ISO_8859_1) +
            bigEndian(5) + byteArrayOf(0, 0) + byteArrayOf(0x03) + "band".toByteArray()

        val header = "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x03, 0x00, 0x00) + synchsafe(artist.size)

        assertNull(LyricsExtractor.extract(header + artist))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a minimal ID3v2.3 tag containing a single UTF-8 `USLT` frame. */
    private fun buildId3v2WithUslt(lyrics: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0x03)                                   // encoding: UTF-8
            write("eng".toByteArray(Charsets.ISO_8859_1)) // language
            write(0x00)                                   // empty descriptor + terminator
            write(lyrics.toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val frame = "USLT".toByteArray(Charsets.ISO_8859_1) +
            bigEndian(body.size) +
            byteArrayOf(0x00, 0x00) +
            body

        return "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x03, 0x00, 0x00) +
            synchsafe(frame.size) +
            frame
    }

    private fun bigEndian(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
