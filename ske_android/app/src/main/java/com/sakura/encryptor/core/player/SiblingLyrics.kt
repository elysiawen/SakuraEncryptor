package com.sakura.encryptor.core.player

import android.net.Uri
import com.sakura.encryptor.core.playlist.PlaylistItem
import com.sakura.encryptor.core.playlist.PlaylistRepository
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Lyrics kept beside the track as a `.lrc` file.
 *
 * Ripped libraries rarely carry lyrics inline, and a file the user placed
 * deliberately is usually more accurate than whatever a tagger embedded — so
 * this is tried before the embedded copy. It works for cloud folders as well as
 * local ones, and the `.lrc` itself may be encrypted or plain.
 */
class SiblingLyricsLoader(
    private val playlistRepository: PlaylistRepository,
    private val cipherBlockSourceFactory: CipherBlockSourceFactory,
) {

    /**
     * @param displayName the track's *decrypted* file name, e.g. `简单爱.flac`.
     * @return the parsed lines, or null when no sibling file exists or it holds
     *   nothing usable.
     */
    suspend fun load(
        directory: String,
        fromCloud: Boolean,
        displayName: String,
        nameKey: ByteArray?,
        password: String,
    ): List<LyricLine>? {
        if (directory.isBlank() || displayName.isBlank()) return null

        val siblings = playlistRepository.listAll(directory, fromCloud, nameKey)
        if (siblings.isEmpty()) return null

        val lyrics = findSibling(siblings, displayName) ?: return null
        val uri = playlistRepository.resolveUri(lyrics, fromCloud) ?: return null

        val source = cipherBlockSourceFactory.create(Uri.parse(uri))
        return try {
            // Same rule as everywhere else: the file may or may not be a
            // container, and a plain one still has to be readable.
            val access = openPlaintext(source, password, cacheBlocks = 2) ?: return null
            val size = minOf(access.size, MAX_BYTES.toLong()).toInt()
            if (size <= 0) return null

            val bytes = access.reader.exact(0, size) ?: return null
            LrcParser.parse(decodeText(bytes)).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * Picks the lyrics file belonging to [displayName].
     *
     * Two conventions are in the wild — `简单爱.lrc` and `简单爱.flac.lrc` — and
     * neither is standardised, so both are accepted. Names arrive decrypted, so
     * matching happens on the readable form and not on the stored file name.
     */
    private fun findSibling(siblings: List<PlaylistItem>, displayName: String): PlaylistItem? {
        val stem = displayName.substringBeforeLast('.', displayName)
        val wanted = listOf("$stem.lrc", "$displayName.lrc")

        // Most specific first, so a full-name match wins over a stem match.
        for (candidate in wanted) {
            siblings.firstOrNull { it.displayName.equals(candidate, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    /**
     * Decodes a lyrics file.
     *
     * A BOM is the only unambiguous signal, so it is honoured first. Without
     * one the bytes are taken as UTF-8 only if they genuinely are UTF-8 —
     * otherwise the file is assumed to be GBK, which is still what most Chinese
     * `.lrc` files in the wild use. Reading those as UTF-8 yields mojibake, and
     * failing the whole load over it would be needlessly strict.
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        return decodeStrictly(bytes, Charsets.UTF_8)
            ?: runCatching { String(bytes, gbk) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    /** Decodes only when every byte is valid for [charset]. */
    private fun decodeStrictly(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private companion object {
        /** A lyrics file is text; anything larger is not one. */
        const val MAX_BYTES = 1 shl 20

        /** Resolved once: not every platform ships the GBK charset. */
        val gbk: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)
    }
}
