package com.sakura.encryptor.core.security

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP with the defaults every authenticator app uses: HMAC-SHA1,
 * 6 digits, 30-second steps.
 *
 * Pure Kotlin (only `javax.crypto`), so the algorithm is pinned down by tests
 * against the RFC vectors rather than discovered on a device. A wrong secret or
 * a drifting clock here means being locked out of the app.
 */
object Totp {

    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L

    /** 160 bits, the size every authenticator app offers for HMAC-SHA1. */
    private const val SECRET_BYTES = 20

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val POWERS = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000)

    /** The code to show the user right now, from [secret]. */
    fun codeAt(secret: ByteArray, timeMillis: Long): String =
        codeForCounter(secret, timeMillis / 1000 / PERIOD_SECONDS)

    /**
     * @return true when [input] is the code of the current step, and only that
     * one.
     *
     * No neighbouring step is accepted on purpose: the instant the authenticator
     * rolls over, the number the user just watched expire must stop working.
     * Accepting ±1 step — the usual choice — would leave the previous code alive
     * for another 30 seconds, which reads as a bug to anyone who saw the
     * countdown hit zero. The authenticator lives on this same device, so there
     * is no second clock to absorb drift from.
     */
    fun verify(secret: ByteArray, input: String, timeMillis: Long): Boolean {
        val digits = input.filter { it.isDigit() }
        if (digits.length != DIGITS) return false

        return MessageDigest.isEqual(
            digits.toByteArray(Charsets.US_ASCII),
            codeAt(secret, timeMillis).toByteArray(Charsets.US_ASCII),
        )
    }

    internal fun codeForCounter(secret: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())

        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % POWERS[DIGITS]).toString().padStart(DIGITS, '0')
    }

    /**
     * Accepts either a Base32 secret (`JBSWY3DPEHPK3PXP`) or a full
     * `otpauth://totp/...?secret=...` uri, i.e. whatever an authenticator app
     * lets the user copy.
     *
     * @return the shared secret, or null when the input is not usable.
     */
    fun decodeSecret(input: String): ByteArray? {
        val text = input.trim()
        if (text.isEmpty()) return null

        val secret = if (text.startsWith("otpauth://", ignoreCase = true)) {
            uriSecret(text)
        } else {
            text
        } ?: return null

        return decodeBase32(secret)
    }

    /** A fresh secret to hand to an authenticator app when binding one. */
    fun generateSecret(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SECRET_BYTES).also(random::nextBytes)

    /**
     * RFC 4648 Base32 without padding — the form authenticator apps accept when
     * the user types the secret in by hand.
     */
    fun encodeBase32(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return out.toString()
    }

    /** The `otpauth://` uri an authenticator app scans or imports. */
    fun otpauthUri(
        secret: String,
        issuer: String = "Sakura Encryptor",
        account: String = "app",
    ): String {
        val encodedIssuer = URLEncoder.encode(issuer, "UTF-8")
        val label = URLEncoder.encode("$issuer:$account", "UTF-8")
        return "otpauth://totp/$label" +
            "?secret=$secret&issuer=$encodedIssuer" +
            "&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    private fun uriSecret(uri: String): String? {
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        return query.split('&')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "")
                if (!name.equals("secret", ignoreCase = true)) return@mapNotNull null
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            .firstOrNull()
    }

    /** RFC 4648 Base32, tolerant of lowercase, spaces and `=` padding. */
    fun decodeBase32(input: String): ByteArray? {
        val cleaned = buildString(input.length) {
            for (char in input.uppercase()) {
                when {
                    char in ALPHABET -> append(char)
                    // Separators people paste along with the secret.
                    char == '=' || char == '-' || char.isWhitespace() -> Unit
                    // Anything else is not Base32: refuse instead of guessing.
                    else -> return null
                }
            }
        }
        if (cleaned.isEmpty()) return null

        var buffer = 0
        var bits = 0
        val out = ByteArrayOutputStream()

        for (char in cleaned) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
