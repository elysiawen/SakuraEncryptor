package com.sakura.encryptor.embedded

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.embedded.EmbeddedServer
import com.sakura.encryptor.core.profile.ensureLocalProfile
import com.sakura.encryptor.core.profile.findLocalProfile
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.core.openlist.createLocalStorage
import com.sakura.encryptor.core.openlist.initStatus
import com.sakura.encryptor.core.openlist.setup
import com.sakura.encryptor.core.openlist.storageMountPaths
import com.sakura.encryptor.core.openlist.updateAdminUser
import com.sakura.encryptor.core.remote.RemoteSession
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.MainActivity
import com.sakura.encryptor.R
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runs OpenList inside the app process.
 *
 * The native half is the gomobile binding produced by `openlist-lib/build.ps1`.
 * That AAR is not committed, so nothing here may reference `openlistlib` types
 * directly: when the binding is missing the app has to keep compiling and
 * [isSupported] simply reports false.
 *
 * The server is a plain HTTP endpoint on the loopback interface; callers talk
 * to it through the same AList client used for a remote server.
 */
class LocalOpenListServer(private val context: Context) {

    /** Where OpenList keeps its config, database and caches. */
    val dataDir: File get() = File(context.filesDir, DATA_DIR_NAME)

    private val native: Native? = runCatching { Native() }
        .onFailure { Log.w(TAG, "OpenList binding unavailable: ${it.message}") }
        .getOrNull()

    private val lock = Any()
    private var serverThread: Thread? = null

    /**
     * OpenList initialises global state that cannot be rebuilt, so `init` runs
     * once per process. Stopping only shuts the HTTP endpoints down; starting
     * again from the same loaded state is what the official mobile client does.
     */
    @Volatile
    private var initialized = false

    @Volatile
    private var running = false

    /** Log lines the Go side emitted, for diagnostics; null means "drop". */
    @Volatile
    var logSink: ((level: Int, message: String) -> Unit)? = null

    /** Reports a failure to come up; the server keeps running half-initialised. */
    @Volatile
    var startErrorSink: ((source: String, message: String) -> Unit)? = null

    /** True only when the native binding loaded and the device ABI matches it. */
    val isSupported: Boolean get() = native != null

    /**
     * Loopback port the server binds to.
     *
     * OpenList reads the port while initialising, so a new value only takes
     * effect on the next start of the process; [start] rewrites config.json
     * with it before the Go side loads the file.
     */
    @Volatile
    var port: Int = DEFAULT_PORT

    /** Port OpenList actually loaded; null until [start] runs its init. */
    @Volatile
    private var boundPort: Int? = null

    /** The port traffic really runs on, which differs from [port] after an edit. */
    val activePort: Int get() = boundPort ?: port

    /** True once the Go side has loaded a config file in this process. */
    val isInitialized: Boolean get() = boundPort != null

    val baseUrl: String get() = "http://127.0.0.1:$activePort"

    val isRunning: Boolean
        get() = running && runCatching { native?.isRunning("http") == true }.getOrDefault(false)

