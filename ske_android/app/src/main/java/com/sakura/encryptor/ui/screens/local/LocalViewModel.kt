package com.sakura.encryptor.ui.screens.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.local.CryptoNotifications
import com.sakura.encryptor.core.local.CryptoTaskRunner
import com.sakura.encryptor.core.local.LocalSkeStore
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.data.prefs.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Local file handling, driven by the **standalone local password** — it is
 * never tied to any cloud profile.
 */
class LocalViewModel(
    private val store: LocalSkeStore,
    private val settings: SettingsStore,
    private val runner: CryptoTaskRunner,
    private val localVault: VaultSession,
    private val keyStore: KeyStoreManager,
    private val notifications: CryptoNotifications,
) : ViewModel() {

    data class UiState(
        val treeUri: String? = null,
        val entries: List<LocalSkeStore.LocalEntry> = emptyList(),
        val displayNames: Map<String, String> = emptyMap(),
        val isLoading: Boolean = false,
        val busy: Boolean = false,
        val progress: TaskProgress? = null,
        val error: String? = null,
    )

    data class TaskProgress(
        val title: String,
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

    private var cachedNameKey: ByteArray? = null

    init {
        localVault.addOnLockListener {
            cachedNameKey = null
            _state.update {
                it.copy(entries = emptyList(), displayNames = emptyMap(), error = null)
            }
        }

        viewModelScope.launch {
            _rememberDefault.value = settings.localRememberPassword.first()

            val stored = settings.localTreeUri.first()
            if (!stored.isNullOrEmpty()) {
                _state.update { it.copy(treeUri = stored) }
                // Listing works without the password; only file *names* need it.
                refresh()
            }
        }

        // Warm the name key in the background so the first scan does not pay the
        // PBKDF2 cost while the user is looking at the screen.
        if (localVault.isUnlocked) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { nameKey() }
            }
        }
    }

    /** Unlock the standalone local password, optionally remembering it. */
    suspend fun unlock(password: String, remember: Boolean) {
        localVault.unlock(password)
        settings.setLocalRememberPassword(remember)
        settings.setLocalSealedPassword(if (remember) keyStore.seal(password) else null)
        refresh()
    }

    /**
     * The deterministic file-name key for the standalone local password.
     *
     * Derived on a background thread and shared process-wide, so it survives
     * tab switches without repeating the expensive PBKDF2 run.
     */
    private suspend fun nameKey(): ByteArray =
        cachedNameKey ?: NameKeyCache.get(localVault.requirePassword()).also { cachedNameKey = it }

    fun onTreeSelected(uri: Uri) {
        viewModelScope.launch {
            settings.setLocalTreeUri(uri.toString())
            _state.update { it.copy(treeUri = uri.toString()) }
            refresh()
        }
    }

    /** Called once the local vault becomes unlocked, to populate a chosen folder. */
    fun onUnlocked() = refresh()

    fun refresh() {
        val tree = _state.value.treeUri ?: return
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            runCatching {
                val entries = store.list(Uri.parse(tree))
                // Without the password we can still list files, just not decrypt
                // their names — the UI falls back to a placeholder for those.
                val names = if (localVault.isUnlocked) {
                    val key = nameKey()
                    entries.associate { entry ->
                        entry.uri.toString() to
                            if (entry.isSke) runner.plainNameOf(entry.name, key) else entry.name
                    }
                } else {
                    emptyMap()
                }
                entries to names
            }.onSuccess { (entries, names) ->
                _state.update {
                    it.copy(entries = entries, displayNames = names, isLoading = false, error = null)
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(isLoading = false, error = throwable.message ?: "无法读取目录")
                }
            }
        }
    }

    /** Decrypt `.ske` documents into the currently browsed directory. */
    fun decrypt(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val tree = _state.value.treeUri
        if (tree == null) {
            emit("请先选择一个目录")
            return
        }
        if (!localVault.isUnlocked) {
            emit("请先输入本地密码")
            return
        }

        startJob("正在解密", CryptoNotifications.ID_DECRYPT) { password, onProgress ->
            runner.decrypt(uris, Uri.parse(tree), password, onProgress)
        }
    }

    private fun startJob(
        title: String,
        notificationId: Int,
        block: suspend (String, (CryptoTaskRunner.Progress) -> Unit) -> List<Uri>,
    ) {
        val password = localVault.password
        if (password.isNullOrEmpty()) {
            emit("请先输入本地密码")
            return
        }

        _state.update { it.copy(busy = true, progress = null) }

        viewModelScope.launch {
            runCatching {
                block(password) { progress ->
                    _state.update {
                        it.copy(
                            progress = TaskProgress(
                                title = title,
                                fileName = progress.fileName,
                                index = progress.index,
                                count = progress.count,
                                fraction = progress.fraction,
                            )
                        )
                    }
                    notifications.progress(
                        id = notificationId,
                        title = title,
                        text = "${progress.fileName} (${progress.index}/${progress.count})",
                        percent = (progress.fraction * 100).toInt(),
                    )
                }
            }.onSuccess { created ->
                notifications.finished(notificationId, "${title}完成", "共处理 ${created.size} 个文件")
                _state.update { it.copy(busy = false, progress = null) }
                emit("${title}完成，共 ${created.size} 个文件")
                refresh()
            }.onFailure { throwable ->
                notifications.cancel(notificationId)
                _state.update { it.copy(busy = false, progress = null) }
                emit(throwable.message ?: "${title}失败")
            }
        }
    }

    fun cancelProgressUi() {
        _state.update { it.copy(busy = false, progress = null) }
    }

    fun displayNameOf(entry: LocalSkeStore.LocalEntry): String =
        _state.value.displayNames[entry.uri.toString()] ?: entry.name

    private fun emit(text: String) = viewModelScope.launch { _messages.send(text) }
}
