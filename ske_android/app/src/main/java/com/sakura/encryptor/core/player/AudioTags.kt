package com.sakura.encryptor.core.player

import android.util.Base64
import android.util.Log
import com.sakura.encryptor.BuildConfig
import com.sakura.encryptor.core.crypto.SkeBlockDecryptor
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.crypto.SkeHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** What a decrypted audio header gives up about the track. */
class AudioTags {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var artwork: ByteArray? = null

    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && artwork == null
}

/**
 * Random access to the decrypted bytes of a container.
 *
 * Parsers walk a container block by block and read only the blocks they want.
 * That matters beyond efficiency: FLAC stores its metadata blocks in sequence,
 * so a multi-megabyte `PICTURE` sitting ahead of `VORBIS_COMMENT` makes any
 * "read a fixed window and parse" approach give up before it reaches the tags.
 */
fun interface TagReader {
    /** Reads up to [length] bytes at [position] into [target] starting at [offset]. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

/**
 * Reads title, artist, album and cover art out of a decrypted audio container.
 *
 * Handled, in the containers that actually appear in the wild:
 *  - ID3v2 (mp3, and the tag some tools prepend to FLAC)
 *  - FLAC metadata blocks, including `VORBIS_COMMENT` and `PICTURE`
 *  - Ogg, for Vorbis and Opus comment headers
 *  - MP4 / M4A `moov.udta.meta.ilst`, with `moov` anywhere in the file
 */
object AudioTagExtractor {

    /** Text frames are tiny; anything larger is a malformed header. */
    private const val MAX_TEXT_BYTES = 1 shl 16

    /** Cover art ceiling, matching the loader's own read limit. */
    private const val MAX_ARTWORK_BYTES = 8 shl 20

    /** Comment blocks can legitimately carry a base64 picture inside them. */
    private const val MAX_COMMENT_BYTES = 8 shl 20

    fun extract(reader: TagReader, size: Long): AudioTags? {
        if (size < 12) return null
        val head = reader.exact(0, 12) ?: return null

        // An ID3v2 tag in front of the real container. That is not valid FLAC,
        // but plenty of taggers write one anyway — and stopping at it would miss
        // the Vorbis comment that actually holds the tags. This is precisely how
        // a file can show correct metadata in a desktop player and look untagged
        // here: desktop players skip the stray tag, this code did not.
        if (isId3v2(head)) {
            val fromId3 = parseId3v2(reader, size)
            val behind = id3v2TotalSize(reader)?.let { offset ->
                val probe = reader.exact(offset, 12)
                when {
                    probe == null -> null
                    isFlac(probe) -> parseFlac(reader, size, offset)
                    isOgg(probe) -> parseOgg(reader, size, offset)
                    else -> null
                }
            }
            return merge(behind, fromId3)
        }

        return when {
            isFlac(head) -> parseFlac(reader, size, 0L)
            isOgg(head) -> parseOgg(reader, size, 0L)
            isMp4(head) -> parseMp4(reader, size)
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // ID3v2
    // -----------------------------------------------------------------------

    private fun isId3v2(data: ByteArray): Boolean =
        data.size >= 3 &&
            data[0] == 'I'.code.toByte() &&
            data[1] == 'D'.code.toByte() &&
            data[2] == '3'.code.toByte()

    private fun parseId3v2(reader: TagReader, size: Long): AudioTags? {
        val header = reader.exact(0, 10) ?: return null
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0) return null

        var offset = 10L

        // Extended header, if present.
        if (flags and 0x40 != 0) {
            val extended = reader.exact(offset, 4) ?: return null
            // ID3v2.4 counts the size field itself, v2.3 does not.
            val extendedSize = if (major >= 4) {
                synchsafe(extended, 0)
            } else {
                4 + bigEndianInt(extended, 0)
            }
            offset += extendedSize
        }

        val tagEnd = minOf(10L + tagSize, size)
        val frameHeaderSize = if (major == 2) 6 else 10
        val tags = AudioTags()

        while (offset + frameHeaderSize <= tagEnd) {
            val frame = reader.exact(offset, frameHeaderSize) ?: break
            val idLength = if (major == 2) 3 else 4
            val id = String(frame, 0, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break

            val frameSize = if (major == 2) {
                ((frame[3].toInt() and 0xFF) shl 16) or
                    ((frame[4].toInt() and 0xFF) shl 8) or
                    (frame[5].toInt() and 0xFF)
            } else if (major >= 4) {
                synchsafe(frame, 4)
            } else {
                bigEndianInt(frame, 4)
            }

            val contentStart = offset + frameHeaderSize
            if (frameSize <= 0 || contentStart + frameSize > tagEnd) break

            when (id) {
                "TIT2", "TT2" -> parseId3Text(reader, contentStart, frameSize)?.let { tags.title = it }
                "TPE1", "TP1" -> parseId3Text(reader, contentStart, frameSize)?.let { tags.artist = it }
                "TALB", "TAL" -> parseId3Text(reader, contentStart, frameSize)?.let { tags.album = it }
                "APIC", "PIC" -> parseId3Picture(reader, contentStart, frameSize, major == 2)
                    ?.let { tags.artwork = it }
            }

            // Skip the body whether or not it was wanted: an unused frame still
            // has a size, and walking past it is what keeps large art cheap.
            offset = contentStart + frameSize
        }

        return tags.takeIf { !it.isEmpty }
    }

    /** A text information frame: one encoding byte, then the encoded string. */
    private fun parseId3Text(reader: TagReader, start: Long, length: Int): String? {
        val body = reader.capped(start, length, MAX_TEXT_BYTES) ?: return null
        if (body.size < 1) return null
        val encoding = body[0].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return runCatching {
            // Writers may pack several values separated by NUL; the first is the
            // one worth showing.
            String(body, 1, body.size - 1, charset)
                .substringBefore('\u0000')
                .trim()
                .ifEmpty { null }
        }.getOrNull()
    }

    /**
     * `APIC` layout: encoding, MIME (NUL-terminated), picture type, description
     * (NUL-terminated), then the image bytes. ID3v2.2's `PIC` swaps the MIME
     * string for a fixed three-character format code that is not terminated.
     */
    private fun parseId3Picture(
        reader: TagReader,
        start: Long,
        length: Int,
        version2: Boolean,
    ): ByteArray? {
        val body = reader.capped(start, length, MAX_ARTWORK_BYTES) ?: return null
        if (body.size < 4) return null
        val encoding = body[0].toInt() and 0xFF

        var position = 1
        if (version2) {
            position += 3
        } else {
            while (position < body.size && body[position] != 0.toByte()) position++
            position++
        }
        if (position >= body.size) return null

        // Picture type.
        position++
        if (position >= body.size) return null

        // Description, terminated by one NUL or two, depending on the encoding.
        val twoByteTerminator = encoding == 1 || encoding == 2
        while (position < body.size) {
            if (twoByteTerminator) {
                if (position + 1 < body.size &&
                    body[position] == 0.toByte() &&
                    body[position + 1] == 0.toByte()
                ) {
                    position += 2
                    break
                }
            } else if (body[position] == 0.toByte()) {
                position++
                break
            }
            position++
        }
        if (position >= body.size) return null

        return body.copyOfRange(position, body.size)
    }

    // -----------------------------------------------------------------------
    // FLAC
    // -----------------------------------------------------------------------

    private fun isFlac(data: ByteArray): Boolean =
        data[0] == 'f'.code.toByte() &&
            data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() &&
            data[3] == 'C'.code.toByte()

    private fun parseFlac(reader: TagReader, size: Long, start: Long): AudioTags? {
        val tags = AudioTags()
        var offset = start + 4

        while (offset + 4 <= size) {
            val header = reader.exact(offset, 4) ?: break
            val isLast = header[0].toInt() and 0x80 != 0
            val blockType = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            val blockStart = offset + 4
            when (blockType) {
                4 -> reader.capped(blockStart, length, MAX_COMMENT_BYTES)
                    ?.let { parseVorbisComment(it, 0, it.size, tags) }

                6 -> reader.capped(blockStart, length, MAX_ARTWORK_BYTES)
                    ?.let { parseFlacPicture(it, 0, it.size)?.let { art -> tags.artwork = art } }
            }

            // Jumping straight to the next block header is the whole point: a
            // large PICTURE never blocks the comment that follows it.
            offset = blockStart + length
            if (isLast) break
        }

        return tags.takeIf { !it.isEmpty }
    }

    /**
     * A `PICTURE` block: type, MIME, description, then width / height / depth /
     * colours as four big-endian ints, and finally the length and bytes of the
     * image itself.
     */
    private fun parseFlacPicture(data: ByteArray, start: Int, end: Int): ByteArray? {
        var position = start + 4
        if (position + 4 > end) return null

        val mimeLength = bigEndianInt(data, position)
        position += 4 + mimeLength
        if (position + 4 > end) return null

        val descriptionLength = bigEndianInt(data, position)
        position += 4 + descriptionLength
        if (position + 16 > end) return null

        position += 16
        if (position + 4 > end) return null

        val dataLength = bigEndianInt(data, position)
        position += 4
        if (dataLength <= 0 || position + dataLength > end) return null

        return data.copyOfRange(position, position + dataLength)
    }

    /** Vorbis comments: `vendor`, then a count, then `KEY=value` entries. */
    private fun parseVorbisComment(data: ByteArray, start: Int, end: Int, tags: AudioTags) {
        val header = parseVorbisCommentHeader(data, start, end) ?: return
        var offset = header

        val commentCount = littleEndianInt(data, offset)
        offset += 4

        repeat(commentCount.coerceAtLeast(0)) {
            if (offset + 4 > end) return
            val length = littleEndianInt(data, offset)
            offset += 4
            if (length <= 0 || offset + length > end) return

            val comment = String(data, offset, length, Charsets.UTF_8)
            offset += length

            val separator = comment.indexOf('=')
            if (separator <= 0) return@repeat
            applyVorbisEntry(
                comment.substring(0, separator),
                comment.substring(separator + 1).trim(),
                tags,
            )
        }
    }

    /** Walks the vendor string and returns the offset of the comment count. */
    private fun parseVorbisCommentHeader(data: ByteArray, start: Int, end: Int): Int? {
        if (start + 4 > end) return null
        val vendorLength = littleEndianInt(data, start)
        val offset = start + 4 + vendorLength
        return if (offset + 4 > end) null else offset
    }

    private fun applyVorbisEntry(key: String, value: String, tags: AudioTags) {
        if (value.isEmpty()) return
        when (key.uppercase()) {
            "TITLE" -> tags.title = value
            "ARTIST" -> tags.artist = value
            "ALBUM" -> tags.album = value
            "METADATA_BLOCK_PICTURE" -> {
                val decoded = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
                if (decoded != null) {
                    parseFlacPicture(decoded, 0, decoded.size)?.let { tags.artwork = it }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Ogg (Vorbis, Opus)
    // -----------------------------------------------------------------------

    private fun isOgg(data: ByteArray): Boolean =
        data[0] == 'O'.code.toByte() &&
            data[1] == 'g'.code.toByte() &&
            data[2] == 'g'.code.toByte() &&
            data[3] == 'S'.code.toByte()

    /**
     * Rebuilds Ogg packets and looks at the second one, which is the comment
     * header for both Vorbis and Opus.
     *
     * Packets are reassembled rather than pattern-matched in a fixed window
     * because a comment header carrying a base64 picture spills past a single
     * page, and the pages have to be stitched back together to read it.
     */
    private fun parseOgg(reader: TagReader, size: Long, start: Long): AudioTags? {
        var offset = start
        var packet = ByteArrayOutputStream()
        var packetIndex = 0

        while (offset + 27 <= size) {
            val page = reader.exact(offset, 27) ?: return null
            if (!isOgg(page)) return null

            val segmentCount = page[26].toInt() and 0xFF
            val table = reader.exact(offset + 27, segmentCount) ?: return null
            val bodyStart = offset + 27 + segmentCount
            val bodyLength = table.sumOf { it.toInt() and 0xFF }
            val body = if (bodyLength > 0) reader.exact(bodyStart, bodyLength) ?: return null else ByteArray(0)

            var cursor = 0
            for (index in 0 until segmentCount) {
                val segmentLength = table[index].toInt() and 0xFF
                packet.write(body, cursor, segmentLength)
                cursor += segmentLength

                // A segment shorter than 255 ends the packet.
                if (segmentLength < 255) {
                    if (packetIndex == 1) return parseOggComment(packet.toByteArray())
                    packetIndex++
                    packet = ByteArrayOutputStream()
                    // The comment header is always the second packet; nothing
                    // beyond it is worth decoding.
                    if (packetIndex > 1) return null
                }
            }

            offset = bodyStart + bodyLength
        }
        return null
    }

    private fun parseOggComment(packet: ByteArray): AudioTags? {
        val tags = AudioTags()

        // Vorbis prefixes "\x03vorbis"; Opus uses "OpusTags".
        val commentStart = when {
            packet.size > 7 && packet[0] == 3.toByte() &&
                String(packet, 1, 6, Charsets.US_ASCII) == "vorbis" -> 7

            packet.size > 8 && String(packet, 0, 8, Charsets.US_ASCII) == "OpusTags" -> 8

            else -> return null
        }

        val header = parseVorbisCommentHeader(packet, commentStart, packet.size) ?: return null
        var offset = header

        val commentCount = littleEndianInt(packet, offset)
        offset += 4

        repeat(commentCount.coerceAtLeast(0)) {
            if (offset + 4 > packet.size) return tags.takeIf { !it.isEmpty }
            val length = littleEndianInt(packet, offset)
            offset += 4
            if (length <= 0 || offset + length > packet.size) return tags.takeIf { !it.isEmpty }

            val comment = String(packet, offset, length, Charsets.UTF_8)
            offset += length

            val separator = comment.indexOf('=')
            if (separator > 0) {
                applyVorbisEntry(
                    comment.substring(0, separator),
                    comment.substring(separator + 1).trim(),
                    tags,
                )
            }
        }

        return tags.takeIf { !it.isEmpty }
    }

    // -----------------------------------------------------------------------
    // MP4 / M4A
    // -----------------------------------------------------------------------

    private fun isMp4(data: ByteArray): Boolean =
        data.size >= 8 && String(data, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    private fun parseMp4(reader: TagReader, size: Long): AudioTags? {
        // `moov` sits at the end of the file unless it was written for
        // streaming, so walk the top level boxes rather than assuming a position.
        val moov = findBox(reader, 0, size, "moov") ?: return null
        val udta = findBox(reader, moov.start, moov.end, "udta") ?: return null
        // `meta` is a full box, so its children start four bytes in.
        val meta = findBox(reader, udta.start, udta.end, "meta", contentSkip = 4) ?: return null
        val ilst = findBox(reader, meta.start, meta.end, "ilst") ?: return null

        val tags = AudioTags()
        var offset = ilst.start

        while (offset + 8 <= ilst.end) {
            val header = reader.exact(offset, 8) ?: break
            val boxSize = (bigEndianInt(header, 0).toLong() and 0xFFFFFFFFL)
            val boxType = String(header, 4, 4, Charsets.ISO_8859_1)
            if (boxSize < 8 || offset + boxSize > ilst.end) break

            val data = findBox(reader, offset + 8, offset + boxSize, "data")
            if (data != null) {
                // A `data` box is version/flags, locale, then the payload.
                val payloadStart = data.start + 8
                val payloadLength = (data.end - payloadStart).toInt()
                if (payloadLength > 0) {
                    when (boxType) {
                        "\u00A9nam" -> readMp4Text(reader, payloadStart, payloadLength)?.let { tags.title = it }
                        "\u00A9ART" -> readMp4Text(reader, payloadStart, payloadLength)?.let { tags.artist = it }
                        "\u00A9alb" -> readMp4Text(reader, payloadStart, payloadLength)?.let { tags.album = it }
                        "covr" -> reader.capped(payloadStart, payloadLength, MAX_ARTWORK_BYTES)
                            ?.let { tags.artwork = it }
                    }
                }
            }

            offset += boxSize
        }

        return tags.takeIf { !it.isEmpty }
    }

    private fun readMp4Text(reader: TagReader, start: Long, length: Int): String? {
        val body = reader.capped(start, length, MAX_TEXT_BYTES) ?: return null
        return runCatching {
            String(body, Charsets.UTF_8).substringBefore('\u0000').trim().ifEmpty { null }
        }.getOrNull()
    }

    /** A box's payload range, or null when [type] is not among the children. */
    private fun findBox(
        reader: TagReader,
        start: Long,
        end: Long,
        type: String,
        contentSkip: Int = 0,
    ): Box? {
        var offset = start
        while (offset + 8 <= end) {
            val header = reader.exact(offset, 8) ?: return null
            var boxSize = bigEndianInt(header, 0).toLong() and 0xFFFFFFFFL
            val boxType = String(header, 4, 4, Charsets.ISO_8859_1)
            var contentStart = offset + 8

            if (boxSize == 1L) {
                // 64-bit size follows the type.
                val extended = reader.exact(offset + 8, 8) ?: return null
                boxSize = bigEndianLong(extended, 0)
                contentStart = offset + 16
            } else if (boxSize == 0L) {
                // Runs to the end of the enclosing box.
                boxSize = end - offset
            }

            if (boxSize < contentStart - offset) return null
            val boxEnd = offset + boxSize
            if (boxEnd > end) return null

            if (boxType == type) {
                return Box((contentStart + contentSkip).coerceAtMost(boxEnd), boxEnd)
            }
            offset = boxEnd
        }
        return null
    }

    private class Box(val start: Long, val end: Long)

    /**
     * Fills the gaps in [primary] from [secondary].
     *
     * Used when a container is preceded by an ID3v2 tag: the real container wins
     * because it holds the fuller set, and whatever it lacks comes from the tag
     * written in front of it.
     */
    private fun merge(primary: AudioTags?, secondary: AudioTags?): AudioTags? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        primary.title = primary.title ?: secondary.title
        primary.artist = primary.artist ?: secondary.artist
        primary.album = primary.album ?: secondary.album
        primary.artwork = primary.artwork ?: secondary.artwork
        return primary
    }

    /** Bytes occupied by the ID3v2 tag at the head of the stream. */
    private fun id3v2TotalSize(reader: TagReader): Long? {
        val header = reader.exact(0, 10) ?: return null
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0) return null
        // A v2.4 footer, when the flag is set, sits after the tag.
        val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
        return 10L + tagSize + footer
    }

    // -----------------------------------------------------------------------
    // Byte helpers
    // -----------------------------------------------------------------------

    private fun synchsafe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun bigEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun bigEndianLong(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (data[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun littleEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}

/** Reads exactly [length] bytes, or null when the stream ends early. */
internal fun TagReader.exact(position: Long, length: Int): ByteArray? {
    if (length <= 0) return null
    val buffer = ByteArray(length)
    var total = 0
    while (total < length) {
        val chunk = read(position + total, buffer, total, length - total)
        if (chunk <= 0) break
        total += chunk
    }
    return if (total == length) buffer else null
}

/** Reads [length] bytes, refusing to go past [limit]. */
internal fun TagReader.capped(position: Long, length: Int, limit: Int): ByteArray? =
    exact(position, minOf(length, limit))

/**
 * Reads a media file's tags, decrypting first when it turns out to be a
 * container.
 *
 * The reader is random access, so parsers jump straight to the blocks that hold
 * tags instead of pulling the whole header into memory.
 */
object AudioTagLoader {

    suspend fun load(source: CipherBlockSource, password: String): AudioTags? = try {
        val access = openPlaintext(source, password)
        if (access == null) {
            null
        } else {
            val tags = AudioTagExtractor.extract(access.reader, access.size)
            if (BuildConfig.DEBUG) {
                Log.d(
                    "SakuraTags",
                    "plain=${access.isPlain} size=${access.size} title=${tags?.title} " +
                        "artist=${tags?.artist} album=${tags?.album} art=${tags?.artwork?.size}",
                )
            }
            tags
        }
    } catch (throwable: Exception) {
        // Reading tags must never be able to take playback down with it. A wrong
        // password or a damaged block simply means this track shows no metadata.
        if (BuildConfig.DEBUG) Log.w("SakuraTags", "load failed", throwable)
        null
    } finally {
        runCatching { source.close() }
    }
}

/**
 * How to read a media file's plaintext, whichever kind it turns out to be.
 *
 * A cloud folder routinely holds ordinary media next to encrypted containers —
 * the player copes because it decides from the first bytes and streams a plain
 * file through untouched. Everything that reads tags or lyrics has to apply the
 * same rule. Assuming a container is what made a plain track report "not a
 * Sakura Encryptor file" and lose every tag it had.
 */
internal class PlaintextAccess(
    val reader: TagReader,
    val size: Long,
    /** True when the file was served verbatim rather than decrypted. */
    val isPlain: Boolean,
)

/**
 * Opens [source] and returns a reader over its plaintext.
 *
 * The caller owns the source and must close it.
 *
 * @return the access, or null when the file cannot be read at all.
 */
internal suspend fun openPlaintext(
    source: CipherBlockSource,
    password: String,
    cacheBlocks: Int = 8,
): PlaintextAccess? = withContext(Dispatchers.IO) {
    try {
        source.open()

        val prefix = ByteArray(SkeFormat.HEADER_SIZE)
        var filled = 0
        while (filled < prefix.size) {
            val chunk = source.read(filled.toLong(), prefix, filled, prefix.size - filled)
            if (chunk <= 0) break
            filled += chunk
        }

        // The same test the player makes before deciding how to serve a file.
        val isContainer = filled >= SkeFormat.MAGIC_SIZE &&
            prefix.copyOf(SkeFormat.MAGIC_SIZE).contentEquals(SkeFormat.MAGIC)

        if (!isContainer) {
            // Plain file: hand out the raw bytes. No password is involved, which
            // is also why this works for media the user never encrypted.
            PlaintextAccess(
                reader = TagReader { position, target, offset, length ->
                    source.read(position, target, offset, length)
                },
                size = source.cipherSize,
                isPlain = true,
            )
        } else {
            val header = SkeHeader.parse(prefix)
            val key = SkeCrypto.deriveKey(password, header.salt)
            val decryptor = SkeBlockDecryptor(header, key)
            val blockReader = SkeBlockReader(
                source, header, decryptor, source.cipherSize,
                // Random access hops around, so keep more blocks warm.
                cacheBlocks = cacheBlocks,
            )

            PlaintextAccess(
                reader = TagReader { position, target, offset, length ->
                    blockReader.read(position, target, offset, length)
                },
                size = blockReader.plaintextSize,
                isPlain = false,
            )
        }
    } catch (throwable: Exception) {
        if (BuildConfig.DEBUG) Log.w("SakuraTags", "openPlaintext failed", throwable)
        null
    }
}
