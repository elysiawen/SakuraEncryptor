package com.sakura.encryptor.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Compact prompt for the standalone local password, with a "remember" opt-in.
 *
 * Shown on demand — from the local browser or the encrypt screen — rather than
 * blocking either screen up front.
 */
@Composable
fun LocalUnlockDialog(
    initialRemember: Boolean,
    onDismiss: () -> Unit,
    onSubmit: suspend (password: String, remember: Boolean) -> Unit,
    onUnlocked: () -> Unit,
    title: String = "输入本地密码",
    message: String = "本地 .ske 使用独立的本地密码，与云端配置互不影响。",
    /** Label of the confirm action, e.g. "解锁" when browsing or "加密" when encrypting. */
    confirmLabel: String = "解锁",
) {
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var remember by remember(initialRemember) { mutableStateOf(initialRemember) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("本地密码") },
                    singleLine = true,
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
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "使用系统密钥库加密保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = remember, onCheckedChange = { remember = it })
                }
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.isEmpty()) {
                        error = "请输入密码"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        runCatching { onSubmit(password, remember) }
                            .onSuccess {
                                busy = false
                                onUnlocked()
                            }
                            .onFailure {
                                busy = false
                                error = it.message ?: "解锁失败"
                            }
                    }
                }
            ) {
                Text(if (busy) "${confirmLabel}中…" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
