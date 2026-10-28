package com.sakura.encryptor.core.security

/**
 * One proof the launch gate can demand.
 *
 * The gate is a set of these rather than a pile of booleans: the user ticks the
 * factors they want, and *every* ticked factor must pass. Adding a new method
 * later (pattern, PIN, NFC key…) means one entry here plus one branch in the
 * lock screen, instead of another boolean and another combination to reason
 * about.
 */
enum class AppLockFactor(val id: String, val title: String) {
    PASSWORD("password", "启动密码"),
    TOTP("totp", "TOTP 令牌"),
    BIOMETRIC("biometric", "指纹或面容");

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
enum class AppLockMatch(val id: String, val title: String) {
    /** Every ticked factor has to pass. */
    ALL("all", "全部通过"),

    /** Any single ticked factor opens the app. */
    ANY("any", "任一通过");

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
