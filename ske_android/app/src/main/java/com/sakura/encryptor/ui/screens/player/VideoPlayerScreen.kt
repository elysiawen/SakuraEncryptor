package com.sakura.encryptor.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.core.playlist.PlaylistItem
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.core.subtitle.SubtitleMatcher
import com.sakura.encryptor.core.subtitle.SubtitleTrack
import com.sakura.encryptor.data.prefs.SettingsStore
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.PlaybackErrorOverlay
import com.sakura.encryptor.ui.components.PlaylistSheet
import com.sakura.encryptor.ui.components.describePlaybackError
import com.sakura.encryptor.ui.navigation.PlaybackSource
import com.sakura.encryptor.ui.rememberAppViewModel
import com.sakura.encryptor.ui.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Gesture being performed on the video surface. */
private enum class VideoGesture { NONE, SEEK, BRIGHTNESS, VOLUME }

/**
 * Full-screen video player with custom controls, gestures and side-loaded
 * subtitles.
 *
 * - drag horizontally → scrub
 * - drag vertically on the left half → brightness
 * - drag vertically on the right half → volume
 * - tap → toggle controls, double tap → play/pause
 *
 * Subtitles are discovered in [directory] using the same naming rules as the
 * web client, decrypted if needed, and attached as side-loaded tracks.
 */
