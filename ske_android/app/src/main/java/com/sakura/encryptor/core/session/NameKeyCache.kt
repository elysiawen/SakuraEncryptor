package com.sakura.encryptor.core.session

import com.sakura.encryptor.core.crypto.SkeCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of derived file-name keys.
 *
 * Deriving a key costs a full PBKDF2 run (~200 ms), and several screens need
 * the same key for the same password. Sharing one cache here means switching
 * tabs — which recreates the ViewModel — does not repeat that work.
 *
 * Entries are dropped whenever a vault locks, so passwords do not linger after
 * the user locks the app.
 */
object NameKeyCache {

    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val lock = Mutex()

    /** Derive (or reuse) the deterministic name key for [password]. */
    suspend fun get(password: String): ByteArray {
        cache[password]?.let { return it }
        return lock.withLock {
            cache[password] ?: withContext(Dispatchers.Default) {
                SkeCrypto.deriveNameKey(password)
            }.also { cache[password] = it }
        }
    }

    fun clear() = cache.clear()
}