    /**
     * Initialises and starts the server, returning once it answers `/ping`.
     *
     * Blocking: call it from a coroutine, never from the main thread. Returns
     * false when the binding is absent, the port is taken or startup failed.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val native = native ?: return@withContext false
        synchronized(lock) {
            if (isRunning) return@withContext true

            if (!initialized) {
                rewriteConfig()
                try {
                    native.setConfigData(dataDir.absolutePath)
                    native.setConfigDebug(false)
                    native.setConfigLogStd(false)
                    native.init(eventHandler(native.loader), logHandler(native.loader))
                    initialized = true
                    boundPort = port
                } catch (e: InvocationTargetException) {
                    Log.e(TAG, "OpenList init failed", e.targetException ?: e)
                    return@withContext false
                } catch (e: Exception) {
                    Log.e(TAG, "OpenList init failed", e)
                    return@withContext false
                }
            }

            // Start blocks for the whole lifetime of the server.
            val thread = Thread({ native.start() }, "openlist-server").apply {
                isDaemon = true
                start()
            }
            serverThread = thread

            val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (ping()) {
                    running = true
                    return@withContext true
                }
                if (!thread.isAlive) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            Log.e(TAG, "OpenList did not answer /ping within ${START_TIMEOUT_MS}ms")
            runCatching { native.shutdown(STOP_TIMEOUT_MS) }
            thread.join(1000)
            serverThread = null
            false
        }
    }

    /** Stops the server and waits for its goroutines to unwind. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val native = native ?: return@withContext
        synchronized(lock) {
            if (running || serverThread != null) {
                // Pending sqlite writes are checkpointed here, so a killed
                // process does not leave a WAL behind.
                runCatching { native.shutdown(STOP_TIMEOUT_MS) }
                    .onFailure { Log.w(TAG, "OpenList shutdown failed", it) }
                serverThread?.join(STOP_TIMEOUT_MS + 1000)
            }
            serverThread = null
            running = false
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * Rewrites the fields the app insists on: loopback binding, its port, and
     * no CDN. Everything else (drivers, users) lives in the sqlite database and
     * is left alone.
     */
    fun rewriteConfig(writePort: Int = port) {
        val dir = dataDir.also { it.mkdirs() }
        val file = File(dir, "config.json")
        val config = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val scheme = config.optJSONObject("scheme") ?: JSONObject().also { config.put("scheme", it) }
        scheme.put("address", "127.0.0.1")
        scheme.put("http_port", writePort)
        scheme.put("https_port", -1)
        scheme.put("force_https", false)
        config.put("temp_dir", File(dir, "temp").absolutePath)
        config.put("bleve_dir", File(dir, "bleve").absolutePath)
        // An empty cdn keeps startup independent of the network.
        config.put("cdn", "")
        file.writeText(config.toString(2))
    }

    private fun ping(): Boolean = try {
        val connection = URL("$baseUrl/ping").openConnection() as HttpURLConnection
        connection.connectTimeout = PING_TIMEOUT_MS
        connection.readTimeout = PING_TIMEOUT_MS
        connection.requestMethod = "GET"
        try {
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun eventHandler(loader: ClassLoader): Any = invokeHandler(loader, EVENT) { method, args ->
        when (method) {
            "onStartError" -> {
                val source = args?.getOrNull(0) as? String ?: ""
                val message = args?.getOrNull(1) as? String ?: ""
                Log.e(TAG, "OpenList start error ($source): $message")
                startErrorSink?.invoke(source, message)
            }
            "onShutdown" -> Log.i(TAG, "OpenList shutdown: ${args?.getOrNull(0)}")
            "onProcessExit" -> Log.e(TAG, "OpenList requested process exit: ${args?.getOrNull(0)}")
        }
    }

    private fun logHandler(loader: ClassLoader): Any = invokeHandler(loader, LOG_CALLBACK) { method, args ->
        if (method == "onLog") {
            val level = args?.getOrNull(0) as? Short ?: 0
            val message = args?.getOrNull(2) as? String ?: ""
            logSink?.invoke(level.toInt(), message)
        }
    }

    /** Proxies one of the Go interfaces; every method returns void here. */
    private fun invokeHandler(
        loader: ClassLoader,
        interfaceName: String,
        action: (method: String, args: Array<Any?>?) -> Unit,
    ): Any = Proxy.newProxyInstance(
        loader,
        arrayOf(Class.forName(interfaceName, false, loader)),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> interfaceName
                else -> action(method.name, args)
            }
            null
        },
    )

    /**
     * Reflection shim over the generated binding.
     *
     * Kept in one place so the rest of the app never mentions the optional
     * artifact. Construction fails — and [isSupported] turns false — when the
     * class is missing or `libgojni.so` was not packaged for this ABI.
     */
    private class Native {
        val loader: ClassLoader

        private val setConfigDataMethod: java.lang.reflect.Method
        private val setConfigDebugMethod: java.lang.reflect.Method
        private val setConfigLogStdMethod: java.lang.reflect.Method
        private val initMethod: java.lang.reflect.Method
        private val startMethod: java.lang.reflect.Method
        private val shutdownMethod: java.lang.reflect.Method
        private val isRunningMethod: java.lang.reflect.Method

        init {
            val cls = Class.forName("openlistlib.Openlistlib")
            loader = cls.classLoader ?: ClassLoader.getSystemClassLoader()
            val eventClass = Class.forName("openlistlib.Event", false, loader)
            val logClass = Class.forName("openlistlib.LogCallback", false, loader)
            setConfigDataMethod = cls.getMethod("setConfigData", String::class.java)
            setConfigDebugMethod = cls.getMethod("setConfigDebug", Boolean::class.javaPrimitiveType)
            setConfigLogStdMethod = cls.getMethod("setConfigLogStd", Boolean::class.javaPrimitiveType)
            initMethod = cls.getMethod("init", eventClass, logClass)
            startMethod = cls.getMethod("start")
            shutdownMethod = cls.getMethod("shutdown", Long::class.javaPrimitiveType)
            isRunningMethod = cls.getMethod("isRunning", String::class.java)
        }

