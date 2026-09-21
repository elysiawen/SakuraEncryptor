package com.sakura.encryptor.core.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Browses the SAF tree the user granted access to, exposing the media files
 * found there (encrypted `.ske` containers as well as plain files).
 */
class LocalSkeStore(private val context: Context) {

    data class LocalEntry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
    ) {
        val isSke: Boolean get() = name.endsWith(".ske", ignoreCase = true)
    }

    /** List immediate children of [treeUri]; returns an empty list if unreadable. */
    suspend fun list(treeUri: Uri): List<LocalEntry> = withContext(Dispatchers.IO) {
        val tree = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
            ?: return@withContext emptyList()

        tree.listFiles()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                if (document.isDirectory) return@mapNotNull null
                LocalEntry(document.uri, name, document.length(), document.lastModified())
            }
            .sortedByDescending { it.lastModified }
    }

    /** Find a file by name inside a tree (used after encrypting). */
    suspend fun findInTree(treeUri: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.findFile(name)?.uri }.getOrNull()
    }
}
