package com.sakura.encryptor.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.BuildConfig
import com.sakura.encryptor.core.remote.RemoteRepository
import com.sakura.encryptor.core.cache.DerivedKeyCache
import com.sakura.encryptor.core.cache.PlaintextBlockCache
import com.sakura.encryptor.core.cache.ThumbnailDiskCache
import com.sakura.encryptor.core.cache.cacheLimitMbToBytes
import com.sakura.encryptor.core.media.PreviewCache
import com.sakura.encryptor.core.openlist.LocalOpenListManager
import com.sakura.encryptor.core.openlist.LocalOpenListService
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.security.AppLockCredential
import com.sakura.encryptor.core.security.AppLockFactor
import com.sakura.encryptor.core.security.AppLockMatch
import com.sakura.encryptor.core.security.BiometricUnlock
import com.sakura.encryptor.core.security.AppLockManager
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.security.Totp
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.data.db.SakuraDatabase
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.data.prefs.ThemeMode
import com.sakura.encryptor.ui.components.CheckboxRow
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.SectionHeader
import com.sakura.encryptor.ui.components.SettingRow
import com.sakura.encryptor.ui.components.SwitchRow
import com.sakura.encryptor.ui.rememberAppViewModel
import com.sakura.encryptor.ui.theme.AccentTheme
import com.sakura.encryptor.ui.theme.lightAccent
import com.sakura.encryptor.ui.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How much room each cache currently takes, plus the memoised key count. */
data class CacheUsage(
    val thumbnails: Long = 0,
    val files: Long = 0,
    val keys: Int = 0,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val localVault: VaultSession,
    private val cloudVault: VaultSession,
    private val remoteRepository: RemoteRepository,
    private val database: SakuraDatabase,
    private val keyStore: KeyStoreManager,
    private val appLockManager: AppLockManager,
    private val localOpenList: LocalOpenListManager,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val accent: StateFlow<AccentTheme> = settings.accent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentTheme.Indigo)

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    val cacheBlocks: StateFlow<Int> = settings.cacheBlocks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_CACHE_BLOCKS)

    val cacheThumbnails: StateFlow<Boolean> = settings.cacheThumbnails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val cacheFiles: StateFlow<Boolean> = settings.cacheFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val cacheDerivedKeys: StateFlow<Boolean> = settings.cacheDerivedKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val cacheThumbnailLimitMb: StateFlow<Int> = settings.cacheThumbnailLimitMb
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_THUMBNAIL_CACHE_MB,
        )

    val cacheFileLimitMb: StateFlow<Int> = settings.cacheFileLimitMb
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_FILE_CACHE_MB,
        )

    private val _cacheUsage = MutableStateFlow(CacheUsage())
    val cacheUsage: StateFlow<CacheUsage> = _cacheUsage.asStateFlow()

    val localRememberPassword: StateFlow<Boolean> = settings.localRememberPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val localUnlocked: StateFlow<Boolean> = localVault.isUnlockedFlow

    val cloudUnlocked: StateFlow<Boolean> = cloudVault.isUnlockedFlow

    val isLoggedIn: StateFlow<Boolean> = remoteRepository.isLoggedInFlow

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

    /** Factors the gate demands; [appLockMatch] decides how they combine. */
    val appLockFactors: StateFlow<Set<AppLockFactor>> = settings.appLockFactors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockFactor.DEFAULT)

    val appLockMatch: StateFlow<AppLockMatch> = settings.appLockMatch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockMatch.ALL)

    suspend fun verifyAppLock(password: String): Boolean = appLockManager.verify(password)

    suspend fun setAppLockPassword(password: String) = appLockManager.setPassword(password)

    suspend fun disableAppLock() = appLockManager.disable()

    /** True once an authenticator token is bound; TOTP cannot be asked for without one. */
    val totpBound: StateFlow<Boolean> = appLockManager.hasTotp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun bindTotp(secret: ByteArray): Boolean = appLockManager.bindTotp(secret)

    /**
     * Unbinding also unticks the factor: the gate would otherwise keep demanding
     * a proof that no longer exists, i.e. no way into the app at all.
     */
    fun unbindTotp() = viewModelScope.launch {
        appLockManager.unbindTotp()
        val current = settings.appLockFactors.first()
        if (AppLockFactor.TOTP in current) {
            settings.setAppLockFactors(
                (current - AppLockFactor.TOTP).ifEmpty { AppLockFactor.DEFAULT }
            )
        }
    }

    fun setAppLockFactor(factor: AppLockFactor, enabled: Boolean) = viewModelScope.launch {
        val current = settings.appLockFactors.first()
        val next = if (enabled) current + factor else current - factor
        // The gate keeps at least one way in, so the last tick cannot be undone.
        if (next.isNotEmpty()) settings.setAppLockFactors(next)
    }

    fun setAppLockMatch(match: AppLockMatch) = viewModelScope.launch {
        settings.setAppLockMatch(match)
    }

    fun setAccent(theme: AccentTheme) = viewModelScope.launch { settings.setAccent(theme) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setCacheBlocks(blocks: Int) = viewModelScope.launch { settings.setCacheBlocks(blocks) }

    // ---- caches --------------------------------------------------------------

    fun refreshCacheUsage() = viewModelScope.launch { _cacheUsage.value = readUsage() }

    private suspend fun readUsage(): CacheUsage = withContext(Dispatchers.IO) {
        CacheUsage(
            thumbnails = ThumbnailDiskCache.usageBytes(),
            files = PlaintextBlockCache.usageBytes(),
            keys = DerivedKeyCache.size(),
        )
    }

    fun setCacheThumbnails(enabled: Boolean) = viewModelScope.launch {
        settings.setCacheThumbnails(enabled)
        // Switching off drops what was already decrypted; nothing refreshes it.
        if (!enabled) {
            PreviewCache.clear()
            ThumbnailDiskCache.clear()
        }
        _cacheUsage.value = readUsage()
    }

    fun clearThumbnailCache() = viewModelScope.launch {
        PreviewCache.clear()
        ThumbnailDiskCache.clear()
        _cacheUsage.value = readUsage()
    }

    fun setCacheFiles(enabled: Boolean) = viewModelScope.launch {
        settings.setCacheFiles(enabled)
        if (!enabled) PlaintextBlockCache.clear()
        _cacheUsage.value = readUsage()
    }

    fun clearFileCache() = viewModelScope.launch {
        PlaintextBlockCache.clear()
        _cacheUsage.value = readUsage()
    }

    fun setCacheDerivedKeys(enabled: Boolean) = viewModelScope.launch {
        settings.setCacheDerivedKeys(enabled)
        if (!enabled) DerivedKeyCache.clear()
        _cacheUsage.value = readUsage()
    }

    fun setCacheThumbnailLimitMb(mb: Int) = viewModelScope.launch {
        settings.setCacheThumbnailLimitMb(mb)
        // Apply eagerly so freed space shows up without a relaunch; the
        // container's collector repeats this, which is harmless.
        withContext(Dispatchers.IO) {
            ThumbnailDiskCache.setLimitBytes(cacheLimitMbToBytes(mb))
        }
        _cacheUsage.value = readUsage()
    }

    fun setCacheFileLimitMb(mb: Int) = viewModelScope.launch {
        settings.setCacheFileLimitMb(mb)
        withContext(Dispatchers.IO) {
            PlaintextBlockCache.setLimitBytes(cacheLimitMbToBytes(mb))
        }
        _cacheUsage.value = readUsage()
    }

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

    // ---- built-in server -----------------------------------------------------

    /** False when this build was made without the optional Go binding. */
    val localOpenListSupported: Boolean = localOpenList.isSupported

    val localOpenListAddress: String = localOpenList.baseUrl

    val localOpenListEnabled: StateFlow<Boolean> = settings.localOpenListEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _localOpenListBusy = MutableStateFlow(false)
    val localOpenListBusy: StateFlow<Boolean> = _localOpenListBusy.asStateFlow()

    private val _localOpenListError = MutableStateFlow<String?>(null)
    val localOpenListError: StateFlow<String?> = _localOpenListError.asStateFlow()

    fun setLocalOpenListEnabled(enabled: Boolean) = viewModelScope.launch {
        if (_localOpenListBusy.value) return@launch
        _localOpenListBusy.value = true
        _localOpenListError.value = null
        // Starting the server can take a few seconds; the switch waits for it.
        val result = if (enabled) {
            localOpenList.enable()
        } else {
            runCatching { localOpenList.disable() }
        }
        result.onFailure { _localOpenListError.value = it.message ?: "内置服务器切换失败" }
        _localOpenListBusy.value = false
    }

    fun clearHistory() = viewModelScope.launch { database.historyDao().clear() }
}

