package com.sakura.encryptor.core.player

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

import android.net.Uri
import com.sakura.encryptor.core.crypto.SkeBlockDecryptor
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.crypto.SkeHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads a whole file into memory, decrypting it when it is a `.ske` container
 * and passing plain files through untouched.
 *
 * Used for side-loaded content (images, subtitles) that is small enough to
 * materialise, where a streaming [SkeDataSource] would be overkill.
 */
class DecryptedBytesReader(private val sourceFactory: CipherBlockSourceFactory) {

    /** @param password required only when the file turns out to be encrypted. */
    suspend fun readAll(uri: Uri, password: String?): ByteArray = withContext(Dispatchers.IO) {
        val source = sourceFactory.create(uri)
        try {
            source.open()

            val cipherSize = source.cipherSize
            if (cipherSize <= 0) throw IOException(t(R.string.player_cannot_determine_file_size))

            // Read past the header so the thumbnail envelope can be skipped.
            val headLength = minOf(cipherSize, SkeFormat.PROBE_SIZE.toLong()).toInt()
            val head = readRange(source, 0, headLength)
            val isContainer = head.size >= SkeFormat.MAGIC_SIZE &&
                head.copyOf(SkeFormat.MAGIC_SIZE).contentEquals(SkeFormat.MAGIC)

            if (!isContainer) {
                if (cipherSize > MAX_BYTES) throw IOException(t(R.string.player_file_too_large_mb, cipherSize / 1024 / 1024))
                return@withContext readRange(source, 0, cipherSize.toInt())
            }

            if (password.isNullOrEmpty()) throw IOException(t(R.string.player_locked_unlock_decrypt_file))

            val header = SkeHeader.parse(head)
            // create() derives with the work factor recorded in the container
            // and rejects a wrong password before any block is read.
            val decryptor = SkeBlockDecryptor.create(header, password)
            // Whole-file reads (images, subtitles) become a pure cache hit on
            // the next visit when the file cache is enabled; the reader keys on
            // the container's salt, which needs no path from here.
            val reader = SkeBlockReader(source, header, decryptor, cipherSize, CACHE_BLOCKS)

            val plaintextSize = reader.plaintextSize
            if (plaintextSize <= 0) throw IOException(t(R.string.player_file_empty))
            if (plaintextSize > MAX_BYTES) {
                throw IOException(t(R.string.player_file_too_large_mb, plaintextSize / 1024 / 1024))
            }

            val out = ByteArray(plaintextSize.toInt())
            var position = 0
            while (position < out.size) {
                val read = reader.read(position.toLong(), out, position, out.size - position)
                if (read <= 0) break
                position += read
            }
            if (position != out.size) {
                throw IOException(t(R.string.player_incomplete_read_bytes, position, out.size))
            }
            out
        } finally {
            runCatching { source.close() }
        }
    }

    private fun readRange(source: CipherBlockSource, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = source.read(offset + read, out, read, length - read)
            if (chunk <= 0) break
            read += chunk
        }
        return if (read == length) out else out.copyOf(read)
    }

    private companion object {
        const val CACHE_BLOCKS = 8
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
