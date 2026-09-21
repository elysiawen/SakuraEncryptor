package com.sakura.encryptor

import android.content.Context
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.alist.AListRepository
import com.sakura.encryptor.core.alist.AListSession
import com.sakura.encryptor.core.download.DownloadManager
import com.sakura.encryptor.core.local.CryptoNotifications
import com.sakura.encryptor.core.local.CryptoTaskRunner
import com.sakura.encryptor.core.local.LocalSkeStore
import com.sakura.encryptor.core.player.CipherBlockSourceFactory
import com.sakura.encryptor.core.player.DecryptedBytesReader
import com.sakura.encryptor.core.player.SkeDataSourceFactory
import com.sakura.encryptor.core.playlist.PlaylistRepository
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.security.AppLockManager
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.core.subtitle.SubtitleRepository
import com.sakura.encryptor.data.db.SakuraDatabase
import com.sakura.encryptor.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
    val appLockManager: AppLockManager by lazy { AppLockManager(settings, scope) }
    val database: SakuraDatabase by lazy { SakuraDatabase.get(appContext) }

    // ---- cloud --------------------------------------------------------------
    val aListApi: AListApi by lazy { AListApi(okHttpClient, json) }
    val aListSession: AListSession by lazy { AListSession() }
    val aListRepository: AListRepository by lazy { AListRepository(aListApi, aListSession) }

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
            aListSession = aListSession,
            aListRepository = aListRepository,
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

    val subtitleRepository: SubtitleRepository by lazy {
        SubtitleRepository(
            context = appContext,
            aListRepository = aListRepository,
            localSkeStore = localSkeStore,
            cipherBlockSourceFactory = cipherBlockSourceFactory,
            bytesReader = decryptedBytesReader,
        )
    }

    /** Builds the "play the rest of this folder" queue for every player. */
    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            aListRepository = aListRepository,
            localSkeStore = localSkeStore,
            cipherBlockSourceFactory = cipherBlockSourceFactory,
        )
    }

    @Volatile
    var cacheBlocks: Int = SettingsStore.DEFAULT_CACHE_BLOCKS
        private set

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

    // ---- local jobs ---------------------------------------------------------
    val cryptoTaskRunner: CryptoTaskRunner by lazy { CryptoTaskRunner(appContext) }
    val cryptoNotifications: CryptoNotifications by lazy { CryptoNotifications(appContext) }
    val localSkeStore: LocalSkeStore by lazy { LocalSkeStore(appContext) }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(appContext, database, aListApi, aListSession, vault, okHttpClient)
    }

    init {
        scope.launch {
            settings.cacheBlocks.collect { cacheBlocks = it }
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
        }

        val invalidate = {
            skeDataSourceFactory.invalidateKeys()
            // Do not keep derived name keys around once a password is locked.
            NameKeyCache.clear()
        }
        vault.addOnLockListener(invalidate)
        localVault.addOnLockListener(invalidate)
    }
}
