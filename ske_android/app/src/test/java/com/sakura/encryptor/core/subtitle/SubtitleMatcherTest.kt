package com.sakura.encryptor.core.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The matching rules are a direct port of the web client's `useSubtitles.js`,
 * so these cases mirror how subtitles are named in practice.
 */
class SubtitleMatcherTest {

    // -----------------------------------------------------------------------
    // Naming helpers
    // -----------------------------------------------------------------------

    @Test
    fun `strips only the last extension`() {
        assertEquals("a.b", SubtitleMatcher.baseName("a.b.srt"))
        assertEquals("movie", SubtitleMatcher.baseName("movie.mkv"))
        assertEquals("noext", SubtitleMatcher.baseName("noext"))
    }

    @Test
    fun `recognises subtitle extensions`() {
        assertTrue(SubtitleMatcher.isSubtitle("movie.srt"))
        assertTrue(SubtitleMatcher.isSubtitle("movie.ASS"))
        assertTrue(SubtitleMatcher.isSubtitle("movie.ssa"))
        assertTrue(SubtitleMatcher.isSubtitle("movie.vtt"))
        assertFalse(SubtitleMatcher.isSubtitle("movie.mkv"))
        assertFalse(SubtitleMatcher.isSubtitle("movie.srt.bak"))
    }

    // -----------------------------------------------------------------------
    // Language tag matching
    // -----------------------------------------------------------------------

    @Test
    fun `exact base name yields an empty tag`() {
        assertEquals("", SubtitleMatcher.languageTagOf("movie", "movie"))
    }

    @Test
    fun `accepts every documented separator`() {
        assertEquals("zh-CN", SubtitleMatcher.languageTagOf("movie", "movie.zh-CN"))
        assertEquals("zh", SubtitleMatcher.languageTagOf("movie", "movie-zh"))
        assertEquals("zh", SubtitleMatcher.languageTagOf("movie", "movie_zh"))
        assertEquals("zh", SubtitleMatcher.languageTagOf("movie", "movie zh"))
    }

    @Test
    fun `rejects unrelated or glued names`() {
        // No separator after the video name.
        assertNull(SubtitleMatcher.languageTagOf("movie", "moviex"))
        // Different base name entirely.
        assertNull(SubtitleMatcher.languageTagOf("movie", "other"))
        // Suffix is shorter than the video name.
        assertNull(SubtitleMatcher.languageTagOf("movie", "mov"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals("zh-CN", SubtitleMatcher.languageTagOf("Movie", "movie.zh-CN"))
    }

    // -----------------------------------------------------------------------
    // Labels
    // -----------------------------------------------------------------------

    @Test
    fun `labels translate known language tags`() {
        assertEquals("简体中文 · SRT", SubtitleMatcher.labelOf("zh-CN", "srt"))
        assertEquals("繁體中文 · ASS", SubtitleMatcher.labelOf("cht", "ass"))
        assertEquals("English · VTT", SubtitleMatcher.labelOf("en", "vtt"))
        assertEquals("双语 · SSA", SubtitleMatcher.labelOf("bilingual", "ssa"))
    }

    @Test
    fun `unknown tags fall back to upper case and bare matches show only the type`() {
        assertEquals("XX · SRT", SubtitleMatcher.labelOf("xx", "srt"))
        assertEquals("SRT", SubtitleMatcher.labelOf("", "srt"))
    }

    // -----------------------------------------------------------------------
    // Matching + ordering
    // -----------------------------------------------------------------------

    @Test
    fun `keeps only subtitles belonging to the video`() {
        val tracks = SubtitleMatcher.match(
            videoDisplayName = "movie.mkv",
            candidates = listOf(
                "tokenA" to "movie.zh-CN.srt",
                "tokenB" to "movie.en.ass",
                "tokenC" to "other.srt",
                "tokenD" to "movie.mkv",
            ),
            location = "/dir",
            fromCloud = true,
        )

        assertEquals(2, tracks.size)
        // Sorted lexicographically, so "en" precedes "zh-CN".
        assertEquals(listOf("en", "zh-CN"), tracks.map { it.languageTag }.sorted())
    }

    @Test
    fun `exact base name sorts before language variants`() {
        val tracks = SubtitleMatcher.match(
            videoDisplayName = "movie.mkv",
            candidates = listOf(
                "t1" to "movie.zh.srt",
                "t2" to "movie.srt",
                "t3" to "movie.en.srt",
            ),
            location = "/dir",
            fromCloud = true,
        )

        assertEquals(3, tracks.size)
        assertEquals("", tracks.first().languageTag)
    }

    @Test
    fun `language variants sort alphabetically`() {
        val tracks = SubtitleMatcher.match(
            videoDisplayName = "movie.mkv",
            candidates = listOf(
                "t1" to "movie.zh.srt",
                "t2" to "movie.en.srt",
                "t3" to "movie.ja.srt",
            ),
            location = "/dir",
            fromCloud = true,
        )

        assertEquals(listOf("en", "ja", "zh"), tracks.map { it.languageTag })
    }

    @Test
    fun `carries the remote name so the file can be fetched`() {
        val tracks = SubtitleMatcher.match(
            videoDisplayName = "movie.mkv",
            candidates = listOf("Zx9.token.ske" to "movie.zh.srt"),
            location = "/dir",
            fromCloud = true,
        )

        assertEquals("Zx9.token.ske", tracks.single().fileName)
        assertEquals("srt", tracks.single().extension)
        assertTrue(tracks.single().fromCloud)
    }

    @Test
    fun `mime types cover every supported extension`() {
        assertEquals("application/x-subrip", SubtitleMatcher.mimeTypeOf("srt"))
        assertEquals("text/vtt", SubtitleMatcher.mimeTypeOf("vtt"))
        assertEquals("text/x-ssa", SubtitleMatcher.mimeTypeOf("ass"))
        assertEquals("text/x-ssa", SubtitleMatcher.mimeTypeOf("ssa"))
    }
}

class SubtitleDecoderTest {

    @Test
    fun `decodes plain utf8`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n你好\n"
        assertEquals(text, SubtitleDecoder.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `strips a utf8 bom`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "WEBVTT\n".toByteArray(Charsets.UTF_8)
        assertEquals("WEBVTT\n", SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `decodes utf16 little endian with bom`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + "字幕".toByteArray(Charsets.UTF_16LE)
        assertEquals("字幕", SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `falls back to gbk for chinese subtitles`() {
        val gbk = "中文字幕测试".toByteArray(Charset.forName("GBK"))
        assertEquals("中文字幕测试", SubtitleDecoder.decode(gbk))
    }

    @Test
    fun `adds the vtt header when missing`() {
        val result = SubtitleDecoder.ensureVtt("00:00.000 --> 00:01.000\nhi")
        assertTrue(result.startsWith("WEBVTT"))
    }

    @Test
    fun `keeps an existing vtt header`() {
        val text = "WEBVTT\n\n00:00.000 --> 00:01.000\nhi"
        assertEquals(text, SubtitleDecoder.ensureVtt(text))
    }

    @Test
    fun `empty input decodes to empty text`() {
        assertEquals("", SubtitleDecoder.decode(ByteArray(0)))
    }
}
