package com.sakura.encryptor.core.player

import com.sakura.encryptor.core.crypto.SkeFileCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Container parsing is invisible until it is wrong, and the failures are format
 * specific — so each layout that matters gets a hand-built sample here.
 */
class AudioTagsTest {

    private fun readerOf(bytes: ByteArray) = TagReader { position, target, offset, length ->
        if (position >= bytes.size) {
            0
        } else {
            val count = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(target, offset, position.toInt(), position.toInt() + count)
            count
        }
    }

    private fun parse(bytes: ByteArray): AudioTags? =
        AudioTagExtractor.extract(readerOf(bytes), bytes.size.toLong())

    // -----------------------------------------------------------------------
    // FLAC
    // -----------------------------------------------------------------------

    /**
     * The regression this whole file exists for: FLAC keeps its metadata blocks
     * in sequence, so cover art ahead of the comment block used to make a fixed
     * read window give up before reaching the tags.
     */
    @Test
    fun `flac tags survive a large picture block in front of them`() {
        val flac = buildFlac(
            pictureBytes = 3 * 1024 * 1024,
            comments = linkedMapOf(
                "TITLE" to "樱",
                "ARTIST" to "某某",
                "ALBUM" to "专辑",
            ),
        )

        val tags = parse(flac)

        assertEquals("樱", tags?.title)
        assertEquals("某某", tags?.artist)
        assertEquals("专辑", tags?.album)
    }

    @Test
    fun `flac still reads tags when no picture is present`() {
        val flac = buildFlac(
            pictureBytes = 0,
            comments = linkedMapOf("TITLE" to "Only Title"),
        )

        assertEquals("Only Title", parse(flac)?.title)
    }

    @Test
    fun `flac reports nothing when there are no comments at all`() {
        val flac = buildFlac(pictureBytes = 0, comments = emptyMap())
        assertNull(parse(flac))
    }

    /**
     * Taggers sometimes write an ID3v2 tag in front of FLAC. Desktop players
     * skip straight past it; failing to do so is what makes a properly tagged
     * file look untagged here.
     */
    @Test
    fun `flac tags are read when an id3 tag is prepended`() {
        val id3 = buildId3(
            frames = listOf(
                id3Frame("TPE1", byteArrayOf(3) + "FromId3".toByteArray(Charsets.UTF_8)),
            ),
        )
        val flac = buildFlac(
            pictureBytes = 0,
            comments = linkedMapOf("TITLE" to "Real Title"),
        )

        val tags = parse(id3 + flac)

        // The real container wins...
        assertEquals("Real Title", tags?.title)
        // ...and whatever it lacks is filled in from the tag in front of it.
        assertEquals("FromId3", tags?.artist)
    }

    // -----------------------------------------------------------------------
    // ID3v2
    // -----------------------------------------------------------------------

    @Test
    fun `id3v2 text frames are read and a large apic does not hide them`() {
        val mp3 = buildId3(
            frames = listOf(
                // Art first, and big enough to matter.
                id3Frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
                    byteArrayOf(0, 3, 0) + ByteArray(512 * 1024)),
                id3Frame("TIT2", byteArrayOf(3) + "标题".toByteArray(Charsets.UTF_8)),
                id3Frame("TPE1", byteArrayOf(3) + "歌手".toByteArray(Charsets.UTF_8)),
                id3Frame("TALB", byteArrayOf(3) + "专辑".toByteArray(Charsets.UTF_8)),
            ),
        )

        val tags = parse(mp3)

