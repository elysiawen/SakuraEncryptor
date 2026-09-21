package com.sakura.encryptor.core.subtitle

/** A side-loaded subtitle that belongs to the video being played. */
data class SubtitleTrack(
    /** Remote (possibly encrypted) file name, or the SAF document name locally. */
    val fileName: String,
    /** Decrypted name, e.g. `movie.zh-CN.srt`. */
    val displayName: String,
    /** Human label, e.g. `简体中文 · SRT`. */
    val label: String,
    /** BCP-47-ish tag used to pick the default, e.g. `zh-cn`; empty for a bare match. */
    val languageTag: String,
    /** srt / ass / ssa / vtt */
    val extension: String,
    /** Cloud: the folder this file lives in. Local: the SAF tree URI. */
    val location: String,
    val fromCloud: Boolean,
)

/**
 * Port of the web client's subtitle discovery rules
 * (`ske_web/src/composables/useSubtitles.js`).
 *
 * A subtitle belongs to a video when its base name starts with the video's base
 * name, optionally followed by a language tag separated by `.`, `-`, `_` or a
 * space — so `movie.zh-CN.srt` and `movie.srt` both match `movie.mkv`.
 */
object SubtitleMatcher {

    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    private val LANGUAGE_LABELS = mapOf(
        "zh" to "中文", "chi" to "中文",
        "chs" to "简体中文", "sc" to "简体中文", "zh-cn" to "简体中文",
        "zh-hans" to "简体中文", "zh_cn" to "简体中文",
        "cht" to "繁體中文", "tc" to "繁體中文", "zh-tw" to "繁體中文",
        "zh-hant" to "繁體中文", "zh_tw" to "繁體中文",
        "en" to "English", "eng" to "English",
        "ja" to "日本語", "jp" to "日本語", "jpn" to "日本語",
        "ko" to "한국어", "kor" to "한국어",
        "fr" to "Français", "fre" to "Français",
        "de" to "Deutsch", "ger" to "Deutsch",
        "es" to "Español", "spa" to "Español",
        "ru" to "Русский", "rus" to "Русский",
        "bilingual" to "双语", "bi" to "双语",
    )

    /** The MIME type Media3 needs for a side-loaded subtitle of this kind. */
    fun mimeTypeOf(extension: String): String = when (extension.lowercase()) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "application/x-subrip"
    }

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isSubtitle(name: String): Boolean = extensionOf(name) in SUBTITLE_EXTENSIONS

    /** `a.b.srt` -> `a.b` */
    fun baseName(name: String): String {
        val index = name.lastIndexOf('.')
        return if (index > 0) name.substring(0, index) else name
    }

    /**
     * @return the language tag when [subtitleBase] belongs to the video, an empty
     *   string for an exact base-name match, or null when it does not match.
     */
    fun languageTagOf(videoBase: String, subtitleBase: String): String? {
        val video = videoBase.lowercase()
        val subtitle = subtitleBase.lowercase()

        if (subtitle == video) return ""
        if (subtitle.length > video.length && subtitle.startsWith(video)) {
            val separator = subtitle[video.length]
            if (separator == '.' || separator == '-' || separator == '_' || separator == ' ') {
                return subtitleBase.substring(video.length + 1)
            }
        }
        return null
    }

    fun labelOf(languageTag: String, extension: String): String {
        val type = extension.uppercase().ifEmpty { "SUB" }
        if (languageTag.isEmpty()) return type
        val language = LANGUAGE_LABELS[languageTag.lowercase()] ?: languageTag.uppercase()
        return "$language · $type"
    }

    /**
     * Pick the subtitles that belong to [videoDisplayName], in display order.
     *
     * @param candidates pairs of (remote/file name, decrypted name).
     */
    fun match(
        videoDisplayName: String,
        candidates: List<Pair<String, String>>,
        location: String,
        fromCloud: Boolean,
    ): List<SubtitleTrack> {
        val videoBase = baseName(videoDisplayName)

        val matched = candidates.mapNotNull { (fileName, decryptedName) ->
            if (!isSubtitle(decryptedName)) return@mapNotNull null
            val tag = languageTagOf(videoBase, baseName(decryptedName)) ?: return@mapNotNull null
            val extension = extensionOf(decryptedName)
            SubtitleTrack(
                fileName = fileName,
                displayName = decryptedName,
                label = labelOf(tag, extension),
                languageTag = tag,
                extension = extension,
                location = location,
                fromCloud = fromCloud,
            )
        }

        // Exact base-name match first, then alphabetically by language tag.
        return matched.sortedWith(
            compareBy(
                { if (it.languageTag.isEmpty()) 0 else 1 },
                { it.languageTag.lowercase() },
            )
        )
    }
}
