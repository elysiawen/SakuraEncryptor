package com.sakura.encryptor.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.BuildConfig
import com.sakura.encryptor.core.alist.AListSession
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.security.AppLockCredential
import com.sakura.encryptor.core.security.BiometricUnlock
import com.sakura.encryptor.core.security.AppLockManager
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.data.db.SakuraDatabase
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.data.prefs.ThemeMode
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.SectionHeader
import com.sakura.encryptor.ui.components.SettingRow
import com.sakura.encryptor.ui.components.SwitchRow
import com.sakura.encryptor.ui.rememberAppViewModel
import com.sakura.encryptor.ui.theme.AccentTheme
import com.sakura.encryptor.ui.theme.lightAccent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsStore,
    private val localVault: VaultSession,
    private val cloudVault: VaultSession,
    private val session: AListSession,
    private val database: SakuraDatabase,
    private val keyStore: KeyStoreManager,
    private val appLockManager: AppLockManager,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val accent: StateFlow<AccentTheme> = settings.accent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentTheme.Indigo)

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    val cacheBlocks: StateFlow<Int> = settings.cacheBlocks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_CACHE_BLOCKS)

    val localRememberPassword: StateFlow<Boolean> = settings.localRememberPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val localUnlocked: StateFlow<Boolean> = localVault.isUnlockedFlow

    val cloudUnlocked: StateFlow<Boolean> = cloudVault.isUnlockedFlow

    val isLoggedIn: StateFlow<Boolean> = session.isLoggedInFlow

    val activeProfileName: StateFlow<String?> =
        combine(profileRepository.observeProfiles(), profileRepository.activeProfileId) { list, id ->
            list.firstOrNull { it.id == id }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Mirrors [AppLockManager]'s view of the gate: a credential that cannot be
     * decoded does not lock anything, so it must not read as "enabled" here
     * either — otherwise the switch lies about the app's actual state.
     */
    val appLockEnabled: StateFlow<Boolean> = settings.appLockCredential
        .map { AppLockCredential.isWellFormed(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val appLockBiometric: StateFlow<Boolean> = settings.appLockBiometric
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun verifyAppLock(password: String): Boolean = appLockManager.verify(password)

    suspend fun setAppLockPassword(password: String) = appLockManager.setPassword(password)

    suspend fun disableAppLock() = appLockManager.disable()

    fun setAppLockBiometric(enabled: Boolean) = viewModelScope.launch {
        settings.setAppLockBiometric(enabled)
    }

    fun setAccent(theme: AccentTheme) = viewModelScope.launch { settings.setAccent(theme) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setCacheBlocks(blocks: Int) = viewModelScope.launch { settings.setCacheBlocks(blocks) }

    fun setLocalRememberPassword(enabled: Boolean) = viewModelScope.launch {
        settings.setLocalRememberPassword(enabled)
        if (enabled) {
            val current = localVault.password
            settings.setLocalSealedPassword(
                if (current.isNullOrEmpty()) null else keyStore.seal(current)
            )
        } else {
            settings.setLocalSealedPassword(null)
        }
    }

    fun lockLocal() = localVault.lock()

    fun clearHistory() = viewModelScope.launch { database.historyDao().clear() }
}

@Composable
fun SettingsScreen(
    onManageProfiles: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val viewModel = rememberAppViewModel { container ->
        SettingsViewModel(
            settings = container.settings,
            localVault = container.localVault,
            cloudVault = container.vault,
            session = container.aListSession,
            database = container.database,
            keyStore = container.keyStore,
            appLockManager = container.appLockManager,
            profileRepository = container.profileRepository,
        )
    }

    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val cacheBlocks by viewModel.cacheBlocks.collectAsStateWithLifecycle()
    val localRememberPassword by viewModel.localRememberPassword.collectAsStateWithLifecycle()
    val localUnlocked by viewModel.localUnlocked.collectAsStateWithLifecycle()
    val cloudUnlocked by viewModel.cloudUnlocked.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val activeProfileName by viewModel.activeProfileName.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockBiometric by viewModel.appLockBiometric.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // Whether the device can fingerprint at all is a device property, so it is
    // asked once rather than recomputed on every recomposition.
    val biometricSupported = remember { BiometricUnlock.isAvailable(context) }

    var showHistoryCleared by remember { mutableStateOf(false) }
    var appLockDialog by remember { mutableStateOf<AppLockDialogKind?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(title = "设置", onMenu = onOpenDrawer)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---- appearance --------------------------------------------------
            SectionHeader("外观")

            SakuraCard {
                SettingRow(title = "强调色", subtitle = accent.label)
                Spacer(Modifier.height(6.dp))
                AccentPicker(selected = accent, onSelect = viewModel::setAccent)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(18.dp))
                ThemeModePicker(selected = themeMode, onSelect = viewModel::setThemeMode)
            }

            Spacer(Modifier.height(22.dp))

            // ---- cloud profiles ----------------------------------------------
            SectionHeader("云端配置")

            SakuraCard {
                SettingRow(
                    title = "管理云端配置",
                    subtitle = when {
                        activeProfileName == null -> "尚未添加配置，点击添加"
                        !isLoggedIn -> "当前：$activeProfileName（未连接）"
                        else -> "当前：$activeProfileName"
                    },
                    onClick = onManageProfiles,
                    trailing = {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "当前配置的密码",
                    subtitle = if (cloudUnlocked) {
                        "已解锁（仅存于内存）"
                    } else {
                        "未解锁，切换到该配置时会提示输入"
                    },
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- local -------------------------------------------------------
            SectionHeader("本地")

            SakuraCard {
                SettingRow(
                    title = "本地密码",
                    subtitle = if (localUnlocked) {
                        "已解锁（仅存于内存，点击可锁定）"
                    } else {
                        "未解锁，在本地页面输入"
                    },
                    onClick = { if (localUnlocked) viewModel.lockLocal() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "记住本地密码",
                    subtitle = "使用系统密钥库加密保存，下次启动自动解锁",
                    checked = localRememberPassword,
                    onCheckedChange = viewModel::setLocalRememberPassword,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "清除播放记录",
                    subtitle = "删除本地保存的播放进度",
                    onClick = {
                        viewModel.clearHistory()
                        showHistoryCleared = true
                    },
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- security ----------------------------------------------------
            SectionHeader("安全")

            SakuraCard {
                SwitchRow(
                    title = "启动密码",
                    subtitle = if (appLockEnabled) {
                        "打开应用时需要输入密码"
                    } else {
                        "未设置，打开应用即可直接使用"
                    },
                    checked = appLockEnabled,
                    onCheckedChange = { enabled ->
                        appLockDialog = if (enabled) {
                            AppLockDialogKind.ENABLE
                        } else {
                            AppLockDialogKind.DISABLE
                        }
                    },
                )
                if (appLockEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        title = "指纹解锁",
                        subtitle = if (biometricSupported) {
                            "在锁屏页用指纹或面容快速解锁"
                        } else {
                            "此设备尚未录入指纹或面容"
                        },
                        checked = appLockBiometric && biometricSupported,
                        enabled = biometricSupported,
                        onCheckedChange = viewModel::setAppLockBiometric,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "修改启动密码",
                        subtitle = "需要先验证当前密码",
                        onClick = { appLockDialog = AppLockDialogKind.CHANGE },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "启动密码只保存在本机，且以 PBKDF2 哈希形式存储，不保存明文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- playback ----------------------------------------------------
            SectionHeader("播放与缓存")

            SakuraCard {
                SettingRow(
                    title = "解密缓存块数",
                    subtitle = "当前 $cacheBlocks 块（约 ${cacheBlocks} MB 内存）",
                )
                Slider(
                    value = cacheBlocks.toFloat(),
                    onValueChange = { viewModel.setCacheBlocks(it.toInt()) },
                    valueRange = SettingsStore.MIN_CACHE_BLOCKS.toFloat()..
                        SettingsStore.MAX_CACHE_BLOCKS.toFloat(),
                    steps = 30,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "缓存已解密的块可以显著减少拖动进度时的流量，但会占用更多内存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- about -------------------------------------------------------
            SectionHeader("关于")

            SakuraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Sakura Encryptor", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "版本 ${BuildConfig.VERSION_NAME} · 安卓客户端",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "与 CLI / Web 端共用 .ske v2.0 格式：AES-256-GCM 分块加密 + PBKDF2 " +
                        "(100,000 次迭代)。密码仅存在于设备内存，服务器只接触密文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "本地密码与云端配置互相独立；一个云端配置 = 一套 AList 账号 + 一套加密密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showHistoryCleared) {
        AlertDialog(
            onDismissRequest = { showHistoryCleared = false },
            title = { Text("已清除") },
            text = { Text("播放记录已删除。") },
            confirmButton = {
                TextButton(onClick = { showHistoryCleared = false }) { Text("好") }
            },
        )
    }

    appLockDialog?.let { kind ->
        AppPasswordDialog(
            kind = kind,
            verifyCurrent = viewModel::verifyAppLock,
            onSubmit = { password ->
                when (kind) {
                    AppLockDialogKind.ENABLE,
                    AppLockDialogKind.CHANGE,
                    -> viewModel.setAppLockPassword(password)

                    AppLockDialogKind.DISABLE -> viewModel.disableAppLock()
                }
            },
            onDismiss = { appLockDialog = null },
        )
    }
}

/** Which launch-password action the settings dialog is performing. */
private enum class AppLockDialogKind(val title: String) {
    ENABLE("设置启动密码"),
    CHANGE("修改启动密码"),
    DISABLE("关闭启动密码");

    /** Disabling only removes the credential, so it needs no replacement. */
    val needsNewPassword: Boolean get() = this != DISABLE

    /** Enabling has nothing to compare against yet. */
    val needsCurrentPassword: Boolean get() = this != ENABLE
}

/**
 * Collects the passwords needed to set, change or clear the launch gate.
 *
 * Validation happens here rather than in the view model so the dialog can show
 * the problem inline without a round-trip, but the authoritative check (does
 * the current password actually match?) always goes through
 * [SettingsViewModel.verifyAppLock].
 */
@Composable
private fun AppPasswordDialog(
    kind: AppLockDialogKind,
    verifyCurrent: suspend (String) -> Boolean,
    onSubmit: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true
        error = null

        scope.launch {
            if (kind.needsCurrentPassword && !verifyCurrent(current)) {
                busy = false
                error = "当前密码不正确"
                return@launch
            }
            if (kind.needsNewPassword) {
                if (next.length < AppLockManager.MIN_LENGTH) {
                    busy = false
                    error = "新密码至少 ${AppLockManager.MIN_LENGTH} 位"
                    return@launch
                }
                if (next != repeated) {
                    busy = false
                    error = "两次输入的新密码不一致"
                    return@launch
                }
            }

            runCatching { onSubmit(if (kind.needsNewPassword) next else "") }
                .onSuccess {
                    busy = false
                    onDismiss()
                }
                .onFailure {
                    busy = false
                    error = it.message ?: "操作失败"
                }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(kind.title) },
        text = {
            Column {
                if (kind.needsCurrentPassword) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = {
                            current = it
                            error = null
                        },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (kind.needsNewPassword) Spacer(Modifier.height(12.dp))
                }

                if (kind.needsNewPassword) {
                    OutlinedTextField(
                        value = next,
                        onValueChange = {
                            next = it
                            error = null
                        },
                        label = { Text("新密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = {
                            repeated = it
                            error = null
                        },
                        label = { Text("确认新密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = !busy &&
                    (!kind.needsCurrentPassword || current.isNotEmpty()) &&
                    (!kind.needsNewPassword || (next.isNotEmpty() && repeated.isNotEmpty())),
            ) {
                Text(if (busy) "处理中…" else "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** Accents per row; the palette outgrew a single line. */
private const val ACCENT_COLUMNS = 4

@Composable
private fun AccentPicker(
    selected: AccentTheme,
    onSelect: (AccentTheme) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccentTheme.entries.chunked(ACCENT_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { theme ->
                    AccentOption(
                        theme = theme,
                        selected = theme == selected,
                        onClick = { onSelect(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the last row so its swatches line up with the ones above.
                repeat(ACCENT_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AccentOption(
    theme: AccentTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = lightAccent(theme).primary,
            border = if (selected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
            } else {
                null
            },
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThemeModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(mode) },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = when (mode) {
                            ThemeMode.System -> Icons.Rounded.PhoneAndroid
                            ThemeMode.Light -> Icons.Rounded.LightMode
                            ThemeMode.Dark -> Icons.Rounded.DarkMode
                        },
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