        assertEquals("标题", tags?.title)
        assertEquals("歌手", tags?.artist)
        assertEquals("专辑", tags?.album)
        assertEquals(512 * 1024, tags?.artwork?.size)
    }

    // -----------------------------------------------------------------------
    // MP4 / M4A
    // -----------------------------------------------------------------------

    @Test
    fun `mp4 ilst atoms are read`() {
        val m4a = buildMp4(
            linkedMapOf(
                "\u00A9nam" to "Song",
                "\u00A9ART" to "Band",
                "\u00A9alb" to "Record",
            ),
        )

        val tags = parse(m4a)

        assertEquals("Song", tags?.title)
        assertEquals("Band", tags?.artist)
        assertEquals("Record", tags?.album)
    }

    /** `moov` at the tail is the common case for non-streaming files. */
    @Test
    fun `mp4 tags are found when moov sits behind a large mdat`() {
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(4))
        val mdat = box("mdat", ByteArray(4 * 1024 * 1024))
        val moov = buildMoov(linkedMapOf("\u00A9nam" to "Tailed"))
        val m4a = ftyp + mdat + moov

        assertEquals("Tailed", parse(m4a)?.title)
    }

    @Test
    fun `unknown containers yield nothing rather than throwing`() {
        assertNull(parse(ByteArray(64) { 0x7F }))
        assertNull(parse(ByteArray(0)))
    }

    // -----------------------------------------------------------------------
    // Encrypted or not
    //
    // A cloud folder mixes both kinds freely, so every reader has to decide per
    // file. Getting this wrong is what made a plain track look like a corrupt
    // container and lose all of its metadata.
    // -----------------------------------------------------------------------

    /** A [CipherBlockSource] over an in-memory byte array. */
    private class ByteArraySource(private val bytes: ByteArray) : CipherBlockSource {
        override fun open() = Unit

        override val cipherSize: Long get() = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun close() = Unit
    }

    @Test
    fun `a plain file is served through untouched`() = runBlocking {
        val flac = buildFlac(pictureBytes = 0, comments = linkedMapOf("TITLE" to "Plain"))

        val access = openPlaintext(ByteArraySource(flac), "irrelevant")

        assertNotNull(access)
        assertTrue(access!!.isPlain)
        assertEquals(flac.size.toLong(), access.size)
        assertEquals("Plain", AudioTagExtractor.extract(access.reader, access.size)?.title)
    }

    @Test
    fun `an encrypted container is decrypted instead`() = runBlocking {
        val flac = buildFlac(pictureBytes = 0, comments = linkedMapOf("TITLE" to "Sealed"))
        val dir = Files.createTempDirectory("ske-tags").toFile()
        val src = File(dir, "a.flac").apply { writeBytes(flac) }
        val enc = File(dir, "a.flac.ske")
        SkeFileCipher.encryptFile(src, enc, "pw")

        val access = openPlaintext(ByteArraySource(enc.readBytes()), "pw")

        assertNotNull(access)
        assertFalse(access!!.isPlain)
        // Decryption must recover the original length, not the container's.
        assertEquals(flac.size.toLong(), access.size)
        assertEquals("Sealed", AudioTagExtractor.extract(access.reader, access.size)?.title)
    }

    @Test
    fun `a container opened with the wrong password yields no tags`() = runBlocking {
        val flac = buildFlac(pictureBytes = 0, comments = linkedMapOf("TITLE" to "Sealed"))
        val dir = Files.createTempDirectory("ske-tags").toFile()
        val src = File(dir, "a.flac").apply { writeBytes(flac) }
        val enc = File(dir, "a.flac.ske")
        SkeFileCipher.encryptFile(src, enc, "right")

        val access = openPlaintext(ByteArraySource(enc.readBytes()), "wrong")

        // The header parses either way; it is block decryption that refuses. The
        // loader has to degrade to "no tags" instead of throwing, because a wrong
        // password must never be able to take playback down with it.
        assertNotNull(access)
        assertNull(AudioTagLoader.load(ByteArraySource(enc.readBytes()), "wrong"))
    }

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    private fun buildFlac(pictureBytes: Int, comments: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // STREAMINFO is 34 bytes; zeroes are structurally valid for our purpose.
        writeFlacBlock(out, type = 0, last = false, body = ByteArray(34))

        if (pictureBytes > 0) {
            writeFlacBlock(out, type = 6, last = false, body = flacPicture(pictureBytes))
        }

        writeFlacBlock(out, type = 4, last = true, body = vorbisComment(comments))
        return out.toByteArray()
    }

    private fun writeFlacBlock(out: ByteArrayOutputStream, type: Int, last: Boolean, body: ByteArray) {
        out.write(if (last) type or 0x80 else type)
        out.write((body.size shr 16) and 0xFF)
        out.write((body.size shr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(body)
    }

    private fun flacPicture(imageSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 3)) // picture type: front cover
        out.write(byteArrayOf(0, 0, 0, 10)) // MIME length
        out.write("image/jpeg".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0)) // description length
        out.write(ByteArray(16)) // width, height, depth, colours
        out.write(beInt(imageSize))
        out.write(ByteArray(imageSize) { 0x42 })
        return out.toByteArray()
    }

    private fun vorbisComment(comments: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "test".toByteArray(Charsets.UTF_8)
        out.write(leInt(vendor.size))
        out.write(vendor)
        out.write(leInt(comments.size))
        for ((key, value) in comments) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            out.write(leInt(entry.size))
            out.write(entry)
        }
        return out.toByteArray()
    }

    private fun buildId3(frames: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        for (frame in frames) body.write(frame)

        val payload = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(3) // major version
        out.write(0) // revision
        out.write(0) // flags
        // Tag size is synchsafe: seven bits per byte.
        out.write((payload.size shr 21) and 0x7F)
        out.write((payload.size shr 14) and 0x7F)
        out.write((payload.size shr 7) and 0x7F)
        out.write(payload.size and 0x7F)
        out.write(payload)
        return out.toByteArray()
    }

    private fun id3Frame(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(beInt(body.size))
        out.write(byteArrayOf(0, 0)) // flags
        out.write(body)
        return out.toByteArray()
    }

    private fun buildMp4(tags: Map<String, String>): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(4))
        return ftyp + buildMoov(tags)
    }

    private fun buildMoov(tags: Map<String, String>): ByteArray {
        val ilstBody = ByteArrayOutputStream()
        for ((key, value) in tags) {
            ilstBody.write(box(key, dataBox(value.toByteArray(Charsets.UTF_8))))
        }
        val meta = box(
            "meta",
            ByteArrayOutputStream().apply {
                write(ByteArray(4)) // full box: version + flags
                write(box("ilst", ilstBody.toByteArray()))
            }.toByteArray(),
        )
        return box("moov", box("udta", meta))
    }

    private fun dataBox(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(beInt(payload.size + 16))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(8)) // version/flags + locale
        out.write(payload)
        return out.toByteArray()
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(beInt(body.size + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun beInt(value: Int) = byteArrayOf(
        (value shr 24 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun leInt(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte(),
    )
}
