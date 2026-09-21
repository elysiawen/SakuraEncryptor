package com.sakura.encryptor.core.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.sakura.encryptor.core.alist.AListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Scheme carried by a queue entry whose real address is not known yet. */
private const val QUEUE_SCHEME = "ske-queue"

/**
 * Wraps a playlist locator in a uri ExoPlayer can hold before the address is
 * known.
 *
 * Resolving a cloud playlist up front would cost one AList request per song
 * just to open the player. Instead every cloud entry goes into the queue as a
 * placeholder, and [QueueUriResolver] swaps in the direct url at the moment
 * that entry actually starts playing.
 *
 * The placeholder matters for more than laziness: ExoPlayer has to *see* the
 * whole queue for the media notification to offer next/previous at all, so the
 * items must exist long before their addresses do.
 */
fun queueUriFor(locator: String): Uri = Uri.Builder()
    .scheme(QUEUE_SCHEME)
    .path(locator)
    .build()

/**
 * Swaps `ske-queue://` placeholders for real, playable uris.
 *
 * Resolution runs on ExoPlayer's loading thread, which exists precisely to do
 * this kind of blocking IO, so the lookup is synchronous there. Results are
 * cached per locator because ExoPlayer re-resolves on every seek and range
 * request — without the cache a single track would fire a burst of identical
 * requests.
 */
class QueueUriResolver(
    private val aListRepository: AListRepository,
    private val cipherBlockSourceFactory: CipherBlockSourceFactory,
) : ResolvingDataSource.Resolver {

    private val cache = ConcurrentHashMap<String, Uri>()

    /** The playable uri for [locator], or null when the lookup failed. */
    suspend fun resolve(locator: String): Uri? = withContext(Dispatchers.IO) {
        cache[locator]?.let { return@withContext it }
        runCatching {
            val (url, size) = aListRepository.resolveRemote(locator)
            // Seed the size so the reader can skip range probing.
            cipherBlockSourceFactory.rememberSize(url, size)
            Uri.parse(url).also { cache[locator] = it }
        }.getOrNull()
    }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (!QUEUE_SCHEME.equals(uri.scheme, ignoreCase = true)) return dataSpec

        val locator = uri.path ?: return dataSpec
        val direct = runBlocking { resolve(locator) } ?: return dataSpec
        return dataSpec.withUri(direct)
    }

    /** Drop cached lookups: they carry tokens a password change invalidates. */
    fun invalidate() {
        cache.clear()
    }
}
