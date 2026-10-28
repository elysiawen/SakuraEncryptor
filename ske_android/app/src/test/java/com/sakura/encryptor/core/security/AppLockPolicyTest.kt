package com.sakura.encryptor.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {

    @Test
    fun `a single required factor is enough on its own`() {
        assertTrue(appLockSatisfied(setOf(AppLockFactor.PASSWORD), setOf(AppLockFactor.PASSWORD)))
        assertTrue(appLockSatisfied(setOf(AppLockFactor.BIOMETRIC), setOf(AppLockFactor.BIOMETRIC)))
        assertFalse(appLockSatisfied(setOf(AppLockFactor.PASSWORD), setOf(AppLockFactor.BIOMETRIC)))
    }

    @Test
    fun `every ticked factor has to pass`() {
        val required = setOf(AppLockFactor.PASSWORD, AppLockFactor.BIOMETRIC)

        assertFalse(appLockSatisfied(required, setOf(AppLockFactor.PASSWORD)))
        assertFalse(appLockSatisfied(required, setOf(AppLockFactor.BIOMETRIC)))
        assertTrue(appLockSatisfied(required, required))
    }

    @Test
    fun `any single factor opens the gate when the user chose so`() {
        val required = setOf(AppLockFactor.PASSWORD, AppLockFactor.BIOMETRIC)

        assertTrue(appLockSatisfied(required, setOf(AppLockFactor.PASSWORD), AppLockMatch.ANY))
        assertTrue(appLockSatisfied(required, setOf(AppLockFactor.BIOMETRIC), AppLockMatch.ANY))
        assertFalse(appLockSatisfied(required, emptySet(), AppLockMatch.ANY))
        // A proof that was not ticked never counts.
        assertFalse(
            appLockSatisfied(
                setOf(AppLockFactor.PASSWORD),
                setOf(AppLockFactor.BIOMETRIC),
                AppLockMatch.ANY,
            )
        )
    }

    @Test
    fun `an empty requirement never opens the gate`() {
        // The UI keeps at least one factor ticked; if the data is empty anyway,
        // the gate must stay closed rather than unlock everything.
        assertFalse(appLockSatisfied(emptySet(), emptySet()))
    }
}
