package com.sakura.encryptor.core.alist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The active AList session — optional.
 *
 * Connecting to a server is a feature, not a prerequisite: the app boots
 * straight into the main UI and this session simply stays empty until the user
 * chooses to sign in.
 *
 * Reproduces the path rules of `useAList.js`:
 *  - a *manually* configured base path is prepended to every request path;
 *  - an *auto-detected* base path is **not** prepended, because for
 *    sub-accounts AList already scopes the API to that root, so a virtual
 *    root is rebuilt from the permissions list instead.
 */
class AListSession {

    @Volatile
    var server: String = ""
        private set

    @Volatile
    var token: String = ""
        private set

    @Volatile
    var username: String = ""
        private set

    /** Manual override entered on the login form. */
    @Volatile
    var manualBasePath: String = ""
        private set

    /** Detected from `/api/me`. */
    @Volatile
    var detectedBasePath: String = ""
        private set

    private val _permissionPaths = CopyOnWriteArrayList<String>()
    val permissionPaths: List<String> get() = _permissionPaths.toList()

    private val _isLoggedIn = MutableStateFlow(false)

    /** Observable login state for the UI. */
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _serverFlow = MutableStateFlow("")

    /** Observable server address, so the UI can label the account. */
    val serverFlow: StateFlow<String> = _serverFlow.asStateFlow()

    val isLoggedIn: Boolean get() = server.isNotEmpty() && token.isNotEmpty()

    fun configure(
        server: String,
        username: String,
        token: String,
        manualBasePath: String,
        detectedBasePath: String,
        permissionPaths: List<String>,
    ) {
        this.server = server.trimEnd('/')
        this.username = username
        this.token = token
        this.manualBasePath = normalizeBase(manualBasePath)
        this.detectedBasePath = normalizeBase(detectedBasePath)
        _permissionPaths.clear()
        _permissionPaths.addAll(permissionPaths.filter { it.isNotEmpty() && it != "/" })

        _serverFlow.value = this.server
        _isLoggedIn.value = true
    }

    fun clear() {
        server = ""
        token = ""
        username = ""
        manualBasePath = ""
        detectedBasePath = ""
        _permissionPaths.clear()
        _serverFlow.value = ""
        _isLoggedIn.value = false
    }

    private fun normalizeBase(raw: String): String {
        if (raw.isBlank()) return ""
        val withSlash = if (raw.startsWith("/")) raw else "/$raw"
        return withSlash.trimEnd('/')
    }

    /** Apply the manual base path, if any, to a virtual path. */
    fun absolutePath(path: String): String {
        if (manualBasePath.isEmpty()) return path
        if (path.startsWith(manualBasePath)) return path
        val normalized = if (path.startsWith("/")) path else "/$path"
        return (manualBasePath + normalized).trimEnd('/').ifEmpty { "/" }
    }

    /** True when [path] must be served from the synthesized permission listing. */
    fun isVirtualRoot(path: String): Boolean =
        path == "/" && manualBasePath.isEmpty() && detectedBasePath.isNotEmpty() && _permissionPaths.isNotEmpty()

    /** Build the virtual root listing (deduplicated top-level folder names). */
    fun virtualRootEntries(): List<FileEntry> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<FileEntry>()
        for (permissionPath in _permissionPaths) {
            val name = permissionPath.trimStart('/').split('/').firstOrNull() ?: continue
            if (name.isEmpty() || !seen.add(name)) continue
            result.add(FileEntry(name = name, size = 0, isDir = true, modified = ""))
        }
        return result
    }
}
