package com.sakura.encryptor.core.playlist

import android.net.Uri
import com.sakura.encryptor.core.alist.AListRepository
import com.sakura.encryptor.core.crypto.SkeCrypto
import com.sakura.encryptor.core.crypto.SkeFormat
import com.sakura.encryptor.core.local.LocalSkeStore
import com.sakura.encryptor.core.player.CipherBlockSourceFactory
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** One playable entry of the queue. */
data class PlaylistItem(
    /**
     * Where the media lives: a remote path for cloud entries, a SAF document
     * uri for local ones. Resolve it with [PlaylistRepository.resolveUri]
     * before handing anything to the player.
     */
    val locator: String,
    val displayName: String,
)

/**
 * Builds the "play the rest of this folder" queue.
 *
 * Mirrors `usePlaylist` from the web client: list the current file's folder,
 * decrypt every name, keep the entries of the same media kind and order them by
 * display name using a Chinese collator. Unlike the web client this also works
 * for local folders, which is a first-class scenario on Android.
 */
class PlaylistRepository(
    private val aListRepository: AListRepository,
    private val localSkeStore: LocalSkeStore,
    private val cipherBlockSourceFactory: CipherBlockSourceFactory,
) {

    /** @return the sibling media items, or an empty list when unavailable. */
    suspend fun load(
        directory: String,
        fromCloud: Boolean,
        kind: MediaKind,
        nameKey: ByteArray?,
    ): List<PlaylistItem> = withContext(Dispatchers.IO) {
        if (directory.isBlank() || nameKey == null) return@withContext emptyList()

        runCatching {
            val candidates = if (fromCloud) {
                aListRepository.list(directory, nameKey)
                    .filterNot { it.isDir }
                    .map { entry ->
                        PlaylistItem(
                            locator = "${directory.trimEnd('/')}/${entry.raw.name}",
                            displayName = entry.displayName,
                        )
                    }
            } else {
                localSkeStore.list(Uri.parse(directory))
                    .map { entry ->
                        PlaylistItem(entry.uri.toString(), plainNameOf(entry.name, nameKey))
                    }
            }

            // A collator is not thread-safe, so keep it local to this call.
            val collator = Collator.getInstance(Locale.CHINA)
            candidates
                .filter { MediaTypes.kindOf(it.displayName) == kind }
                .sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        }.getOrDefault(emptyList())
    }

    /**
     * Turn an item into something ExoPlayer can read.
     *
     * Cloud entries need a `raw_url` (and its size seeded into the block cache
     * so range probing is skipped); local ones are already playable as-is.
     *
     * @return the playable uri, or null when the cloud lookup failed.
     */
    suspend fun resolveUri(item: PlaylistItem, fromCloud: Boolean): String? {
        if (!fromCloud) return item.locator
        return runCatching {
            val (url, size) = aListRepository.resolveRemote(item.locator)
            cipherBlockSourceFactory.rememberSize(url, size)
            url
        }.getOrNull()
    }

    /** Reverse an encrypted local file name, falling back to the raw name. */
    private fun plainNameOf(encryptedName: String, nameKey: ByteArray): String {
        if (!encryptedName.endsWith(SkeFormat.SKE_EXT, ignoreCase = true)) return encryptedName
        val withoutExtension = encryptedName.dropLast(SkeFormat.SKE_EXT.length)
        return runCatching { SkeCrypto.decryptName(withoutExtension, nameKey) }
            .getOrDefault(withoutExtension)
    }
}
