package com.sakura.encryptor.core.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Materialises a whole image into a [Bitmap], decrypting `.ske` containers and
 * passing plain images through.
 *
 * Decoding is sampled so very large photos do not blow up the heap, and
 * failures name their cause instead of a generic message.
 */
class ImageDecryptor(
    sourceFactory: CipherBlockSourceFactory,
    private val bytesReader: DecryptedBytesReader = DecryptedBytesReader(sourceFactory),
) {

    suspend fun decrypt(uri: Uri, password: String): Bitmap = withContext(Dispatchers.IO) {
        val payload = bytesReader.readAll(uri, password)
        if (payload.isEmpty()) throw IOException("图片内容为空")
        decode(payload)
    }

    private fun decode(bytes: ByteArray): Bitmap {
        // Probe the dimensions first so the sample factor can be chosen.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("无法识别图片格式（HEIC / AVIF 等可能不被系统支持）")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("图片解码失败（${bytes.size / 1024} KB，${bounds.outWidth}×${bounds.outHeight}）")
    }

    /** Largest power-of-two sample that keeps the longest edge within [MAX_DIMENSION]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val MAX_DIMENSION = 4096
    }
}
