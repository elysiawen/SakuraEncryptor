package com.sakura.encryptor.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory holder for the encryption password.
 *
 * This is intentionally independent from the AList session: a user who only
 * wants to play local `.ske` files never has to sign in to any server, but
 * still needs to unlock here before anything can be decrypted.
 *
 * The password lives only in RAM for the lifetime of the process — it is never
 * written to disk, logged, or sent anywhere. [lock] clears it and notifies
 * listeners so memoised keys can be dropped.
 */
class VaultSession {

    private val passwordRef = AtomicReference<String?>(null)
    private val lockListeners = CopyOnWriteArrayList<() -> Unit>()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlockedFlow: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /**
     * Becomes true once the "remembered password" restore attempt has finished,
     * so the UI can tell "still restoring" apart from "locked".
     */
    private val _restoreFinished = MutableStateFlow(false)
    val restoreFinished: StateFlow<Boolean> = _restoreFinished.asStateFlow()

    val password: String? get() = passwordRef.get()

    val isUnlocked: Boolean get() = !passwordRef.get().isNullOrEmpty()

    /** Accessor for the media pipeline; throws when the vault is locked. */
    fun requirePassword(): String =
        passwordRef.get()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Vault is locked")

    fun unlock(password: String) {
        passwordRef.set(password)
        _isUnlocked.value = true
    }

    fun lock() {
        passwordRef.set(null)
        _isUnlocked.value = false
        lockListeners.forEach { runCatching { it() } }
    }

    /** Signals that the startup auto-unlock attempt is over. */
    fun markRestoreFinished() {
        _restoreFinished.value = true
    }

    fun addOnLockListener(listener: () -> Unit) {
        lockListeners.add(listener)
    }
}
