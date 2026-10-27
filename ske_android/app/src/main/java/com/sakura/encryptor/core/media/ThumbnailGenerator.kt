package com.sakura.encryptor.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.sakura.encryptor.core.crypto.SkeFormat
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds the v003 preview payload: a JPEG whose longest side is
 * [SkeFormat.THUMB_MAX_SIDE].
 *
 * Mirrors `generate_thumbnail()` in `ske_cli/crypto.py`, including the two
 * "not worth it" rules — a picture that already fits needs no preview, and a
 * preview may not cost more than a quarter of the file it describes. Videos are
 * sampled with [MediaMetadataRetriever], i.e. the platform's own decoder, so no
 * ffmpeg port is needed; which still-image formats are readable is likewise up
 * to the platform, so HEIF works here even though the Python path skips it.
 *
 * Generation is best-effort by design: a container without a preview is still a
 * perfectly valid .ske file, so callers should treat `null` as "carry on".
 */
object ThumbnailGenerator {

    /** Return a JPEG preview of [src], or `null` when none is worth storing. */
    fun generate(src: File): ByteArray? {
        val sourceSize = src.length()
        if (sourceSize <= 0) return null

        // Decoding a 100 MP JPEG can throw OutOfMemoryError; a missing preview
        // must never abort an encryption job.
        val preview = runCatching {
            if (isVideo(src)) videoPreview(src) else imagePreview(src)
        }.getOrNull() ?: return null

        if (preview.size < SkeFormat.THUMB_MIN_BYTES) return null
        if (preview.size.toLong() * SkeFormat.THUMB_COST_DIVISOR >= sourceSize) return null
        return preview
    }

    private fun isVideo(src: File): Boolean {
        val extension = src.extension.lowercase()
        return extension.isNotEmpty() && ".$extension" in SkeFormat.VIDEO_SUFFIXES
    }

    private fun imagePreview(src: File): ByteArray? {
        // Probe the dimensions first so the sample factor can be chosen.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        // Already small enough: a preview would only be an upscale.
        if (maxOf(width, height) <= SkeFormat.THUMB_MAX_SIDE) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height)
        }
        val decoded = BitmapFactory.decodeFile(src.absolutePath, options) ?: return null
        val preview = compressToPreview(decoded)
        if (!decoded.isRecycled) decoded.recycle()
        return preview
    }

    private fun videoPreview(src: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(src.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L

            // Sample near 30% like the Python path: past any black intro, and
            // a sync frame — far cheaper to decode than an arbitrary frame.
            val targetUs = durationMs * 300L // ms * 1000 * 0.3
            val frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null

            val preview = compressToPreview(frame)
            if (!frame.isRecycled) frame.recycle()
            return preview
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Encode [source] as a JPEG no larger than the preview limit.
     *
     * Video frames are not downscaled when they already fit — unlike still
     * pictures, a small video frame still saves the player a decode.
     */
    private fun compressToPreview(source: Bitmap): ByteArray? {
        val longSide = maxOf(source.width, source.height)
        val scaled = if (longSide <= SkeFormat.THUMB_MAX_SIDE) {
            source
        } else {
            val factor = SkeFormat.THUMB_MAX_SIDE.toFloat() / longSide
            val width = (source.width * factor).toInt().coerceAtLeast(1)
            val height = (source.height * factor).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, width, height, true)
        }

        val out = ByteArrayOutputStream()
        val encoded = scaled.compress(Bitmap.CompressFormat.JPEG, SkeFormat.THUMB_JPEG_QUALITY, out)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return if (encoded) out.toByteArray() else null
    }

    /**
     * Power-of-two sample factor that keeps the decode at ~2x the preview size:
     * small enough to stay in memory, large enough that the final downscale is
     * still sharp.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val target = SkeFormat.THUMB_MAX_SIDE * 2
        var sample = 1
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= target) {
            sample *= 2
        }
        return sample
    }
}
