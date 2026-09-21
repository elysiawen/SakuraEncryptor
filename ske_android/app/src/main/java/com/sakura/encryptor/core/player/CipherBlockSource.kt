package com.sakura.encryptor.core.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.sakura.encryptor.core.crypto.SkeFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Random access to the *ciphertext* of a `.ske` container.
 *
 * Implementations only ever serve raw encrypted bytes; decryption happens in
 * [SkeBlockReader]. This separation keeps the HTTP and SAF paths tiny.
 */
interface CipherBlockSource {
    /** Prepare the source and resolve [cipherSize]. */
    fun open()

    /** Total size of the `.ske` container in bytes. */
    val cipherSize: Long

    /**
     * Read up to [length] ciphertext bytes starting at [position].
     * @return bytes read, or -1 at end of stream.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

// ---------------------------------------------------------------------------
// HTTP / AList raw_url
// ---------------------------------------------------------------------------

/**
 * Serves ciphertext via HTTP Range requests against an AList `raw_url`.
 *
 * Range support is what makes seeking cheap: the player asks for one 1 MiB
 * encrypted block and only that block travels over the network.
 */
class HttpCipherBlockSource(
    private val client: OkHttpClient,
    private val url: String,
    private val knownSize: Long? = null,
) : CipherBlockSource {

    private var resolvedSize: Long = knownSize ?: -1L

    override fun open() {
        if (resolvedSize <= 0) resolvedSize = probeSize()
    }

    override val cipherSize: Long get() = resolvedSize

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        val end = position + length - 1
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-$end")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while reading bytes $position-$end")
            }
            val stream = response.body?.byteStream() ?: return -1

            // A server that ignores Range replies 200 with the whole body.
            if (response.code == 200 && position > 0) {
                skipFully(stream, position)
            }
            return readUpTo(stream, buffer, offset, length)
        }
    }

    override fun close() = Unit

    private fun probeSize(): Long {
        // HEAD first: cheapest way to learn Content-Length.
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) return it }
                }
            }
        }

        // Fallback: ask for a single byte and read the total from Content-Range.
        client.newCall(
            Request.Builder().url(url).header("Range", "bytes=0-0").build()
        ).execute().use { response ->
            response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.let {
                if (it > 0) return it
            }
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) return it }
            }
        }
        throw IOException("Unable to determine the size of $url")
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (skipped < 0) throw IOException("Unexpected end of stream while skipping")
            remaining -= skipped
        }
    }

    private fun readUpTo(stream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, offset + total, length - total)
            if (read < 0) break
            total += read
        }
        return if (total == 0) -1 else total
    }
}

// ---------------------------------------------------------------------------
// Local content:// or file://
// ---------------------------------------------------------------------------

/**
 * Serves ciphertext from a local `.ske` file opened through the Storage Access
 * Framework. The file descriptor is seekable, so block access is O(1).
 */
class LocalCipherBlockSource(
    private val context: Context,
    private val uri: Uri,
) : CipherBlockSource {

    private var descriptor: ParcelFileDescriptor? = null
    private var stream: FileInputStream? = null
    private var channel: FileChannel? = null
    private var resolvedSize: Long = -1L

    override fun open() {
        if (channel != null) return

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open $uri")

        // The stream wraps the descriptor's file number, so the PFD has to stay
        // open while we read — closing it here would invalidate the channel.
        // It is released in close() instead.
        val fileStream = FileInputStream(pfd.fileDescriptor)

        descriptor = pfd
        stream = fileStream
        channel = fileStream.channel
        resolvedSize = pfd.statSize.takeIf { it > 0 } ?: fileStream.channel.size()
    }

    override val cipherSize: Long get() = resolvedSize

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val fileChannel = channel ?: throw IOException("Source not open")
        if (length <= 0) return 0
        fileChannel.position(position)
        val target = ByteBuffer.wrap(buffer, offset, length)
        var total = 0
        while (target.hasRemaining()) {
            val read = fileChannel.read(target)
            if (read < 0) break
            total += read
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        runCatching { channel?.close() }
        runCatching { stream?.close() }
        runCatching { descriptor?.close() }
        channel = null
        stream = null
        descriptor = null
    }
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

/**
 * Builds the right [CipherBlockSource] for a URI and remembers resolved sizes,
 * so re-opening the same media does not repeat the size probe.
 */
class CipherBlockSourceFactory(
    private val client: OkHttpClient,
    private val context: Context,
) {
    private val sizeCache = ConcurrentHashMap<String, Long>()

    /**
     * Register the authoritative ciphertext size for [key] (a URI string).
     *
     * AList already reports the exact size through `/api/fs/get`, so seeding it
     * here avoids a `HEAD`/`Range` probe whose result is not always trustworthy
     * behind proxies and redirects.
     */
    fun rememberSize(key: String, size: Long) {
        if (size > 0) sizeCache[key] = size
    }

    fun create(uri: Uri): CipherBlockSource = when (uri.scheme?.lowercase()) {
        "content", "file", "android.resource" ->
            CachedSizeLocalSource(LocalCipherBlockSource(context, uri), sizeCache, uri.toString())

        else ->
            CachedSizeHttpSource(
                HttpCipherBlockSource(client, uri.toString(), sizeCache[uri.toString()]),
                sizeCache,
                uri.toString(),
            )
    }

    private class CachedSizeHttpSource(
        private val delegate: HttpCipherBlockSource,
        private val cache: MutableMap<String, Long>,
        private val key: String,
    ) : CipherBlockSource by delegate {
        override fun open() {
            delegate.open()
            if (delegate.cipherSize > 0) cache[key] = delegate.cipherSize
        }
    }

    private class CachedSizeLocalSource(
        private val delegate: LocalCipherBlockSource,
        private val cache: MutableMap<String, Long>,
        private val key: String,
    ) : CipherBlockSource by delegate {
        override fun open() {
            delegate.open()
            if (delegate.cipherSize > 0) cache[key] = delegate.cipherSize
        }
    }

    companion object {
        /** Bytes of the header, needed before any block can be read. */
        val HEADER_RANGE: IntRange = 0 until SkeFormat.HEADER_SIZE
    }
}
