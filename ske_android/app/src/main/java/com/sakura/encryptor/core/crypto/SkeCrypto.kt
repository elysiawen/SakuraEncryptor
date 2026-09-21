package com.sakura.encryptor.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitives shared by the whole app.
 *
 * Every routine here is a 1:1 port of `ske_cli/crypto.py` so that files are
 * fully interchangeable between the CLI, the Web player and this client.
 *
 * Only JVM-stable APIs are used (no `android.util.Base64`) so this object is
 * unit-testable on a plain JVM.
 */
object SkeCrypto {

    private const val AES = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128 // 16-byte GCM tag, matches Python's default
    private const val SHA_256 = "SHA-256"

    private val secureRandom = SecureRandom()

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    // -----------------------------------------------------------------------
    // Key derivation
    // -----------------------------------------------------------------------

    /**
     * Derive a 256-bit key from [password] and [salt] via PBKDF2-HMAC-SHA256
     * with 100,000 iterations.
     *
     * The password is UTF-8 encoded by the JCE provider, matching Python's
     * `password.encode("utf-8")`.
     */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, SkeFormat.KDF_ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
    }

    /** Key used for the deterministic file-name encryption. */
    fun deriveNameKey(password: String): ByteArray = deriveKey(password, SkeFormat.NAME_SALT)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    // -----------------------------------------------------------------------
    // Deterministic name encryption
    // -----------------------------------------------------------------------

    /** Fixed 12-byte IV derived from [key]: HMAC-SHA256(key, "ske-name-iv")[:12]. */
    private fun nameIv(key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(SkeFormat.NAME_IV_SEED).copyOf(SkeFormat.IV_SIZE)
    }

    /** Encrypt a single path component into a URL-safe Base64 token (no padding). */
    fun encryptName(name: String, key: ByteArray): String {
        val ct = gcmEncrypt(key, nameIv(key), name.toByteArray(Charsets.UTF_8), null)
        return urlEncoder.encodeToString(ct)
    }

    /** Reverse of [encryptName]. Throws [SkeDecryptionException] on a bad token/key. */
    fun decryptName(token: String, key: ByteArray): String {
        val ct = try {
            urlDecoder.decode(token)
        } catch (e: IllegalArgumentException) {
            throw SkeDecryptionException("Invalid encrypted name token", e)
        }
        return try {
            String(gcmDecrypt(key, nameIv(key), ct, null), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SkeDecryptionException("Failed to decrypt name token", e)
        }
    }

    // -----------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------

    fun encryptPath(path: String, key: ByteArray): String =
        normalizeParts(path).joinToString("/") { encryptName(it, key) }

    fun decryptPath(path: String, key: ByteArray): String =
        normalizeParts(path).joinToString("/") { decryptName(it, key) }

    private fun normalizeParts(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() }

    // -----------------------------------------------------------------------
    // Block-level nonce
    // -----------------------------------------------------------------------

    /**
     * Derive the unique 12-byte nonce for [blockIndex] by XOR-ing [masterIv]
     * with the big-endian, zero-padded block index.
     */
    fun blockNonce(masterIv: ByteArray, blockIndex: Long): ByteArray {
        val indexBytes = ByteArray(SkeFormat.IV_SIZE)
        var value = blockIndex
        for (i in SkeFormat.IV_SIZE - 1 downTo 0) {
            indexBytes[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val nonce = ByteArray(SkeFormat.IV_SIZE)
        for (i in 0 until SkeFormat.IV_SIZE) {
            nonce[i] = (indexBytes[i].toInt() xor masterIv[i].toInt()).toByte()
        }
        return nonce
    }

    // -----------------------------------------------------------------------
    // Raw AES-256-GCM
    // -----------------------------------------------------------------------

    /** Returns `ciphertext || 16-byte tag`, exactly like Python's `AESGCM.encrypt`. */
    fun gcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Reverse of [gcmEncrypt]. Throws `AEADBadTagException` on failure. */
    fun gcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // -----------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------

    /** First 12 bytes of the SHA-256 of [data] — used for the header integrity tag. */
    fun headerTag(data: ByteArray): ByteArray = sha256(data).copyOf(SkeFormat.HEADER_TAG_SIZE)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance(SHA_256).digest(data)

    fun newSha256(): MessageDigest = MessageDigest.getInstance(SHA_256)

    /** Constant-time comparison, mirrors Python's `hmac.compare_digest`. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
