package com.sakura.encryptor.core.alist

import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import java.io.File


/**
 * Bridges [AListApi] and [AListSession] with name decryption.
 *
 * Cloud entries carry *encrypted* names, so every listing is decrypted on the
 * way out to rebuild the original tree. Sub-account roots are synthesized from
 * the permission list, exactly like the web client.
 */
class AListRepository(
    private val api: AListApi,
    private val session: AListSession,
) {

    suspend fun login(
        server: String,
        username: String,
        password: String,
        manualBasePath: String,
    ): Result<Unit> = runCatching {
        val token = api.login(server, username, password)
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

    /** List [path] with names decrypted using [nameKey]. */
    suspend fun list(path: String, nameKey: ByteArray, refresh: Boolean = false): List<DecryptedEntry> {
        if (session.isVirtualRoot(path)) {
            return session.virtualRootEntries().map { decryptEntry(it, nameKey) }
        }
        val data = api.list(session.server, session.token, session.absolutePath(path), refresh)
        return (data.content ?: emptyList())
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
            .map { decryptEntry(it, nameKey) }
    }

    /** Fetch the direct URL and ciphertext size for a remote `.ske` file. */
    suspend fun resolveRemote(remotePath: String): Pair<String, Long> =
        api.resolveRawUrl(session.server, session.token, session.absolutePath(remotePath))

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
        api.mkdir(session.server, session.token, "$parent/$segment")
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
        api.rename(session.server, session.token, "$parent/$currentRemoteName", newRemoteName)
    }

    suspend fun delete(parentPath: String, encryptedNames: List<String>): Result<Unit> = runCatching {
        api.remove(session.server, session.token, session.absolutePath(parentPath), encryptedNames)
    }

    /** Upload a local (already encrypted) file into a cloud directory. */
    suspend fun uploadEncrypted(
        parentPath: String,
        encryptedFileName: String,
        file: File,
    ): Result<Unit> = runCatching {
        api.upload(
            server = session.server,
            token = session.token,
            dirPath = session.absolutePath(parentPath),
            fileName = encryptedFileName,
            file = file,
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
