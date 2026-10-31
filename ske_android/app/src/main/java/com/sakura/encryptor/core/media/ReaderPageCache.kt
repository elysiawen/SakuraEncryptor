package com.sakura.encryptor.core.media

import android.graphics.Bitmap

/**
 * Decrypted images of the viewer, kept in memory only.
 *
 * Both image modes compose one page at a time: the continuous reader puts each
 * page in a LazyColumn item, and the single-page view keeps one bitmap while
 * the playlist moves. Either way, leaving a page would otherwise drop it for
 * good — going back then pays for the whole pipeline again (PBKDF2, AES-GCM,
 * a fetch and a bitmap decode), which is far too slow to feel like "the page is
 * still there".
 *
 * Entries are bounded by a byte budget derived from the heap instead of a
 * fixed count: one 1080×4000 strip is ~17 MB, so a page count would either
 * waste memory on small images or blow the heap on tall ones. Evicted bitmaps
 * are not recycled on purpose — a composable may still be drawing one.
 */
object ReaderPageCache {

    /**
     * Decoded pages are large — a 1080×4000 strip is ~17 MB — so a small budget
     * only held two or three pages, and scrolling back past them paid for the
     * whole pipeline again. A third of the heap keeps a dozen or more pages on
     * a normal phone; ImageBytesCache catches whatever is still evicted.
     */
    private const val MIN_BUDGET_BYTES = 64L * 1024 * 1024
    private const val MAX_BUDGET_BYTES = 256L * 1024 * 1024

    private val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 3).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

    /** Access-ordered LRU; eviction is done by hand so the byte count stays exact. */
    private val cache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {}

    private var usedBytes = 0L

    /**
     * Identity of a page.
     *
     * The password hash keeps the same path from showing another vault's
     * contents when profiles are switched without locking in between. [variant]
     * separates the strip reader's screen-sized bitmaps from the single-page
     * viewer's full-resolution ones, so one mode never paints the other's.
     */
    fun key(locator: String, password: String, variant: String = ""): String =
        "$locator|${password.hashCode()}|$variant"

    fun peek(key: String): Bitmap? = synchronized(cache) { cache[key] }

    fun put(key: String, bitmap: Bitmap) {
        val size = bitmap.allocationByteCount.toLong()
        // A single page bigger than the whole budget is simply not cached.
        if (size > budgetBytes) return

        synchronized(cache) {
            cache.remove(key)?.let { previous ->
                usedBytes -= previous.allocationByteCount.toLong()
            }
            cache[key] = bitmap
            usedBytes += size

            val iterator = cache.entries.iterator()
            while (usedBytes > budgetBytes && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == key) continue
                iterator.remove()
                usedBytes -= entry.value.allocationByteCount.toLong()
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
