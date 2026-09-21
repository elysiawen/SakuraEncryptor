package com.sakura.encryptor.core.subtitle

import android.content.Context
import android.net.Uri
import com.sakura.encryptor.core.alist.AListRepository
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.local.LocalSkeStore
import com.sakura.encryptor.core.player.CipherBlockSourceFactory
import com.sakura.encryptor.core.player.DecryptedBytesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds and loads side-loaded subtitles for a video.
 *
 * Mirrors the web client: list the video's folder, decrypt every file name,
 * keep the ones that match the video's base name, then fetch + decode the text.
 * Encrypted subtitles go through the same decrypting reader as everything else,
 * so nothing here needs to care whether they are `.ske` or plain.
 */
class SubtitleRepository(
    private val context: Context,
    private val aListRepository: AListRepository,
    private val localSkeStore: LocalSkeStore,
    private val cipherBlockSourceFactory: CipherBlockSourceFactory,
    private val bytesReader: DecryptedBytesReader,
) {

    /** Scan a cloud folder for subtitles belonging to [videoDisplayName]. */
    suspend fun scanCloud(
        dirPath: String,
        videoDisplayName: String,
        nameKey: ByteArray,
    ): List<SubtitleTrack> = runCatching {
        val entries = aListRepository.list(dirPath, nameKey, refresh = false)
        val candidates = entries
            .filter { !it.isDir }
            .map { it.raw.name to it.displayName }
        SubtitleMatcher.match(videoDisplayName, candidates, dirPath, fromCloud = true)
    }.getOrDefault(emptyList())

    /** Scan a SAF folder for subtitles belonging to [videoDisplayName]. */
    suspend fun scanLocal(
        treeUri: Uri,
        videoDisplayName: String,
        nameKey: ByteArray,
    ): List<SubtitleTrack> = runCatching {
        val entries = localSkeStore.list(treeUri)
        val candidates = entries.map { it.name to decryptedLocalName(it.name, nameKey) }
        SubtitleMatcher.match(
            videoDisplayName = videoDisplayName,
            candidates = candidates,
            location = treeUri.toString(),
            fromCloud = false,
        )
    }.getOrDefault(emptyList())

    /**
     * Fetch, decode and write [track] to a cache file so Media3 can read it.
     * @return the file, or null when the subtitle could not be loaded.
     */
    suspend fun materialize(track: SubtitleTrack, password: String?): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = resolveUri(track) ?: return@runCatching null
                val bytes = bytesReader.readAll(uri, password)
                if (bytes.isEmpty()) return@runCatching null

                val text = SubtitleDecoder.decode(bytes)
                if (text.isBlank()) return@runCatching null

                // SubRip is parsed natively by Media3; only VTT needs the header.
                val content = if (track.extension == "vtt") SubtitleDecoder.ensureVtt(text) else text

                subtitleCacheFile(track).apply { writeText(content, Charsets.UTF_8) }
            }.getOrNull()
        }

    /** Drop cached subtitle files; call when leaving the player. */
    fun clearCache() {
        runCatching {
            subtitleDir().listFiles()?.forEach { it.delete() }
        }
    }

    private suspend fun resolveUri(track: SubtitleTrack): Uri? = if (track.fromCloud) {
        val remotePath = "${track.location.trimEnd('/')}/${track.fileName}"
        val (url, size) = aListRepository.resolveRemote(remotePath)
        cipherBlockSourceFactory.rememberSize(url, size)
        Uri.parse(url)
    } else {
        localSkeStore.findInTree(Uri.parse(track.location), track.fileName)
    }

    private fun subtitleDir(): File =
        File(context.cacheDir, "subtitles").apply { mkdirs() }

    private fun subtitleCacheFile(track: SubtitleTrack): File =
        File(subtitleDir(), "${track.fileName.hashCode()}.${track.extension}")

    /** Decrypt a stored local file name, tolerating plain names. */
    private fun decryptedLocalName(name: String, nameKey: ByteArray): String {
        if (!name.endsWith(SkeFormat.SKE_EXT, ignoreCase = true)) return name
        val base = name.dropLast(SkeFormat.SKE_EXT.length)
        return runCatching { SkeCrypto.decryptName(base, nameKey) }.getOrDefault(base)
    }
}
