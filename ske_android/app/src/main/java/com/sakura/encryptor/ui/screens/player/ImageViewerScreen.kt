package com.sakura.encryptor.ui.screens.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sakura.encryptor.core.media.ReaderPageCache
import com.sakura.encryptor.core.player.ImageDecryptor
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.playlist.PlaylistItem
import com.sakura.encryptor.core.session.NameKeyCache
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.LoadingState
import com.sakura.encryptor.ui.components.PlaylistSheet
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.components.describeFailure
import com.sakura.encryptor.ui.navigation.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How long the top bar stays hidden after the strip stops moving. */
private const val COMIC_BAR_IDLE_MS = 300L

@Composable
fun ImageViewerScreen(
    uri: String,
    displayName: String,
    source: PlaybackSource,
    directory: String,
    onBack: () -> Unit,
) {
    val container = appContainer()
    val decryptor = remember { ImageDecryptor(container.cipherBlockSourceFactory) }

    // Local files use the standalone local password, remote ones the cloud one.
    val password = remember(source) {
        when (source) {
            PlaybackSource.LOCAL -> container.localVault.password.orEmpty()
            PlaybackSource.CLOUD -> container.vault.password.orEmpty()
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var retryToken by remember { mutableIntStateOf(0) }

    // ---- playlist -----------------------------------------------------------
    var playlist by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var playlistIndex by remember { mutableIntStateOf(-1) }
    var showPlaylist by remember { mutableStateOf(false) }
    var currentUri by remember { mutableStateOf(uri) }
    var currentName by remember { mutableStateOf(displayName) }
    val playerScope = rememberCoroutineScope()

    // Decrypted images go through the same cache as the continuous reader.
    // The key drops the url's signature so swiping back to a page already seen
    // is a cache hit instead of another fetch and decrypt.
    val cacheKey = remember(currentUri, password) {
        ReaderPageCache.key(currentUri.substringBefore('?'), password)
    }
    // The page under the finger. Bundling the bitmap with the uri keeps the
    // outgoing page intact while the next one is still decrypting.
    var page by remember {
        mutableStateOf(
            ImagePageState(currentUri, currentName, ReaderPageCache.peek(cacheKey), null)
        )
    }

    // ---- finger-tracked page swipe -----------------------------------------
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // How far the current page has been dragged; negative is towards "next".
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // The page peeking in from the other side, when its bitmap is already cached.
    var neighbor by remember { mutableStateOf<ImagePageState?>(null) }
    var neighborNext by remember { mutableStateOf<Boolean?>(null) }
    // In-flight decrypt of the page being dragged towards, cancelled when the
    // drag changes direction.
    var neighborJob by remember { mutableStateOf<Job?>(null) }
    // Resolved urls seen so far, so a neighbour can be previewed without a
    // second cloud lookup.
    val resolvedUris = remember { mutableStateMapOf<String, String>() }

    // ---- continuous (comic) reader -----------------------------------------
    var comicMode by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    val comicListState = rememberLazyListState()

    fun playItem(item: PlaylistItem) {
        playerScope.launch {
            val resolved = container.playlistRepository
                .resolveUri(item, source == PlaybackSource.CLOUD)
                ?: return@launch
            resolvedUris[item.locator] = resolved
            currentName = item.displayName
            currentUri = resolved
            playlist.indexOf(item).takeIf { it >= 0 }?.let { playlistIndex = it }
            // Show a cached page at once so the switch never reveals a spinner.
            val resolvedKey = ReaderPageCache.key(resolved.substringBefore('?'), password)
            page = ImagePageState(
                resolved,
                item.displayName,
                ReaderPageCache.peek(resolvedKey),
                null,
            )
        }
    }

    fun neighborItem(next: Boolean): PlaylistItem? {
        if (playlist.size <= 1 || playlistIndex < 0) return null
        val step = if (next) 1 else -1
        return playlist[(playlistIndex + step + playlist.size) % playlist.size]
    }

    /** The neighbour as far as it is known: its bitmap may still be uncached. */
    fun previewOf(item: PlaylistItem): ImagePageState {
        val uri = resolvedUris[item.locator]
            ?: item.locator.takeIf { source == PlaybackSource.LOCAL }
        if (uri == null) {
            return ImagePageState("pending:${item.locator}", item.displayName, null, null)
        }
        val key = ReaderPageCache.key(uri.substringBefore('?'), password)
        return ImagePageState(uri, item.displayName, ReaderPageCache.peek(key), null)
    }

    fun prepareNeighbor(next: Boolean) {
        if (neighborNext == next && neighbor != null) return
        neighborNext = next
        val item = neighborItem(next) ?: run {
            neighbor = null
            return
        }
        val preview = previewOf(item)
        neighbor = preview
        // Already decoded: it follows the finger straight away.
        if (preview.bitmap != null) return

        // Decrypt while the finger is still moving, so the page slides in
        // instead of leaving a black gap until the swipe is committed.
        neighborJob?.cancel()
        neighborJob = playerScope.launch {
            val resolved = container.playlistRepository
                .resolveUri(item, source == PlaybackSource.CLOUD)
                ?: return@launch
            resolvedUris[item.locator] = resolved
            val key = ReaderPageCache.key(resolved.substringBefore('?'), password)
            val decoded = ReaderPageCache.peek(key) ?: try {
                decryptor.decrypt(Uri.parse(resolved), password)
                    .also { ReaderPageCache.put(key, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@launch
            }
            // Only replace the preview if the drag still points at this page.
            if (neighborNext == next && neighbor?.uri == preview.uri) {
                neighbor = ImagePageState(resolved, item.displayName, decoded, null)
            }
        }
    }

    fun settleBack() {
        playerScope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = 0f,
                animationSpec = tween(200),
            ) { value, _ -> dragOffset = value }
            dragOffset = 0f
            dragging = false
            neighbor = null
            neighborNext = null
        }
    }

    /** Animate the current page away in [next]'s direction, then commit. */
    fun slideTo(item: PlaylistItem, next: Boolean) {
        playerScope.launch {
            // Reuse what the drag already prepared. Resolving again here would
            // put a cloud round trip between the finger lifting and the slide
            // starting, which reads as a hitch right at release.
            val preview = neighbor?.takeIf {
                neighborNext == next && !it.uri.startsWith("pending:")
            }
            val resolved = preview?.uri
                ?: resolvedUris[item.locator]
                ?: container.playlistRepository
                    .resolveUri(item, source == PlaybackSource.CLOUD)
            if (resolved == null) {
                settleBack()
                return@launch
            }
            resolvedUris[item.locator] = resolved
            val key = ReaderPageCache.key(resolved.substringBefore('?'), password)
            val bitmap = preview?.takeIf { it.uri == resolved }?.bitmap
                ?: ReaderPageCache.peek(key)
            val target = ImagePageState(resolved, item.displayName, bitmap, null)

            neighbor = target
            neighborNext = next
            dragging = true
            animate(
                initialValue = dragOffset,
                targetValue = if (next) -screenWidthPx else screenWidthPx,
                animationSpec = tween(220),
            ) { value, _ -> dragOffset = value }

            playlist.indexOf(item).takeIf { it >= 0 }?.let { playlistIndex = it }
            currentName = item.displayName
            currentUri = resolved
            page = target
            dragOffset = 0f
            dragging = false
            neighbor = null
            neighborNext = null
        }
    }

    fun playNext() {
        neighborItem(true)?.let { slideTo(it, next = true) }
    }

    fun playPrevious() {
        neighborItem(false)?.let { slideTo(it, next = false) }
    }

    fun commitSwipe(next: Boolean) {
        val item = neighborItem(next)
        if (item == null) settleBack() else slideTo(item, next)
    }

    /**
     * Enter the continuous reader at the current page; on the way out, open the
     * page that was being read so the single-page view continues from there.
     */
    fun toggleComicMode() {
        if (!comicMode) {
            comicMode = true
            return
        }

        val index = comicListState.firstVisibleItemIndex
        comicMode = false
        if (index != playlistIndex) {
            playlist.getOrNull(index)?.let { playItem(it) }
        }
    }

    LaunchedEffect(currentUri, directory) {
        if (directory.isBlank()) return@LaunchedEffect
        val nameKey = runCatching { NameKeyCache.get(password) }.getOrNull()
            ?: return@LaunchedEffect

        val items = container.playlistRepository.load(
            directory = directory,
            fromCloud = source == PlaybackSource.CLOUD,
            kind = MediaKind.IMAGE,
            nameKey = nameKey,
        )
        playlist = items
        val index = items.indexOfFirst { it.displayName == currentName }
        playlistIndex = index
        // The page we entered with is already resolved; keep it available as
        // a swipe preview for its neighbours.
        items.getOrNull(index)?.let { resolvedUris[it.locator] = currentUri }
    }

    LaunchedEffect(cacheKey, retryToken, comicMode) {
        // The continuous reader decrypts its own pages from the same cache,
        // so the single bitmap can stay where it is while strip mode is up.
        if (comicMode) return@LaunchedEffect

        // A new picture starts unmoved.
        scale = 1f
        offsetX = 0f
        offsetY = 0f

        // A page seen before is already decrypted: no fetch, no PBKDF2.
        val cached = ReaderPageCache.peek(cacheKey)
        page = ImagePageState(currentUri, currentName, cached, null)
        if (cached != null) return@LaunchedEffect

        try {
            val decoded = decryptor.decrypt(Uri.parse(currentUri), password)
            ReaderPageCache.put(cacheKey, decoded)
            page = ImagePageState(currentUri, currentName, decoded, null)
        } catch (cancelled: CancellationException) {
            // A newer page superseded this one; its own effect owns the state.
            throw cancelled
        } catch (failure: Exception) {
            page = ImagePageState(currentUri, currentName, null, describeFailure(failure))
        }
    }

    // Once the current page is up, warm the next one in the background so a
    // swipe has a bitmap to follow the finger with from the first frame.
    LaunchedEffect(currentUri, page.bitmap != null, playlistIndex, playlist.size, comicMode) {
        if (comicMode || page.bitmap == null || playlist.size <= 1) return@LaunchedEffect
        val item = neighborItem(true) ?: return@LaunchedEffect
        // Cloud pages need their direct url first; resolveUri is cheap for
        // local ones and passes the locator straight through.
        val resolved = container.playlistRepository
            .resolveUri(item, source == PlaybackSource.CLOUD)
            ?: return@LaunchedEffect
        resolvedUris[item.locator] = resolved
        val key = ReaderPageCache.key(resolved.substringBefore('?'), password)
        if (ReaderPageCache.peek(key) != null) return@LaunchedEffect
        runCatching { decryptor.decrypt(Uri.parse(resolved), password) }
            .getOrNull()?.let { ReaderPageCache.put(key, it) }
    }

    LaunchedEffect(comicMode) {
        if (comicMode && playlist.isNotEmpty()) {
            comicListState.scrollToItem(playlistIndex.coerceIn(0, playlist.lastIndex))
        }
    }

    LaunchedEffect(comicMode, comicListState) {
        if (!comicMode) {
            barsVisible = true
            return@LaunchedEffect
        }
        // Hide the bar while the strip moves and bring it back once the finger
        // (or the fling) stops, mirroring the web viewer.
        snapshotFlow { comicListState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    barsVisible = false
                } else {
                    delay(COMIC_BAR_IDLE_MS)
                    barsVisible = true
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (comicMode) {
            ComicReader(
                items = playlist,
                fromCloud = source == PlaybackSource.CLOUD,
                password = password,
                listState = comicListState,
                onTap = { barsVisible = !barsVisible },
            )
        } else {
            // The next/previous page peeks in only when its bitmap is cached;
            // otherwise the swipe reveals black until the new page loads.
            neighbor?.takeIf { dragging }?.bitmap?.let { preview ->
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = neighbor?.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // Lambda form: reading dragOffset inside the layer block
                        // updates only the layer per frame instead of
                        // recomposing the whole screen while the finger moves.
                        .graphicsLayer {
                            translationX = dragOffset + if (neighborNext == true) {
                                screenWidthPx
                            } else {
                                -screenWidthPx
                            }
                        },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragOffset },
            ) {
                val current = page.bitmap
                when {
                    page.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        StateMessage(
                            icon = Icons.Rounded.BrokenImage,
                            title = "无法显示图片",
                            message = page.error,
                            actionLabel = "重试",
                            onAction = { retryToken++ },
                        )
                    }

                    current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingState("正在解密图片…")
                    }

                    else -> Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = page.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectZoomPanAndSwipe(
                                    isZoomed = { scale > 1f },
                                    onTransform = { pan, zoom ->
                                        val nextScale = (scale * zoom).coerceIn(1f, 8f)
                                        scale = nextScale
                                        if (nextScale > 1f) {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    },
                                    onDrag = { dx, dy ->
                                        if (abs(dx) > abs(dy)) {
                                            val next = dx < 0f
                                            if (!dragging || neighborNext != next) {
                                                prepareNeighbor(next)
                                            }
                                            dragging = true
                                            dragOffset = dx
                                        }
                                    },
                                    onDragEnd = { swipeLeft ->
                                        if (dragging) {
                                            when (swipeLeft) {
                                                null -> settleBack()
                                                true -> commitSwipe(true)
                                                false -> commitSwipe(false)
                                            }
                                        }
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { barsVisible = !barsVisible },
                                    onDoubleTap = {
                                        if (scale > 1f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            scale = 2.5f
                                        }
                                    },
                                )
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            },
                    )
                }
            }
        }

        // Previous / next, mirroring the web viewer's side arrows. The
        // continuous reader has no pages to flip through. They follow the top
        // bar, so one tap hides every control for a clean view.
        AnimatedVisibility(
            visible = !comicMode && playlist.size > 1 && barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            IconButton(
                onClick = { playPrevious() },
                modifier = Modifier
                    .padding(start = 10.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = "上一张",
                    tint = Color.White,
                )
            }
        }
        AnimatedVisibility(
            visible = !comicMode && playlist.size > 1 && barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            IconButton(
                onClick = { playNext() },
                modifier = Modifier
                    .padding(end = 10.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "下一张",
                    tint = Color.White,
                )
            }
        }

        if (comicMode && playlist.size > 1) {
            Text(
                text = "${comicListState.firstVisibleItemIndex + 1} / ${playlist.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000)))
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    text = if (comicMode) {
                        playlist.getOrNull(comicListState.firstVisibleItemIndex)?.displayName
                            ?: currentName
                    } else {
                        currentName
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                if (!comicMode && scale > 1f) {
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                }
                if (playlist.size > 1) {
                    IconButton(onClick = { toggleComicMode() }) {
                        Icon(
                            imageVector = Icons.Rounded.MenuBook,
                            contentDescription = if (comicMode) "单页模式" else "条漫模式",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { showPlaylist = true }) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistPlay,
                            contentDescription = "播放列表",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    if (showPlaylist) {
        PlaylistSheet(
            items = playlist,
            // In the continuous reader the strip decides what is being read, so
            // the sheet has to mark (and open at) the page under the viewport,
            // not the one the view was entered from.
            currentIndex = if (comicMode) {
                comicListState.firstVisibleItemIndex
            } else {
                playlistIndex
            },
            unit = "张",
            onSelect = { item ->
                showPlaylist = false
                val index = playlist.indexOf(item)
                if (comicMode) {
                    // The single-page pipeline is unused while the strip is up;
                    // scrolling the lazily-composed list is the jump.
                    if (index >= 0) {
                        playerScope.launch { comicListState.scrollToItem(index) }
                    }
                } else {
                    if (index >= 0) {
                        slideTo(item, next = index > playlistIndex)
                    } else {
                        playItem(item)
                    }
                }
            },
            onDismiss = { showPlaylist = false },
        )
    }
}

/** Distance a drag covers before it is treated as a page swipe. */
private val SwipeThreshold = 64.dp

/**
 * Zoom, pan and page swipe from a single gesture loop.
 *
 * Two separate detectors cannot coexist here: `detectTransformGestures` claims
 * every horizontal movement, leaving nothing for a swipe detector. This loop
 * only claims the pointer when there is something to transform — a pinch, or a
 * drag on an already zoomed picture — and otherwise reports the live drag so
 * the page can follow the finger.
 *
 * [onDragEnd] receives `true` for a swipe towards the next page, `false` for
 * the previous one, and `null` when the drag was too short and must snap back.
 */
private suspend fun PointerInputScope.detectZoomPanAndSwipe(
    isZoomed: () -> Boolean,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: (swipeLeft: Boolean?) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    var dx = 0f
    var dy = 0f
    var transformed = false
    var dragged = false

    do {
        val event = awaitPointerEvent()
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        val pinching = event.changes.count { it.pressed } > 1

        if (pinching || zoom != 1f || isZoomed()) {
            if (zoom != 1f || pan != Offset.Zero) {
                onTransform(pan, zoom)
                transformed = true
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } else {
            dx += pan.x
            dy += pan.y
            // Once the drag is clearly horizontal, claim it so the tap
            // detector no longer mistakes a swipe for the first half of a
            // double tap, and move the page with the finger.
            if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) {
                dragged = true
                onDrag(dx, dy)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    } while (event.changes.any { it.pressed })

    if (dragged && !transformed && !isZoomed()) {
        val threshold = SwipeThreshold.toPx()
        onDragEnd(
            if (abs(dx) > threshold && abs(dx) > abs(dy)) dx < 0f else null
        )
    }
}

/** What one page of the single-page viewer is showing right now. */
private data class ImagePageState(
    val uri: String,
    val name: String,
    val bitmap: Bitmap?,
    val error: String?,
)
