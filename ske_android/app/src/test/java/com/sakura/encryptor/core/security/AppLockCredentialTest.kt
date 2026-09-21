package com.sakura.encryptor.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockCredentialTest {

    @Test
    fun `accepts the password it was created from`() {
        val credential = AppLockCredential.create("111111")
        assertTrue(AppLockCredential.matches("111111", credential))
    }

    @Test
    fun `rejects anything else`() {
        val credential = AppLockCredential.create("111111")
        assertFalse(AppLockCredential.matches("111112", credential))
        assertFalse(AppLockCredential.matches("", credential))
        assertFalse(AppLockCredential.matches("111111 ", credential))
        assertFalse(AppLockCredential.matches("11111", credential))
    }

    @Test
    fun `stores two base64 halves and nothing else`() {
        repeat(50) {
            val credential = AppLockCredential.create("111111")

            assertEquals("a credential is salt:hash", 2, credential.split(':').size)
            assertTrue(AppLockCredential.isWellFormed(credential))
            // `byte[].toString()` looks like `[B@1a2b3c`; it must never appear,
            // because a stored raw array decodes to nothing and locks the user out.
            assertFalse("leaked a raw array: $credential", credential.contains("[B@"))
        }
    }

    @Test
    fun `uses a fresh salt every time`() {
        val first = AppLockCredential.create("same-password")
        val second = AppLockCredential.create("same-password")

        assertNotEquals("salts must not repeat", first, second)
        assertTrue(AppLockCredential.matches("same-password", first))
        assertTrue(AppLockCredential.matches("same-password", second))
    }

    @Test
    fun `handles unicode passwords`() {
        val credential = AppLockCredential.create("樱花密码🔒")
        assertTrue(AppLockCredential.matches("樱花密码🔒", credential))
        assertFalse(AppLockCredential.matches("樱花密码", credential))
    }

    @Test
    fun `flags malformed credentials instead of treating them as a gate`() {
        // Exactly what a buggy writer produced once: a raw array toString.
        assertFalse(AppLockCredential.isWellFormed("[B@de497e3:[B@d2e05e0"))
        assertFalse(AppLockCredential.isWellFormed("nonsense"))
        assertFalse(AppLockCredential.isWellFormed("only-one-part"))
        assertFalse(AppLockCredential.isWellFormed(""))
        assertFalse(AppLockCredential.isWellFormed(null))

        // And matching against one simply fails rather than throwing.
        assertFalse(AppLockCredential.matches("111111", "[B@de497e3:[B@d2e05e0"))
        assertFalse(AppLockCredential.matches("111111", ""))
    }
}
