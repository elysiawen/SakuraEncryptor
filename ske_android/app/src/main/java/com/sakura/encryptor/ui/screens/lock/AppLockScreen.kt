package com.sakura.encryptor.ui.screens.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.sakura.encryptor.core.security.BiometricUnlock
import kotlinx.coroutines.launch

/**
 * The gate shown before the app itself when a launch password is configured.
 *
 * Nothing else is composed behind it, so there is no way around it — and the
 * password is never kept around after the check. When fingerprint / face unlock
 * is enabled the system prompt is offered first, with the password field left
 * on screen as the way out.
 */
@Composable
fun AppLockScreen(
    verify: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
    biometricEnabled: Boolean = false,
    biometricAvailable: Boolean = false,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val activity = LocalContext.current.findFragmentActivity()

    val biometricUsable = biometricEnabled && biometricAvailable && activity != null

    fun requestBiometric() {
        val host = activity ?: return
        if (!biometricEnabled || !biometricAvailable) return

        BiometricUnlock.prompt(
            activity = host,
            onSucceeded = onUnlocked,
            // Empty means the user backed out on purpose; say nothing.
            onFailed = { message -> if (message.isNotEmpty()) error = message },
        )
    }

    fun submit() {
        if (checking || password.isEmpty()) return
        checking = true
        error = null
        scope.launch {
            val ok = runCatching { verify(password) }.getOrDefault(false)
            checking = false
            if (ok) {
                onUnlocked()
            } else {
                error = "密码不正确"
                password = ""
            }
        }
    }

    // Offer the sensor as soon as the gate appears.
    LaunchedEffect(biometricEnabled, biometricAvailable) {
        if (biometricEnabled && biometricAvailable) requestBiometric()
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        imageVector = if (biometricUsable) {
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
                text = if (biometricUsable) "使用指纹解锁，或输入启动密码" else "请输入启动密码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(30.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = null
                },
                label = { Text("启动密码") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { submit() },
                enabled = password.isNotEmpty() && !checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("解锁")
                }
            }

            if (biometricUsable) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { requestBiometric() }) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("使用指纹解锁")
                }
            }
        }
    }
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
