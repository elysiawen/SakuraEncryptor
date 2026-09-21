package com.sakura.encryptor.ui.screens.player

import androidx.compose.runtime.Composable
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.ui.navigation.PlaybackSource

/** Dispatches to the right player for the decrypted file name. */
@Composable
fun PlayerScreen(
    uri: String,
    displayName: String,
    source: PlaybackSource,
    directory: String,
    onBack: () -> Unit,
) {
    when (MediaTypes.kindOf(displayName)) {
        MediaKind.AUDIO -> MusicPlayerScreen(uri, displayName, source, directory, onBack)
        MediaKind.IMAGE -> ImageViewerScreen(uri, displayName, source, directory, onBack)
        // Video is the only one that also looks for side-loaded subtitles.
        else -> VideoPlayerScreen(uri, displayName, source, directory, onBack)
    }
}