        fun setConfigData(path: String) = setConfigDataMethod.invoke(null, path)

        fun setConfigDebug(enabled: Boolean) = setConfigDebugMethod.invoke(null, enabled)

        fun setConfigLogStd(enabled: Boolean) = setConfigLogStdMethod.invoke(null, enabled)

        fun init(event: Any, logCallback: Any) = initMethod.invoke(null, event, logCallback)

        fun start() = startMethod.invoke(null)

        fun shutdown(timeoutMs: Long) = shutdownMethod.invoke(null, timeoutMs)

        fun isRunning(name: String): Boolean = isRunningMethod.invoke(null, name) as Boolean
    }

    companion object {
        private const val TAG = "LocalOpenList"
        private const val DATA_DIR_NAME = "openlist"
        private const val EVENT = "openlistlib.Event"
        private const val LOG_CALLBACK = "openlistlib.LogCallback"

        const val DEFAULT_PORT = 5244

        private const val START_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 300L
        private const val PING_TIMEOUT_MS = 1_000
    }
}

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

    val port: Int get() = server.activePort

    /** Account name last read from settings; kept in sync by [connect] and [updateSettings]. */
    @Volatile
    private var cachedUsername: String = SettingsStore.DEFAULT_ADMIN_USER

    val username: String get() = cachedUsername

    /** The stored admin password, unsealed for the settings editor. */
    suspend fun readPassword(): String? =
        settings.localOpenListSealedPassword.first()
            ?.let { runCatching { keyStore.open(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

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

    /**
     * Apply the port and admin account edited in settings.
     *
     * The account lives in the server's sqlite database rather than in app
     * preferences, so a change is pushed to the running server; the password
     * copy is re-sealed afterwards. A new port only goes into config.json,
     * which OpenList reads while initialising — it binds on the next start of
     * the process.
     */
    suspend fun updateSettings(newPort: Int, newUsername: String, newPassword: String): Result<Unit> =
        mutex.withLock {
            runCatching {
                check(server.isSupported) { "此安装包未包含内置服务器组件" }
                require(newPort in MIN_PORT..MAX_PORT) { "端口需在 $MIN_PORT 到 $MAX_PORT 之间" }
                val user = newUsername.trim()
                require(user.isNotEmpty() && user.none(Char::isWhitespace)) { "用户名不能为空，也不能包含空格" }
                require(newPassword.isNotEmpty()) { "管理员密码不能为空" }

                val oldPort = settings.localOpenListPort.first()
                val oldUser = settings.localOpenListUsername.first()
                val oldPassword = readPassword()
                val enabled = settings.localOpenListEnabled.first()
                val credentialsChanged = user != oldUser || newPassword != oldPassword

                var startedHere = false
                if (credentialsChanged) {
                    // Renaming the account needs an admin token, which only the
                    // server itself can hand out.
                    if (!server.isRunning) {
                        server.port = oldPort
                        startedHere = true
                        check(server.start()) { "内置服务器启动失败，请稍后重试" }
                    }
                    val address = server.baseUrl
                    if (api.initStatus(address)) {
                        val token = if (session.isLoggedIn && session.server == address) {
                            session.token
                        } else {
                            oldPassword?.let { api.login(address, oldUser, it) }
                                ?: error("无法登录内置服务器，请先开启后重试")
                        }
                        api.updateAdminUser(address, token, oldUser, user, newPassword)
                    }
                }

                val sealed = keyStore.seal(newPassword) ?: error("无法保存内置服务器的账号凭据")
                settings.setLocalOpenListPort(newPort)
                settings.setLocalOpenListUsername(user)
                settings.setLocalOpenListSealedPassword(sealed)
                cachedUsername = user
                updateLocalProfile(user, newPassword)

                if (enabled && credentialsChanged &&
                    settings.activeProfileId.first() == settings.localOpenListProfileId.first()
                ) {
                    session.clear()
                    profiles.connectActive().getOrThrow()
                }

                if (newPort != oldPort) {
                    // Until the process restarts the old port keeps serving, so
                    // the binding address must not change yet.
                    if (!server.isInitialized) server.port = newPort
                    server.rewriteConfig(writePort = newPort)
                }

                if (startedHere && !enabled) server.stop()
            }
        }

    /** Mirror the new account onto the profile row so auto-login keeps working. */
    private suspend fun updateLocalProfile(username: String, password: String) {
        val id = settings.localOpenListProfileId.first() ?: return
        val entity = profiles.find(id) ?: return
        profiles.save(
            id = entity.id,
            name = entity.name,
            server = entity.server,
            username = username,
            basePath = entity.basePath,
            provider = entity.remoteProvider,
            alistPassword = password,
            masterPassword = null,
        )
    }

    private suspend fun connect(isRestore: Boolean) {
        check(server.isSupported) { "此安装包未包含内置服务器组件" }
        server.port = settings.localOpenListPort.first()
        cachedUsername = settings.localOpenListUsername.first()
        check(server.start()) { "内置服务器启动失败，请稍后重试" }

        val password = adminPassword()
        check(server.isRunning) { "内置服务器未能启动，请稍后重试" }
        if (!api.initStatus(baseUrl)) {
            api.setup(baseUrl, cachedUsername, password)
        }

        val sealedPassword = keyStore.seal(password) ?: error("无法保存内置服务器的账号凭据")
        val localId = profiles.ensureLocalProfile(
            profileId = settings.localOpenListProfileId.first(),
            server = baseUrl,
            username = cachedUsername,
            sealedPassword = sealedPassword,
        )
        settings.setLocalOpenListProfileId(localId)

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

        /** Directory under [LocalOpenListServer.dataDir] mounted at `/`. */
        private const val STORAGE_DIR = "data"

        private const val PASSWORD_LENGTH = 24
        private const val ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** Loopback ports outside the privileged range are accepted. */
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}

/**
 * Keeps the process alive while OpenList serves files.
 *
 * The server itself is started by [LocalOpenListManager] — this only owns the
 * foreground notification, so the system treats the process as doing visible
 * work and does not freeze it the moment the app leaves the screen.
 */
class LocalOpenListService : Service() {

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "内置服务器",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "在本机运行 OpenList 时显示" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sakura)
                .setContentTitle("内置服务器运行中")
                .setContentText("OpenList 正在本机提供文件服务")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Android 15 limits `dataSync` services; when the system asks us to go, the
     * server stops with the process and the switch is turned off next launch.
     */
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "openlist_server"
        private const val NOTIFICATION_ID = 4103

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocalOpenListService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalOpenListService::class.java)) }
        }
    }
}

