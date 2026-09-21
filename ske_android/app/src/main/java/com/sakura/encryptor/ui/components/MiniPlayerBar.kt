package com.sakura.encryptor.ui.components

import android.content.ComponentName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import com.sakura.encryptor.core.player.MusicPlaybackService
import com.sakura.encryptor.core.player.PlaybackExtras
import kotlinx.coroutines.delay

/**
 * The app's single connection to the music service.
 *
 * Held above the navigation graph so the player screen and the mini player drive
 * the same session rather than opening competing connections to it.
 */
val LocalMusicController = compositionLocalOf<MediaController?> { null }

/** Connects to [MusicPlaybackService] for as long as the caller is composed. */
@Composable
fun rememberMusicController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            // get() does not block: the listener only runs once it is complete.
            { controller = runCatching { future.get() }.getOrNull() },
            MoreExecutors.directExecutor(),
        )
        onDispose {
            controller = null
            // Releasing the connection leaves the service playing; that is the
            // whole point of holding the player over there.
            MediaController.releaseFuture(future)
        }
    }

    return controller
}

/**
 * Compact now-playing bar, the way music apps keep the current track reachable
 * from anywhere in the app.
 *
 * Draws nothing at all when the session holds no music, so a playing video — or
 * an idle service — never leaves an empty strip behind.
 *
 * @param enabled false inside a player screen, where the bar would only repeat
 *   what is already on screen.
 * @param onOpen invoked with the playing item so the caller can reopen the full
 *   player for it.
 */
@Composable
fun MiniPlayerBar(
    controller: MediaController?,
    enabled: Boolean,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = controller

    var item by remember { mutableStateOf<MediaItem?>(null) }

    // Kept apart from [item] on purpose: an item's own metadata holds only what
    // the queue put there, while artwork and tags live in the session's combined
    // copy. Reading the wrong one is why the cover went missing here.
    var metadata by remember { mutableStateOf<MediaMetadata?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        if (player == null) {
            item = null
            metadata = null
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    item = mediaItem
                    metadata = player.mediaMetadata
                }

                // Cover art and tags arrive after the item itself does.
                override fun onMediaMetadataChanged(value: MediaMetadata) {
                    metadata = value
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            }
            player.addListener(listener)

            // A listener hears changes only, and attaching mid-track produces
            // none — so take the current state directly.
            item = player.currentMediaItem
            metadata = player.mediaMetadata
            isPlaying = player.isPlaying
            onDispose { player.removeListener(listener) }
        }
    }

    // Only music entries carry these extras; a video would not.
    val music = item?.takeIf {
        it.mediaMetadata.extras?.containsKey(PlaybackExtras.DISPLAY_NAME) == true
    }
    val visible = enabled && music != null

    LaunchedEffect(player, visible) {
        val active = player ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        while (true) {
            positionMs = active.currentPosition.coerceAtLeast(0L)
            durationMs = active.duration.takeIf { it > 0 } ?: 0L
            isPlaying = active.isPlaying
            delay(500)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val current = music ?: return@AnimatedVisibility
        val active = player ?: return@AnimatedVisibility
        // The session's combined metadata, not the item's own: only the former
        // carries the artwork ExoPlayer decoded.
        val info = metadata ?: return@AnimatedVisibility

        val fraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        val artwork = info.artworkData
        val artist = info.artist?.toString().orEmpty()

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onOpen(current) },
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(
                        start = 10.dp,
                        end = 4.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artwork != null) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(11.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = info.title?.toString().orEmpty().ifEmpty { "未知曲目" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artist.isNotEmpty()) {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (active.isPlaying) active.pause() else active.play() },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (isPlaying) "暂停" else "播放",
                        )
                    }

                    IconButton(onClick = { active.seekToNextMediaItem() }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
                    }
                }

                // Hairline progress, drawn rather than using a slider so the bar
                // stays compact.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
