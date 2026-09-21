package com.sakura.encryptor.ui.screens.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.sakura.encryptor.core.player.LyricLine
import com.sakura.encryptor.core.player.LyricsLoader
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.core.playlist.PlaylistItem
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.PlaybackErrorOverlay
import com.sakura.encryptor.ui.components.PlaylistSheet
import com.sakura.encryptor.ui.components.describePlaybackError
import com.sakura.encryptor.ui.navigation.PlaybackSource
import com.sakura.encryptor.ui.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MusicPlayerScreen(
    uri: String,
    displayName: String,
    source: PlaybackSource,
    directory: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()

    // Local files are protected by the standalone local password, remote ones
    // by the active cloud profile's password.
    val password = remember(source) {
        when (source) {
            PlaybackSource.LOCAL -> container.localVault.password.orEmpty()
            PlaybackSource.CLOUD -> container.vault.password.orEmpty()
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(container.skeDataSourceFactory))
            .build()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsLoading by remember { mutableStateOf(true) }

    var isBuffering by remember { mutableStateOf(true) }
    var showBuffering by remember { mutableStateOf(false) }

    // Grace period so brief seeks don't flash the spinner.
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            delay(350)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }

    // ---- playlist -----------------------------------------------------------
    var playlist by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var playlistIndex by remember { mutableIntStateOf(-1) }
    var showPlaylist by remember { mutableStateOf(false) }

    // Set when playback fails irrecoverably, so the user gets a reason and a
    // way out instead of a silent stop.
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }

    var currentUri by remember { mutableStateOf(uri) }
    var currentName by remember { mutableStateOf(displayName) }
    val playerScope = rememberCoroutineScope()

    fun playItem(item: PlaylistItem) {
        playerScope.launch {
            val resolved = container.playlistRepository
                .resolveUri(item, source == PlaybackSource.CLOUD)
                ?: return@launch
            currentName = item.displayName
            currentUri = resolved
        }
    }

    fun playNext() {
        if (playlist.size <= 1 || playlistIndex < 0) return
        playItem(playlist[(playlistIndex + 1) % playlist.size])
    }

    fun playPrevious() {
        if (playlist.size <= 1 || playlistIndex < 0) return
        playItem(playlist[(playlistIndex - 1 + playlist.size) % playlist.size])
    }

    LaunchedEffect(currentUri, directory) {
        if (directory.isBlank()) return@LaunchedEffect
        val nameKey = runCatching { NameKeyCache.get(password) }.getOrNull()
            ?: return@LaunchedEffect

        val items = container.playlistRepository.load(
            directory = directory,
            fromCloud = source == PlaybackSource.CLOUD,
            kind = MediaKind.AUDIO,
            nameKey = nameKey,
        )
        playlist = items
        playlistIndex = items.indexOfFirst { it.displayName == currentName }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                // Reaching READY means whatever went wrong has been recovered.
                if (playbackState == Player.STATE_READY) playbackError = null
                // Auto-advance, matching the web client's music view.
                if (playbackState == Player.STATE_ENDED) playNext()
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = describePlaybackError(error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentUri, retryToken) {
        val item = MediaItem.Builder()
            .setUri(Uri.parse(currentUri))
            .setMimeType(MediaTypes.mimeOf(currentName))
            .build()
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Read embedded lyrics from the first decrypted block.
    LaunchedEffect(currentUri) {
        lyricsLoading = true
        lyrics = runCatching {
            LyricsLoader.load(container.cipherBlockSourceFactory.create(Uri.parse(currentUri)), password)
        }.getOrNull().orEmpty()
        lyricsLoading = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            if (!isDragging) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            val reported = exoPlayer.duration
            durationMs = if (reported > 0) reported else 0L
            delay(400)
        }
    }

    val shownPosition = if (isDragging) dragValue.toLong() else positionMs
    val sliderMax = durationMs.toFloat().coerceAtLeast(1f)

    val currentLyricIndex = remember(lyrics, shownPosition) {
        if (lyrics.isEmpty()) {
            -1
        } else {
            lyrics.indexOfLast { it.timeMs in 1..shownPosition }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentLyricIndex) {
        if (currentLyricIndex >= 0) {
            listState.animateScrollToItem((currentLyricIndex - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.weight(1f))
            if (playlist.size > 1) {
                IconButton(onClick = { showPlaylist = true }) {
                    Icon(Icons.Rounded.PlaylistPlay, contentDescription = "播放列表")
                }
            }
            IconButton(
                onClick = {
                    repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    exoPlayer.repeatMode = repeatMode
                }
            ) {
                Icon(
                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = "循环模式",
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The disc itself doubles as the loading indicator.
            if (showBuffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(46.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(70.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = currentName,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${MediaTypes.labelOf(currentName)} · 流式解密播放",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                lyricsLoading -> Text(
                    text = "正在读取歌词…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                lyrics.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "未找到内嵌歌词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    itemsIndexed(lyrics) { index, line ->
                        LyricRow(line = line, isCurrent = index == currentLyricIndex)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Slider(
            value = shownPosition.toFloat().coerceIn(0f, sliderMax),
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                exoPlayer.seekTo(dragValue.toLong())
                isDragging = false
            },
            valueRange = 0f..sliderMax,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(shownPosition),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = { playPrevious() },
                enabled = playlist.size > 1,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首")
            }

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0L)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.Replay10, contentDescription = "后退 10 秒")
            }

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                modifier = Modifier.size(70.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(34.dp),
                )
            }

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    val duration = exoPlayer.duration
                    val target = exoPlayer.currentPosition + 10_000
                    exoPlayer.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.Forward10, contentDescription = "前进 10 秒")
            }

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = { playNext() },
                enabled = playlist.size > 1,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
            }
        }

        Spacer(Modifier.height(30.dp))
    }

    playbackError?.let { message ->
        PlaybackErrorOverlay(
            message = message,
            onRetry = {
                playbackError = null
                retryToken++
            },
            onBack = onBack,
        )
    }

    if (showPlaylist) {
        PlaylistSheet(
            items = playlist,
            currentIndex = playlistIndex,
            unit = "首",
            onSelect = { item ->
                playItem(item)
                showPlaylist = false
            },
            onDismiss = { showPlaylist = false },
        )
    }
}

@Composable
private fun LyricRow(line: LyricLine, isCurrent: Boolean) {
    Text(
        text = line.text,
        style = if (isCurrent) {
            MaterialTheme.typography.titleMedium
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp, horizontal = 6.dp),
    )
}
