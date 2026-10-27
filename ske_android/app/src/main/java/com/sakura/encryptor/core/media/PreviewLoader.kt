package com.sakura.encryptor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.sakura.encryptor.core.crypto.SkeFileCipher
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.crypto.SkeHeader
import com.sakura.encryptor.core.player.CipherBlockSource
import com.sakura.encryptor.core.player.CipherBlockSourceFactory
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bitmaps for grid tiles, kept in memory only.
 *
 * A tile is ~320 px, so 64 entries sit around 25 MB — enough for a screenful of
 * scrolling without making the app a memory hog. Decoding the same key twice
 * concurrently (two recompositions racing) is deduplicated per key.
 */
object PreviewCache {

    private const val MAX_ENTRIES = 64

    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_ENTRIES
    }

    private val locks = HashMap<String, Mutex>()

    fun peek(key: String): Bitmap? = synchronized(cache) { cache[key] }

    suspend fun load(key: String, block: suspend () -> Bitmap?): Bitmap? {
        peek(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            peek(key) ?: block()?.also { bitmap -> synchronized(cache) { cache[key] = bitmap } }
        }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(locks) { locks.clear() }
    }
}

/**
 * Loads the preview a grid tile shows, reusing the very same code paths that
 * play the file.
 *
 * For a `.ske` container only the v003 envelope is read — header plus the
 * encrypted JPEG — so a remote tile costs one ~40 KB range request instead of a
 * download. Plaintext files (only possible for local folders) are decoded
 * directly, and video frames come from the platform decoder.
 */
class PreviewLoader(
    private val context: Context,
    private val sources: CipherBlockSourceFactory,
) {

    suspend fun load(uri: Uri, displayName: String, password: String?): Bitmap? {
        // The key carries the lock state: unlocking must not keep a null hit.
        val key = "$uri|${if (password.isNullOrEmpty()) "locked" else "unlocked"}"
        return PreviewCache.load(key) { build(uri, displayName, password) }
    }

    private suspend fun build(uri: Uri, displayName: String, password: String?): Bitmap? =
        withContext(Dispatchers.IO) {
            val source = runCatching { sources.create(uri).also { it.open() } }.getOrNull()
                ?: return@withContext null
            try {
                if (source.cipherSize <= 0) return@withContext null
                val headLength = minOf(source.cipherSize, SkeFormat.PROBE_SIZE.toLong()).toInt()
                val head = source.readRange(0, headLength)
                if (isContainer(head)) {
                    if (password.isNullOrEmpty()) return@withContext null
                    decode(EnvelopeReader.read(head, source, password) ?: return@withContext null)
                } else {
                    plainPreview(uri, displayName, source)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            } finally {
                runCatching { source.close() }
            }
        }

    private fun plainPreview(
        uri: Uri,
        displayName: String,
        source: CipherBlockSource,
    ): Bitmap? = when (MediaTypes.kindOf(displayName)) {
        MediaKind.IMAGE -> {
            if (source.cipherSize > MAX_PLAIN_BYTES) null
            else decode(source.readRange(0, source.cipherSize.toInt()))
        }
        // A remote frame grab would pull megabytes for one tile; the tile falls
        // back to the video icon instead.
        MediaKind.VIDEO -> if (isLocal(uri)) videoFrame(uri) else null
        else -> null
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val frame = retriever.getFrameAtTime(
                durationMs * 300L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: retriever.frameAtTime ?: return null
            downscale(frame)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= TARGET_PX) return source
        val factor = TARGET_PX.toFloat() / longSide
        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        return scaled
    }

    private fun isContainer(head: ByteArray): Boolean =
        head.size >= SkeFormat.MAGIC.size &&
            head.copyOf(SkeFormat.MAGIC.size).contentEquals(SkeFormat.MAGIC)

    private fun isLocal(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf("content", "file", "android.resource")

    /** Keep the decode near 2x the tile so the final downscale stays sharp. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= TARGET_PX) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val TARGET_PX = 320
        const val MAX_PLAIN_BYTES = 24L * 1024 * 1024
    }
}

/**
 * Pulls the decrypted preview out of a v003 container.
 *
 * Free of Android graphics types on purpose: the byte plumbing is what differs
 * between an HTTP range request and an SAF stream, and this way it can be
 * covered by JVM tests.
 */
internal object EnvelopeReader {

    fun read(head: ByteArray, source: CipherBlockSource, password: String): ByteArray? {
        if (head.size < SkeFormat.PROBE_SIZE) return null
        val header = runCatching { SkeHeader.parse(head) }.getOrNull() ?: return null
        if (!header.hasEmbeddedThumbnail) return null

        val ciphertext = source.readRange(SkeFormat.PROBE_SIZE.toLong(), header.thumbnailLength)
        if (ciphertext.size != header.thumbnailLength) return null

        return runCatching { SkeFileCipher.decryptThumbnail(header, ciphertext, password) }
            .getOrNull()
    }
}

/** Read exactly [length] bytes at [position], tolerating short reads. */
internal fun CipherBlockSource.readRange(position: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(position + offset, buffer, offset, length - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == length) buffer else buffer.copyOf(offset)
}
