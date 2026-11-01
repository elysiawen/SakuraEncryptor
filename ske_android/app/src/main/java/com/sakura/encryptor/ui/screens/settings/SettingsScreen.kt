package com.sakura.encryptor.ui.screens.settings

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

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
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.sakura.encryptor.core.embedded.EmbeddedServer
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
import com.sakura.encryptor.data.prefs.AppLanguage
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
    private val embeddedServer: EmbeddedServer,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val accent: StateFlow<AccentTheme> = settings.accent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentTheme.Indigo)

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    val language: StateFlow<AppLanguage> = settings.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.SYSTEM)

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

    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settings.setLanguage(language) }

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

    /** False when this package is the `plain` flavor, i.e. ships no AAR at all. */
    val localOpenListBundled: Boolean = embeddedServer.isBundled

    /** False when the binding is present but unusable on this device/ABI. */
    val localOpenListSupported: Boolean = embeddedServer.isSupported

    val localOpenListPort: StateFlow<Int> = settings.localOpenListPort
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_LOCAL_PORT,
        )

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
            embeddedServer.enable()
        } else {
            runCatching { embeddedServer.disable() }
        }
        result.onFailure { _localOpenListError.value = it.message ?: t(R.string.settings_failed_switch_built_server) }
        _localOpenListBusy.value = false
    }

    fun embeddedServerStart(context: Context) = embeddedServer.startForegroundService(context)

    fun embeddedServerStop(context: Context) = embeddedServer.stopForegroundService(context)

    fun clearHistory() = viewModelScope.launch { database.historyDao().clear() }
}

