package com.sakura.encryptor.ui.screens.player

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.sakura.encryptor.BuildConfig
import com.sakura.encryptor.core.player.AudioTagLoader
import com.sakura.encryptor.core.player.AudioTags
import com.sakura.encryptor.core.player.PlaybackExtras
import com.sakura.encryptor.core.player.LrcParser
import com.sakura.encryptor.core.player.LyricLine
import com.sakura.encryptor.core.player.LyricsLoader
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.core.player.MusicPlaybackService
import com.sakura.encryptor.core.player.queueUriFor
import com.sakura.encryptor.core.playlist.PlaylistItem
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.LocalMusicController
import com.sakura.encryptor.ui.components.PlaybackErrorOverlay
import com.sakura.encryptor.ui.components.PlaylistSheet
import com.sakura.encryptor.ui.components.describePlaybackError
import com.sakura.encryptor.ui.navigation.PlaybackSource
import com.sakura.encryptor.ui.util.formatDuration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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

    // The player lives in [MusicPlaybackService]; this screen only drives the
    // connection the app already holds, so the mini player and this screen are
    // always talking to the same session.
    val controller = LocalMusicController.current

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsLoading by remember { mutableStateOf(true) }
    var artwork by remember { mutableStateOf<ByteArray?>(null) }

    // Tags read from the file itself. They arrive with the same metadata as the
    // art, and supersede the decrypted file name.
    var tagTitle by remember { mutableStateOf<String?>(null) }
    var tagArtist by remember { mutableStateOf<String?>(null) }
    var tagAlbum by remember { mutableStateOf<String?>(null) }

    var isBuffering by remember { mutableStateOf(true) }
    var showBuffering by remember { mutableStateOf(false) }

    // The grace period only exists to keep a *fast* seek from flickering the
    // indicator. A cloud seek takes long enough to be seen, and that is wanted:
    // a drag with no sign of life reads as "it did nothing". Showing it is cheap
    // now that it sits in the corner instead of replacing the cover.
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            delay(BUFFER_GRACE_MS)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }

    // ---- playlist -----------------------------------------------------------
    var playlist by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var showPlaylist by remember { mutableStateOf(false) }

    val fromCloud = source == PlaybackSource.CLOUD

    /**
     * The track's own title, or null when the container carries none.
     *
     * Reading this *before* playback is what lets the media notification show the
     * real name: the notification renders the MediaItem's own metadata, and a
     * title cannot be corrected afterwards without restarting the track.
     *
     * Reads through [AppContainer.trackTags], so the full tag set (and the
     * miss, cached as an empty object) is shared with the now-playing screen.
     */
    suspend fun tagTitleOf(item: PlaylistItem): String? {
        container.trackTags[item.locator]?.let { tags ->
            return tags.title?.takeIf { it.isNotBlank() }
        }

        val uri = container.playlistRepository.resolveUri(item, fromCloud) ?: return null
        val tags = runCatching {
            AudioTagLoader.load(container.cipherBlockSourceFactory.create(Uri.parse(uri)), password)
        }.getOrNull()
        container.trackTags[item.locator] = tags ?: AudioTags()
        return tags?.title?.takeIf { it.isNotBlank() }
    }

    // Which queue entry the session is on, tracked as state because reading it
    // straight off the controller is not observable from Compose.
    var currentLocator by remember { mutableStateOf<String?>(null) }
    val playlistIndex = playlist.indexOfFirst { it.locator == currentLocator }

    // Set when playback fails irrecoverably, so the user gets a reason and a
    // way out instead of a silent stop.
    var playbackError by remember { mutableStateOf<String?>(null) }

    val currentName = playlist.firstOrNull { it.locator == currentLocator }
        ?.displayName
        ?: displayName

    fun playItem(item: PlaylistItem) {
        val index = playlist.indexOfFirst { it.locator == item.locator }
        if (index >= 0) controller?.seekTo(index, 0L)
    }

    fun playNext() {
        controller?.seekToNextMediaItem()
    }

    fun playPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    // Keep the screen on while the player is open: glanceable playback (lyrics,
    // cover) is the page's whole job, and a dimming screen fights that.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(controller) {
        val player = controller
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    // Reaching READY means whatever went wrong has been recovered.
                    if (playbackState == Player.STATE_READY) playbackError = null
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    trace("transition mediaId=${mediaItem?.mediaId}")
                    currentLocator = mediaItem?.mediaId
                    // New track: drop the previous track's picture and tags until
                    // its own arrive.
                    artwork = null
                    tagTitle = null
                    tagArtist = null
                    tagAlbum = null
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    // The picture comes from here regardless: ExoPlayer decodes
                    // it out of the container, which is exactly what the media
                    // notification renders, and it reaches formats our own parser
                    // cannot (m4a, for one).
                    mediaMetadata.artworkData?.let { artwork = it }

                    // Tags are a fallback for those same formats. Our parser is
                    // the primary source because it can tell a real tag from the
                    // file name we seeded as the title — ExoPlayer keeps that
                    // name, so for it the two look identical. Artist and album
                    // are not seeded, so they carry no such ambiguity.
                    if (tagTitle == null) {
                        mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                            ?.let { tagTitle = it }
                    }
                    if (tagArtist == null) {
                        mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                            ?.let { tagArtist = it }
                    }
                    if (tagAlbum == null) {
                        mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                            ?.let { tagAlbum = it }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError = describePlaybackError(error)
                }
            }
            player.addListener(listener)

            // Listeners only report *changes*, and arriving on this screen
            // halfway through a track produces none — so seed everything from the
            // session. Without this the screen sits on its defaults: a spinner
            // that never stops, and no cover art.
            currentLocator = player.currentMediaItem?.mediaId
            isBuffering = player.playbackState == Player.STATE_BUFFERING
            isPlaying = player.isPlaying
            player.mediaMetadata.artworkData?.let { artwork = it }
            trace("listener attached, mediaId=$currentLocator")

            onDispose { player.removeListener(listener) }
        }
    }

    // Hand the folder to the session once, unless it is already playing from
    // this queue — reopening the player mid-song must not restart it.
    LaunchedEffect(controller, uri, directory) {
        val player = controller ?: return@LaunchedEffect
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
        trace("queue loaded: ${items.size} items, playing=${player.currentMediaItem?.mediaId}")
        if (items.isEmpty()) return@LaunchedEffect

        val requestedIndex = items.indexOfFirst { it.displayName == displayName }.coerceAtLeast(0)

        // Stand down only when the session is already playing the track that was
        // asked for — that is the reopen-from-the-notification case. Finding the
        // current track *somewhere* in the queue is not enough: picking a
        // different song from the same folder has to switch to it, and an earlier
        // version of this check silently swallowed exactly that.
        if (items[requestedIndex].locator == player.currentMediaItem?.mediaId) {
            trace("already on the requested track; keeping the session")
            return@LaunchedEffect
        }

        // Looping now comes from the player's repeat mode, so there is no
        // manual wrap-around left to do here.
        val startIndex = requestedIndex

        val mediaItems = items.mapIndexed { index, item ->
            MediaItem.Builder()
                // The locator doubles as the media id, so the sheet and the
                // session always agree on which entry is playing.
                .setMediaId(item.locator)
                // Cloud entries stay a placeholder until they are played; the
                // resolver swaps in the direct url at that point.
                .setUri(if (fromCloud) queueUriFor(item.locator) else Uri.parse(item.locator))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        // The media notification renders this very title, and it
                        // cannot be corrected later without restarting the track
                        // — so the entry about to play gets its real name now.
                        .setTitle(
                            if (index == startIndex) {
                                tagTitleOf(item) ?: item.displayName
                            } else {
                                item.displayName
                            }
                        )
                        // Carried so the mini player can reopen the full player
                        // for this entry: the title holds a tag, which cannot
                        // identify the queue entry it came from.
                        .setExtras(
                            bundleOf(
                                PlaybackExtras.DISPLAY_NAME to item.displayName,
                                PlaybackExtras.SOURCE to source.name,
                                PlaybackExtras.DIRECTORY to directory,
                            )
                        )
                        .build()
                )
                .build()
        }

        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()

        // Then fill in the rest. Swapping an entry that is not playing leaves the
        // audio untouched, so this runs while the first track plays — and by the
        // time the user reaches those entries, the notification already shows the
        // right name. Batching keeps a big folder from opening hundreds of reads
        // at once.
        for (chunk in items.withIndex().chunked(TAG_PREFETCH_BATCH)) {
            val resolved = coroutineScope {
                chunk.map { (index, item) -> async { index to tagTitleOf(item) } }.awaitAll()
            }

            for ((index, title) in resolved) {
                if (title == null || title == items[index].displayName) continue
                // Never touch the entry that is playing: replacing it restarts it.
                if (index == player.currentMediaItemIndex) continue
                runCatching {
                    val existing = player.getMediaItemAt(index)
                    player.replaceMediaItem(
                        index,
                        existing.buildUpon()
                            .setMediaMetadata(
                                existing.mediaMetadata.buildUpon().setTitle(title).build()
                            )
                            .build(),
                    )
                }
            }
        }
    }

    // Lyrics and cover art need a readable url, which for a cloud entry only
    // exists once it has been resolved. The resolver caches, so this costs
    // nothing extra when the player already resolved the same track.
    var currentUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentLocator) {
        val locator = currentLocator ?: return@LaunchedEffect
        currentUri = if (source == PlaybackSource.CLOUD) {
            container.queueUriResolver.resolve(locator)?.toString()
        } else {
            locator
        }
        trace("locator=$locator uri=$currentUri")
    }

    // Lyrics, preferring a `.lrc` sitting next to the track: that is how most
    // libraries ship them, and a file placed on purpose usually beats the
    // embedded copy. Whatever is inside the audio is the fallback.
    LaunchedEffect(currentLocator, currentUri) {
        val locator = currentLocator ?: return@LaunchedEffect
        val url = currentUri ?: return@LaunchedEffect

        // A track visited in this session already has its lyrics cached.
        container.trackLyrics[locator]?.let { cached ->
            lyrics = cached
            lyricsLoading = false
            return@LaunchedEffect
        }

        lyricsLoading = true

        val nameKey = runCatching { NameKeyCache.get(password) }.getOrNull()
        val sibling = if (nameKey != null && directory.isNotBlank()) {
            runCatching {
                container.siblingLyricsLoader.load(
                    directory = directory,
                    fromCloud = fromCloud,
                    displayName = currentName,
                    nameKey = nameKey,
                    password = password,
                )
            }.getOrNull()
        } else {
            null
        }

        lyrics = sibling ?: runCatching {
            LyricsLoader.load(container.cipherBlockSourceFactory.create(Uri.parse(url)), password)
        }.getOrNull().orEmpty()

        // Cache the empty result too: a track without lyrics shouldn't
        // re-read the file on every visit.
        container.trackLyrics[locator] = lyrics
        lyricsLoading = false
    }

    // Title, artist and album, read straight from the file. This is the primary
    // source because it can distinguish "the file carries this tag" from "we
    // fell back to the file name" — the session's metadata cannot, since it
    // keeps whatever title we seeded. Whatever this misses, the listener below
    // fills in from the session.
    LaunchedEffect(currentLocator, currentUri) {
        val locator = currentLocator ?: return@LaunchedEffect
        val url = currentUri ?: return@LaunchedEffect

        // A track visited in this session already has its tags cached.
        container.trackTags[locator]?.let { cached ->
            cached.title?.let { tagTitle = it }
            cached.artist?.let { tagArtist = it }
            cached.album?.let { tagAlbum = it }
            if (artwork == null) cached.artwork?.let { artwork = it }
            return@LaunchedEffect
        }

        val tags = runCatching {
            AudioTagLoader.load(container.cipherBlockSourceFactory.create(Uri.parse(url)), password)
        }.getOrNull()

        // Cache the miss too, so a tagless track is only read once per session.
        container.trackTags[locator] = tags ?: AudioTags()
        tags ?: return@LaunchedEffect

        // Only assign what was actually found, so the session's fallback
        // survives when a tag is absent here.
        tags.title?.let { tagTitle = it }
        tags.artist?.let { tagArtist = it }
        tags.album?.let { tagAlbum = it }
        // Never clobber a picture the session already handed us.
        if (artwork == null) tags.artwork?.let { artwork = it }
    }

    LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        while (true) {
            isPlaying = player.isPlaying
            if (!isDragging) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
            }
            val reported = player.duration
            durationMs = if (reported > 0) reported else 0L
            delay(400)
        }
    }

    val shownPosition = if (isDragging) dragValue.toLong() else positionMs
    val sliderMax = durationMs.toFloat().coerceAtLeast(1f)

    val currentLyricIndex = remember(lyrics, shownPosition) {
        if (lyrics.isEmpty()) -1
        else lyrics.indexOfLast { it.timeMs in 1..shownPosition }
    }

    val syncedLyrics = remember(lyrics) { LrcParser.isSynced(lyrics) }

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
                    controller?.repeatMode = repeatMode
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
            // The disc never turns into a spinner. The cover is what identifies
            // the track, so it stays put and loading is reported beside it.
            val cover = artwork
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(70.dp),
                )
            }

            if (showBuffering) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(9.dp)
                        .size(30.dp)
                        .background(Color(0x8C000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = tagTitle ?: currentName,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Artist and album when the file carries them; otherwise say what we
            // know for certain — the container and how it is being played.
            text = listOfNotNull(tagArtist, tagAlbum)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · ")
                ?: "${MediaTypes.labelOf(currentName)} · 流式解密播放",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))

        LyricsBox(
            lyrics = lyrics,
            currentIndex = currentLyricIndex,
            synced = syncedLyrics,
            loading = lyricsLoading,
            onSeek = { controller?.seekTo(it) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))

        Slider(
            value = shownPosition.toFloat().coerceIn(0f, sliderMax),
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                controller?.seekTo(dragValue.toLong())
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
                onClick = {
                    controller?.let { player ->
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L))
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.Replay10, contentDescription = "后退 10 秒")
            }

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    controller?.let { player ->
                        if (player.isPlaying) player.pause() else player.play()
                    }
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
                    controller?.let { player ->
                        val duration = player.duration
                        val target = player.currentPosition + 10_000
                        player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                    }
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
                controller?.prepare()
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

