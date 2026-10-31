package com.sakura.encryptor

import android.content.Context
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.openlist.OpenListApi
import com.sakura.encryptor.core.remote.RemoteRepository
import com.sakura.encryptor.core.remote.RemoteSession
import com.sakura.encryptor.core.cache.DerivedKeyCache
import com.sakura.encryptor.core.cache.PlaintextBlockCache
import com.sakura.encryptor.core.cache.ThumbnailDiskCache
import com.sakura.encryptor.core.cache.cacheLimitMbToBytes
import com.sakura.encryptor.core.download.DownloadManager
import com.sakura.encryptor.core.local.CryptoNotifications
import com.sakura.encryptor.core.local.CryptoTaskRunner
import com.sakura.encryptor.core.local.LocalSkeStore
import com.sakura.encryptor.core.media.PreviewCache
import com.sakura.encryptor.core.media.PreviewLoader
import com.sakura.encryptor.core.media.ReaderPageCache
import com.sakura.encryptor.core.embedded.EmbeddedServer
import com.sakura.encryptor.core.embedded.EmbeddedServers
import com.sakura.encryptor.core.player.AudioTags
import com.sakura.encryptor.core.player.CipherBlockSourceFactory
import com.sakura.encryptor.core.player.DecryptedBytesReader
import com.sakura.encryptor.core.player.ImageBytesCache
import com.sakura.encryptor.core.player.LyricLine
import com.sakura.encryptor.core.player.QueueUriResolver
import com.sakura.encryptor.core.player.SiblingLyricsLoader
import com.sakura.encryptor.core.player.SkeDataSourceFactory
import com.sakura.encryptor.core.playlist.PlaylistRepository
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.security.AppLockManager
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.core.subtitle.SubtitleRepository
import com.sakura.encryptor.core.upload.UploadManager
import com.sakura.encryptor.data.db.SakuraDatabase
import com.sakura.encryptor.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * The graph is shallow, so an annotated DI framework would add ceremony
 * without buying much. Everything is lazy so cold start stays cheap.
 */
class AppContainer(private val appContext: Context) {

    val applicationContext: Context get() = appContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    // ---- data ---------------------------------------------------------------
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val keyStore: KeyStoreManager by lazy { KeyStoreManager() }

    /**
     * Optional password asked for when the app is opened.
     *
     * Kept apart from [vault] and [localVault] on purpose: those unlock data,
     * this one unlocks the app itself.
     */
    val appLockManager: AppLockManager by lazy { AppLockManager(settings, keyStore, scope) }
    val database: SakuraDatabase by lazy { SakuraDatabase.get(appContext) }

    // ---- cloud --------------------------------------------------------------
    // Each backend owns its client but they share one session, because only
    // one account can be signed in at a time. [remoteRepository] is the only
    // entry point the rest of the app uses, so the active provider is decided
    // once (by the signed-in profile) instead of at every call site.
    val aListApi: AListApi by lazy { AListApi(okHttpClient, json) }
    private val openListApi: OpenListApi by lazy { OpenListApi(okHttpClient, json) }
    private val remoteSession: RemoteSession by lazy { RemoteSession() }

    val remoteRepository: RemoteRepository by lazy {
        RemoteRepository(
            session = remoteSession,
            aListApi = aListApi,
            openListApi = openListApi,
        )
    }

    /**
     * Optional OpenList running inside this process, so a user without a
     * remote server still has somewhere to store files. Resolves to a disabled
     * stand-in in builds that did not run `openlist-lib/build.ps1`.
     */
    val embeddedServer: EmbeddedServer by lazy {
        EmbeddedServers.create(
            context = appContext,
            settings = settings,
            keyStore = keyStore,
            api = aListApi,
            session = remoteSession,
            profiles = profileRepository,
        )
    }

    /**
     * Password of the currently active cloud profile.
     * Settled by [ProfileRepository] whenever the user switches profiles.
     */
    val vault: VaultSession by lazy { VaultSession() }

