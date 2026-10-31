package com.sakura.encryptor.ui.screens.browse

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.sakura.encryptor.core.alist.AListRepository
import com.sakura.encryptor.core.alist.AListSession
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
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.session.VaultSession
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
    private val repository: AListRepository,
    private val session: AListSession,
    private val vault: VaultSession,
    private val downloadManager: DownloadManager,
    private val runner: CryptoTaskRunner,
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
        /** True while an upload job runs, so the picker cannot be re-entered. */
        val isUploading: Boolean = false,
        /** Non-null while the upload progress dialog should be on screen. */
        val uploadProgress: UploadProgress? = null,
    ) {
        val inSelectionMode: Boolean get() = selected.isNotEmpty()
    }

    data class UploadProgress(
        val fileName: String,
        val index: Int,
        val count: Int,
        /** Progress of the whole job, not just the current file. */
        val fraction: Float,
        /** What the current file is doing right now, e.g. 加密中 / 上传中. */
        val stage: String = "",
    )

    sealed interface Event {
        data class OpenPlayer(val uri: String, val name: String, val source: PlaybackSource) : Event
        data class Message(val text: String) : Event
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

    /** Reconnect the active profile using its stored AList credentials. */
    fun connectActive() {
        viewModelScope.launch {
            profileRepository.connectActive()
                .onFailure { send(Event.Message(it.message ?: "连接失败")) }
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

        // Initial loading is driven by the UI, which observes unlock/login state.
    }

    /**
     * Reset the listing whenever the unlock / login state changes.
     * Called from the UI so the screen reflects "locked" and "not connected".
     */
    fun syncSession() {
        if (!vault.isUnlocked || !session.isLoggedIn) {
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
        if (!vault.isUnlocked || !session.isLoggedIn) {
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
     * Upload picked local [uris] into the current directory.
     *
     * Mirrors the web client: with [encrypt] both the bytes and the file name are
     * converted into vault format (`.ske`), so the result is only readable inside
     * the app; otherwise the file goes up as-is under its own name. [names] holds
     * the user-edited name for each uri; blank or missing entries fall back to the
     * picked document's own name.
     */
    fun upload(uris: List<Uri>, encrypt: Boolean, names: List<String> = emptyList()) {
        if (uris.isEmpty()) return
        if (_state.value.isUploading) {
            send(Event.Message("已有上传任务正在进行"))
            return
        }

        // The AList form API cannot address a drive root, and a sub-account's
        // virtual root is not a real directory either.
        val target = session.absolutePath(_state.value.path)
        if (target.isBlank() || target == "/") {
            send(Event.Message("请先进入一个具体目录再上传"))
            return
        }
        if (encrypt && !vault.isUnlocked) {
            send(Event.Message("缺少加密主密码，无法加密上传"))
            return
        }

        _state.update { it.copy(isUploading = true, uploadProgress = null) }

        viewModelScope.launch {
            val total = uris.size
            var uploaded = 0
            // Progress callbacks fire once per buffer — thousands of times for a
            // big file — so only publish when the rounded percentage or the stage
            // changes. Otherwise every chunk would recompose the dialog.
            var lastBucket = -1
            var lastStage = ""
            fun publish(fileName: String, position: Int, fileFraction: Float, stage: String) {
                val overall = ((position - 1) + fileFraction.coerceIn(0f, 1f)) / total
                val bucket = (overall * 100).toInt()
                if (bucket == lastBucket && stage == lastStage) return
                lastBucket = bucket
                lastStage = stage
                _state.update {
                    it.copy(uploadProgress = UploadProgress(fileName, position, total, overall, stage))
                }
            }

            try {
                uris.forEachIndexed { index, uri ->
                    val name = names.getOrNull(index)?.trim().orEmpty().ifEmpty {
                        runner.displayNameOf(uri) ?: "file_${System.currentTimeMillis()}"
                    }
                    val position = index + 1
                    publish(name, position, 0f, "准备中")

                    val remoteName = if (encrypt) {
                        SkeCrypto.encryptName(name, nameKey()) + SkeFormat.SKE_EXT
                    } else {
                        // A separator typed into the name field would become a
                        // sub-path instead of part of the file name.
                        name.replace('/', '_').replace('\\', '_')
                    }

                    val payload = if (encrypt) {
                        runner.encryptToCache(uri, vault.requirePassword()) { bytesDone, bytesTotal ->
                            val fraction = if (bytesTotal <= 0) {
                                0f
                            } else {
                                (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
                            }
                            // Encryption and upload both take real time, so each
                            // file's slot is split between the two stages.
                            publish(name, position, fraction * 0.5f, "加密中")
                        }
                    } else {
                        runner.copyToCache(uri)
                    }

                    // The payload is always a cache temp file we own, so it goes
                    // away whether or not the request succeeded.
                    try {
                        repository.upload(_state.value.path, remoteName, payload) { sent, length ->
                            val fraction = if (length <= 0) {
                                0f
                            } else {
                                (sent.toFloat() / length).coerceIn(0f, 1f)
                            }
                            val fileFraction = if (encrypt) 0.5f + fraction * 0.5f else fraction
                            publish(name, position, fileFraction, "上传中")
                        }.getOrThrow()
                    } finally {
                        payload.delete()
                    }
                    uploaded++
                    publish(name, position, 1f, "已上传")
                }

                send(Event.Message("已上传 $uploaded 个文件"))
                refresh()
            } catch (throwable: Throwable) {
                send(Event.Message(throwable.message ?: "上传失败"))
            } finally {
                _state.update { it.copy(isUploading = false, uploadProgress = null) }
            }
        }
    }

    /** Hide the progress dialog; the job keeps running in the background. */
    fun dismissUploadUi() = _state.update { it.copy(uploadProgress = null) }

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
