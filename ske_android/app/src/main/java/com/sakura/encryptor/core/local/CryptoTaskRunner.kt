package com.sakura.encryptor.core.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFileCipher
import com.sakura.encryptor.core.crypto.SkeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Runs local encrypt / decrypt jobs against SAF documents.
 *
 * `.ske` requires a rewritable header (its integrity tag covers the whole
 * ciphertext), so work always goes through a seekable file in the app cache
 * and is copied to the destination afterwards.
 */
class CryptoTaskRunner(private val context: Context) {

    data class Progress(
        val fileName: String,
        val index: Int,
        val count: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        val fraction: Float
            get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    /** Encrypt each of [uris] into [outputTree]; returns the created documents. */
    suspend fun encrypt(
        uris: List<Uri>,
        outputTree: Uri,
        password: String,
        onProgress: (Progress) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val nameKey = SkeCrypto.deriveNameKey(password)
        val created = ArrayList<Uri>(uris.size)

        uris.forEachIndexed { index, uri ->
            val displayName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
            onProgress(Progress(displayName, index + 1, uris.size, 0, 0))

            val tempPlain = createTemp("plain")
            val tempEncrypted = createTemp("ske")
            try {
                copyUriToFile(uri, tempPlain)
                SkeFileCipher.encryptFile(tempPlain, tempEncrypted, password) { done, total ->
                    onProgress(Progress(displayName, index + 1, uris.size, done, total))
                }
                val outputName = SkeCrypto.encryptName(displayName, nameKey) + SkeFormat.SKE_EXT
                created += writeToTree(outputTree, outputName, OCTET_STREAM, tempEncrypted)
            } finally {
                tempPlain.delete()
                tempEncrypted.delete()
            }
        }
        created
    }

    /** Decrypt each `.ske` in [uris] into [outputTree]; returns the created documents. */
    suspend fun decrypt(
        uris: List<Uri>,
        outputTree: Uri,
        password: String,
        onProgress: (Progress) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val nameKey = SkeCrypto.deriveNameKey(password)
        val created = ArrayList<Uri>(uris.size)

        uris.forEachIndexed { index, uri ->
            val rawName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}.ske"
            onProgress(Progress(rawName, index + 1, uris.size, 0, 0))

            val tempEncrypted = createTemp("ske")
            val tempPlain = createTemp("out")
            try {
                copyUriToFile(uri, tempEncrypted)
                SkeFileCipher.decryptFile(tempEncrypted, tempPlain, password) { done, total ->
                    onProgress(Progress(rawName, index + 1, uris.size, done, total))
                }
                val outputName = plainNameOf(rawName, nameKey)
                created += writeToTree(outputTree, outputName, OCTET_STREAM, tempPlain)
            } finally {
                tempEncrypted.delete()
                tempPlain.delete()
            }
        }
        created
    }

    /** Reverse the encrypted file name, falling back to the raw name. */
    fun plainNameOf(encryptedName: String, nameKey: ByteArray): String {
        if (!encryptedName.endsWith(SkeFormat.SKE_EXT, ignoreCase = true)) return encryptedName
        val withoutExtension = encryptedName.dropLast(SkeFormat.SKE_EXT.length)
        return runCatching { SkeCrypto.decryptName(withoutExtension, nameKey) }.getOrDefault(withoutExtension)
    }

    /** Encrypt a single local file into the app cache; used before uploading. */
    suspend fun encryptToCache(uri: Uri, password: String, onProgress: ((Long, Long) -> Unit)? = null): File =
        withContext(Dispatchers.IO) {
            val tempPlain = createTemp("plain")
            val tempEncrypted = createTemp("ske")
            copyUriToFile(uri, tempPlain)
            SkeFileCipher.encryptFile(tempPlain, tempEncrypted, password) { done, total ->
                onProgress?.invoke(done, total)
            }
            tempPlain.delete()
            tempEncrypted
        }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun createTemp(suffix: String): File =
        File.createTempFile("ske_", ".$suffix", context.cacheDir)

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        input.use { source ->
            target.outputStream().use { out -> source.copyTo(out, BUFFER_SIZE) }
        }
    }

    private fun writeToTree(treeUri: Uri, name: String, mime: String, source: File): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法访问输出目录，请重新选择")

        val existing = tree.findFile(name)
        val document = existing ?: tree.createFile(mime, name)
        ?: throw IOException("无法在输出目录创建 $name")

        context.contentResolver.openOutputStream(document.uri, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out, BUFFER_SIZE) }
        } ?: throw IOException("无法写入 $name")

        return document.uri
    }

    private fun queryDisplayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) return cursor.getString(0)
                }
        }
        return DocumentFile.fromSingleUri(context, uri)?.name
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val OCTET_STREAM = "application/octet-stream"
    }
}