/** [EmbeddedServer] backed by the Go OpenList binding in this build. */
class BundledOpenList(private val manager: LocalOpenListManager) : EmbeddedServer {

    override val isBundled: Boolean = true
    override val isSupported: Boolean get() = manager.isSupported
    override val address: String get() = manager.baseUrl
    override val isRunning: Boolean get() = manager.isRunning

    override val port: Int get() = manager.port

    override val username: String get() = manager.username

    override suspend fun readPassword(): String? = manager.readPassword()

    override suspend fun updateSettings(port: Int, username: String, password: String): Result<Unit> =
        manager.updateSettings(port, username, password)

    override fun requireSession(): Pair<String, String> = manager.requireSession()

    override suspend fun enable(): Result<Unit> = manager.enable()

    override suspend fun restore(): Result<Unit> = manager.restore()

    override suspend fun disable() = manager.disable()

    override fun startForegroundService(context: Context) = LocalOpenListService.start(context)

    override fun stopForegroundService(context: Context) = LocalOpenListService.stop(context)

    companion object {
        /**
         * Reflected by name from [com.sakura.encryptor.core.embedded.EmbeddedServers];
         * keep the signature in sync with it.
         */
        @JvmStatic
        fun create(
            context: Context,
            settings: SettingsStore,
            keyStore: KeyStoreManager,
            api: AListApi,
            session: RemoteSession,
            profiles: ProfileRepository,
        ): EmbeddedServer = BundledOpenList(
            LocalOpenListManager(
                settings = settings,
                keyStore = keyStore,
                server = LocalOpenListServer(context),
                api = api,
                session = session,
                profiles = profiles,
            ),
        )
    }
}