@Composable
fun SettingsScreen(
    onManageProfiles: () -> Unit,
    onManageLocalStorage: () -> Unit,
    onOpenServerSettings: () -> Unit,
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
            embeddedServer = container.embeddedServer,
            profileRepository = container.profileRepository,
        )
    }

    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
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
    val localOpenListPort by viewModel.localOpenListPort.collectAsStateWithLifecycle()

    // Sizes require a directory walk, so they are read when the screen opens.
    LaunchedEffect(Unit) { viewModel.refreshCacheUsage() }

    val context = LocalContext.current

    // The notification is what keeps the embedded server's process unfrozen.
    LaunchedEffect(localOpenListEnabled) {
        if (localOpenListEnabled) {
            viewModel.embeddedServerStart(context)
        } else {
            viewModel.embeddedServerStop(context)
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
        SakuraTopBar(title = t(R.string.app_settings), onMenu = onOpenDrawer)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---- appearance --------------------------------------------------
            SectionHeader(t(R.string.settings_appearance))

            SakuraCard {
                SettingRow(title = t(R.string.settings_accent_colour), subtitle = accent.label)
                Spacer(Modifier.height(6.dp))
                AccentPicker(selected = accent, onSelect = viewModel::setAccent)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(18.dp))
                ThemeModePicker(selected = themeMode, onSelect = viewModel::setThemeMode)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(18.dp))
                LanguagePicker(selected = language, onSelect = viewModel::setLanguage)
            }

            Spacer(Modifier.height(22.dp))

            // ---- cloud profiles ----------------------------------------------
            SectionHeader(t(R.string.profiles_cloud_profiles))

            SakuraCard {
                SettingRow(
                    title = t(R.string.settings_manage_cloud_profiles),
                    subtitle = when {
                        activeProfileName == null -> t(R.string.settings_profile_yet_tap_add_one)
                        !isLoggedIn -> t(R.string.settings_active_connected, activeProfileName)
                        else -> t(R.string.settings_active, activeProfileName)
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
                    title = t(R.string.settings_active_profile_s_password),
                    subtitle = if (cloudUnlocked) {
                        t(R.string.settings_unlocked_memory_only)
                    } else {
                        t(R.string.settings_locked_asked_when_switching)
                    },
                )
            }

            Spacer(Modifier.height(22.dp))

            // The section only exists in the flavor that actually ships the
            // server; the plain APK would otherwise advertise a switch that can
            // never be turned on.
            if (viewModel.localOpenListBundled) {
                // ---- built-in server -----------------------------------------
                SectionHeader(t(R.string.settings_built_server))

                SakuraCard {
                    SwitchRow(
                        title = t(R.string.settings_run_open_list_device),
                        subtitle = when {
                            !viewModel.localOpenListSupported ->
                                t(R.string.settings_built_server_unavailable_device_s_architecture)
                            localOpenListBusy -> t(R.string.settings_starting_please_wait)
                            localOpenListEnabled -> t(R.string.settings_running_http127001, localOpenListPort)
                            else -> t(R.string.settings_remote_server_needed_files_stay_app)
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
                    SettingRow(
                        title = t(R.string.settings_server_settings),
                        subtitle = t(R.string.settings_change_port_admin_username_password),
                        onClick = onOpenServerSettings,
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
                        title = t(R.string.settings_manage_built_storage),
                        subtitle = if (localOpenListEnabled) {
                            t(R.string.settings_add_web_davs3_other_storage_adjust)
                        } else {
                            t(R.string.settings_enable_built_server_add_manage_storage)
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = t(R.string.settings_when_enabled_app_switches_built_server) +
                            t(R.string.settings_files_go_app_private_storage_removed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }

                Spacer(Modifier.height(22.dp))
            }

            // ---- local -------------------------------------------------------
            SectionHeader(t(R.string.app_local))

            SakuraCard {
                SettingRow(
                    title = t(R.string.components_local_password),
                    subtitle = if (localUnlocked) {
                        t(R.string.settings_unlocked_memory_tap_lock)
                    } else {
                        t(R.string.settings_locked_enter_local_page)
                    },
                    onClick = { if (localUnlocked) viewModel.lockLocal() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = t(R.string.settings_remember_local_password),
                    subtitle = t(R.string.settings_sealed_android_keystore_unlocks_automatically_next),
                    checked = localRememberPassword,
                    onCheckedChange = viewModel::setLocalRememberPassword,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = t(R.string.settings_clear_playback_history),
                    subtitle = t(R.string.settings_remove_saved_playback_positions),
                    onClick = {
                        viewModel.clearHistory()
                        showHistoryCleared = true
                    },
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- security ----------------------------------------------------
            SectionHeader(t(R.string.settings_security))

            SakuraCard {
                SwitchRow(
                    title = t(R.string.lock_launch_password),
                    subtitle = if (appLockEnabled) {
                        t(R.string.settings_tick_password_fingerprint_other_verification_methods)
                    } else {
                        t(R.string.settings_set_app_opens_directly)
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
                        text = t(R.string.settings_verification_methods_multi_select_all_ticked),
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
                                    t(R.string.settings_fingerprint_face_enrolled_device)
                                factor == AppLockFactor.TOTP && !usable ->
                                    t(R.string.settings_bind_one_under_totp_authenticator_below)
                                factor == AppLockFactor.PASSWORD -> t(R.string.settings_enter_launch_password_when_opening_app)
                                factor == AppLockFactor.TOTP -> t(R.string.settings_enter6_digit_authenticator_code_when)
                                else -> t(R.string.settings_verify_fingerprint_face_when_opening_app)
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
                        title = t(R.string.settings_totp_authenticator),
                        subtitle = if (totpBound) {
                            t(R.string.settings_bound_tick_verification_method)
                        } else {
                            t(R.string.settings_bound_tap_generate_secret_authenticator_app)
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
                            title = t(R.string.settings_any_verified_method_opens_app),
                            subtitle = if (appLockMatch == AppLockMatch.ANY) {
                                t(R.string.settings_any_one_ticked_methods_opens_app)
                            } else {
                                t(R.string.settings_every_ticked_method_must_pass_enter)
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
                            text = t(R.string.settings_password_verification_off_fingerprint_becomes_unavailable) +
                                t(R.string.settings_able_enter_app),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow(
                        title = t(R.string.settings_change_launch_password),
                        subtitle = t(R.string.settings_current_password_must_verified_first),
                        onClick = { appLockDialog = AppLockDialogKind.CHANGE },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = t(R.string.settings_launch_password_stays_device_pbkdf2_hash),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- playback ----------------------------------------------------
            SectionHeader(t(R.string.components_playback))

            SakuraCard {
                SettingRow(
                    title = t(R.string.settings_decrypted_chunk_cache_size),
                    subtitle = t(R.string.settings_chunks_about_mb_memory, cacheBlocks, cacheBlocks),
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
                    text = t(R.string.settings_caching_decrypted_chunks_cuts_network_traffic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- caches ------------------------------------------------------
            SectionHeader(t(R.string.settings_cache2))

            SakuraCard {
                SwitchRow(
                    title = t(R.string.settings_cache_thumbnails),
                    subtitle = t(R.string.settings_used, formatBytes(cacheUsage.thumbnails)),
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
                    title = t(R.string.settings_clear_thumbnail_cache),
                    subtitle = if (cacheUsage.thumbnails > 0) {
                        t(R.string.settings_tap_free, formatBytes(cacheUsage.thumbnails))
                    } else {
                        t(R.string.settings_cache)
                    },
                    onClick = if (cacheUsage.thumbnails > 0) {
                        { viewModel.clearThumbnailCache() }
                    } else {
                        null
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = t(R.string.settings_cache_files),
                    subtitle = t(R.string.settings_used, formatBytes(cacheUsage.files)),
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
                    title = t(R.string.settings_clear_file_cache),
                    subtitle = if (cacheUsage.files > 0) {
                        t(R.string.settings_tap_free, formatBytes(cacheUsage.files))
                    } else {
                        t(R.string.settings_cache)
                    },
                    onClick = if (cacheUsage.files > 0) {
                        { viewModel.clearFileCache() }
                    } else {
                        null
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = t(R.string.settings_cache_derived_keys),
                    subtitle = t(R.string.settings_keys_file_memoised_memory_storage_used, cacheUsage.keys),
                    checked = cacheDerivedKeys,
                    onCheckedChange = viewModel::setCacheDerivedKeys,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = t(R.string.settings_limits_yours_set_thumbnail_cache_keeps) +
                        t(R.string.settings_revisiting_needs_download_least_recently_used) +
                        t(R.string.settings_cleared_automatically_when_lock_password_leave),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---- about -------------------------------------------------------
            SectionHeader(t(R.string.settings_about))

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
                            text = t(R.string.settings_version_android_client, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = t(R.string.settings_shares_ske_format_version001_cli) +
                        t(R.string.settings_100000_iterations_password_only_exists),
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
                        text = t(R.string.settings_local_password_cloud_profiles_independent_one),
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
            title = { Text(t(R.string.settings_cleared)) },
            text = { Text(t(R.string.settings_playback_history_deleted)) },
            confirmButton = {
                TextButton(onClick = { showHistoryCleared = false }) { Text(t(R.string.settings_ok)) }
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
private enum class AppLockDialogKind {
    ENABLE,
    CHANGE,
    DISABLE;

    /** Dialog titles follow the interface language, so they are read live. */
    val title: String
        get() = when (this) {
            ENABLE -> t(R.string.settings_set_launch_password)
            CHANGE -> t(R.string.settings_change_launch_password)
            DISABLE -> t(R.string.settings_disable_launch_password)
        }

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
                error = t(R.string.settings_current_password_incorrect)
                return@launch
            }
            if (kind.needsNewPassword) {
                if (next.length < AppLockManager.MIN_LENGTH) {
                    busy = false
                    error = t(R.string.settings_new_password_needs_least_characters, AppLockManager.MIN_LENGTH)
                    return@launch
                }
                if (next != repeated) {
                    busy = false
                    error = t(R.string.settings_new_passwords_match)
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
                    error = it.message ?: t(R.string.components_operation_failed)
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
                        label = { Text(t(R.string.settings_current_password)) },
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
                        label = { Text(t(R.string.settings_new_password)) },
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
                        label = { Text(t(R.string.settings_confirm_new_password)) },
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
                Text(if (busy) t(R.string.settings_working) else t(R.string.settings_ok2))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(t(R.string.security_cancel)) }
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
            title = { Text(t(R.string.settings_totp_authenticator)) },
            text = {
                Text(t(R.string.settings_bound_unbinding_also_unticks_method_otherwise))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnbind()
                        onDismiss()
                    }
                ) { Text(t(R.string.settings_unbind)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.security_cancel)) } },
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
            error = t(R.string.settings_invalid_secret_use_base32_string_otpauth)
            return
        }

        busy = true
        error = null
        scope.launch {
            // Checks the secret and the device clock before storing anything.
            if (!Totp.verify(secret, code, System.currentTimeMillis())) {
                busy = false
                code = ""
                error = t(R.string.settings_code_incorrect_expired_enter_current_digits)
                return@launch
            }

            val saved = runCatching { onBind(secret) }.getOrDefault(false)
            busy = false
            if (saved) onDismiss() else error = t(R.string.settings_could_save_secret_try_again)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(t(R.string.settings_bind_totp_authenticator)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = t(R.string.settings_authenticator_app_google_authenticator_aegis1_password) +
                        t(R.string.settings_fill_secret_below_enter6_digit),
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
                        label = { Text(t(R.string.settings_secret_otpauth_link)) },
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
                            Text(t(R.string.settings_copy_secret))
                        }
                        TextButton(onClick = { context.copyText(Totp.otpauthUri(generatedText)) }) {
                            Text(t(R.string.settings_copy_link))
                        }
                    }
                }

                TextButton(
                    onClick = {
                        manual = !manual
                        error = null
                    }
                ) {
                    Text(if (manual) t(R.string.settings_generate_secret_instead) else t(R.string.settings_use_existing_secret))
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter { char -> char.isDigit() }.take(Totp.DIGITS)
                        error = null
                    },
                    label = { Text(t(R.string.settings_6_digit_authenticator_code)) },
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
                Text(if (busy) t(R.string.settings_working) else t(R.string.settings_bind))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(t(R.string.security_cancel)) }
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
 * Language as a drop-down rather than a segmented control: the list is only
 * going to grow, and a row of equally weighted segments runs out of room long
 * before the translations do.
 */
@Composable
private fun LanguagePicker(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            title = t(R.string.settings_language),
            subtitle = selected.label,
            onClick = { expanded = true },
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.label) },
                    trailingIcon = if (language == selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(language)
                    },
                )
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
            text = t(R.string.settings_cache_limit),
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
    mb <= SettingsStore.UNLIMITED_CACHE_MB -> t(R.string.settings_limit)
    mb >= 1024 -> "${mb / 1024} GB"
    else -> "$mb MB"
}
