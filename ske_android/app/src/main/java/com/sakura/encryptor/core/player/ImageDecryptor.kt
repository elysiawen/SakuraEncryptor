package com.sakura.encryptor.core.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.sakura.encryptor.core.cache.PlaintextBlockCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Decrypted image payloads (the JPEG/PNG bytes), kept in memory first and
 * mirrored to the file cache when the user has enabled it.
 *
 * [ReaderPageCache] holds decoded bitmaps, but those are huge and only a dozen
 * fit in it. Once a page is evicted, the next visit would otherwise fetch the
 * ciphertext and run AES-GCM again. The compressed bytes are small enough to
 * keep many more pages in memory, so a revisit only costs a bitmap decode.
 *
 * With 10 MB pages, memory alone still runs out after a dozen pages, so
 * `.ske` payloads also go to [PlaintextBlockCache] (the "缓存文件" setting,
 * app-private, cleared on every lock). A revisit then re-reads one blob from
 * disk instead of re-downloading and decrypting the whole page.
 */
object ImageBytesCache {

    /** Derived from the heap, like [ReaderPageCache]; compressed pages are small. */
    private const val MIN_BUDGET_BYTES = 32L * 1024 * 1024
    private const val MAX_BUDGET_BYTES = 192L * 1024 * 1024

    /** Covers the 10 MB pages this is meant to keep, without letting one hog it. */
    private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024

    private val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

    private val cache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {}
    private var usedBytes = 0L

    /** Signed query strings are re-issued per login, so only the path is keyed. */
    fun key(uri: Uri, password: String): String =
        "${uri.toString().substringBefore('?')}|${password.hashCode()}"

    fun get(key: String): ByteArray? = synchronized(cache) { cache[key] }

    fun put(key: String, bytes: ByteArray) {
        if (bytes.size.toLong() > MAX_ENTRY_BYTES) return
        synchronized(cache) {
            cache.remove(key)?.let { usedBytes -= it.size }
            cache[key] = bytes
            usedBytes += bytes.size

            val iterator = cache.entries.iterator()
            while (usedBytes > budgetBytes && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == key) continue
                iterator.remove()
                usedBytes -= entry.value.size
            }
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
            usedBytes = 0L
        }
    }
}

/**
 * Materialises a whole image into a [Bitmap], decrypting `.ske` containers and
 * passing plain images through.
 *
 * Decoding is sampled so very large photos do not blow up the heap, and
 * failures name their cause instead of a generic message.
 */
class ImageDecryptor(
    sourceFactory: CipherBlockSourceFactory,
    private val bytesReader: DecryptedBytesReader = DecryptedBytesReader(sourceFactory),
) {

    /**
     * @param maxPixels caps the decoded bitmap's pixel count. The continuous
     *   reader passes a budget here because it draws pages at screen width and
     *   never zooms: decoding a 4000×3000 photo at full size costs 48 MB, while
     *   the reader only ever shows it at ~1080 px wide. `null` keeps the
     *   original resolution (the single-page viewer needs it for zoom).
     */
    suspend fun decrypt(uri: Uri, password: String, maxPixels: Int? = null): Bitmap {
        val key = ImageBytesCache.key(uri, password)
        // "img|" keeps whole-page blobs from colliding with the block entries
        // that share the same store.
        val diskKey = "img|$key"
        val payload = ImageBytesCache.get(key) ?: withContext(Dispatchers.IO) {
            PlaintextBlockCache.get(diskKey)?.also { ImageBytesCache.put(key, it) }
                ?: run {
                    val bytes = bytesReader.readAll(uri, password)
                    if (bytes.isEmpty()) throw IOException("图片内容为空")
                    ImageBytesCache.put(key, bytes)
                    // Only containers need this: a plain local image is already
                    // on disk and costs nothing to read again.
                    if (isContainer(uri)) PlaintextBlockCache.put(diskKey, bytes)
                    bytes
                }
        }
        return withContext(Dispatchers.IO) { decode(payload, maxPixels) }
    }

    private fun isContainer(uri: Uri): Boolean =
        uri.toString().substringBefore('?').endsWith(".ske", ignoreCase = true)

    private fun decode(bytes: ByteArray, maxPixels: Int?): Bitmap {
        // Probe the dimensions first so the sample factor can be chosen.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("无法识别图片格式（HEIC / AVIF 等可能不被系统支持）")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("图片解码失败（${bytes.size / 1024} KB，${bounds.outWidth}×${bounds.outHeight}）")
    }

    /**
     * Largest power-of-two sample that fits the requested budget.
     *
     * Without [maxPixels] the longest edge stays within [MAX_DIMENSION], which
     * still allows a 4096×4096 bitmap. With it, only the pixel count matters —
     * important for tall comic strips, where the longest-edge rule would shrink
     * the width far below the screen while the area stays small anyway.
     */
    private fun sampleSizeFor(width: Int, height: Int, maxPixels: Int?): Int {
        var sample = 1
        if (maxPixels != null) {
            while (width.toLong() * height.toLong() > maxPixels.toLong() * sample * sample) {
                sample *= 2
            }
        } else {
            while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) {
                sample *= 2
            }
        }
        return sample
    }

    private companion object {
        const val MAX_DIMENSION = 4096
    }
}
