package com.sakura.encryptor.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned to the SHA-1 vectors in RFC 6238 appendix B. Getting these wrong means
 * a user's authenticator app and this gate disagree, i.e. a lockout.
 */
class TotpTest {

    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `matches the RFC 6238 vectors`() {
        assertEquals("287082", Totp.codeAt(rfcSecret, 59_000L))
        assertEquals("081804", Totp.codeAt(rfcSecret, 1_111_111_109_000L))
        assertEquals("050471", Totp.codeAt(rfcSecret, 1_111_111_111_000L))
        assertEquals("005924", Totp.codeAt(rfcSecret, 1_234_567_890_000L))
        assertEquals("279037", Totp.codeAt(rfcSecret, 2_000_000_000_000L))
        assertEquals("353130", Totp.codeAt(rfcSecret, 20_000_000_000_000L))
    }

    @Test
    fun `a code dies the moment the authenticator rolls over`() {
        // Aligned to a 30-second boundary so the arithmetic below is exact.
        val rollover = 1_700_000_000_000L - 20_000L
        val code = Totp.codeAt(rfcSecret, rollover)

        assertTrue(Totp.verify(rfcSecret, code, rollover))
        assertTrue(Totp.verify(rfcSecret, code, rollover + 29_000L))

        // The next window already shows a different number: the old one is dead,
        // and so is the one before the current window.
        assertFalse(Totp.verify(rfcSecret, code, rollover + 30_000L))
        assertFalse(Totp.verify(rfcSecret, code, rollover - 30_000L))
    }

    @Test
    fun `rejects malformed input`() {
        val now = 1_700_000_000_000L
        val code = Totp.codeAt(rfcSecret, now)

        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertFalse(Totp.verify(rfcSecret, "", now))
        assertFalse(Totp.verify(rfcSecret, "12345", now))
        assertFalse(Totp.verify(rfcSecret, "abcdef", now))
        assertFalse(Totp.verify(rfcSecret, wrong, now))
    }

    @Test
    fun `decodes a base32 secret`() {
        // Base32 of the RFC secret above.
        val decoded = Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertArrayEquals(rfcSecret, decoded)

        // Padding, spaces and lowercase are what people paste from a QR export.
        assertArrayEquals(rfcSecret, Totp.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq===="))
    }

    @Test
    fun `reads the secret out of an otpauth uri`() {
        val uri = "otpauth://totp/Sakura:me?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Sakura"
        assertArrayEquals(rfcSecret, Totp.decodeSecret(uri))
        assertArrayEquals(rfcSecret, Totp.decodeSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
    }

    @Test
    fun `rejects input that is not a secret`() {
        assertNull(Totp.decodeSecret(""))
        assertNull(Totp.decodeSecret("not base32!"))
        assertNull(Totp.decodeSecret("otpauth://totp/x?issuer=Sakura"))
    }

    @Test
    fun `encodes secrets the way authenticator apps expect`() {
        // Base32 of the RFC secret, unpadded.
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.encodeBase32(rfcSecret))
        assertEquals("", Totp.encodeBase32(ByteArray(0)))
        // Whatever was shown to the user has to decode back to what is stored.
        assertArrayEquals(rfcSecret, Totp.decodeBase32(Totp.encodeBase32(rfcSecret)))
    }

    @Test
    fun `a generated secret round trips and is long enough`() {
        val secret = Totp.generateSecret()

        assertEquals(20, secret.size)
        assertArrayEquals(secret, Totp.decodeBase32(Totp.encodeBase32(secret)))
        // The code for a secret the UI just showed must verify at that moment.
        val now = 1_700_000_000_000L
        assertTrue(Totp.verify(secret, Totp.codeAt(secret, now), now))
    }

    @Test
    fun `the otpauth uri carries the secret back`() {
        val secret = Totp.encodeBase32(rfcSecret)
        val uri = Totp.otpauthUri(secret)

        assertTrue(uri.startsWith("otpauth://totp/"))
        assertArrayEquals(rfcSecret, Totp.decodeSecret(uri))
        assertTrue(uri.contains("digits=6"))
        assertTrue(uri.contains("period=30"))
    }
}
