package com.sakura.encryptor.ui.navigation

import android.net.Uri

/** All navigation routes in one place. */
object Routes {
    const val BROWSE = "browse"
    const val LOCAL = "local"

    /** Standalone file-encryption workspace, kept out of the local browser. */
    const val ENCRYPT = "encrypt"

    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    /** Cloud profile management. */
    const val PROFILES = "profiles"

    const val PROFILE_ARG_ID = "profileId"

    /** `profileId = -1` means "create a new profile". */
    const val PROFILE_EDIT = "profile_edit?$PROFILE_ARG_ID={$PROFILE_ARG_ID}"
    const val NEW_PROFILE_ID = -1L

    fun profileEdit(id: Long?): String = "profile_edit?$PROFILE_ARG_ID=${id ?: NEW_PROFILE_ID}"

    const val PLAYER_ARG_URI = "uri"
    const val PLAYER_ARG_NAME = "name"
    const val PLAYER_ARG_SOURCE = "source"

    /** Folder the media came from — used to discover side-loaded subtitles. */
    const val PLAYER_ARG_DIR = "dir"

    const val PLAYER = "player?$PLAYER_ARG_URI={$PLAYER_ARG_URI}" +
        "&$PLAYER_ARG_NAME={$PLAYER_ARG_NAME}" +
        "&$PLAYER_ARG_SOURCE={$PLAYER_ARG_SOURCE}" +
        "&$PLAYER_ARG_DIR={$PLAYER_ARG_DIR}"

    fun player(
        uri: String,
        displayName: String,
        source: String,
        dir: String = "",
    ): String =
        "player?$PLAYER_ARG_URI=${Uri.encode(uri)}" +
            "&$PLAYER_ARG_NAME=${Uri.encode(displayName)}" +
            "&$PLAYER_ARG_SOURCE=${Uri.encode(source)}" +
            "&$PLAYER_ARG_DIR=${Uri.encode(dir)}"

    /** The drawer destinations, in order. Encryption leads and is the start screen. */
    val mainDestinations = listOf(ENCRYPT, BROWSE, LOCAL, DOWNLOADS, SETTINGS)
}

/** Where a playback request came from. */
enum class PlaybackSource {
    /** A remote AList raw_url consumed through the decrypting HTTP source. */
    CLOUD,

    /** A local content:// document consumed through the decrypting SAF source. */
    LOCAL;

    companion object {
        fun fromId(id: String?): PlaybackSource =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: CLOUD
    }
}
