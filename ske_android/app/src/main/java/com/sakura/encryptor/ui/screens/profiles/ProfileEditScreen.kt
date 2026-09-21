package com.sakura.encryptor.ui.screens.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.ui.components.PrimaryButton
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.rememberAppViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileEditViewModel(
    private val profileRepository: ProfileRepository,
    private val settings: SettingsStore,
    private val profileId: Long?,
) : ViewModel() {

    data class UiState(
        val isNew: Boolean = true,
        val name: String = "",
        val server: String = "",
        val username: String = "",
        val alistPassword: String = "",
        val masterPassword: String = "",
        val basePath: String = "",
        val showAdvanced: Boolean = false,
        val showPasswords: Boolean = false,
        val hasStoredAlistPassword: Boolean = false,
        val hasStoredMasterPassword: Boolean = false,
        val isLoading: Boolean = false,
        val saved: Boolean = false,
        val deleted: Boolean = false,
        val error: String? = null,
    ) {
        val canSave: Boolean
            get() = !isLoading &&
                name.isNotBlank() &&
                server.isNotBlank() &&
                username.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (profileId == null) {
                val server = settings.lastServer.first()
                val username = settings.lastUsername.first()
                _state.update { it.copy(server = server, username = username) }
            } else {
                val profile = profileRepository.find(profileId)
                if (profile != null) {
                    _state.update {
                        it.copy(
                            isNew = false,
                            name = profile.name,
                            server = profile.server,
                            username = profile.username,
                            basePath = profile.basePath,
                            hasStoredAlistPassword = !profile.sealedAlistPassword.isNullOrEmpty(),
                            hasStoredMasterPassword = !profile.sealedMasterPassword.isNullOrEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onServerChange(value: String) = _state.update { it.copy(server = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onAlistPasswordChange(value: String) =
        _state.update { it.copy(alistPassword = value, error = null) }
    fun onMasterPasswordChange(value: String) =
        _state.update { it.copy(masterPassword = value, error = null) }
    fun onBasePathChange(value: String) = _state.update { it.copy(basePath = value) }
    fun onToggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun onToggleShowPasswords() = _state.update { it.copy(showPasswords = !it.showPasswords) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            runCatching {
                profileRepository.save(
                    id = profileId,
                    name = current.name,
                    server = current.server,
                    username = current.username,
                    basePath = current.basePath,
                    // Empty means "keep the stored secret".
                    alistPassword = current.alistPassword.takeIf { it.isNotEmpty() },
                    masterPassword = current.masterPassword.takeIf { it.isNotEmpty() },
                )
            }.onSuccess { id ->
                settings.setLastLogin(current.server.trim(), current.username.trim())
                // Make the profile the active one right away.
                profileRepository.activateStored(profileId ?: id)
                _state.update { it.copy(isLoading = false, saved = true) }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(isLoading = false, error = throwable.message ?: "保存失败")
                }
            }
        }
    }

    fun delete() {
        val id = profileId ?: return
        viewModelScope.launch {
            runCatching { profileRepository.delete(id) }
                .onSuccess { _state.update { it.copy(deleted = true) } }
                .onFailure { throwable ->
                    _state.update { it.copy(error = throwable.message ?: "删除失败") }
                }
        }
    }
}

@Composable
fun ProfileEditScreen(
    profileId: Long?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = rememberAppViewModel(key = "profile_edit_${profileId ?: "new"}") { container ->
        ProfileEditViewModel(
            profileRepository = container.profileRepository,
            settings = container.settings,
            profileId = profileId,
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = if (state.isNew) "新增配置" else "编辑配置",
            subtitle = "一套 AList 账号 + 一套加密密码",
            onBack = onBack,
            actions = {
                if (!state.isNew) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除配置")
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            SakuraCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
                SakuraField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = "配置名称",
                    placeholder = "例如：家庭云盘",
                    leading = Icons.Rounded.Badge,
                )
                Spacer(Modifier.height(12.dp))
                SakuraField(
                    value = state.server,
                    onValueChange = viewModel::onServerChange,
                    label = "AList 服务器地址",
                    placeholder = "https://alist.example.com",
                    leading = Icons.Rounded.Cloud,
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(12.dp))
                SakuraField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = "用户名",
                    leading = Icons.Rounded.Person,
                )
                Spacer(Modifier.height(12.dp))
                SakuraField(
                    value = state.alistPassword,
                    onValueChange = viewModel::onAlistPasswordChange,
                    label = if (state.hasStoredAlistPassword) {
                        "AList 密码（已保存，留空则不变）"
                    } else {
                        "AList 密码"
                    },
                    leading = Icons.Rounded.Key,
                    isPassword = true,
                    visible = state.showPasswords,
                    onToggleVisibility = viewModel::onToggleShowPasswords,
                )
                Spacer(Modifier.height(12.dp))
                SakuraField(
                    value = state.masterPassword,
                    onValueChange = viewModel::onMasterPasswordChange,
                    label = if (state.hasStoredMasterPassword) {
                        "加密主密码（已保存，留空则不变）"
                    } else {
                        "加密主密码"
                    },
                    leading = Icons.Rounded.Lock,
                    isPassword = true,
                    visible = state.showPasswords,
                    onToggleVisibility = viewModel::onToggleShowPasswords,
                    imeAction = ImeAction.Done,
                )

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = viewModel::onToggleAdvanced,
                    modifier = Modifier.padding(start = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.showAdvanced) "收起高级选项" else "高级选项")
                }

                if (state.showAdvanced) {
                    SakuraField(
                        value = state.basePath,
                        onValueChange = viewModel::onBasePathChange,
                        label = "基础路径（可选）",
                        placeholder = "/media",
                        leading = Icons.Rounded.FolderOpen,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "两个密码都会使用系统密钥库（Android Keystore）加密保存，仅在连接与解密时于内存中使用。" +
                    "加密主密码决定能看到哪些文件——不同配置使用不同密码时，各自对应不同的加密目录树。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (state.isNew) "保存并启用" else "保存",
                onClick = viewModel::save,
                enabled = state.canSave,
                loading = state.isLoading,
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除配置？") },
            text = { Text("将删除该配置及其保存的密码，云端文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete()
                    showDeleteDialog = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SakuraField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leading: ImageVector,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isPassword: Boolean = false,
    visible: Boolean = true,
    onToggleVisibility: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        leadingIcon = { Icon(leading, contentDescription = null, modifier = Modifier.size(19.dp)) },
        trailingIcon = if (isPassword && onToggleVisibility != null) {
            {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !visible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}
