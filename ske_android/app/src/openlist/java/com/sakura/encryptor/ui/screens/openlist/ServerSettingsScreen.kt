package com.sakura.encryptor.ui.screens.openlist

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.MainActivity
import com.sakura.encryptor.core.embedded.EmbeddedServer
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.ui.components.PrimaryButton
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.SectionHeader
import com.sakura.encryptor.ui.rememberAppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port and admin account of the server that runs in this package.
 *
 * OpenList reads its port while initialising, so a new one is written to disk
 * and binds after the app restarts; the account is pushed to the live server
 * instead, because it lives in the server's own database.
 */
class ServerSettingsViewModel(
    private val settings: SettingsStore,
    private val embeddedServer: EmbeddedServer,
) : ViewModel() {

    val port: StateFlow<Int> = settings.localOpenListPort
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_LOCAL_PORT,
        )

    val username: StateFlow<String> = settings.localOpenListUsername
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_ADMIN_USER,
        )

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Set when the saved port is not the one the process is already serving. */
    private val _restartRequired = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    /** True while the stored port is not the one the server is actually bound to. */
    val portPending: StateFlow<Boolean> = settings.localOpenListPort
        .map { it != embeddedServer.port }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            _password.value = withContext(Dispatchers.IO) { embeddedServer.readPassword() }.orEmpty()
        }
    }

    fun save(newPort: Int, newUsername: String, newPassword: String) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        _error.value = null
        embeddedServer.updateSettings(newPort, newUsername, newPassword)
            .onSuccess {
                _password.value = newPassword
                // The running process keeps serving the old port; only a fresh
                // process re-reads config.json and binds the new one.
                _restartRequired.value = newPort != embeddedServer.port
            }
            .onFailure { _error.value = it.message ?: "内置服务器设置保存失败" }
        _busy.value = false
    }

    fun dismissRestart() {
        _restartRequired.value = false
    }
}

@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val viewModel = rememberAppViewModel { container ->
        ServerSettingsViewModel(container.settings, container.embeddedServer)
    }
    val port by viewModel.port.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val portPending by viewModel.portPending.collectAsStateWithLifecycle()
    val restartRequired by viewModel.restartRequired.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Edited copies; the stored values only change when the user presses save.
    var portInput by remember(port) { mutableStateOf(port.toString()) }
    var usernameInput by remember(username) { mutableStateOf(username) }
    var passwordInput by remember(password) { mutableStateOf(password) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(title = "服务器设置", subtitle = "内置服务器", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader("连接")

            SakuraCard {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { input ->
                        portInput = input.filter(Char::isDigit).take(5)
                    },
                    label = { Text("端口") },
                    supportingText = {
                        Text(
                            if (portPending) {
                                "新端口将在重启应用后生效"
                            } else {
                                "仅在本机 127.0.0.1 上监听"
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )

                Text(
                    text = "修改账号会同步到正在运行的内置服务器，应用内保存的登录密码也会一并更新；" +
                        "端口只在下次启动应用时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                PrimaryButton(
                    text = if (busy) "保存中…" else "保存",
                    onClick = {
                        viewModel.save(
                            newPort = portInput.toIntOrNull() ?: port,
                            newUsername = usernameInput,
                            newPassword = passwordInput,
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        }
    }

    if (restartRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestart,
            title = { Text("重启应用以生效") },
            text = {
                Text(
                    "端口已保存为 $port，但运行中的服务器仍在旧端口上提供服务。" +
                        "重启应用后才会切到新端口，现在重启吗？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissRestart()
                        restartApp(context)
                    }
                ) { Text("立即重启") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestart) { Text("稍后") }
            },
        )
    }
}

/** Relaunches the launcher activity in a fresh process. */
private fun restartApp(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        },
    )
    android.os.Process.killProcess(android.os.Process.myPid())
}