/** How many queue entries have their tags read at once during backfill. */
private const val TAG_PREFETCH_BATCH = 4

/** How long buffering must last before the loading indicator appears. */
private const val BUFFER_GRACE_MS = 350L

/** Debug tracing for the tag pipeline; stripped from release builds. */
private fun trace(message: String) {
    if (BuildConfig.DEBUG) Log.d("SakuraFlow", message)
}

// ── Lyrics ────────────────────────────────────────────────────────────

/** Duration for line size/alpha animations. */
private val lyricTransition = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

/**
 * Fixed metrics for every lyric row. With both constant, an item's height is
 * exactly `lineHeight + 2 * vPadding` — the centring scroll computes it
 * directly instead of measuring laid-out items.
 */
private val LYRIC_LINE_HEIGHT = 34.sp
private val LYRIC_V_PADDING = 10.dp

/**
 * Self-contained lyrics panel: auto-scrolls to keep the current line centred,
 * pauses when the user scrolls manually (shows a "back to current" chip),
 * and resumes auto-scroll after 3 s of inactivity.
 */
@Composable
private fun LyricsBox(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    synced: Boolean,
    loading: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // ── user-scroll detection ──────────────────────────────────────
    var userScrolling by remember { mutableStateOf(false) }
    var autoScrolling by remember { mutableStateOf(false) }

    // snapshotFlow watches the lazy-list scroll state; when it is
    // scrolling and we did NOT trigger it ourselves, the user is dragging.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && !autoScrolling) userScrolling = true
            }
    }

    // ── auto-scroll: keep the current line centred ─────────────────
    // Line heights are FIXED (see LyricLineRow), so the item height is
    // computable directly — no two-phase measuring. scrollToItem's offset
    // is signed: positive scrolls the item UP (forward), so +half puts the
    // line's centre exactly on the viewport centre.
    LaunchedEffect(currentIndex, viewportPx, userScrolling) {
        if (currentIndex >= 0 && viewportPx > 0 && !userScrolling) {
            autoScrolling = true
            val itemHeightPx = with(density) {
                (LYRIC_LINE_HEIGHT.toPx() + LYRIC_V_PADDING.toPx() * 2).roundToInt()
            }
            listState.animateScrollToItem(currentIndex, itemHeightPx / 2)
            autoScrolling = false
        }
    }

    // ── resume auto-scroll after 3 s of inactivity ────────────────
    LaunchedEffect(userScrolling) {
        if (userScrolling) {
            delay(3000)
            userScrolling = false
        }
    }

    // ── UI ─────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .onSizeChanged { viewportPx = it.height },
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "正在读取歌词…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            lyrics.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.Lyrics, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "未找到歌词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val halfViewport = with(density) { (viewportPx / 2).toDp() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = halfViewport),
                ) {
                    itemsIndexed(lyrics) { index, line ->
                        val distance = if (synced) abs(index - currentIndex) else 0
                        LyricLineRow(
                            text = line.text,
                            timeMs = line.timeMs,
                            distance = distance,
                            synced = synced,
                            onClick = { if (line.timeMs > 0L) onSeek(line.timeMs) },
                        )
                    }
                }

                // Edge fades so lines blend into the card at top & bottom.
                LyricFade(MaterialTheme.colorScheme.surfaceContainerLow)

                // "Back to current" chip when user has scrolled away.
                if (userScrolling && currentIndex >= 0) {
                    FilledTonalButton(
                        onClick = { userScrolling = false },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ExpandMore, null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("回到当前", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * A single lyric line. Its visual weight is determined by [distance] from
 * the currently playing line — closer lines are larger and more opaque,
 * farther ones recede. The formula is continuous so the list feels like
 * one surface that comes into focus at the centre.
 */
@Composable
private fun LyricLineRow(
    text: String,
    timeMs: Long,
    distance: Int,
    synced: Boolean,
    onClick: () -> Unit,
) {
    val isCurrent = distance == 0

    // Continuous decay curves — no hard "when" branches.
    // size: 22 sp at d=0 → 16 sp asymptotically
    // alpha: 1.0  at d=0 → 0.2 asymptotically
    val targetSize = if (!synced) 16f else 16f + 6f / (1f + distance * 1.5f)
    val targetAlpha = if (!synced) 1f else max(0.2f, 1f / (1f + distance * 0.8f))

    val size by animateFloatAsState(targetSize, lyricTransition, label = "lyricSize")
    val alpha by animateFloatAsState(targetAlpha, lyricTransition, label = "lyricAlpha")
    val colour by animateColorAsState(
        targetValue = if (isCurrent) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(300),
        label = "lyricColour",
    )

    Text(
        text = text,
        fontSize = size.sp,
        // Fixed line height for every row, current or not. A changing one
        // would make rows resize as the focus moves and the list would
        // twitch; a fixed one also makes each item's height exactly
        // predictable, which the centring math relies on.
        lineHeight = LYRIC_LINE_HEIGHT,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = colour,
        textAlign = TextAlign.Center,
        // Trim.Both crops Android's font padding above and below the line,
        // so the rendered height matches this lineHeight exactly.
        style = TextStyle(
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = synced && timeMs > 0L, onClick = onClick)
            .padding(vertical = LYRIC_V_PADDING, horizontal = 16.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/** Fades the top and bottom edges of the lyrics card into its background. */
@Composable
private fun BoxScope.LyricFade(colour: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(50.dp)
            .background(Brush.verticalGradient(listOf(colour, Color.Transparent))),
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(50.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, colour))),
    )
}
