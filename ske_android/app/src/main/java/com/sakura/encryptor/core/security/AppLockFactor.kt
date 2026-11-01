package com.sakura.encryptor.core.security

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

/**
 * One proof the launch gate can demand.
 *
 * The gate is a set of these rather than a pile of booleans: the user ticks the
 * factors they want, and *every* ticked factor must pass. Adding a new method
 * later (pattern, PIN, NFC key…) means one entry here plus one branch in the
 * lock screen, instead of another boolean and another combination to reason
 * about.
 */
enum class AppLockFactor(val id: String) {
    PASSWORD("password"),
    TOTP("totp"),
    BIOMETRIC("biometric");

    /** Shown in the lock screen and the factor picker. */
    val title: String
        get() = when (this) {
            PASSWORD -> t(R.string.lock_launch_password)
            TOTP -> t(R.string.lock_factor_totp)
            BIOMETRIC -> t(R.string.lock_factor_biometric)
        }

    companion object {
        /** What a gate falls back to when nothing usable is configured. */
        val DEFAULT: Set<AppLockFactor> = setOf(PASSWORD)

        fun fromId(id: String): AppLockFactor? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How several ticked factors combine.
 *
 * Both options stay meaningful as factors are added: "all" is strict 2FA,
 * "any" is a convenience gate with alternatives.
 */
enum class AppLockMatch(val id: String) {
    /** Every ticked factor has to pass. */
    ALL("all"),

    /** Any single ticked factor opens the app. */
    ANY("any");

    val title: String
        get() = when (this) {
            ALL -> t(R.string.lock_match_all)
            ANY -> t(R.string.lock_match_any)
        }

    companion object {
        fun fromId(id: String?): AppLockMatch =
            entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * Whether the gate may open.
 *
 * Callers pass only factors that are actually usable on this device, so a
 * sensor the user removed can never be demanded (and never locks them out).
 */
fun appLockSatisfied(
    required: Set<AppLockFactor>,
    verified: Set<AppLockFactor>,
    match: AppLockMatch = AppLockMatch.ALL,
): Boolean = when (match) {
    AppLockMatch.ALL -> required.isNotEmpty() && verified.containsAll(required)
    AppLockMatch.ANY -> verified.any { it in required }
}