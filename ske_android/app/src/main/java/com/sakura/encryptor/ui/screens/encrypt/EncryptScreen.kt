package com.sakura.encryptor.ui.screens.encrypt

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.local.CryptoNotifications
import com.sakura.encryptor.core.local.CryptoTaskRunner
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.LocalUnlockDialog
import com.sakura.encryptor.ui.components.PrimaryButton
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.SectionHeader
import com.sakura.encryptor.ui.components.SettingRow
import com.sakura.encryptor.ui.components.TaskProgressDialog
import com.sakura.encryptor.ui.rememberAppViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Standalone "encrypt files" workspace, separate from the local file browser.
 *
 * Encryption always targets a dedicated output folder so the result does not
 * get mixed into whatever directory happens to be open elsewhere.
 */
class EncryptViewModel(
    private val settings: SettingsStore,
    private val runner: CryptoTaskRunner,
    private val localVault: VaultSession,
    private val keyStore: KeyStoreManager,
    private val notifications: CryptoNotifications,
) : ViewModel() {

    data class UiState(
        val outputUri: String? = null,
        val busy: Boolean = false,
        val progress: Progress? = null,
    )

    data class Progress(
        val fileName: String,
        val index: Int,
        val count: Int,
        val fraction: Float,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _rememberDefault = MutableStateFlow(false)
    val rememberDefault: StateFlow<Boolean> = _rememberDefault.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            _rememberDefault.value = settings.localRememberPassword.first()
            _state.update { it.copy(outputUri = settings.localOutputUri.first()) }
        }
    }

    fun onOutputDirSelected(uri: Uri) {
        viewModelScope.launch {
            settings.setLocalOutputUri(uri.toString())
            _state.update { it.copy(outputUri = uri.toString()) }
        }
    }

    /** Unlock the local password, optionally remembering it. */
    suspend fun unlock(password: String, remember: Boolean) {
        localVault.unlock(password)
        settings.setLocalRememberPassword(remember)
        settings.setLocalSealedPassword(if (remember) keyStore.seal(password) else null)
    }

    fun encrypt(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val output = _state.value.outputUri
        if (output == null) {
            emit(t(R.string.encrypt_choose_output_folder_first))
            return
        }
        val password = localVault.password
        if (password.isNullOrEmpty()) {
            emit(t(R.string.encrypt_enter_local_password_first))
            return
        }

        _state.update { it.copy(busy = true, progress = null) }

        viewModelScope.launch {
            runCatching {
                runner.encrypt(uris, Uri.parse(output), password) { progress ->
                    _state.update {
                        it.copy(
                            progress = Progress(
                                fileName = progress.fileName,
                                index = progress.index,
                                count = progress.count,
                                fraction = progress.fraction,
                            )
                        )
                    }
                    notifications.progress(
                        id = CryptoNotifications.ID_ENCRYPT,
                        title = t(R.string.encrypt_encrypting),
                        text = "${progress.fileName} (${progress.index}/${progress.count})",
                        percent = (progress.fraction * 100).toInt(),
                    )
                }
            }.onSuccess { created ->
                notifications.finished(
                    CryptoNotifications.ID_ENCRYPT,
                    t(R.string.encrypt_encryption_complete),
                    t(R.string.encrypt_file, created.size),
                )
                _state.update { it.copy(busy = false, progress = null) }
                emit(t(R.string.encrypt_encryption_complete_file, created.size))
            }.onFailure { throwable ->
                notifications.cancel(CryptoNotifications.ID_ENCRYPT)
                _state.update { it.copy(busy = false, progress = null) }
                emit(throwable.message ?: t(R.string.encrypt_encryption_failed))
            }
        }
    }

    fun dismissProgress() = _state.update { it.copy(busy = false, progress = null) }

    private fun emit(text: String) = viewModelScope.launch { _messages.send(text) }
}

