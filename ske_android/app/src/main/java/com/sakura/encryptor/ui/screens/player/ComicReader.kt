package com.sakura.encryptor.ui.screens.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sakura.encryptor.core.media.ReaderPageCache
import com.sakura.encryptor.core.player.ImageDecryptor
import com.sakura.encryptor.core.playlist.PlaylistItem
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.components.describeFailure

/**
 * Pixel budget for one strip page.
 *
 * Pages are drawn at screen width and never zoomed, so decoding at the source
 * resolution wastes memory: a 4000×3000 photo becomes a 48 MB bitmap that the
 * reader shows at ~1080 px wide. 6 MP keeps normal 1080-wide strips untouched
 * while bringing such photos down to ~12 MB, so many more fit in the cache.
 */
private const val COMIC_PAGE_PIXELS = 6_000_000

/**
 * Vertical continuous ("webtoon") reading mode.
 *
 * Every page of the playlist is stacked in one [LazyColumn] at full width, so
 * the reader just scrolls down. Images are decrypted lazily: only the pages the
 * list actually composes are decrypted, which matters because each `.ske` page
 * costs one PBKDF2 derivation, a block-by-block AES-GCM decryption and a bitmap
 * decode (~17 MB for a 1080×4000 strip).
 *
 * Cloud pages are traded for a direct url right before decryption, mirroring
 * [PlaylistRepository.resolvePlayable]; the resolved urls and sizes are cached
 * by the repository and the block source factory. Decrypted bitmaps additionally
 * live in [ReaderPageCache], because the LazyColumn destroys the state of any
 * page that scrolls out of the viewport — without that cache, scrolling back
 * would decrypt the page again.
 */
@Composable
internal fun ComicReader(
    items: List<PlaylistItem>,
    fromCloud: Boolean,
    password: String,
    listState: LazyListState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = appContainer()
    val decryptor = remember { ImageDecryptor(container.cipherBlockSourceFactory) }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        itemsIndexed(items, key = { _, item -> item.locator }) { _, item ->
            ComicPage(
                item = item,
                fromCloud = fromCloud,
                password = password,
                decryptor = decryptor,
                onTap = onTap,
            )
        }
    }
}

@Composable
private fun ComicPage(
    item: PlaylistItem,
    fromCloud: Boolean,
    password: String,
    decryptor: ImageDecryptor,
    onTap: () -> Unit,
) {
    val container = appContainer()
    // The "strip" variant keeps screen-sized pages apart from the single-page
    // viewer's full-resolution bitmaps; sharing them would hand a zoomed view
    // a bitmap decoded at screen width.
    val cacheKey = remember(item.locator, password) {
        ReaderPageCache.key(item.locator, password, variant = "strip")
    }
    // Re-entering a page that is still cached shows it at once; the effect below
    // then finds the entry and skips the decryption altogether.
    var bitmap by remember(cacheKey) { mutableStateOf(ReaderPageCache.peek(cacheKey)) }
    var error by remember(item.locator) { mutableStateOf<String?>(null) }
    var attempt by remember(item.locator) { mutableIntStateOf(0) }

    LaunchedEffect(cacheKey, attempt) {
        ReaderPageCache.peek(cacheKey)?.let {
            bitmap = it
            error = null
            return@LaunchedEffect
        }

        bitmap = null
        error = null
        runCatching {
            val locator = container.playlistRepository
                .resolvePlayable(item.locator, fromCloud)
                ?: error("无法获取文件链接")
            decryptor.decrypt(Uri.parse(locator), password, maxPixels = COMIC_PAGE_PIXELS)
        }
            .onSuccess {
                ReaderPageCache.put(cacheKey, it)
                bitmap = it
            }
            .onFailure { error = describeFailure(it) }
    }

    val current = bitmap
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            current != null -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(current.width.toFloat() / current.height),
            )

            error != null -> StateMessage(
                icon = Icons.Rounded.BrokenImage,
                title = "无法显示这一页",
                message = error,
                actionLabel = "重试",
                onAction = { attempt++ },
            )

            else -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }
    }
}
