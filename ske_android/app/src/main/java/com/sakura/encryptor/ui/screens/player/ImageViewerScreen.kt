package com.sakura.encryptor.ui.screens.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch

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

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
            kind = MediaKind.IMAGE,
            nameKey = nameKey,
        )
        playlist = items
        playlistIndex = items.indexOfFirst { it.displayName == currentName }
    }

    LaunchedEffect(currentUri, retryToken) {
        bitmap = null
        error = null
        // A new picture starts unmoved.
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        runCatching { decryptor.decrypt(Uri.parse(currentUri), password) }
            .onSuccess { bitmap = it }
            .onFailure { error = describeFailure(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val current = bitmap
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StateMessage(
                    icon = Icons.Rounded.BrokenImage,
                    title = "无法显示图片",
                    message = error,
                    actionLabel = "重试",
                    onAction = { retryToken++ },
                )
            }

            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState("正在解密图片…")
            }

            else -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 8f)
                            scale = nextScale
                            if (nextScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        }

        // Previous / next, mirroring the web viewer's side arrows.
        if (playlist.size > 1) {
            IconButton(
                onClick = { playPrevious() },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = "上一张",
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = { playNext() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
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
                text = currentName,
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
            if (scale > 1f) {
                Text(
                    text = "${(scale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(end = 14.dp),
                )
            }
        }
    }

    if (showPlaylist) {
        PlaylistSheet(
            items = playlist,
            currentIndex = playlistIndex,
            unit = "张",
            onSelect = { item ->
                playItem(item)
                showPlaylist = false
            },
            onDismiss = { showPlaylist = false },
        )
    }
}