@Composable
fun EncryptScreen(onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val viewModel = rememberAppViewModel { container ->
        EncryptViewModel(
            settings = container.settings,
            runner = container.cryptoTaskRunner,
            localVault = container.localVault,
            keyStore = container.keyStore,
            notifications = container.cryptoNotifications,
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val rememberDefault by viewModel.rememberDefault.collectAsStateWithLifecycle()
    val container = appContainer()
    val unlocked by container.localVault.isUnlockedFlow.collectAsStateWithLifecycle()

    // The dialog is shared by file encryption and the text converter, so it has
    // to carry both the wording and what to resume afterwards. Without that the
    // text actions only opened the prompt and then did nothing once the vault
    // was open — the user had to press the button a second time.
    var unlockRequest by remember { mutableStateOf<UnlockRequest?>(null) }
    var mode by remember { mutableStateOf(EncryptMode.FILES) }

    val outputPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.onOutputDirSelected(uri)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (unlocked) {
            viewModel.encrypt(uris)
        } else {
            unlockRequest = UnlockRequest(
                message = t(R.string.encrypt_enter_local_password_use_encryption),
                confirmLabel = t(R.string.app_encrypt),
            ) { viewModel.encrypt(uris) }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val outputLabel = state.outputUri
        ?.let { Uri.decode(it).substringAfterLast(':').substringAfterLast('/') }
        ?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = t(R.string.app_encrypt),
            subtitle = when (mode) {
                EncryptMode.FILES ->
                    outputLabel?.let { t(R.string.encrypt_output, it) } ?: t(R.string.encrypt_output_folder_chosen)

                EncryptMode.TEXT -> t(R.string.encrypt_text_filenames_share_same_encryption)
            },
            onMenu = onOpenDrawer,
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            EncryptMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { mode = item },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = EncryptMode.entries.size,
                    ),
                ) {
                    Text(item.label)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // The text workspace has nothing in common with the file one, so it
            // replaces the content outright instead of nesting inside it.
            if (mode == EncryptMode.TEXT) {
                TextConverterPanel(
                    unlocked = unlocked,
                    onRequestUnlock = { encrypting, afterUnlock ->
                        unlockRequest = UnlockRequest(
                            message = if (encrypting) {
                                t(R.string.encrypt_enter_local_password_use_encryption)
                            } else {
                                t(R.string.encrypt_enter_local_password_use_decryption)
                            },
                            confirmLabel = if (encrypting) t(R.string.app_encrypt) else t(R.string.encrypt_decrypt),
                            afterUnlock = afterUnlock,
                        )
                    },
                )
                Spacer(Modifier.height(40.dp))
                return@Column
            }

            SakuraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t(R.string.encrypt_local_encryption), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = t(R.string.encrypt_both_content_filename_encrypted_result_uploaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(t(R.string.encrypt_output_folder))

            SakuraCard {
                SettingRow(
                    title = outputLabel ?: t(R.string.encrypt_choose_output_folder),
                    subtitle = t(R.string.encrypt_encrypted_ske_files_saved_here),
                    onClick = { outputPicker.launch(null) },
                    trailing = {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            Spacer(Modifier.height(22.dp))

            PrimaryButton(
                text = t(R.string.encrypt_select_files_start_encrypting),
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = state.outputUri != null,
                icon = Icons.Rounded.UploadFile,
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (unlocked) {
                        t(R.string.encrypt_local_password_unlocked_ready_encrypt)
                    } else {
                        t(R.string.encrypt_asks_local_password_before_encrypting_remember)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.outputUri == null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = t(R.string.encrypt_choose_output_folder_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionHeader(t(R.string.encrypt_what_happens_after_encryption))

            SakuraCard {
                BulletLine(t(R.string.encrypt_filenames_become_encrypted_tokens_eg))
                Spacer(Modifier.height(8.dp))
                BulletLine(t(R.string.encrypt_content_split1_mi_b_chunks_encrypted))
                Spacer(Modifier.height(8.dp))
                BulletLine(t(R.string.encrypt_keys_derived_password_via_pbkdf2100))
                Spacer(Modifier.height(8.dp))
                BulletLine(t(R.string.encrypt_original_file_unchanged_deleted))
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    unlockRequest?.let { request ->
        LocalUnlockDialog(
            initialRemember = rememberDefault,
            message = request.message,
            confirmLabel = request.confirmLabel,
            onDismiss = { unlockRequest = null },
            onSubmit = { password, remember -> viewModel.unlock(password, remember) },
            onUnlocked = {
                unlockRequest = null
                // Finish whatever the user originally pressed.
                request.afterUnlock()
            },
        )
    }

    val progress = state.progress
    if (state.busy) {
        TaskProgressDialog(
            title = t(R.string.encrypt_encrypting),
            detail = if (progress != null) {
                "${progress.fileName}  (${progress.index}/${progress.count})"
            } else {
                t(R.string.encrypt_preparing)
            },
            fraction = progress?.fraction ?: 0f,
            onSendToBackground = viewModel::dismissProgress,
        )
    }
}

@Composable
private fun BulletLine(text: String) {
    Row {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Which workspace the encryption screen is showing. */
private enum class EncryptMode {
    FILES,
    TEXT;

    /** Read on every draw so the segmented control follows the language. */
    val label: String
        get() = when (this) {
            FILES -> t(R.string.player_files)
            TEXT -> t(R.string.encrypt_text)
        }
}

/**
 * A pending request for the local password.
 *
 * The dialog is shared, so it needs to know which wording to use and, more
 * importantly, what to finish once the vault is open — several different flows
 * sit behind the same prompt.
 */
private class UnlockRequest(
    val message: String,
    val confirmLabel: String,
    val afterUnlock: () -> Unit,
)

/**
 * Turns a snippet of text into the very same kind of token used for file names.
 *
 * Mirrors the desktop GUI's "文本转换" tab: derive the name key from the local
 * password, then apply deterministic AES-GCM. Because it is the same scheme,
 * a token produced here can be pasted back into the CLI or the web client and
 * decrypted there, and vice versa.
 */
@Composable
private fun TextConverterPanel(
    unlocked: Boolean,
    /**
     * Ask for the vault password, then run the supplied continuation so the
     * action the user actually pressed is the one that completes.
     */
    onRequestUnlock: (encrypting: Boolean, afterUnlock: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(encrypting: Boolean) {
        val password = container.localVault.password
        if (password.isNullOrEmpty()) {
            // Not an error: ask for the password, then carry on from here. The
            // recursive call finds a password and takes the normal path.
            onRequestUnlock(encrypting) { run(encrypting) }
            return
        }
        val source = input.trim()
        if (source.isEmpty()) {
            error = t(R.string.encrypt_enter_some_content_first)
            return
        }

        scope.launch {
            runCatching {
                val nameKey = NameKeyCache.get(password)
                if (encrypting) {
                    SkeCrypto.encryptName(source, nameKey)
                } else {
                    SkeCrypto.decryptName(source, nameKey)
                }
            }.onSuccess {
                output = it
                error = null
            }.onFailure {
                output = ""
                error = if (encrypting) {
                    t(R.string.encrypt_encryption_failed)
                } else {
                    t(R.string.encrypt_decryption_failed_wrong_password_text_produced)
                }
            }
        }
    }

    SakuraCard {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(t(R.string.encrypt_enter_content)) },
            placeholder = { Text(t(R.string.encrypt_text_encrypt_paste_ciphertext)) },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!unlocked) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = t(R.string.encrypt_text_conversion_uses_local_password_asks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { run(encrypting = true) },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(t(R.string.app_encrypt))
            }
            OutlinedButton(
                onClick = { run(encrypting = false) },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(t(R.string.encrypt_decrypt))
            }
        }

        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (output.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                text = t(R.string.encrypt_result),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("ske-text", output))
                    Toast.makeText(context, t(R.string.encrypt_copied_clipboard), Toast.LENGTH_SHORT).show()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(t(R.string.encrypt_copy_result))
            }
        }
    }
}
