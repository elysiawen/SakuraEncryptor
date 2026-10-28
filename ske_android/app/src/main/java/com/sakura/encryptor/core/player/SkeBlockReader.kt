package com.sakura.encryptor.core.player

import com.sakura.encryptor.core.cache.PlaintextBlockCache
import com.sakura.encryptor.core.crypto.SkeBlockDecryptor
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.crypto.SkeHeader
import java.io.IOException

/**
 * A tiny access-ordered LRU cache of decrypted blocks.
 *
 * Keeps the memory ceiling bounded while making seeks within already-played
 * regions instant.
 */
class BlockLruCache(private val maxEntries: Int) {

    private val entries = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(blockIndex: Long): ByteArray? = entries[blockIndex]

    @Synchronized
    fun put(blockIndex: Long, plaintext: ByteArray) {
        entries[blockIndex] = plaintext
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * Turns a random-access ciphertext source into a random-access *plaintext*
 * stream.
 *
 * Layout recap: plaintext block `i` lives at ciphertext offset
 * `bodyOffset + i * (CHUNK_SIZE + TAG_SIZE)` and is at most `CHUNK_SIZE`
 * bytes long (`bodyOffset` is the header size plus the thumbnail envelope).
 * Reading plaintext range `[p, p+n)` therefore only
 * touches the blocks that range spans — that is what makes HTTP Range seeking
 * possible.
 */
class SkeBlockReader(
    private val source: CipherBlockSource,
    val header: SkeHeader,
    private val decryptor: SkeBlockDecryptor,
    cipherSize: Long,
    cacheBlocks: Int,
) {

    val plaintextSize: Long = SkeHeader.plaintextSize(cipherSize, header.bodyOffset)
    private val blockCount: Long = SkeHeader.blockCount(cipherSize, header.bodyOffset)
    private val cache = BlockLruCache(cacheBlocks)
    private var cachedFinalBytes: Long = -1L
    private val saltHex: String = header.salt.joinToString("") { "%02x".format(it) }

    val blockCountValue: Long get() = blockCount

    /**
     * Read up to [length] plaintext bytes at [position] into [buffer].
     * @return bytes read, or -1 when [position] is at/after the end.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= plaintextSize) return -1
        if (length <= 0) return 0

        var cursor = position
        var written = 0
        var remaining = minOf(length.toLong(), plaintextSize - position).toInt()

        while (remaining > 0) {
            val blockIndex = cursor / SkeFormat.CHUNK_SIZE
            val offsetInBlock = (cursor % SkeFormat.CHUNK_SIZE).toInt()
            val block = block(blockIndex)

            val available = block.size - offsetInBlock
            if (available <= 0) break

            val toCopy = minOf(available, remaining)
            System.arraycopy(block, offsetInBlock, buffer, offset + written, toCopy)

            written += toCopy
            cursor += toCopy
            remaining -= toCopy
        }
        return written
    }

    /** Decrypt (or fetch from cache) the block at [blockIndex]. */
    private fun block(blockIndex: Long): ByteArray {
        cache.get(blockIndex)?.let { return it }
        if (blockIndex >= blockCount) throw IOException("Block $blockIndex out of range")

        val expected = minOf(
            SkeFormat.CHUNK_SIZE.toLong(),
            plaintextSize - blockIndex * SkeFormat.CHUNK_SIZE,
        ).toInt()
        // The salt names the container: unique per file and stable across
        // launches, unlike the signed download url it was fetched from.
        val diskKey = PlaintextBlockCache.key(saltHex, blockIndex)

        // A cached block is plaintext, not authenticated: a torn write would
        // surface as a short read, so only accept the exact expected length.
        val cached = PlaintextBlockCache.get(diskKey)
        if (cached != null && cached.size == expected) {
            cachedFinalBytes = cached.size.toLong()
            cache.put(blockIndex, cached)
            return cached
        }

        val start = header.bodyOffset + blockIndex * SkeFormat.ENC_BLOCK_SIZE
        val end = minOf(start + SkeFormat.ENC_BLOCK_SIZE, source.cipherSize)
        val size = (end - start).toInt()
        if (size <= SkeFormat.TAG_SIZE) throw IOException("Truncated block $blockIndex")

        val ciphertext = ByteArray(size)
        var read = 0
        while (read < size) {
            val chunk = source.read(start + read, ciphertext, read, size - read)
            if (chunk <= 0) throw IOException("Unexpected end of stream in block $blockIndex")
            read += chunk
        }

        val plaintext = decryptor.decryptBlock(blockIndex, ciphertext)
        cachedFinalBytes = plaintext.size.toLong()
        cache.put(blockIndex, plaintext)
        PlaintextBlockCache.put(diskKey, plaintext)
        return plaintext
    }

    fun clearCache() = cache.clear()

    fun cacheSize(): Int = cache.size()

    companion object {
        /**
         * Read and parse the header (plus the thumbnail envelope) from a
         * source that is already open.
         */
        fun readHeader(source: CipherBlockSource): SkeHeader {
            val bytes = ByteArray(SkeFormat.PROBE_SIZE)
            var position = 0L
            while (position < SkeFormat.PROBE_SIZE.toLong()) {
                val offset = position.toInt()
                val chunk = source.read(position, bytes, offset, SkeFormat.PROBE_SIZE - offset)
                if (chunk <= 0) break
                position += chunk
            }
            val filled = minOf(position, SkeFormat.PROBE_SIZE.toLong()).toInt()
            return SkeHeader.parse(bytes.copyOf(filled))
        }
    }
}
