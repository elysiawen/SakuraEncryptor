package com.sakura.encryptor.core.player

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

/** Broad media categories the app knows how to present. */
enum class MediaKind { VIDEO, AUDIO, IMAGE, OTHER }

/**
 * Maps a (decrypted) file name to a media kind and MIME type.
 *
 * The MIME type matters for the streaming player: ExoPlayer uses it to pick
 * the right extractor, and a decrypted `.ske` gives it no other hint.
 */
object MediaTypes {

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "flv", "wmv", "mpg", "mpeg")
    private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "ape", "alac")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): MediaKind = when (extensionOf(name)) {
        in VIDEO -> MediaKind.VIDEO
        in AUDIO -> MediaKind.AUDIO
        in IMAGE -> MediaKind.IMAGE
        else -> MediaKind.OTHER
    }

    fun isPlayable(name: String): Boolean {
        val kind = kindOf(name)
        return kind == MediaKind.VIDEO || kind == MediaKind.AUDIO || kind == MediaKind.IMAGE
    }

    fun mimeOf(name: String): String = when (extensionOf(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "mpg", "mpeg" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"

        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        "ape" -> "audio/x-ape"

        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"

        else -> "application/octet-stream"
    }

    /** A human-friendly label for the file kind, used on cards and badges. */
    fun labelOf(name: String): String = when (kindOf(name)) {
        MediaKind.VIDEO -> t(R.string.player_video)
        MediaKind.AUDIO -> t(R.string.player_audio)
        MediaKind.IMAGE -> t(R.string.player_image)
        MediaKind.OTHER -> t(R.string.player_files)
    }
}
