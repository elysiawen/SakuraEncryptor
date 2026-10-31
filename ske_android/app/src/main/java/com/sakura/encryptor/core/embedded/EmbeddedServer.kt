package com.sakura.encryptor.core.embedded

import android.content.Context
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.remote.RemoteSession
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.data.prefs.SettingsStore

/**
 * An OpenList server compiled into the package.
 *
 * The implementation only exists in the `openlist` flavor, so a `plain` build
 * resolves [EmbeddedServers.create] to [DisabledEmbeddedServer] and carries no
 * part of the server. Everything above this interface — profiles, the settings
 * toggle, storage screens — stays backend-agnostic.
 */
interface EmbeddedServer {
    /** True when this package actually ships the Go binding. */
    val isBundled: Boolean
    val isSupported: Boolean
    val address: String
    val isRunning: Boolean

    /** Loopback port the server listens on, as configured in settings. */
    val port: Int

    /** Admin account name the server is set up with. */
    val username: String

    /**
     * Current admin password in plain text, or null when it cannot be read.
     *
     * It is only shown in the settings editor, which is why the sealed copy is
     * unsealed here instead of behind an admin API.
     */
    suspend fun readPassword(): String?

    /**
     * Apply a new port / account. Credentials are pushed to the running server;
     * a changed port is written to disk and takes effect on the next start.
     */
    suspend fun updateSettings(port: Int, username: String, password: String): Result<Unit>

    /** Address and admin token of the embedded endpoint. */
    fun requireSession(): Pair<String, String>

    suspend fun enable(): Result<Unit>
    suspend fun restore(): Result<Unit>
    suspend fun disable()

    fun startForegroundService(context: Context)
    fun stopForegroundService(context: Context)
}

/** Stand-in for packages without the embedded server. */
object DisabledEmbeddedServer : EmbeddedServer {
    private const val UNSUPPORTED = "此安装包未包含内置服务器组件"

    override val isBundled: Boolean = false
    override val isSupported: Boolean = false
    override val address: String = ""
    override val isRunning: Boolean = false
    override val port: Int = SettingsStore.DEFAULT_LOCAL_PORT
    override val username: String = ""

    override suspend fun readPassword(): String? = null

    override suspend fun updateSettings(port: Int, username: String, password: String): Result<Unit> =
        Result.failure(IllegalStateException(UNSUPPORTED))

    override fun requireSession(): Pair<String, String> = error(UNSUPPORTED)

    override suspend fun enable(): Result<Unit> = Result.failure(IllegalStateException(UNSUPPORTED))

    override suspend fun restore(): Result<Unit> = Result.success(Unit)

    override suspend fun disable() = Unit

    override fun startForegroundService(context: Context) = Unit

    override fun stopForegroundService(context: Context) = Unit
}

object EmbeddedServers {
    private const val IMPLEMENTATION = "com.sakura.encryptor.embedded.BundledOpenList"

    /**
     * Instantiates the flavor's implementation by name.
     *
     * Reflection keeps [AppContainer] free of any compile-time reference to it,
     * which is what lets the plain build drop the class entirely.
     */
    fun create(
        context: Context,
        settings: SettingsStore,
        keyStore: KeyStoreManager,
        api: AListApi,
        session: RemoteSession,
        profiles: ProfileRepository,
    ): EmbeddedServer = runCatching {
        Class.forName(IMPLEMENTATION)
            .getMethod(
                "create",
                Context::class.java,
                SettingsStore::class.java,
                KeyStoreManager::class.java,
                AListApi::class.java,
                RemoteSession::class.java,
                ProfileRepository::class.java,
            )
            .invoke(null, context, settings, keyStore, api, session, profiles) as EmbeddedServer
    }.getOrDefault(DisabledEmbeddedServer)
}
