package com.sakura.encryptor.core.security

import com.sakura.encryptor.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/** Where the launch gate currently stands. */
sealed interface AppLockState {
    /** The stored credential has not been read yet, so nothing can be decided. */
    data object Loading : AppLockState

    /** A launch password is configured and has not been entered yet. */
    data object Locked : AppLockState

    /** Nothing to prove: either no password is set, or it was already entered. */
    data object Unlocked : AppLockState
}

/**
 * Optional password asked for when the app is opened.
 *
 * Only a salted PBKDF2-HMAC-SHA256 hash is persisted — never the password
 * itself — so the gate still holds against someone who can read the app's
 * private storage. The encoding lives in [AppLockCredential], which is unit
 * tested on the JVM.
 *
 * This is deliberately separate from the vault passwords: those unlock *data*,
 * this one unlocks *the app*.
 */
class AppLockManager(
    private val settings: SettingsStore,
    private val keyStore: KeyStoreManager,
    scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<AppLockState>(AppLockState.Loading)
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    init {
        scope.launch {
            val stored = runCatching { settings.appLockCredential.first() }.getOrNull()
            // A credential that cannot be decoded can never be satisfied, so
            // treating it as a gate would strand the user outside the app with
            // no way in. Fall back to "no password set" instead.
            _state.value = if (AppLockCredential.isWellFormed(stored)) {
                AppLockState.Locked
            } else {
                AppLockState.Unlocked
            }
        }
    }

    /**
     * @return true when [password] matches the stored credential.
     *
     * Runs off the main thread: PBKDF2 with 100,000 iterations is far too
     * expensive to do while the user watches the button they just pressed.
     */
    suspend fun verify(password: String): Boolean = withContext(Dispatchers.Default) {
        val stored = settings.appLockCredential.first() ?: return@withContext false
        AppLockCredential.matches(password, stored)
    }

    /**
     * Enable the gate, or replace its password.
     *
     * The session is left unlocked on purpose: setting a password should not
     * immediately throw the user out of the screen they are standing on.
     */
    suspend fun setPassword(password: String) = withContext(Dispatchers.Default) {
        settings.setAppLockCredential(AppLockCredential.create(password))
        _state.value = AppLockState.Unlocked
    }

    /** Turn the gate off. */
    suspend fun disable() {
        settings.setAppLockCredential(null)
        _state.value = AppLockState.Unlocked
    }

    // ---- TOTP ----------------------------------------------------------------

    /** True once a token has been bound; the factor is unusable without one. */
    val hasTotp: Flow<Boolean> =
        settings.appLockTotpSecret.map { !it.isNullOrBlank() }

    /**
     * Bind [secret] (already decoded from Base32).
     *
     * Only the Keystore-sealed form is persisted, so reading the settings file
     * does not hand out the shared secret.
     *
     * @return false when the Keystore refused to seal it, i.e. nothing was saved.
     */
    suspend fun bindTotp(secret: ByteArray): Boolean {
        val encoded = Base64.getEncoder().encodeToString(secret)
        val sealed = keyStore.seal(encoded) ?: return false
        settings.setAppLockTotpSecret(sealed)
        return true
    }

    suspend fun unbindTotp() = settings.setAppLockTotpSecret(null)

    /** @return true when [code] is valid for the current 30-second step. */
    suspend fun verifyTotp(code: String): Boolean = withContext(Dispatchers.Default) {
        val secret = totpSecret() ?: return@withContext false
        Totp.verify(secret, code, System.currentTimeMillis())
    }

    private suspend fun totpSecret(): ByteArray? {
        val sealed = settings.appLockTotpSecret.first() ?: return null
        val encoded = keyStore.open(sealed) ?: return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    /** Called once the password has been entered correctly. */
    fun unlock() {
        _state.value = AppLockState.Unlocked
    }

    companion object {
        /** Short enough to stay convenient, long enough not to be a formality. */
        const val MIN_LENGTH = 4
    }
}
