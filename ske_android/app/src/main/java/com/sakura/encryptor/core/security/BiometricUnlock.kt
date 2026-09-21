package com.sakura.encryptor.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint / face unlock for the launch gate.
 *
 * Uses `BIOMETRIC_WEAK` so both strong sensors and the weaker ones (many
 * under-display readers, 2D face unlock) qualify. This guards a convenience
 * gate — the data itself stays protected by the PBKDF2-derived key either way,
 * and the password remains available as a fallback.
 */
object BiometricUnlock {

    /** Authenticator class the prompt is allowed to accept. */
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** True when this device has an enrolled fingerprint or face. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Show the system prompt.
     *
     * @param onSucceeded the user proved who they are.
     * @param onFailed    dismissed, or the sensor could not identify them. The
     *                    password field stays on screen, so a failure never
     *                    leaves the user stuck.
     */
    fun prompt(
        activity: FragmentActivity,
        onSucceeded: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    onSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Backing out on purpose is not worth an error message.
                    val silent = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onFailed(if (silent) "" else errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // One unrecognised touch: the sensor keeps listening.
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁 Sakura Encryptor")
                .setSubtitle("使用指纹或面容验证身份")
                // Falling back to the password is always allowed.
                .setNegativeButtonText("使用启动密码")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
        )
    }
}
