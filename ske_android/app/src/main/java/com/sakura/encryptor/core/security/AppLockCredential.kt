package com.sakura.encryptor.core.security

import com.sakura.encryptor.core.crypto.SkeCrypto
import java.security.MessageDigest
import java.util.Base64

/**
 * The stored form of the launch password: `salt:hash`, both URL-safe Base64.
 *
 * Deliberately separate from [AppLockManager] so the cryptography can be tested
 * on the JVM without an Android context. An encode/decode pair drifting apart
 * is the classic way a gate like this fails silently — it keeps asking for a
 * password and rejects every answer — so it gets pinned down by tests.
 */
object AppLockCredential {

    private const val SALT_SIZE = 16
    private const val SEPARATOR = ':'

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Hash [password] under a fresh random salt. */
    fun create(password: String): String {
        val salt = SkeCrypto.randomBytes(SALT_SIZE)
        return encodeToString(salt) + SEPARATOR + encodeToString(SkeCrypto.deriveKey(password, salt))
    }

    /** @return true when [password] reproduces the hash stored in [credential]. */
    fun matches(password: String, credential: String): Boolean {
        val parts = credential.split(SEPARATOR)
        if (parts.size != 2) return false

        return runCatching {
            val salt = decoder.decode(parts[0])
            val expected = decoder.decode(parts[1])
            MessageDigest.isEqual(expected, SkeCrypto.deriveKey(password, salt))
        }.getOrDefault(false)
    }

    /**
     * True when [credential] decodes cleanly, regardless of any password.
     *
     * Used to tell "no password set" apart from "the stored password is
     * corrupt". Treating the latter as a gate would strand the user outside the
     * app with no way back in.
     */
    fun isWellFormed(credential: String?): Boolean {
        val parts = credential?.split(SEPARATOR) ?: return false
        if (parts.size != 2) return false

        return runCatching {
            decoder.decode(parts[0])
            decoder.decode(parts[1])
        }.isSuccess
    }

    private fun encodeToString(bytes: ByteArray): String = encoder.encodeToString(bytes)
}
