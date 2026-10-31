package com.sakura.encryptor.core.openlist

import android.util.Log
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.remote.RemoteSession
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom

/**
 * Runs OpenList on the device and points the cloud screens at it.
 *
 * The embedded server is just another AList endpoint, so everything above it —
 * profiles, token handling, the virtual root — keeps working unchanged. What
 * differs is that the account is created here: a random admin password sealed
 * by the Keystore, setup on the very first start, and a Local storage mounted
 * at `/` inside the app's private directory.
 *
 * Switching it on makes the built-in profile active; switching it off returns
 * to the profile that was active before, if that one can still be restored.
 * Both directions are serialised: the startup path and the settings toggle can
 * race otherwise.
 */
class LocalOpenListManager(
    private val settings: SettingsStore,
    private val keyStore: KeyStoreManager,
    private val server: LocalOpenListServer,
    private val api: AListApi,
    private val session: RemoteSession,
    private val profiles: ProfileRepository,
) {

    /** True only when this build actually carries the Go binding. */
    val isSupported: Boolean get() = server.isSupported

    val baseUrl: String get() = server.baseUrl

    val isRunning: Boolean get() = server.isRunning

    private val mutex = Mutex()

    /**
     * Server address and admin token of the embedded endpoint.
     *
     * Admin-only screens (storage management) go through this instead of the
     * session directly so they fail with something the user can act on when
     * the switch is off or the app has not reconnected yet.
     */
    fun requireSession(): Pair<String, String> {
        check(session.isLoggedIn && session.server == baseUrl) {
            "内置服务器未在运行，请先在设置中开启"
        }
        return session.server to session.token
    }

    /** Brings the server up and signs in, remembering where to return to. */
    suspend fun enable(): Result<Unit> = mutex.withLock {
        runCatching { connect(isRestore = false) }
    }

    /** Called on startup; a no-op unless the user left the switch on. */
    suspend fun restore(): Result<Unit> = mutex.withLock {
        runCatching {
            if (!settings.localOpenListEnabled.first()) return@runCatching
            connect(isRestore = true)
        }
    }

    /** Stops the server and hands the cloud screens back to the previous profile. */
    suspend fun disable() = mutex.withLock {
        settings.setLocalOpenListEnabled(false)

        val localId = profiles.findLocalProfile(baseUrl)?.id
        val activeId = settings.activeProfileId.first()
        if (localId != null && localId == activeId) {
            session.clear()
            val restoreId = settings.localOpenListRestoreProfileId.first()
            settings.setActiveProfileId(restoreId)
            if (restoreId != null) {
                // Only succeeds when the previous profile has stored secrets;
                // otherwise the user just picks it again from the profile list.
                runCatching { profiles.activateStored(restoreId) }
            }
        } else if (session.server == baseUrl) {
            session.clear()
        }

        settings.setLocalOpenListRestoreProfileId(null)
        server.stop()
    }

    private suspend fun connect(isRestore: Boolean) {
        check(server.isSupported) { "此安装包未包含内置服务器组件" }
        check(server.start()) { "内置服务器启动失败，请稍后重试" }

        val password = adminPassword()
        if (!api.initStatus(baseUrl)) {
            api.setup(baseUrl, ADMIN_USER, password)
        }

        val sealedPassword = keyStore.seal(password) ?: error("无法保存内置服务器的账号凭据")
        val localId = profiles.ensureLocalProfile(baseUrl, ADMIN_USER, sealedPassword)

        // Only remember the profile we are leaving on the first enable: on a
        // restart the active profile is already the built-in one.
        if (!isRestore && !settings.localOpenListEnabled.first()) {
            val previous = settings.activeProfileId.first()
            if (previous != null && previous != localId) {
                settings.setLocalOpenListRestoreProfileId(previous)
            }
        }

        profiles.activateStored(localId)
        if (!session.isLoggedIn) {
            // activateStored only auto-connects when the encryption password is
            // stored too; the server account itself is always known.
            profiles.connectActive().getOrThrow()
        }
        settings.setLocalOpenListEnabled(true)

        ensureStorage()
    }

    /**
     * Give the fresh server somewhere to store files.
     *
     * A new OpenList has no storages at all, so `/` would list nothing. The
     * Local driver gets a directory inside the app sandbox, which the app
     * already treats as private and uninstalling removes.
     */
    private suspend fun ensureStorage() {
        if (!session.isLoggedIn || session.server != baseUrl) return

        val root = File(server.dataDir, STORAGE_DIR).also { it.mkdirs() }
        if (api.storageMountPaths(baseUrl, session.token).any { it == "/" }) return

        runCatching {
            api.createLocalStorage(
                server = baseUrl,
                token = session.token,
                mountPath = "/",
                rootPath = root.absolutePath,
            )
        }.onFailure {
            // The server is up and usable with a mount the user adds later, so
            // this is worth a log rather than a failed toggle.
            Log.w(TAG, "Could not create the local storage: ${it.message}")
        }
    }

    /** Reads the stored admin password, generating and sealing one on first use. */
    private suspend fun adminPassword(): String {
        settings.localOpenListSealedPassword.first()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { keyStore.open(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val generated = buildString(PASSWORD_LENGTH) {
            val random = SecureRandom()
            repeat(PASSWORD_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        val sealed = keyStore.seal(generated) ?: error("无法保存内置服务器的管理员密码")
        settings.setLocalOpenListSealedPassword(sealed)
        return generated
    }

    companion object {
        private const val TAG = "LocalOpenList"

        /** Independent of the user's language: the API only ever sees this name. */
        const val ADMIN_USER = "admin"

        /** Directory under [LocalOpenListServer.dataDir] mounted at `/`. */
        private const val STORAGE_DIR = "data"

        private const val PASSWORD_LENGTH = 24
        private const val ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