@Composable
fun VideoPlayerScreen(
    uri: String,
    displayName: String,
    source: PlaybackSource,
    directory: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val container = appContainer()
    val playbackViewModel = rememberAppViewModel { PlaybackViewModel(it.database) }
    val subtitleRepository = container.subtitleRepository

    val password = remember(source) {
        when (source) {
            PlaybackSource.LOCAL -> container.localVault.password.orEmpty()
            PlaybackSource.CLOUD -> container.vault.password.orEmpty()
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            // Must be DefaultMediaSourceFactory: only it expands a MediaItem's
            // subtitleConfigurations into side-loaded sources. A bare
            // ProgressiveMediaSource.Factory silently ignores them.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(container.skeDataSourceFactory)
            )
            // Take audio focus so a call or another player pauses us instead of
            // both sounding at once, and stop on unplug.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }

    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var selectedTrack by remember { mutableStateOf<SubtitleTrack?>(null) }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var showSubtitleMenu by remember { mutableStateOf(false) }

    // Decrypting a block can stall playback; show a spinner rather than leaving
    // the user staring at a frozen frame wondering if it hung. A short grace
    // period keeps ordinary seeks from flashing it.
    var isBuffering by remember { mutableStateOf(true) }
    var showBuffering by remember { mutableStateOf(false) }

    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            delay(350)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }

    // ---- playlist -----------------------------------------------------------
    // Sibling videos sitting in the same folder, like the web client offers.
    var playlist by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var playlistIndex by remember { mutableIntStateOf(-1) }
    var showPlaylist by remember { mutableStateOf(false) }

    // Set when playback fails irrecoverably, so the user sees a reason and a
    // way out instead of a black rectangle.
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }

    // The playing media is held in state rather than read from the arguments so
    // switching episodes stays inside this screen — no re-navigation, so the
    // player instance (and its buffer) survives.
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
            kind = MediaKind.VIDEO,
            nameKey = nameKey,
        )
        playlist = items
        playlistIndex = items.indexOfFirst { it.displayName == currentName }
    }

    val maxVolume = remember { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }
    var brightness by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.5f)
    }
    var volume by remember {
        mutableFloatStateOf(
            audioManager?.let {
                it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
            } ?: 0.5f
        )
    }

    var gestureHint by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        // Watching a video should not be interrupted by the screen timing out.
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val position = exoPlayer.currentPosition
            val duration = exoPlayer.duration
            if (position > 0) {
                playbackViewModel.savePosition(
                    key = currentUri,
                    displayName = currentName,
                    remotePath = currentUri,
                    server = container.aListSession.server,
                    positionMs = position,
                    durationMs = if (duration > 0) duration else 0L,
                )
            }
            activity?.let { act ->
                val params = act.window.attributes
                params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                act.window.attributes = params
            }
            subtitleRepository.clearCache()
            exoPlayer.release()
        }
    }

    // Subtitles are rendered by Compose instead of PlayerView's built-in
    // SubtitleView. The built-in one lays every cue of the same instant out at
    // the same coordinates, so bilingual tracks (two cues sharing one timestamp)
    // print on top of each other. Flattening them into a single multi-line block
    // keeps them stacked, and lets the styling match the rest of the app.
    var subtitleText by remember { mutableStateOf<String?>(null) }

    // Subtitle size is persisted, but mirrored locally so dragging the slider
    // previews immediately instead of waiting for a DataStore round-trip.
    val savedSubtitleSize by container.settings.subtitleTextSize.collectAsState(
        initial = SettingsStore.DEFAULT_SUBTITLE_TEXT_SIZE
    )
    var subtitleSize by remember { mutableFloatStateOf(savedSubtitleSize.toFloat()) }
    LaunchedEffect(savedSubtitleSize) { subtitleSize = savedSubtitleSize.toFloat() }
    val settingsScope = rememberCoroutineScope()

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                // Reaching READY means whatever went wrong has been recovered.
                if (playbackState == Player.STATE_READY) playbackError = null
                // Roll on to the next episode when one finishes.
                if (playbackState == Player.STATE_ENDED) playNext()
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = describePlaybackError(error)
            }

            override fun onCues(cueGroup: CueGroup) {
                val lines = cueGroup.cues
                    .asSequence()
                    .mapNotNull { cue -> cue.text?.toString() }
                    .flatMap { text -> text.split('\n').asSequence() }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toList()
                subtitleText = lines.joinToString("\n").ifBlank { null }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentUri, retryToken) {
        val resumeAt = playbackViewModel.resumePosition(currentUri)
        val item = MediaItem.Builder()
            .setUri(Uri.parse(currentUri))
            .setMimeType(MediaTypes.mimeOf(currentName))
            .build()

        exoPlayer.setMediaItem(item, resumeAt.coerceAtLeast(0))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Discover subtitles in the background and attach them once ready. Playback
    // starts immediately; the item is rebuilt at the current position when the
    // tracks arrive.
    LaunchedEffect(currentUri, directory) {
        // A different episode may come with different (or no) subtitles.
        subtitleTracks = emptyList()
        selectedTrack = null
        subtitleText = null

        if (directory.isBlank() || password.isEmpty()) return@LaunchedEffect

        val nameKey = runCatching { NameKeyCache.get(password) }.getOrNull()
            ?: return@LaunchedEffect

        val tracks = if (source == PlaybackSource.LOCAL) {
            subtitleRepository.scanLocal(Uri.parse(directory), currentName, nameKey)
        } else {
            subtitleRepository.scanCloud(directory, currentName, nameKey)
        }
        if (tracks.isEmpty()) return@LaunchedEffect

        val configs = tracks.mapNotNull { track ->
            val file = subtitleRepository.materialize(track, password)
                ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setMimeType(SubtitleMatcher.mimeTypeOf(track.extension))
                .setLanguage(track.languageTag.ifEmpty { null })
                .setLabel(track.label)
                .setSelectionFlags(
                    if (track == tracks.first()) C.SELECTION_FLAG_DEFAULT else 0
                )
                .build()
        }
        if (configs.isEmpty()) return@LaunchedEffect

        subtitleTracks = tracks
        selectedTrack = tracks.first()
        subtitlesEnabled = true

        val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exoPlayer.isPlaying
        val itemWithSubs = MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMimeType(MediaTypes.mimeOf(displayName))
            .setSubtitleConfigurations(configs)
            .build()

        exoPlayer.setMediaItem(itemWithSubs, resumeAt)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }

    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            if (!isDragging) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            val reported = exoPlayer.duration
            durationMs = if (reported > 0) reported else 0L
            delay(300)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val position = exoPlayer.currentPosition
            val duration = exoPlayer.duration
            if (position > 0 && duration > 0) {
                playbackViewModel.savePosition(
                    key = currentUri,
                    displayName = currentName,
                    remotePath = currentUri,
                    server = container.aListSession.server,
                    positionMs = position,
                    durationMs = duration,
                )
            }
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, isDragging) {
        if (controlsVisible && isPlaying && !isDragging) {
            delay(3_500)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(900)
            gestureHint = null
        }
    }

    /** Switch the active text track, or turn subtitles off entirely. */
    fun applySubtitle(track: SubtitleTrack?) {
        selectedTrack = track
        subtitlesEnabled = track != null
        // Drop the outgoing track's text; the incoming one fills it on its next cue.
        subtitleText = null

        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)

        if (track == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            // Prefer an exact override; preferring a language would fail for
            // bare matches (`movie.srt`) that carry no language tag.
            val group = exoPlayer.currentTracks.groups.firstOrNull { candidate ->
                candidate.type == C.TRACK_TYPE_TEXT &&
                    candidate.length > 0 &&
                    runCatching { candidate.getTrackFormat(0).label }.getOrNull() == track.label
            }
            if (group != null) {
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, /* trackIndex = */ 0)
                )
            } else if (track.languageTag.isNotEmpty()) {
                builder.setPreferredTextLanguage(track.languageTag)
            }
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    val shownPosition = if (isDragging) dragPosition else positionMs
    val sliderMax = durationMs.toFloat().coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Subtitles are drawn by Compose; see [subtitleText].
                    subtitleView?.visibility = android.view.View.GONE
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- gestures -------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            controlsVisible = true
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x

                        var gesture = VideoGesture.NONE
                        var accumulatedX = 0f
                        var accumulatedY = 0f
                        var seekStartMs = 0L
                        var valueStart = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            accumulatedX += change.positionChange().x
                            accumulatedY += change.positionChange().y

                            if (gesture == VideoGesture.NONE) {
                                val threshold = 16.dp.toPx()
                                if (abs(accumulatedX) > threshold || abs(accumulatedY) > threshold) {
                                    gesture = when {
                                        abs(accumulatedX) > abs(accumulatedY) -> VideoGesture.SEEK
                                        startX < size.width / 2f -> VideoGesture.BRIGHTNESS
                                        else -> VideoGesture.VOLUME
                                    }
                                    seekStartMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                                    valueStart = when (gesture) {
                                        VideoGesture.BRIGHTNESS -> brightness
                                        else -> volume
                                    }
                                }
                            }

                            when (gesture) {
                                VideoGesture.SEEK -> {
                                    if (durationMs > 0) {
                                        val delta = (accumulatedX / size.width) * durationMs
                                        dragPosition = (seekStartMs + delta.toLong())
                                            .coerceIn(0L, durationMs)
                                        isDragging = true
                                        gestureHint =
                                            "${formatDuration(dragPosition)} / ${formatDuration(durationMs)}"
                                    }
                                }

                                VideoGesture.BRIGHTNESS -> {
                                    val next = (valueStart - accumulatedY / (size.height / 2f))
                                        .coerceIn(0f, 1f)
                                    brightness = next
                                    activity?.let { act ->
                                        val params = act.window.attributes
                                        params.screenBrightness = next.coerceAtLeast(0.01f)
                                        act.window.attributes = params
                                    }
                                    gestureHint = "亮度 ${(next * 100).roundToInt()}%"
                                }

                                VideoGesture.VOLUME -> {
                                    val next = (valueStart - accumulatedY / (size.height / 2f))
                                        .coerceIn(0f, 1f)
                                    volume = next
                                    audioManager?.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        (next * maxVolume).roundToInt().coerceIn(0, maxVolume),
                                        0,
                                    )
                                    gestureHint = "音量 ${(next * 100).roundToInt()}%"
                                }

                                VideoGesture.NONE -> Unit
                            }

                            change.consume()
                        }

                        if (gesture == VideoGesture.SEEK && isDragging) {
                            exoPlayer.seekTo(dragPosition)
                            positionMs = dragPosition
                            isDragging = false
                        }
                        gestureHint = null
                    }
                },
        )

        // ---- subtitles -------------------------------------------------------
        subtitleText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = if (controlsVisible) 104.dp else 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = subtitleSize.sp,
                    lineHeight = (subtitleSize * 1.45f).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // ---- gesture readout ------------------------------------------------
        gestureHint?.let { hint ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xCC000000),
                ) {
                    Text(
                        text = hint,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    )
                }
            }
        }

        // ---- buffering -------------------------------------------------------
        if (showBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(42.dp),
                )
            }
        }

        // ---- chrome ---------------------------------------------------------
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000)))
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )

                    if (playlist.size > 1) {
                        IconButton(onClick = { showPlaylist = true }) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistPlay,
                                contentDescription = "播放列表",
                                tint = Color.White,
                            )
                        }
                    }

                    if (subtitleTracks.isNotEmpty()) {
                        IconButton(onClick = { showSubtitleMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Subtitles,
                                contentDescription = "字幕",
                                tint = if (subtitlesEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0x99FFFFFF)
                                },
                            )
                        }
                    }
                }

                // Hidden while buffering so it doesn't compete with the spinner.
                if (!showBuffering) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0x59000000),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(74.dp)
                            .clickable {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color(0x00000000), Color(0xCC000000)))
                        )
                        .navigationBarsPadding()
                        .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 8.dp),
                ) {
                    Slider(
                        value = shownPosition.toFloat().coerceIn(0f, sliderMax),
                        onValueChange = {
                            isDragging = true
                            dragPosition = it.toLong()
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo(dragPosition)
                            positionMs = dragPosition
                            isDragging = false
                        },
                        valueRange = 0f..sliderMax,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color(0x59FFFFFF),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatDuration(shownPosition),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                        Text(
                            text = formatDuration(durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xB3FFFFFF),
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
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

    if (showSubtitleMenu) {
        AlertDialog(
            onDismissRequest = { showSubtitleMenu = false },
            title = { Text("字幕") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SubtitleOption(
                        label = "关闭字幕",
                        selected = !subtitlesEnabled,
                        onClick = {
                            applySubtitle(null)
                            showSubtitleMenu = false
                        },
                    )
                    subtitleTracks.forEach { track ->
                        SubtitleOption(
                            label = track.displayName,
                            secondary = track.label,
                            selected = subtitlesEnabled && selectedTrack == track,
                            onClick = {
                                applySubtitle(track)
                                showSubtitleMenu = false
                            },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "字幕大小", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${subtitleSize.roundToInt()} sp",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "A",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = subtitleSize,
                            onValueChange = { subtitleSize = it },
                            onValueChangeFinished = {
                                settingsScope.launch {
                                    container.settings.setSubtitleTextSize(subtitleSize.roundToInt())
                                }
                            },
                            valueRange = SettingsStore.MIN_SUBTITLE_TEXT_SIZE.toFloat()..
                                SettingsStore.MAX_SUBTITLE_TEXT_SIZE.toFloat(),
                            steps = SettingsStore.MAX_SUBTITLE_TEXT_SIZE -
                                SettingsStore.MIN_SUBTITLE_TEXT_SIZE - 1,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        Text(
                            text = "A",
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleMenu = false }) { Text("完成") }
            },
        )
    }

    if (showPlaylist) {
        PlaylistSheet(
            items = playlist,
            currentIndex = playlistIndex,
            unit = "个",
            onSelect = { item ->
                playItem(item)
                showPlaylist = false
            },
            onDismiss = { showPlaylist = false },
        )
    }
}

@Composable
private fun SubtitleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    secondary: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** Walk the context chain to find the hosting [Activity]. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
