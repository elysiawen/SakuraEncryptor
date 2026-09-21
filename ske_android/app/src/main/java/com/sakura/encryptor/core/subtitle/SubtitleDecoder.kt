package com.sakura.encryptor.core.subtitle

/**
 * Turns raw subtitle bytes into text.
 *
 * Port of the web client's `decodeSubtitleBuffer`: respect a BOM, prefer UTF-8,
 * then fall back to the CJK encodings that are common in subtitles.
 */
object SubtitleDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        // UTF-16 LE / BE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return decodeWith(bytes, 2, "UTF-16LE") ?: String(bytes, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return decodeWith(bytes, 2, "UTF-16BE") ?: String(bytes, Charsets.UTF_8)
        }

        // Strict UTF-8 first.
        strictUtf8(bytes)?.let { return it }

        // Then the usual CJK encodings seen in the wild.
        for (charset in listOf("GBK", "GB18030", "Big5")) {
            val text = decodeWith(bytes, 0, charset)
            if (text != null && text.isNotBlank()) return text
        }

        return String(bytes, Charsets.UTF_8)
    }

    /** Decode with [charset], returning null when the bytes do not validate. */
    private fun decodeWith(bytes: ByteArray, offset: Int, charset: String): String? = runCatching {
        val decoder = java.nio.charset.Charset.forName(charset).newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }.getOrNull()

    private fun strictUtf8(bytes: ByteArray): String? = decodeWith(bytes, 0, "UTF-8")

    /**
     * Media3 parses SubRip natively, so SRT is passed through untouched.
     * VTT is normalised to start with the required `WEBVTT` header.
     */
    fun ensureVtt(text: String): String {
        val clean = text.removePrefix("\uFEFF")
        val head = clean.trimStart()
        return if (head.startsWith("WEBVTT", ignoreCase = true)) clean else "WEBVTT\n\n$clean"
    }
}
