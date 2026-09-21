package com.sakura.encryptor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sakura.encryptor.core.playlist.PlaylistItem

/**
 * Bottom sheet listing the other media files sitting next to the one playing.
 *
 * The Android counterpart of the web client's `PlaylistPanel`, laid out as a
 * sheet because that is how the platform presents an in-player list.
 *
 * @param unit counter suffix, e.g. "个" for videos, "首" for audio.
 */
@Composable
fun PlaylistSheet(
    items: List<PlaylistItem>,
    currentIndex: Int,
    unit: String,
    onSelect: (PlaylistItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val listState = rememberLazyListState()

        // Open scrolled to whatever is playing.
        LaunchedEffect(currentIndex) {
            if (currentIndex >= 0 && currentIndex < items.size) {
                listState.scrollToItem(currentIndex)
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "播放列表", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${items.size} $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    val active = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                            .padding(horizontal = 24.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "正在播放",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier
                    .height(12.dp)
                    .navigationBarsPadding()
            )
        }
    }
}
