package com.sakura.encryptor.core.player

/**
 * Keys carried on every music queue entry so the track can be identified from
 * outside the player screen.
 *
 * The mini player needs to reopen the full player for whatever is playing, and
 * it only has the session's [androidx.media3.common.MediaItem] to go on. The
 * title is useless for that — it holds the *tag* after backfill, while the
 * player matches queue entries by their decrypted file name.
 */
object PlaybackExtras {

    /** The decrypted file name, which is what identifies an entry in the queue. */
    const val DISPLAY_NAME = "sakura.displayName"

    /** [com.sakura.encryptor.ui.navigation.PlaybackSource] id. */
    const val SOURCE = "sakura.source"

    /** Folder the queue was built from, empty when unknown. */
    const val DIRECTORY = "sakura.directory"
}
