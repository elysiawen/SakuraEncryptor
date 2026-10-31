package com.sakura.encryptor.core.remote

import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.alist.DecryptedEntry
import com.sakura.encryptor.core.alist.FileEntry
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.openlist.OpenListApi
import com.sakura.encryptor.core.util.DisplayNameOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import java.io.File

/**
 * The active cloud backend.
 *
 * Both API clients share one [RemoteSession]: only one account is signed in at
 * a time, so the dispatcher remembers which provider is active and forwards
 * each call. It contains no transport or decryption logic of its own beyond
 * turning encrypted names into display names.
 */
class RemoteRepository(
    private val session: RemoteSession,
    private val aListApi: AListApi,
    private val openListApi: OpenListApi,
) {

    @Volatile
    var provider: RemoteProvider = RemoteProvider.ALIST
        private set

    private val _isLoggedIn = MutableStateFlow(false)

    /** Observable login state for the UI. */
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _serverFlow = MutableStateFlow("")

    /** Observable server address, so the UI can label the account. */
    val serverFlow: StateFlow<String> = _serverFlow.asStateFlow()

    private val activeApi: RemoteClient
        get() = when (provider) {
            RemoteProvider.ALIST -> aListApi
            RemoteProvider.OPENLIST -> openListApi
        }

    val isLoggedIn: Boolean get() = session.isLoggedIn

    val server: String get() = session.server

    fun absolutePath(path: String): String = session.absolutePath(path)

    /**
     * Sign in to [provider], which then becomes the active backend.
     *
     * Every attempt starts from a clean slate so a failed switch cannot leave
     * the previous account's token behind.
     */
    suspend fun login(
        provider: RemoteProvider,
        server: String,
        username: String,
        password: String,
        manualBasePath: String,
        otpCode: String = "",
    ): Result<Unit> {
        session.clear()

        val api = when (provider) {
            RemoteProvider.ALIST -> aListApi
            RemoteProvider.OPENLIST -> openListApi
        }

        val result = runCatching {
            val token = api.login(server, username, password, otpCode)
            val profile = api.me(server, token)

            val detectedBasePath = profile?.basePath
                ?.takeIf { it.isNotBlank() && it != "/" }
                .orEmpty()

            val permissionPaths = profile?.permissions
                ?.mapNotNull { it.path }
                ?.filter { it.isNotEmpty() && it != "/" }
                .orEmpty()

            session.configure(
                server = server,
                username = username,
                token = token,
                manualBasePath = manualBasePath,
                detectedBasePath = detectedBasePath,
                permissionPaths = permissionPaths,
            )
        }

        if (result.isSuccess) {
            this.provider = provider
            _isLoggedIn.value = true
            _serverFlow.value = session.server
        } else {
            _isLoggedIn.value = false
            _serverFlow.value = ""
        }
        return result
    }

    /** Drop the session without touching the saved profiles. */
    fun clear() {
        session.clear()
        _isLoggedIn.value = false
        _serverFlow.value = ""
    }

    /** List [path] with names decrypted using [nameKey]. */
    suspend fun list(path: String, nameKey: ByteArray, refresh: Boolean = false): List<DecryptedEntry> {
        val entries = if (session.isVirtualRoot(path)) {
            session.virtualRootEntries().map { decryptEntry(it, nameKey) }
        } else {
            val data = activeApi.list(session.server, session.token, session.absolutePath(path), refresh)
            (data.content ?: emptyList()).map { decryptEntry(it, nameKey) }
        }

        // The raw names are opaque tokens, so ordering them would look random.
        // Sort after decryption instead: folders first, then natural order.
        return entries.sortedWith(
            compareByDescending<DecryptedEntry> { it.isDir }
                .thenBy(DisplayNameOrder) { it.displayName },
        )
    }

    /** Fetch the direct URL and ciphertext size for a remote `.ske` file. */
    suspend fun resolveRemote(remotePath: String): Pair<String, Long> =
        activeApi.resolveRawUrl(session.server, session.token, session.absolutePath(remotePath))

    /**
     * Create a folder.
     *
     * @param encryptName true to store the name as a deterministic token,
     *   false to store it in plain text (readable by any other client).
     */
    suspend fun makeDirectory(
        parentPath: String,
        plainName: String,
        nameKey: ByteArray,
        encryptName: Boolean = true,
    ): Result<Unit> = runCatching {
        val segment = if (encryptName) SkeCrypto.encryptName(plainName, nameKey) else plainName
        val parent = session.absolutePath(parentPath).trimEnd('/')
        activeApi.mkdir(session.server, session.token, "$parent/$segment")
    }

    /**
     * Rename an entry (file or folder).
     *
     * Both names are the *remote* names: callers pass the current encrypted
     * token and the already-encoded replacement.
     */
    suspend fun rename(
        parentPath: String,
        currentRemoteName: String,
        newRemoteName: String,
    ): Result<Unit> = runCatching {
        val parent = session.absolutePath(parentPath).trimEnd('/')
        activeApi.rename(session.server, session.token, "$parent/$currentRemoteName", newRemoteName)
    }

    suspend fun delete(parentPath: String, encryptedNames: List<String>): Result<Unit> = runCatching {
        activeApi.remove(session.server, session.token, session.absolutePath(parentPath), encryptedNames)
    }

    /**
     * Upload [file] into a cloud directory, naming it [fileName] remotely.
     *
     * The caller owns the payload: an encrypted `.ske` container and a plain
     * file both go through here.
     */
    suspend fun upload(
        parentPath: String,
        fileName: String,
        file: File,
        onProgress: ((bytesWritten: Long, contentLength: Long) -> Unit)? = null,
        onCall: ((Call) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        activeApi.upload(
            server = session.server,
            token = session.token,
            dirPath = session.absolutePath(parentPath),
            fileName = fileName,
            file = file,
            onProgress = onProgress,
            onCall = onCall,
        )
    }

    /** Decrypt a single listing entry's name, tolerating non-encrypted entries. */
    fun decryptEntry(entry: FileEntry, nameKey: ByteArray): DecryptedEntry {
        val raw = entry.name
        val isContainer = raw.endsWith(SkeFormat.SKE_EXT, ignoreCase = true)
        val token = if (isContainer) raw.dropLast(SkeFormat.SKE_EXT.length) else raw

        return try {
            DecryptedEntry(entry, SkeCrypto.decryptName(token, nameKey), true)
        } catch (_: Exception) {
            DecryptedEntry(entry, raw, false)
        }
    }
}
