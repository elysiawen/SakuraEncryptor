package com.sakura.encryptor.core.player

import com.sakura.encryptor.core.crypto.SkeBlockDecryptor
import com.sakura.encryptor.core.crypto.SkeCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One synced lyric line. [timeMs] is -1 for unsynced plain text. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Parses LRC content (`[mm:ss.xx]text`).
 *
 * Plain-text lyrics (no timestamps) are also accepted and returned as
 * unsynced lines so they can still be browsed while playing.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val METADATA = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun parse(raw: String): List<LyricLine> {
        val synced = ArrayList<LyricLine>()

        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || METADATA.matches(trimmed)) continue

            val stamps = TIMESTAMP.findAll(trimmed).toList()
            if (stamps.isEmpty()) continue

            val text = trimmed.substring(stamps.last().range.last + 1).trim()
            if (text.isEmpty()) continue

            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
                val fraction = stamp.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.toLong()
                }
                synced += LyricLine(minutes * 60_000 + seconds * 1_000 + millis, text)
            }
        }

        if (synced.isNotEmpty()) return synced.sortedBy { it.timeMs }

        // Fall back to unsynced text.
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !METADATA.matches(it) }
            .map { LyricLine(-1, it) }
    }

    /** True when the lines carry timestamps. */
    fun isSynced(lines: List<LyricLine>): Boolean = lines.any { it.timeMs >= 0 }
}

/**
 * Pulls embedded lyrics out of a decrypted audio header.
 *
 * Supports the two containers that matter in practice:
 *  - ID3v2 `USLT` (MP3)
 *  - FLAC `VORBIS_COMMENT` / `LYRICS` (FLAC, and OGG-style tags)
 */
object LyricsExtractor {

    fun extract(data: ByteArray): String? {
        if (data.size < 10) return null
        return when {
            data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte() ->
                parseId3v2(data)

            data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
                data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte() ->
                parseFlac(data)

            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // ID3v2
    // -----------------------------------------------------------------------

    private fun parseId3v2(data: ByteArray): String? {
        val major = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val tagSize = synchsafe(data, 6)
        if (tagSize <= 0) return null

        var offset = 10

        // Extended header, if present.
        if (flags and 0x40 != 0 && offset + 4 <= data.size) {
            val extendedSize = if (major >= 4) synchsafe(data, offset) else bigEndianInt(data, offset)
            offset += extendedSize
        }

        val tagEnd = minOf(10 + tagSize, data.size)
        val frameHeaderSize = if (major == 2) 6 else 10

        while (offset + frameHeaderSize <= tagEnd) {
            val idLength = if (major == 2) 3 else 4
            if (offset + idLength > tagEnd) break
            val id = String(data, offset, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break

            val frameSize = if (major == 2) {
                ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 8) or
                    (data[offset + 5].toInt() and 0xFF)
            } else if (major >= 4) {
                synchsafe(data, offset + 4)
            } else {
                bigEndianInt(data, offset + 4)
            }

            val contentStart = offset + frameHeaderSize
            val contentEnd = contentStart + frameSize
            if (frameSize <= 0 || contentEnd > tagEnd) break

            if (id == "USLT" || id == "ULT") {
                parseUslt(data, contentStart, contentEnd)?.let { return it }
            }

            offset = contentEnd
        }
        return null
    }

    private fun parseUslt(data: ByteArray, start: Int, end: Int): String? {
        if (end - start < 4) return null
        val encoding = data[start].toInt() and 0xFF

        // Skip encoding byte and the 3-byte language code.
        var position = start + 4

        // Skip the content descriptor, terminated by one or two NUL bytes.
        val twoByteTerminator = encoding == 1 || encoding == 2
        while (position < end) {
            if (!twoByteTerminator) {
                if (data[position] == 0.toByte()) {
                    position++
                    break
                }
            } else {
                if (position + 1 < end && data[position] == 0.toByte() && data[position + 1] == 0.toByte()) {
                    position += 2
                    break
                }
            }
            position++
        }
        if (position >= end) return null

        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }

        return runCatching {
            String(data, position, end - position, charset)
                .trim('\u0000')
                .trim()
                .ifEmpty { null }
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // FLAC / Vorbis comment
    // -----------------------------------------------------------------------

    private fun parseFlac(data: ByteArray): String? {
        var offset = 4
        while (offset + 4 <= data.size) {
            val header = data[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val length = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

            val blockStart = offset + 4
            val blockEnd = blockStart + length
            if (blockEnd > data.size) break

            if (blockType == 4) {
                parseVorbisComment(data, blockStart, blockEnd)?.let { return it }
            }
            if (isLast) break
            offset = blockEnd
        }
        return null
    }

    private fun parseVorbisComment(data: ByteArray, start: Int, end: Int): String? {
        var offset = start
        if (offset + 4 > end) return null

        val vendorLength = littleEndianInt(data, offset)
        offset += 4 + vendorLength
        if (offset + 4 > end) return null

        val commentCount = littleEndianInt(data, offset)
        offset += 4

        repeat(commentCount.coerceAtLeast(0)) {
            if (offset + 4 > end) return null
            val length = littleEndianInt(data, offset)
            offset += 4
            if (length <= 0 || offset + length > end) return null

            val comment = String(data, offset, length, Charsets.UTF_8)
            offset += length

            val separator = comment.indexOf('=')
            if (separator > 0) {
                val key = comment.substring(0, separator).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") {
                    val value = comment.substring(separator + 1).trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
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

    private fun littleEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Decrypts just enough of a `.ske` audio container to read its embedded tags.
 *
 * Only the leading blocks are needed — ID3v2 and FLAC metadata always live at
 * the very start of the file — so this stays cheap even for large tracks.
 */
object LyricsLoader {

    suspend fun load(
        source: CipherBlockSource,
        password: String,
        maxBytes: Int = 1 shl 20,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        source.open()
        try {
            val header = SkeBlockReader.readHeader(source)
            val key = SkeCrypto.deriveKey(password, header.salt)
            val decryptor = SkeBlockDecryptor(header, key)
            val reader = SkeBlockReader(source, header, decryptor, source.cipherSize, cacheBlocks = 2)

            val available = minOf(reader.plaintextSize, maxBytes.toLong())
            if (available <= 0) return@withContext null

            val size = available.toInt()
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val chunk = reader.read(read.toLong(), bytes, read, size - read)
                if (chunk <= 0) break
                read += chunk
            }
            if (read <= 0) return@withContext null

            val raw = LyricsExtractor.extract(bytes) ?: return@withContext null
            LrcParser.parse(raw).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { source.close() }
        }
    }
}
