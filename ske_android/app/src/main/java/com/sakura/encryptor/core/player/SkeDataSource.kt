package com.sakura.encryptor.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.sakura.encryptor.core.crypto.SkeBlockDecryptor
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.crypto.SkeHeader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * A Media3 [DataSource] that presents a media file to ExoPlayer, decrypting it
 * on the fly when it is a `.ske` container.
 *
 * The mode is decided per file from its first bytes:
 *  - `.ske` container → reads are translated into the minimum set of encrypted
 *    blocks, fetched on demand and decrypted in memory; no plaintext touches disk.
 *  - anything else → the bytes are streamed through untouched, so ordinary
 *    (unencrypted) videos and music play as well.
 */
class SkeDataSource(
    private val factory: SkeDataSourceFactory,
    private val sourceFactory: CipherBlockSourceFactory,
    private val cacheBlocksProvider: () -> Int,
) : BaseDataSource(/* isNetwork = */ true) {

    private var source: CipherBlockSource? = null
    private var reader: SkeBlockReader? = null
    private var currentUri: Uri? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = 0

    /** True when the file is not a container and is served verbatim. */
    private var passthrough: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        val source = sourceFactory.create(uri)
        try {
            source.open()

            val prefix = readAt(source, 0, SkeFormat.HEADER_SIZE)
            val isContainer = prefix.size >= SkeFormat.MAGIC_SIZE &&
                prefix.copyOf(SkeFormat.MAGIC_SIZE).contentEquals(SkeFormat.MAGIC)

            val totalSize = if (isContainer) {
                val header = SkeHeader.parse(prefix)
                val password = factory.passwordFor(uri)
                val key = factory.deriveKeyFor(uri, password, header.salt)
                val decryptor = SkeBlockDecryptor(header, key)

                val blockReader = SkeBlockReader(
                    source = source,
                    header = header,
                    decryptor = decryptor,
                    cipherSize = source.cipherSize,
                    cacheBlocks = cacheBlocksProvider(),
                )
                reader = blockReader
                passthrough = false
                blockReader.plaintextSize
            } else {
                // Plain file: serve it as-is, no password required.
                reader = null
                passthrough = true
                source.cipherSize
            }

            if (totalSize <= 0) {
                throw IOException("File is empty or its size is unknown: ${uri.lastPathSegment}")
            }
            if (dataSpec.position > totalSize) {
                throw IOException("Requested position ${dataSpec.position} is past the end of the stream")
            }

            this.source = source
            this.currentUri = uri
            this.readPosition = dataSpec.position
            this.bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                totalSize - dataSpec.position
            } else {
                minOf(dataSpec.length, totalSize - dataSpec.position)
            }

            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            runCatching { source.close() }
            this.source = null
            this.reader = null
            throw if (e is IOException) e else IOException("Failed to open stream", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val source = this.source ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val read = if (passthrough) {
            source.read(readPosition, buffer, offset, toRead)
        } else {
            val blockReader = reader ?: return C.RESULT_END_OF_INPUT
            blockReader.read(readPosition, buffer, offset, toRead)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT

        readPosition += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        try {
            source?.close()
        } finally {
            source = null
            reader = null
            currentUri = null
            passthrough = false
            transferEnded()
        }
    }

    override fun getUri(): Uri? = currentUri

    private fun readAt(source: CipherBlockSource, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = source.read(offset + read, out, read, length - read)
            if (chunk <= 0) break
            read += chunk
        }
        return if (read == length) out else out.copyOf(read)
    }
}

/**
 * Builds [SkeDataSource] instances and caches derived keys.
 *
 * The password is resolved per URI, because local `content://` documents use
 * the standalone local password while remote files use the active cloud
 * profile's password.
 *
 * PBKDF2 with 100,000 iterations costs ~100 ms; the player calls `open()` on
 * every seek, so keys are memoised per (uri, password, salt).
 */
class SkeDataSourceFactory(
    private val sourceFactory: CipherBlockSourceFactory,
    private val passwordProvider: (Uri) -> String?,
    private val cacheBlocksProvider: () -> Int,
) : DataSource.Factory {

    private val derivedKeys = ConcurrentHashMap<String, ByteArray>()

    override fun createDataSource(): DataSource =
        SkeDataSource(this, sourceFactory, cacheBlocksProvider)

    /** Resolve the password for [uri], failing loudly when the vault is locked. */
    internal fun passwordFor(uri: Uri): String =
        passwordProvider(uri)?.takeIf { it.isNotEmpty() }
            ?: throw IOException("尚未解锁，无法解密该文件")

    internal fun deriveKeyFor(uri: Uri, password: String, salt: ByteArray): ByteArray {
        val cacheKey = buildString {
            append(uri)
            append('|')
            append(password.hashCode())
            append('|')
            append(salt.joinToString("") { "%02x".format(it) })
        }
        return derivedKeys.getOrPut(cacheKey) { SkeCrypto.deriveKey(password, salt) }
    }

    /** Drop memoised keys, e.g. when a password changes or the vault locks. */
    fun invalidateKeys() = derivedKeys.clear()
}
