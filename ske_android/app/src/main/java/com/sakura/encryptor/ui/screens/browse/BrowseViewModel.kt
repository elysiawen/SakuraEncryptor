package com.sakura.encryptor.ui.screens.browse

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.sakura.encryptor.core.alist.DecryptedEntry
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.download.DownloadManager
import com.sakura.encryptor.core.local.CryptoTaskRunner
import com.sakura.encryptor.core.media.PreviewLoader
import com.sakura.encryptor.core.player.CipherBlockSourceFactory
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.remote.RemoteException
import com.sakura.encryptor.core.remote.RemoteRepository
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.core.upload.UploadManager
import com.sakura.encryptor.data.db.ProfileEntity
import com.sakura.encryptor.ui.navigation.PlaybackSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowseViewModel(
    private val repository: RemoteRepository,
    private val vault: VaultSession,
    private val downloadManager: DownloadManager,
    private val runner: CryptoTaskRunner,
    private val uploadManager: UploadManager,
    private val profileRepository: ProfileRepository,
    private val cipherBlockSourceFactory: CipherBlockSourceFactory,
    private val previews: PreviewLoader,
) : ViewModel() {

    data class UiState(
        val path: String = "/",
        val entries: List<DecryptedEntry> = emptyList(),
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val selected: Set<String> = emptySet(),
        /** True while a `.ske` entry is being resolved to a playable url. */
        val isPreparing: Boolean = false,
    ) {
        val inSelectionMode: Boolean get() = selected.isNotEmpty()
    }

    sealed interface Event {
        data class OpenPlayer(val uri: String, val name: String, val source: PlaybackSource) : Event
        data class Message(val text: String) : Event

        /** The account asked for a one-time password before it will sign in. */
        data object NeedOtp : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Breadcrumb labels derived from the encrypted path. */
    private val _breadcrumbs = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val breadcrumbs: StateFlow<List<Pair<String, String>>> = _breadcrumbs.asStateFlow()

    private var cachedNameKey: ByteArray? = null

    /** Remote path to already-resolved raw url, so a tile resolves once. */
    private val previewUrls = HashMap<String, String>()

    /**
     * Grid preview of [entry], or `null` for folders and anything the storage
     * cannot preview.
     *
     * A `.ske` container carries the preview in its encrypted envelope: resolving
     * the raw url and reading two small ranges is all it takes. A plain file has
     * no envelope, so it uses the thumbnail AList already put in the listing —
     * one small download instead of the original. Neither path downloads the
     * media itself for a tile.
     */
    suspend fun previewOf(entry: DecryptedEntry): Bitmap? {
        if (entry.isDir) return null
        val remotePath = "${_state.value.path.trimEnd('/')}/${entry.raw.name}"

        if (!entry.isSke) {
            // No storage preview means no tile: pulling a full-size photo (or
            // grabbing a remote frame) for a 320 px square is not worth it.
            val thumb = entry.remoteThumb ?: return null
            return previews.load(
                identity = remotePath,
                displayName = entry.displayName,
                password = null,
                remoteThumbUrl = thumb,
            ) {
                resolvePreviewUri(remotePath)
            }
        }

        // Keyed on the vault path, not on the signed raw url, so the cache
        // survives a restart; when it hits, resolveRemote is never called.
        return previews.load(remotePath, entry.displayName, vault.password) {
            resolvePreviewUri(remotePath)
        }
    }

    private suspend fun resolvePreviewUri(remotePath: String): Uri? {
        previewUrls[remotePath]?.let { return Uri.parse(it) }

        val resolved = runCatching { repository.resolveRemote(remotePath) }.getOrNull() ?: return null
        cipherBlockSourceFactory.rememberSize(resolved.first, resolved.second)
        previewUrls[remotePath] = resolved.first
        return Uri.parse(resolved.first)
    }

    // ---- cloud profiles ------------------------------------------------------

    val profiles: StateFlow<List<ProfileEntity>> = profileRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long?> = profileRepository.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the active profile has a stored password (pre-ticks "remember me"). */
    val rememberDefault: StateFlow<Boolean> =
        combine(profiles, activeProfileId) { list, id ->
            list.firstOrNull { it.id == id }?.canAutoUnlock ?: false
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Unlock the active profile; optionally store the typed password. */
    suspend fun unlockActive(password: String, remember: Boolean) =
        profileRepository.unlockActive(password, remember)

    /**
     * Reconnect the active profile using its stored credentials.
     *
     * AList and OpenList accounts with two-factor login reject the first attempt
     * and surface a need for the one-time code, which the screen then collects
     * and passes back here.
     */
    fun connectActive(otpCode: String = "") {
        viewModelScope.launch {
            profileRepository.connectActive(otpCode)
                .onFailure { throwable ->
                    val needsOtp = (throwable as? RemoteException)?.needsOtp == true
                    if (needsOtp) {
                        _events.send(Event.NeedOtp)
                    } else {
                        send(Event.Message(throwable.message ?: "连接失败"))
                    }
                }
        }
    }

    init {
        vault.addOnLockListener {
            cachedNameKey = null
            _state.update {
                it.copy(
                    entries = emptyList(),
                    selected = emptySet(),
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                )
            }
            _breadcrumbs.value = emptyList()
        }

        // Warm the name key in the background so the first folder listing does
        // not pay the PBKDF2 cost while the user is looking at the screen.
        if (vault.isUnlocked) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { nameKey() }
            }
        }

        // Uploads run in the app-level queue, so a finished one may land while
        // the screen is open; re-list to make the new file visible.
        viewModelScope.launch {
            uploadManager.completed.collect { refresh() }
        }

        // Initial loading is driven by the UI, which observes unlock/login state.
    }

    /**
     * Reset the listing whenever the unlock / login state changes.
     * Called from the UI so the screen reflects "locked" and "not connected".
     */
    fun syncSession() {
        if (!vault.isUnlocked || !repository.isLoggedIn) {
            _state.update {
                it.copy(
                    entries = emptyList(),
                    selected = emptySet(),
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                )
            }
            _breadcrumbs.value = emptyList()
            return
        }
        load(_state.value.path.ifBlank { "/" }, initial = true)
    }

    /**
     * The deterministic file-name key for the active password.
     *
     * Derived on a background thread and shared process-wide, so it survives
     * tab switches (which recreate this ViewModel) without repeating the
     * expensive PBKDF2 run.
     */
    private suspend fun nameKey(): ByteArray =
        cachedNameKey ?: NameKeyCache.get(vault.requirePassword()).also { cachedNameKey = it }

    fun load(path: String, refresh: Boolean = false, initial: Boolean = false) {
        // Nothing can be listed without both an unlocked vault and a session.
        if (!vault.isUnlocked || !repository.isLoggedIn) {
            _state.update {
                it.copy(
                    path = path,
                    entries = emptyList(),
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                    selected = emptySet(),
                )
            }
            return
        }

        _state.update {
            it.copy(
                path = path,
                isLoading = initial || it.entries.isEmpty(),
                isRefreshing = !initial && it.entries.isNotEmpty(),
                error = null,
                selected = emptySet(),
            )
        }

        viewModelScope.launch {
            // Path components decrypt locally, so the breadcrumb (and therefore
            // the screen title) updates without waiting for the listing.
            updateBreadcrumbs(path)

            runCatching { repository.list(path, nameKey(), refresh = refresh) }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(
                            entries = entries,
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = throwable.message ?: "加载失败",
                        )
                    }
                }
        }
    }

    private suspend fun updateBreadcrumbs(path: String) {
        val key = nameKey()
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var accumulated = ""
        val crumbs = ArrayList<Pair<String, String>>(segments.size + 1)
        crumbs += "/" to "根目录"
        for (segment in segments) {
            accumulated = "$accumulated/$segment"
            val label = runCatching { SkeCrypto.decryptName(segment, key) }.getOrDefault(segment)
            crumbs += accumulated to label
        }
        _breadcrumbs.value = crumbs
    }

    fun refresh() = load(_state.value.path, refresh = true)

    /** @return true when navigation was consumed (i.e. we moved one level up). */
    fun goUp(): Boolean {
        val path = _state.value.path
        if (path == "/" || path.isBlank()) return false
        val parent = path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
        load(parent.ifEmpty { "/" })
        return true
    }

    fun openFolder(entry: DecryptedEntry) {
        val child = "${_state.value.path.trimEnd('/')}/${entry.raw.name}"
        load(child)
    }

    fun toggleSelection(encryptedName: String) {
        _state.update { current ->
            val next = current.selected.toMutableSet()
            if (!next.add(encryptedName)) next.remove(encryptedName)
            current.copy(selected = next)
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Resolve a `.ske` entry to a playable URL and emit a navigation event. */
    fun play(entry: DecryptedEntry) {
        if (entry.isDir) {
            openFolder(entry)
            return
        }
        val kind = MediaTypes.kindOf(entry.displayName)
        if (kind == MediaKind.OTHER) {
            send(Event.Message("「${entry.displayName}」不是可播放的媒体文件"))
            return
        }

        _state.update { it.copy(isPreparing = true) }
        viewModelScope.launch {
            val remotePath = "${_state.value.path.trimEnd('/')}/${entry.raw.name}"
            runCatching { repository.resolveRemote(remotePath) }
                .onSuccess { (url, size) ->
                    // Seed the exact size AList reported, so the player and the
                    // image decryptor never have to guess it.
                    cipherBlockSourceFactory.rememberSize(url, size)
                    _state.update { it.copy(isPreparing = false) }
                    _events.send(Event.OpenPlayer(url, entry.displayName, PlaybackSource.CLOUD))
                }
                .onFailure { throwable ->
                    _state.update { it.copy(isPreparing = false) }
                    send(Event.Message(throwable.message ?: "无法获取播放地址"))
                }
        }
    }

    /** Queue a download job for the selection; `.ske` files are decrypted too. */
    fun download(entries: List<DecryptedEntry>) {
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) {
            send(Event.Message("请选择要下载的文件"))
            return
        }
        viewModelScope.launch {
            send(Event.Message("已加入下载队列：${files.size} 个文件"))
            for (entry in files) {
                val remotePath = "${_state.value.path.trimEnd('/')}/${entry.raw.name}"
                val id = downloadManager.enqueue(entry, remotePath)
                downloadManager.start(id)
            }
        }
        clearSelection()
    }

    /** Default remote names for [uris], prefilled in the upload dialog. */
    fun suggestedUploadNames(uris: List<Uri>): List<String> =
        uris.map { runner.displayNameOf(it) ?: "file_${System.currentTimeMillis()}" }

    /**
     * Queue the picked local [uris] into the current directory.
     *
     * The actual work lives in the app-level [UploadManager], so it keeps going
     * when this screen is left. With [encrypt] both the bytes and the name are
     * converted into vault format (`.ske`); otherwise the file goes up as-is.
     * [names] holds the user-edited name for each uri; blanks fall back to the
     * picked document's own name.
     */
    fun upload(uris: List<Uri>, encrypt: Boolean, names: List<String> = emptyList()) {
        if (uris.isEmpty()) return

        // The form API cannot address a drive root, and a sub-account's
        // virtual root is not a real directory either.
        val target = repository.absolutePath(_state.value.path)
        if (target.isBlank() || target == "/") {
            send(Event.Message("请先进入一个具体目录再上传"))
            return
        }

        val created = runCatching {
            uploadManager.enqueue(
                uris = uris,
                names = names,
                encrypt = encrypt,
                plainPath = _state.value.path,
            )
        }.getOrElse { throwable ->
            send(Event.Message(throwable.message ?: "无法加入上传队列"))
            return
        }

        send(Event.Message("已加入上传队列：${created.size} 个文件"))
    }

    fun delete(entries: List<DecryptedEntry>) {
        val names = entries.map { it.raw.name }
        if (names.isEmpty()) return
        viewModelScope.launch {
            repository.delete(_state.value.path, names)
                .onSuccess {
                    send(Event.Message("已删除 ${names.size} 项"))
                    clearSelection()
                    refresh()
                }
                .onFailure { send(Event.Message(it.message ?: "删除失败")) }
        }
    }

    fun createFolder(plainName: String, encryptName: Boolean) {
        val name = plainName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.makeDirectory(_state.value.path, name, nameKey(), encryptName)
                .onSuccess {
                    send(Event.Message("已创建「$name」"))
                    refresh()
                }
                .onFailure { send(Event.Message(it.message ?: "创建文件夹失败")) }
        }
    }

    /**
     * Rename a file or folder. The user types a plain name; the remote name is
     * re-encoded (the `.ske` suffix is preserved for encrypted files).
     */
    fun rename(entry: DecryptedEntry, newPlainName: String, encryptName: Boolean) {
        val name = newPlainName.trim()
        if (name.isEmpty() || name == entry.displayName) return

        viewModelScope.launch {
            val currentRemoteName = entry.raw.name
            val isSkeFile = currentRemoteName.endsWith(SkeFormat.SKE_EXT, ignoreCase = true)
            // The `.ske` suffix is a type marker, so it survives either way.
            val suffix = if (isSkeFile) SkeFormat.SKE_EXT else ""

            val newRemoteName = if (encryptName) {
                SkeCrypto.encryptName(name, nameKey()) + suffix
            } else {
                name + suffix
            }

            repository.rename(_state.value.path, currentRemoteName, newRemoteName)
                .onSuccess {
                    send(Event.Message("已重命名为「$name」"))
                    clearSelection()
                    refresh()
                }
                .onFailure { send(Event.Message(it.message ?: "重命名失败")) }
        }
    }

    private fun send(event: Event) = viewModelScope.launch { _events.send(event) }
}