    /**
     * The standalone local password, deliberately independent from every
     * cloud profile so local `.ske` files keep working on their own.
     */
    val localVault: VaultSession by lazy { VaultSession() }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            dao = database.profileDao(),
            settings = settings,
            keyStore = keyStore,
            cloudVault = vault,
            remoteRepository = remoteRepository,
        )
    }

    // ---- crypto / playback --------------------------------------------------
    val cipherBlockSourceFactory: CipherBlockSourceFactory by lazy {
        CipherBlockSourceFactory(okHttpClient, appContext)
    }

    /** Reads whole files, decrypting `.ske` containers. Used for images and subtitles. */
    val decryptedBytesReader: DecryptedBytesReader by lazy {
        DecryptedBytesReader(cipherBlockSourceFactory)
    }

    /** Grid-tile previews; reads only the thumbnail envelope, never the whole file. */
    val previewLoader: PreviewLoader by lazy {
        PreviewLoader(appContext, cipherBlockSourceFactory, okHttpClient)
    }

    val subtitleRepository: SubtitleRepository by lazy {
        SubtitleRepository(
            context = appContext,
            remoteRepository = remoteRepository,
            localSkeStore = localSkeStore,
            cipherBlockSourceFactory = cipherBlockSourceFactory,
            bytesReader = decryptedBytesReader,
        )
    }

    /** Builds the "play the rest of this folder" queue for every player. */
    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            remoteRepository = remoteRepository,
            localSkeStore = localSkeStore,
            cipherBlockSourceFactory = cipherBlockSourceFactory,
        )
    }

    /**
     * Lyrics stored beside a track as a `.lrc` file.
     *
     * Lives here rather than in the player screen so the lookup can reuse the
     * playlist repository's decrypted directory listing — a lyrics file may be
     * encrypted and named with a token, exactly like the audio it belongs to.
     */
    val siblingLyricsLoader: SiblingLyricsLoader by lazy {
        SiblingLyricsLoader(playlistRepository, cipherBlockSourceFactory)
    }

    @Volatile
    var cacheBlocks: Int = SettingsStore.DEFAULT_CACHE_BLOCKS
        private set

    /**
     * Tags and lyrics, keyed by the stable queue locator.
     *
     * The player screen holds its metadata in `remember {}` state, so leaving
     * it destroys everything and coming back re-reads the network. These maps
     * live as long as the process: a track visited once during a session is
     * never fetched again. Cleared together with the derived keys below.
     */
    val trackTags = ConcurrentHashMap<String, AudioTags>()
    val trackLyrics = ConcurrentHashMap<String, List<LyricLine>>()

    val skeDataSourceFactory: SkeDataSourceFactory by lazy {
        SkeDataSourceFactory(
            sourceFactory = cipherBlockSourceFactory,
            // Local documents use the local password, remote files the cloud one.
            passwordProvider = { uri ->
                when (uri.scheme?.lowercase()) {
                    "content", "file", "android.resource" -> localVault.password
                    else -> vault.password
                }
            },
            cacheBlocksProvider = { cacheBlocks },
        )
    }

    /**
     * Turns queue placeholders into playable urls.
     *
     * Shared deliberately: the playback service resolves an entry in order to
     * play it, while the UI needs the same real url to read lyrics and cover
     * art from. One cache means a track is never resolved twice.
     */
    val queueUriResolver: QueueUriResolver by lazy {
        QueueUriResolver(remoteRepository, cipherBlockSourceFactory)
    }

    // ---- local jobs ---------------------------------------------------------
    val cryptoTaskRunner: CryptoTaskRunner by lazy { CryptoTaskRunner(appContext) }
    val cryptoNotifications: CryptoNotifications by lazy { CryptoNotifications(appContext) }
    val localSkeStore: LocalSkeStore by lazy { LocalSkeStore(appContext) }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(appContext, database, remoteRepository, vault, okHttpClient)
    }

    /**
     * Queue for encrypting and pushing local files to the cloud.
     *
     * Kept in memory on purpose: the picked documents are content URIs whose
     * read grant dies with the activity, so a restored job could only fail.
     */
    val uploadManager: UploadManager by lazy {
        UploadManager(remoteRepository, vault, cryptoTaskRunner)
    }

    init {
        scope.launch {
            settings.cacheBlocks.collect { cacheBlocks = it }
        }

        // Disk caches live in cacheDir and must exist before the first screen
        // asks for a preview. The toggles mirror the user's settings.
        ThumbnailDiskCache.init(appContext)
        PlaintextBlockCache.init(appContext)
        scope.launch { settings.cacheThumbnails.collect { ThumbnailDiskCache.enabled = it } }
        scope.launch { settings.cacheFiles.collect { PlaintextBlockCache.enabled = it } }
        scope.launch { settings.cacheDerivedKeys.collect { DerivedKeyCache.enabled = it } }
        // Budgets come from the user; lowering one trims the directory at once.
        scope.launch {
            settings.cacheThumbnailLimitMb.collect {
                ThumbnailDiskCache.setLimitBytes(cacheLimitMbToBytes(it))
            }
        }
        scope.launch {
            settings.cacheFileLimitMb.collect {
                PlaintextBlockCache.setLimitBytes(cacheLimitMbToBytes(it))
            }
        }

        // Restore the local password when the user asked us to remember it.
        scope.launch {
            runCatching {
                if (settings.localRememberPassword.first()) {
                    settings.localSealedPassword.first()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { keyStore.open(it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { localVault.unlock(it) }
                }
            }
            localVault.markRestoreFinished()
        }

        // Re-activate the last cloud profile (unlocks + reconnects when stored).
        scope.launch {
            runCatching {
                settings.activeProfileId.first()?.let { profileRepository.activateStored(it) }
            }
            vault.markRestoreFinished()
            // Sequenced after the profile restore so the embedded server's
            // login wins over a stored profile that now points nowhere.
            embeddedServer.restore()
        }

        val invalidate = {
            skeDataSourceFactory.invalidateKeys()
            // Direct urls are token-bearing, so a lock invalidates them too.
            queueUriResolver.invalidate()
            // Listings were decrypted with the key that is about to be dropped.
            playlistRepository.clearListings()
            // Same for anything the tags/lyrics caches were decrypted from.
            trackTags.clear()
            trackLyrics.clear()
            // Do not keep derived name keys around once a password is locked.
            NameKeyCache.clear()
            // Previews are decrypted bitmaps of the vault's contents.
            PreviewCache.clear()
            // Same for the pages the continuous reader has decrypted.
            ReaderPageCache.clear()
            ImageBytesCache.clear()
            // …and the disk caches hold decrypted media: a locked vault must not
            // leave readable plaintext behind.
            ThumbnailDiskCache.clear()
            PlaintextBlockCache.clear()
            DerivedKeyCache.clear()
        }
        vault.addOnLockListener(invalidate)
        localVault.addOnLockListener(invalidate)
    }
}