@Composable
fun SettingsScreen(
    onManageProfiles: () -> Unit,
    onManageLocalStorage: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val viewModel = rememberAppViewModel { container ->
        SettingsViewModel(
            settings = container.settings,
            localVault = container.localVault,
            cloudVault = container.vault,
            remoteRepository = container.remoteRepository,
            database = container.database,
            keyStore = container.keyStore,
            appLockManager = container.appLockManager,
            localOpenList = container.localOpenListManager,
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
    val appLockFactors by viewModel.appLockFactors.collectAsStateWithLifecycle()
    val appLockMatch by viewModel.appLockMatch.collectAsStateWithLifecycle()
    val totpBound by viewModel.totpBound.collectAsStateWithLifecycle()
    val cacheThumbnails by viewModel.cacheThumbnails.collectAsStateWithLifecycle()
    val cacheFiles by viewModel.cacheFiles.collectAsStateWithLifecycle()
    val cacheDerivedKeys by viewModel.cacheDerivedKeys.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsage.collectAsStateWithLifecycle()
    val cacheThumbnailLimitMb by viewModel.cacheThumbnailLimitMb.collectAsStateWithLifecycle()
    val cacheFileLimitMb by viewModel.cacheFileLimitMb.collectAsStateWithLifecycle()
    val localOpenListEnabled by viewModel.localOpenListEnabled.collectAsStateWithLifecycle()
    val localOpenListBusy by viewModel.localOpenListBusy.collectAsStateWithLifecycle()
    val localOpenListError by viewModel.localOpenListError.collectAsStateWithLifecycle()

    // Sizes require a directory walk, so they are read when the screen opens.
    LaunchedEffect(Unit) { viewModel.refreshCacheUsage() }

    val context = LocalContext.current

    // The notification is what keeps the embedded server's process unfrozen.
    LaunchedEffect(localOpenListEnabled) {
        if (localOpenListEnabled) {
            LocalOpenListService.start(context)
        } else {
            LocalOpenListService.stop(context)
        }
    }

    // Whether the device can fingerprint at all is a device property, so it is
    // asked once rather than recomputed on every recomposition.
    val biometricSupported = remember { BiometricUnlock.isAvailable(context) }

    var showHistoryCleared by remember { mutableStateOf(false) }
    var appLockDialog by remember { mutableStateOf<AppLockDialogKind?>(null) }
    var showTotpDialog by remember { mutableStateOf(false) }

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

            // ---- built-in server ---------------------------------------------
            SectionHeader("内置服务器")

            SakuraCard {
                SwitchRow(
                    title = "在本机运行 OpenList",
                    subtitle = when {
                        !viewModel.localOpenListSupported ->
                            "此安装包未包含内置服务器，需先运行 openlist-lib/build.ps1"
                        localOpenListBusy -> "正在启动，请稍候…"
                        localOpenListEnabled -> "运行中 · ${viewModel.localOpenListAddress}"
                        else -> "无需远程服务器，文件保存在应用私有目录"
                    },
                    checked = localOpenListEnabled,
                    enabled = viewModel.localOpenListSupported && !localOpenListBusy,
                    onCheckedChange = viewModel::setLocalOpenListEnabled,
                )
                localOpenListError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "开启后会切换到「内置服务器」这套云端配置，加密密码照常在浏览页输入；" +
                        "文件写入应用私有目录，卸载应用时会一并删除。关闭后回到之前使用的云端配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "管理内置存储",
                    subtitle = if (localOpenListEnabled) {
                        "添加 WebDAV、S3 等存储，或调整已有挂载"
                    } else {
                        "开启内置服务器后可添加和管理存储"
                    },
                    onClick = if (localOpenListEnabled) onManageLocalStorage else null,
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
                        "已开启，可勾选密码、指纹等验证方式"
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
                    Text(
                        text = "验证方式（可多选，勾选的都通过才能进入）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    )
                    AppLockFactor.entries.forEach { factor ->
                        val usable = when (factor) {
                            AppLockFactor.BIOMETRIC -> biometricSupported
                            AppLockFactor.TOTP -> totpBound
                            AppLockFactor.PASSWORD -> true
                        }
                        val checked = factor in appLockFactors
                        CheckboxRow(
                            title = factor.title,
                            subtitle = when {
                                factor == AppLockFactor.BIOMETRIC && !usable ->
                                    "此设备尚未录入指纹或面容"
                                factor == AppLockFactor.TOTP && !usable ->
                                    "先在下方「TOTP 身份验证器」中绑定"
                                factor == AppLockFactor.PASSWORD -> "打开应用时输入启动密码"
                                factor == AppLockFactor.TOTP -> "打开应用时输入验证器中的 6 位验证码"
                                else -> "打开应用时验证指纹或面容"
                            },
                            checked = checked,
                            // Never let the last remaining factor be unticked, and
                            // never demand a factor this device cannot prove.
                            enabled = usable && !(checked && appLockFactors.size == 1),
                            onCheckedChange = { viewModel.setAppLockFactor(factor, it) },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = "TOTP 身份验证器",
                        subtitle = if (totpBound) {
                            "已绑定，可勾选为验证方式"
                        } else {
                            "未绑定，点击生成密钥并添加到一个验证器应用"
                        },
                        onClick = { showTotpDialog = true },
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (appLockFactors.size > 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            title = "任一方式通过即可",
                            subtitle = if (appLockMatch == AppLockMatch.ANY) {
                                "勾选的方式中任意一个通过就能进入应用"
                            } else {
                                "勾选的方式必须全部通过才能进入应用"
                            },
                            checked = appLockMatch == AppLockMatch.ANY,
                            onCheckedChange = { any ->
                                viewModel.setAppLockMatch(
                                    if (any) AppLockMatch.ANY else AppLockMatch.ALL,
                                )
                            },
                        )
                    }
                    if (appLockFactors == setOf(AppLockFactor.BIOMETRIC)) {
                        Text(
                            text = "已关闭密码验证。若指纹不可用（例如在系统中删除指纹），" +
                                "将无法进入应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
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
            SectionHeader("播放")

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

            // ---- caches ------------------------------------------------------
            SectionHeader("缓存")

            SakuraCard {
                SwitchRow(
                    title = "缓存缩略图",
                    subtitle = "已占用 ${formatBytes(cacheUsage.thumbnails)}",
                    checked = cacheThumbnails,
                    onCheckedChange = viewModel::setCacheThumbnails,
                )
                CacheLimitChips(
                    selectedMb = cacheThumbnailLimitMb,
                    options = SettingsStore.THUMBNAIL_CACHE_OPTIONS_MB,
                    enabled = cacheThumbnails,
                    onSelect = { viewModel.setCacheThumbnailLimitMb(it) },
                )
                SettingRow(
                    title = "清理缩略图缓存",
                    subtitle = if (cacheUsage.thumbnails > 0) {
                        "点击释放 ${formatBytes(cacheUsage.thumbnails)}"
                    } else {
                        "暂无缓存"
                    },
                    onClick = if (cacheUsage.thumbnails > 0) {
                        { viewModel.clearThumbnailCache() }
                    } else {
                        null
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "缓存文件",
                    subtitle = "已占用 ${formatBytes(cacheUsage.files)}",
                    checked = cacheFiles,
                    onCheckedChange = viewModel::setCacheFiles,
                )
                CacheLimitChips(
                    selectedMb = cacheFileLimitMb,
                    options = SettingsStore.FILE_CACHE_OPTIONS_MB,
                    enabled = cacheFiles,
                    onSelect = { viewModel.setCacheFileLimitMb(it) },
                )
                SettingRow(
                    title = "清理文件缓存",
                    subtitle = if (cacheUsage.files > 0) {
                        "点击释放 ${formatBytes(cacheUsage.files)}"
                    } else {
                        "暂无缓存"
                    },
                    onClick = if (cacheUsage.files > 0) {
                        { viewModel.clearFileCache() }
                    } else {
                        null
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "缓存派生密钥",
                    subtitle = "内存中记住 ${cacheUsage.keys} 个文件的密钥，不占用存储",
                    checked = cacheDerivedKeys,
                    onCheckedChange = viewModel::setCacheDerivedKeys,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "上限由你决定：缩略图缓存保存解密后的预览图，文件缓存保存播放、查看时解密出来的内容，" +
                        "再次访问无需下载。超出上限时自动淘汰最久未使用的内容。两者都在应用私有目录，" +
                        "锁定密码或退出配置时会自动清空。",
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
                    text = "与 CLI / Web 端共用 .ske 版本 001 格式：AES-256-GCM 分块加密 + PBKDF2 " +
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

    if (showTotpDialog) {
        TotpDialog(
            bound = totpBound,
            onBind = viewModel::bindTotp,
            onUnbind = viewModel::unbindTotp,
            onDismiss = { showTotpDialog = false },
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

/**
 * Binds or unbinds the authenticator token behind the TOTP factor.
 *
 * Binding takes two steps on purpose: the secret is shown first, and only a
 * code generated for it by the user's own app finishes the job. A secret that
 * was copied down wrong therefore never becomes a factor nobody can prove —
 * which, on a launch gate, would mean being shut out of the app.
 */
@Composable
private fun TotpDialog(
    bound: Boolean,
    onBind: suspend (ByteArray) -> Boolean,
    onUnbind: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (bound) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("TOTP 身份验证器") },
            text = {
                Text("已绑定。解绑后该验证方式会同时取消勾选，否则门禁会要求一个再也无法通过的验证。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnbind()
                        onDismiss()
                    }
                ) { Text("解绑") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    } else {
        TotpSetupDialog(onBind = onBind, onDismiss = onDismiss)
    }
}

@Composable
private fun TotpSetupDialog(
    onBind: suspend (ByteArray) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generated = remember { Totp.generateSecret() }
    val generatedText = remember(generated) { Totp.encodeBase32(generated) }

    // Off by default: generating a secret is one tap, pasting one from an app
    // that already holds it is the exception.
    var manual by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pastedSecret = remember(pasted) { Totp.decodeSecret(pasted) }

    fun submit() {
        if (busy || code.length != Totp.DIGITS) return

        val secret = if (manual) pastedSecret else generated
        if (secret == null) {
            error = "密钥格式不正确，应为 Base32 字符串或 otpauth 链接"
            return
        }

        busy = true
        error = null
        scope.launch {
            // Checks the secret and the device clock before storing anything.
            if (!Totp.verify(secret, code, System.currentTimeMillis())) {
                busy = false
                code = ""
                error = "验证码不正确或已过期，请按验证器上当前的数字重新输入"
                return@launch
            }

            val saved = runCatching { onBind(secret) }.getOrDefault(false)
            busy = false
            if (saved) onDismiss() else error = "无法保存密钥，请重试"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("绑定 TOTP 身份验证器") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "在身份验证器应用（Google Authenticator、Aegis、1Password 等）" +
                        "中添加账号，密钥填下面这一串，再输入它生成的 6 位验证码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (manual) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = {
                            pasted = it
                            error = null
                        },
                        label = { Text("密钥或 otpauth 链接") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = generatedText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Row {
                        TextButton(onClick = { context.copyText(generatedText) }) {
                            Text("复制密钥")
                        }
                        TextButton(onClick = { context.copyText(Totp.otpauthUri(generatedText)) }) {
                            Text("复制链接")
                        }
                    }
                }

                TextButton(
                    onClick = {
                        manual = !manual
                        error = null
                    }
                ) {
                    Text(if (manual) "改为自动生成密钥" else "改为使用已有密钥")
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter { char -> char.isDigit() }.take(Totp.DIGITS)
                        error = null
                    },
                    label = { Text("验证器中的 6 位验证码") },
                    placeholder = { Text("000000") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy && code.length == Totp.DIGITS) {
                Text(if (busy) "处理中…" else "绑定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** Plain-text copy; a clipboard the system refuses is not worth interrupting for. */
private fun Context.copyText(text: String) {
    runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Sakura Encryptor", text))
    }
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

/**
 * Preset budget picker for one cache.
 *
 * A handful of presets beats a 30-step slider: every stop is a size people
 * actually pick, and "no limit" is an explicit choice rather than an
 * unreachable end of a range. Selecting one applies — and trims — immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheLimitChips(
    selectedMb: Int,
    options: List<Int>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "缓存上限",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { mb ->
                val isSelected = mb == selectedMb
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = enabled) { onSelect(mb) },
                ) {
                    Text(
                        text = cacheLimitLabel(mb),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** `128 MB` / `5 GB` / `无上限`. */
private fun cacheLimitLabel(mb: Int): String = when {
    mb <= SettingsStore.UNLIMITED_CACHE_MB -> "无上限"
    mb >= 1024 -> "${mb / 1024} GB"
    else -> "$mb MB"
}
