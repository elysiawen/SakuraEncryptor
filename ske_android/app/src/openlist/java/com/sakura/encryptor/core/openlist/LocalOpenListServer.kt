package com.sakura.encryptor.core.openlist

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL

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

    val baseUrl: String get() = "http://127.0.0.1:$DEFAULT_PORT"

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
                writeConfig()
                try {
                    native.setConfigData(dataDir.absolutePath)
                    native.setConfigDebug(false)
                    native.setConfigLogStd(false)
                    native.init(eventHandler(native.loader), logHandler(native.loader))
                    initialized = true
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
    private fun writeConfig() {
        val dir = dataDir.also { it.mkdirs() }
        val file = File(dir, "config.json")
        val config = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val scheme = config.optJSONObject("scheme") ?: JSONObject().also { config.put("scheme", it) }
        scheme.put("address", "127.0.0.1")
        scheme.put("http_port", DEFAULT_PORT)
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
