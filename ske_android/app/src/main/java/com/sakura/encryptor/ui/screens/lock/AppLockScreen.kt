package com.sakura.encryptor.ui.screens.lock

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sakura.encryptor.core.security.AppLockFactor
import com.sakura.encryptor.core.security.AppLockMatch
import com.sakura.encryptor.core.security.BiometricUnlock
import com.sakura.encryptor.core.security.Totp
import com.sakura.encryptor.core.security.appLockSatisfied
import kotlinx.coroutines.launch

/**
 * The gate shown before the app itself when a launch password is configured.
 *
 * The required proofs are exactly the ticked factors ([AppLockFactor]) — no
 * extras, no substitutes. One tick means one proof; several ticks mean all of
 * them (or any of them, when the user picked [AppLockMatch.ANY]). Each proof is
 * remembered as it succeeds, so the order is up to the user.
 *
 * A factor that cannot be used right now (sensor removed, token not bound) is
 * reported instead of being quietly swapped for another one.
 */
@Composable
fun AppLockScreen(
    verify: suspend (String) -> Boolean,
    verifyTotp: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
    requiredFactors: Set<AppLockFactor> = AppLockFactor.DEFAULT,
    biometricAvailable: Boolean = false,
    totpBound: Boolean = false,
    match: AppLockMatch = AppLockMatch.ALL,
) {
    val configured = remember(requiredFactors) {
        requiredFactors.ifEmpty { AppLockFactor.DEFAULT }
    }
    // A ticked TOTP with no token bound can never be proven. Demanding it would
    // shut the user out with no way to bind one — the gate is behind this screen
    // — so it is dropped, and said out loud instead of swapped for something else.
    val unboundTotp = !totpBound && AppLockFactor.TOTP in configured
    val required = remember(configured, unboundTotp) {
        if (unboundTotp) configured - AppLockFactor.TOTP else configured
    }
    // Every ticked factor turned out unusable: an empty gate admits no one, so
    // the app opens and the notice above explains what to fix.
    val nothingToProve = required.isEmpty()

    val needsPassword = AppLockFactor.PASSWORD in required
    val needsTotp = AppLockFactor.TOTP in required
    val needsBiometric = AppLockFactor.BIOMETRIC in required

    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var checkingPassword by remember { mutableStateOf(false) }
    var totpCode by remember { mutableStateOf("") }
    var totpError by remember { mutableStateOf<String?>(null) }
    var checkingTotp by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var verified by remember(required) { mutableStateOf<Set<AppLockFactor>>(emptySet()) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val passwordFocus = remember { FocusRequester() }
    val totpFocus = remember { FocusRequester() }
    val activity = context.findFragmentActivity()

    val biometricUsable = needsBiometric && canUseBiometric(activity, biometricAvailable)

    fun maybeUnlock() {
        if (nothingToProve || appLockSatisfied(required, verified, match)) onUnlocked()
    }

    fun requestBiometric() {
        val host = activity ?: return
        if (!biometricUsable || AppLockFactor.BIOMETRIC in verified) return

        BiometricUnlock.prompt(
            activity = host,
            onSucceeded = {
                biometricError = null
                verified = verified + AppLockFactor.BIOMETRIC
                maybeUnlock()
            },
            // Empty means the user backed out on purpose; say nothing.
            onFailed = { message -> if (message.isNotEmpty()) biometricError = message },
        )
    }

    fun submitPassword() {
        if (checkingPassword || password.isEmpty()) return
        checkingPassword = true
        passwordError = null
        scope.launch {
            val ok = runCatching { verify(password) }.getOrDefault(false)
            checkingPassword = false
            if (!ok) {
                passwordError = "密码不正确"
                password = ""
                return@launch
            }

            verified = verified + AppLockFactor.PASSWORD
            password = ""
            if (appLockSatisfied(required, verified, match)) {
                onUnlocked()
            } else if (match == AppLockMatch.ALL && biometricUsable) {
                // Still short of the sensor: ask for it straight away.
                requestBiometric()
            }
        }
    }

    fun submitTotp() {
        if (checkingTotp || totpCode.length != Totp.DIGITS) return
        checkingTotp = true
        totpError = null
        scope.launch {
            val ok = runCatching { verifyTotp(totpCode) }.getOrDefault(false)
            checkingTotp = false
            if (!ok) {
                totpError = "验证码不正确或已过期"
                totpCode = ""
                return@launch
            }

            verified = verified + AppLockFactor.TOTP
            totpCode = ""
            if (appLockSatisfied(required, verified, match)) {
                onUnlocked()
            } else if (match == AppLockMatch.ALL && biometricUsable) {
                requestBiometric()
            }
        }
    }

    // Nothing usable is left to demand: open, and let the notice explain why.
    LaunchedEffect(nothingToProve) {
        if (nothingToProve) onUnlocked()
    }

    // Offer the sensor as soon as the gate appears — and again if it becomes
    // available while the gate is open (enrolled from system settings).
    LaunchedEffect(required, biometricUsable) {
        if (biometricUsable) requestBiometric()
    }

    // Put the caret where the user is expected to type first.
    LaunchedEffect(required, verified) {
        when {
            needsPassword && AppLockFactor.PASSWORD !in verified ->
                runCatching { passwordFocus.requestFocus() }
            needsTotp && AppLockFactor.TOTP !in verified ->
                runCatching { totpFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            // Edge-to-edge means the window no longer shrinks for the keyboard,
            // so the gate has to make room for it itself.
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Also scrollable: on a short screen the keyboard can eat most
                // of the height, and the fields still have to be reachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (needsBiometric && !needsPassword && !needsTotp) {
                            Icons.Rounded.Fingerprint
                        } else {
                            Icons.Rounded.Lock
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(text = "Sakura Encryptor", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    nothingToProve -> "正在进入应用"
                    required.size > 1 && match == AppLockMatch.ANY ->
                        "以下任一方式通过即可：${required.joinToString("、") { it.title }}"
                    required.size > 1 -> "需要${required.joinToString("和") { it.title }}都通过"
                    needsPassword -> "请输入启动密码"
                    needsTotp -> "请输入令牌应用中的 6 位验证码"
                    else -> "请验证指纹或面容"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (unboundTotp) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "已勾选的 TOTP 尚未绑定令牌，本次已跳过。解锁后请到「设置 → 安全」" +
                        "中绑定，或取消勾选该方式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (needsBiometric && !biometricUsable) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "此设备的指纹或面容不可用，请先在系统设置中重新录入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        runCatching { context.startActivity(context.biometricSetupIntent()) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text("打开系统设置")
                }
            } else {
                if (needsPassword) {
                    Spacer(Modifier.height(26.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = null
                        },
                        label = { Text("启动密码") },
                        singleLine = true,
                        isError = passwordError != null,
                        supportingText = passwordError?.let { message -> { Text(message) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { submitPassword() },
                        enabled = password.isNotEmpty() && !checkingPassword,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        if (checkingPassword) {
                            ProgressDot()
                        } else {
                            Text(if (required.size == 1) "解锁" else "验证密码")
                        }
                    }
                }

                if (needsTotp) {
                    Spacer(Modifier.height(if (needsPassword) 22.dp else 26.dp))
                    OutlinedTextField(
                        value = totpCode,
                        onValueChange = { input ->
                            totpCode = input.filter { it.isDigit() }.take(Totp.DIGITS)
                            totpError = null
                        },
                        label = { Text("TOTP 验证码") },
                        placeholder = { Text("000000") },
                        singleLine = true,
                        isError = totpError != null,
                        supportingText = totpError?.let { message -> { Text(message) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitTotp() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(totpFocus),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { submitTotp() },
                        enabled = totpCode.length == Totp.DIGITS && !checkingTotp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        if (checkingTotp) {
                            ProgressDot()
                        } else {
                            Text(if (required.size == 1) "解锁" else "验证令牌")
                        }
                    }
                }

                if (needsBiometric) {
                    Spacer(Modifier.height(if (needsPassword || needsTotp) 14.dp else 30.dp))
                    Button(
                        onClick = { requestBiometric() },
                        enabled = AppLockFactor.BIOMETRIC !in verified,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (AppLockFactor.BIOMETRIC in verified) {
                                "指纹已验证"
                            } else {
                                "验证指纹"
                            }
                        )
                    }
                }

                // Only "all" leaves something pending; with "any" the first pass
                // has already opened the gate.
                val pending = required - verified
                if (match == AppLockMatch.ALL &&
                    required.size > 1 &&
                    pending.isNotEmpty() &&
                    verified.isNotEmpty()
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "已通过${verified.joinToString("、") { it.title }}，" +
                            "还需要${pending.joinToString("和") { it.title }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (biometricError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = biometricError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressDot() {
    CircularProgressIndicator(
        color = MaterialTheme.colorScheme.onPrimary,
        strokeWidth = 2.dp,
        modifier = Modifier.size(18.dp),
    )
}

/** The sensor must exist, be enrolled, and be hosted by a FragmentActivity. */
private fun canUseBiometric(activity: FragmentActivity?, available: Boolean): Boolean =
    activity != null && available

/** Opens the screen where the user can enrol a fingerprint or face. */
private fun Context.biometricSetupIntent(): Intent {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** `LocalContext` is often a wrapper; walk out to the hosting Activity. */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
