package com.sakura.encryptor.ui.screens.openlist

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

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
            .onFailure { _error.value = it.message ?: t(R.string.openlist_failed_save_built_server_settings) }
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
        SakuraTopBar(title = t(R.string.settings_server_settings), subtitle = t(R.string.settings_built_server), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(t(R.string.browse_connect))

            SakuraCard {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { input ->
                        portInput = input.filter(Char::isDigit).take(5)
                    },
                    label = { Text(t(R.string.openlist_port)) },
                    supportingText = {
                        Text(
                            if (portPending) {
                                t(R.string.openlist_new_port_takes_effect_after_restarting)
                            } else {
                                t(R.string.openlist_listens127001_only)
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
                    label = { Text(t(R.string.profiles_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text(t(R.string.openlist_password)) },
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
                    text = t(R.string.openlist_account_changes_sync_running_built_server) +
                        t(R.string.openlist_port_only_takes_effect_next_time),
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
                    text = if (busy) t(R.string.openlist_saving) else t(R.string.profiles_save),
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
            title = { Text(t(R.string.openlist_restart_app_apply)) },
            text = {
                Text(
                    t(R.string.openlist_port_saved_running_server_still_serves, port) +
                        t(R.string.openlist_new_port_applies_after_restart_restart),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissRestart()
                        restartApp(context)
                    }
                ) { Text(t(R.string.openlist_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestart) { Text(t(R.string.openlist_later)) }
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
