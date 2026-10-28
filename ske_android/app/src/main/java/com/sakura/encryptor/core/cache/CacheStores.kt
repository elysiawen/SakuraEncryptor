package com.sakura.encryptor.core.cache

import android.content.Context
import com.sakura.encryptor.core.crypto.SkeCrypto
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts a budget in MB to bytes, where `0` means "no budget at all".
 *
 * [Long.MAX_VALUE] keeps [BlobDiskCache.trim] from ever evicting, which is what
 * "unlimited" has to look like internally.
 */
fun cacheLimitMbToBytes(mb: Int): Long =
    if (mb <= 0) Long.MAX_VALUE else mb.toLong() * 1024 * 1024

/**
 * Directory for [name], discarded once when [version] changes.
 *
 * The marker lives next to the directory (not inside it) so trimming can never
 * evict it and silently wipe the cache on the next launch.
 */
private fun cacheDirectory(context: Context, name: String, version: Int): File {
    val root = File(context.cacheDir, name)
    val marker = File(context.cacheDir, "$name.v$version")
    if (!marker.isFile) {
        root.deleteRecursively()
        root.mkdirs()
        runCatching { marker.createNewFile() }
    }
    return root
}

/**
 * A size-capped directory of opaque blobs, evicted oldest-read-first.
 *
 * Keys never reach the file system: they are hashed to a fixed 64-character
 * name, which keeps Chinese file names, path separators and over-long uris from
 * turning into illegal or unwritable paths.
 *
 * The budget is owned by the caller — it mirrors a user setting — so [limit] is
 * mutable and lowering it drops entries right away.
 */
class BlobDiskCache(private val root: File, initialLimit: Long) {

    private val lock = Any()

    @Volatile
    private var limit: Long = initialLimit.coerceAtLeast(0)

    fun limitBytes(): Long = limit

    /** Change the budget; anything over it is evicted immediately. */
    fun setLimitBytes(bytes: Long) {
        limit = bytes.coerceAtLeast(0)
        trim()
    }

    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching {
            // Touch on read: the trim below evicts the least recently *used*.
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            root.mkdirs()
            fileFor(key).writeBytes(bytes)
            trim()
        }
    }

    fun usageBytes(): Long =
        root.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun clear() {
        synchronized(lock) {
            root.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = buildString(digest.size * 2) {
            for (byte in digest) append("%02x".format(byte))
        }
        return File(root, name)
    }

    /** Drop the oldest files until the directory fits in the current budget. */
    private fun trim() {
        synchronized(lock) {
            val max = limit
            val files = root.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= max) return

            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= max) return
                total -= file.length()
                runCatching { file.delete() }
            }
        }
    }
}

/**
 * Thumbnails decrypted from the containers' preview envelopes.
 *
 * Storing the JPEG bytes (not the decoded bitmap) keeps the cache small and
 * lets the grid skip the request *and* the PBKDF2 run on a second visit.
 */
object ThumbnailDiskCache {

    /** First-launch budget; the user's setting takes over as soon as it loads. */
    const val DEFAULT_LIMIT_BYTES = 128L * 1024 * 1024

    /**
     * Bumped when the key scheme changes, so entries written under the old
     * scheme are dropped instead of lingering as unreachable garbage. Version 2
     * keys on the vault path; version 1 keyed on the signed raw url, which made
     * every entry miss after a restart.
     */
    private const val KEY_VERSION = 2

    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var limitBytes: Long = DEFAULT_LIMIT_BYTES

    private var store: BlobDiskCache? = null

    fun init(context: Context) {
        store = BlobDiskCache(cacheDirectory(context, "thumbnails", KEY_VERSION), limitBytes)
    }

    fun setLimitBytes(bytes: Long) {
        limitBytes = bytes.coerceAtLeast(0)
        store?.setLimitBytes(bytes)
    }

    fun limitBytes(): Long = limitBytes

    fun get(key: String): ByteArray? = if (enabled) store?.get(key) else null

    fun put(key: String, bytes: ByteArray) {
        if (enabled) store?.put(key, bytes)
    }

    fun usageBytes(): Long = store?.usageBytes() ?: 0L

    fun clear() = store?.clear()
}

/**
 * Decrypted media blocks, keyed by (salt, block index).
 *
 * Playing a video again then costs no network request and no AES-GCM work for
 * the parts already watched. The plaintext lives only while the vault is
 * unlocked: [clear] runs on every lock, and the directory is app-private.
 */
object PlaintextBlockCache {

    /** First-launch budget; the user's setting takes over as soon as it loads. */
    const val DEFAULT_LIMIT_BYTES = 1024L * 1024 * 1024

    /** Version 2 keys on the container salt instead of the signed raw url. */
    private const val KEY_VERSION = 2

    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var limitBytes: Long = DEFAULT_LIMIT_BYTES

    private var store: BlobDiskCache? = null

    fun init(context: Context) {
        store = BlobDiskCache(cacheDirectory(context, "plaintext", KEY_VERSION), limitBytes)
    }

    fun setLimitBytes(bytes: Long) {
        limitBytes = bytes.coerceAtLeast(0)
        store?.setLimitBytes(bytes)
    }

    fun limitBytes(): Long = limitBytes

    /**
     * Identity of one block. The salt alone is enough: `.ske` generates a fresh
     * random salt per container, so it names the file uniquely *and* survives
     * a restart — unlike the signed raw url, which is re-issued on every login.
     */
    fun key(saltHex: String, blockIndex: Long): String = "$saltHex|$blockIndex"

    fun get(key: String): ByteArray? = if (enabled) store?.get(key) else null

    fun put(key: String, bytes: ByteArray) {
        if (enabled) store?.put(key, bytes)
    }

    fun usageBytes(): Long = store?.usageBytes() ?: 0L

    fun clear() = store?.clear()
}

/**
 * Derived file keys, memoised per (password, salt, work factor).
 *
 * PBKDF2 with 100,000 iterations costs ~100 ms on a phone and every container
 * has its own salt, so a cache entry only ever spares a *repeat* derivation
 * (revisiting a folder, seeking back into a file). Keys are 32 bytes and never
 * leave the process; [clear] runs on lock.
 */
object DerivedKeyCache {

    /** 32 bytes per entry, i.e. ~128 KB at the ceiling; memory only. */
    private const val MAX_ENTRIES = 4096

    @Volatile
    var enabled: Boolean = true

    private val keys = ConcurrentHashMap<String, ByteArray>()

    fun getOrDerive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        if (!enabled) return SkeCrypto.deriveKey(password, salt, iterations)

        val cacheKey = buildString {
            append(password)
            append('|')
            for (byte in salt) append("%02x".format(byte))
            append('|')
            append(iterations)
        }

        keys[cacheKey]?.let { return it }
        val derived = SkeCrypto.deriveKey(password, salt, iterations)
        // Bounded so a long browsing session cannot grow this forever.
        if (keys.size >= MAX_ENTRIES) keys.clear()
        keys[cacheKey] = derived
        return derived
    }

    fun size(): Int = keys.size

    fun clear() = keys.clear()
}
