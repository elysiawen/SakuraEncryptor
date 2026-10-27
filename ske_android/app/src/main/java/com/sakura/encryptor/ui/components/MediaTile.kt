package com.sakura.encryptor.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sakura.encryptor.core.media.PreviewCache

/** Top-bar switch between the list and the grid, mirroring the web client. */
@Composable
fun ViewModeToggle(
    grid: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
            contentDescription = if (grid) "切换到列表视图" else "切换到网格视图",
        )
    }
}

/**
 * One grid cell: a square preview with the file name underneath.
 *
 * The preview is loaded lazily by [loadPreview]; until it arrives — or when the
 * file has none — the media-type icon stands in, so the grid never looks broken.
 */
@Composable
fun MediaTile(
    name: String,
    icon: ImageVector,
    cacheKey: String,
    loadPreview: suspend () -> Bitmap?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    badges: @Composable BoxScope.() -> Unit = {},
) {
    // Seeded from the cache so scrolling back shows previews without a flash.
    val preview by produceState<Bitmap?>(PreviewCache.peek(cacheKey), cacheKey) {
        if (value == null) value = loadPreview()
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = preview
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
            badges()
        }

        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp, bottom = 2.dp),
        )
    }
}

/** Lock / play / size overlays on a tile, matching the web grid's badges. */
@Composable
fun BoxScope.TileBadges(
    locked: Boolean = false,
    video: Boolean = false,
    sizeText: String? = null,
) {
    if (video) {
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = CircleShape,
            modifier = Modifier.align(Alignment.Center).size(34.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "视频",
                tint = Color.White,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
    if (locked) {
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Text(
                text = "🔒",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
    if (sizeText != null) {
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        ) {
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
