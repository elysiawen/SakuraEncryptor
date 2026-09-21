package com.sakura.encryptor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Asks for an encryption password.
 *
 * Purely presentational: the caller decides which password scope this unlocks
 * (the local vault or a cloud profile) and where the "remember me" value goes.
 *
 * There is no server that could validate the password, so a wrong one surfaces
 * as a decryption failure when a file is opened.
 */
@Composable
fun UnlockPanel(
    title: String,
    message: String,
    initialRemember: Boolean,
    onSubmit: suspend (password: String, remember: Boolean) -> Unit,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: @Composable (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var rememberPassword by remember(initialRemember) { mutableStateOf(initialRemember) }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(72.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = { Text("加密主密码") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(19.dp))
            },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        modifier = Modifier.size(19.dp),
                    )
                }
            },
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "使用系统密钥库加密保存，下次自动解锁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = rememberPassword,
                onCheckedChange = { rememberPassword = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        PrimaryButton(
            text = "解锁",
            onClick = {
                if (password.isEmpty()) {
                    error = "请输入密码"
                    return@PrimaryButton
                }
                busy = true
                scope.launch {
                    runCatching { onSubmit(password, rememberPassword) }
                        .onSuccess {
                            busy = false
                            password = ""
                            onUnlocked()
                        }
                        .onFailure {
                            busy = false
                            error = it.message ?: "解锁失败"
                        }
                }
            },
            loading = busy,
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (secondaryAction != null) {
            Spacer(Modifier.height(18.dp))
            secondaryAction()
        }
    }
}
