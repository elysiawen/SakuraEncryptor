package com.sakura.encryptor.ui.screens.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.core.download.DownloadManager
import com.sakura.encryptor.core.upload.UploadManager
import com.sakura.encryptor.core.upload.UploadPhase
import com.sakura.encryptor.core.upload.UploadStatus
import com.sakura.encryptor.core.upload.UploadTask
import com.sakura.encryptor.data.db.DownloadStatus
import com.sakura.encryptor.data.db.DownloadTaskEntity
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.rememberAppViewModel
import com.sakura.encryptor.ui.util.formatBytes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransfersViewModel(
    private val downloadManager: DownloadManager,
    private val uploadManager: UploadManager,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadTaskEntity>> = downloadManager.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uploads: StateFlow<List<UploadTask>> = uploadManager.tasks

    fun pauseDownload(id: String) = downloadManager.pause(id)

    fun resumeDownload(id: String) = downloadManager.start(id)

    fun retryDownload(id: String) = downloadManager.retry(id)

    fun cancelDownload(id: String) = downloadManager.cancel(id)

    fun deleteDownload(id: String) = downloadManager.delete(id)

    fun cancelUpload(id: String) = uploadManager.cancel(id)

    fun deleteUpload(id: String) = uploadManager.delete(id)

    /** The user sees one finished group, so clearing it empties both queues. */
    fun clearFinished() {
        uploadManager.clearFinished()
        viewModelScope.launch { downloadManager.clearFinished() }
    }
}

private enum class TransferTab(
    val title: String,
    val empty: String,
    val icon: ImageVector,
) {
    UPLOAD("上传", "暂无进行中的上传", Icons.Rounded.UploadFile),
    DOWNLOAD("下载", "暂无进行中的下载", Icons.Rounded.Downloading),
    FINISHED("完成", "还没有结束的任务", Icons.Rounded.CheckCircle),
}

/** One row of the merged finished list; both queues look the same there. */
private sealed interface FinishedEntry {
    val endedAt: Long

    data class Upload(val task: UploadTask) : FinishedEntry {
        override val endedAt: Long
            get() = task.finishedAt.takeIf { it > 0 } ?: task.startedAt
    }

    data class Download(val task: DownloadTaskEntity) : FinishedEntry {
        override val endedAt: Long get() = task.createdAt
    }
}

/** Badge colours and label for one row, derived from its state. */
private data class Tone(
    val container: Color,
    val content: Color,
    val label: String,
)

@Composable
fun TransfersScreen(onOpenDrawer: () -> Unit) {
    val viewModel = rememberAppViewModel {
        TransfersViewModel(it.downloadManager, it.uploadManager)
    }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()

    var cancelTarget by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var selectedTab by remember { mutableStateOf<TransferTab?>(null) }

    val activeUploads = uploads.filter {
        it.status == UploadStatus.QUEUED || it.status == UploadStatus.RUNNING
    }
    val activeDownloads = downloads.filter { downloadStatus(it) in ACTIVE_DOWNLOAD_STATUSES }
    val finished = buildList<FinishedEntry> {
        uploads.filter {
            it.status == UploadStatus.DONE ||
                it.status == UploadStatus.FAILED ||
                it.status == UploadStatus.CANCELLED
        }.forEach { add(FinishedEntry.Upload(it)) }
        downloads.filter { downloadStatus(it) in FINISHED_DOWNLOAD_STATUSES }
            .forEach { add(FinishedEntry.Download(it)) }
    }.sortedByDescending { it.endedAt }

    val defaultTab = when {
        activeUploads.isNotEmpty() -> TransferTab.UPLOAD
        activeDownloads.isNotEmpty() -> TransferTab.DOWNLOAD
        else -> TransferTab.FINISHED
    }
    val tab = selectedTab ?: defaultTab
    val counts = mapOf(
        TransferTab.UPLOAD to activeUploads.size,
        TransferTab.DOWNLOAD to activeDownloads.size,
        TransferTab.FINISHED to finished.size,
    )

    val activeCount = activeUploads.size + activeDownloads.size
    val isEmpty = uploads.isEmpty() && downloads.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = "传输",
            subtitle = when {
                isEmpty -> "暂无任务"
                activeCount == 0 -> "暂无进行中任务"
                else -> "$activeCount 个进行中"
            },
            onMenu = onOpenDrawer,
            actions = {
                if (finished.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearFinished) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "清除已完成")
                    }
                }
            },
        )

        if (isEmpty) {
            StateMessage(
                icon = Icons.Rounded.SwapVert,
                title = "没有传输任务",
                message = "在云端目录中上传文件，或下载加密文件，任务会出现在这里。加密与解密都在本机完成，切换页面不影响进度。",
            )
            return@Column
        }

        TransferTabs(
            selected = tab,
            counts = counts,
            onSelect = { selectedTab = it },
        )

        val tabEmpty = when (tab) {
            TransferTab.UPLOAD -> activeUploads.isEmpty()
            TransferTab.DOWNLOAD -> activeDownloads.isEmpty()
            TransferTab.FINISHED -> finished.isEmpty()
        }

        if (tabEmpty) {
            TabEmpty(tab)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                TransferTab.UPLOAD -> items(items = activeUploads, key = { "u_${it.id}" }) { task ->
                    UploadCard(
                        task = task,
                        onCancel = { viewModel.cancelUpload(task.id) },
                        onDelete = { viewModel.deleteUpload(task.id) },
                    )
                }

                TransferTab.DOWNLOAD -> items(items = activeDownloads, key = { "d_${it.id}" }) { task ->
                    DownloadCard(
                        task = task,
                        onPause = { viewModel.pauseDownload(task.id) },
                        onResume = { viewModel.resumeDownload(task.id) },
                        onRetry = { viewModel.retryDownload(task.id) },
                        onCancel = { cancelTarget = task },
                        onDelete = { viewModel.deleteDownload(task.id) },
                    )
                }

                TransferTab.FINISHED -> items(
                    items = finished,
                    key = {
                        when (it) {
                            is FinishedEntry.Upload -> "u_${it.task.id}"
                            is FinishedEntry.Download -> "d_${it.task.id}"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is FinishedEntry.Upload -> UploadCard(
                            task = entry.task,
                            onCancel = {},
                            onDelete = { viewModel.deleteUpload(entry.task.id) },
                        )

                        is FinishedEntry.Download -> DownloadCard(
                            task = entry.task,
                            onPause = { viewModel.pauseDownload(entry.task.id) },
                            onResume = { viewModel.resumeDownload(entry.task.id) },
                            onRetry = { viewModel.retryDownload(entry.task.id) },
                            onCancel = { cancelTarget = entry.task },
                            onDelete = { viewModel.deleteDownload(entry.task.id) },
                        )
                    }
                }
            }
        }
    }

    cancelTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("取消下载？") },
            text = {
                Text(
                    "将取消「${task.displayName}」并丢弃已下载的部分。\n" +
                        "如果只是想稍后继续，请改用暂停。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelDownload(task.id)
                    cancelTarget = null
                }) { Text("取消下载") }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) { Text("继续下载") }
            },
        )
    }
}

/** Pill selector: the three queues side by side, count kept inside the pill. */
@Composable
private fun TransferTabs(
    selected: TransferTab,
    counts: Map<TransferTab, Int>,
    onSelect: (TransferTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TransferTab.entries.forEach { item ->
            val on = item == selected
            val container = if (on) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val content = if (on) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = CircleShape,
                color = container,
                contentColor = content,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .clickable { onSelect(item) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    val count = counts[item] ?: 0
                    if (count > 0) {
                        Spacer(Modifier.width(5.dp))
                        Surface(
                            shape = CircleShape,
                            color = if (on) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = content,
                        ) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Empty list inside an otherwise active page. */
@Composable
private fun TabEmpty(tab: TransferTab) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = tab.empty,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UploadCard(
    task: UploadTask,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = task.status == UploadStatus.QUEUED || task.status == UploadStatus.RUNNING
    val fraction = if (task.totalBytes > 0) {
        (task.doneBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    val tone = when (task.status) {
        UploadStatus.QUEUED -> Tone(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "排队",
        )

        UploadStatus.RUNNING -> Tone(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            if (task.phase == UploadPhase.ENCRYPTING) "加密中" else "上传中",
        )

        UploadStatus.DONE -> Tone(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "已上传",
        )

        UploadStatus.FAILED -> Tone(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "失败",
        )

        UploadStatus.CANCELLED -> Tone(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "已取消",
        )
    }

    TransferCard(onDelete = onDelete) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransferIcon(
                icon = when {
                    task.status == UploadStatus.DONE -> Icons.Rounded.CheckCircle
                    task.status == UploadStatus.FAILED -> Icons.Rounded.ErrorOutline
                    task.status == UploadStatus.CANCELLED -> Icons.Rounded.Close
                    task.status == UploadStatus.QUEUED -> Icons.Rounded.Schedule
                    task.phase == UploadPhase.ENCRYPTING -> Icons.Rounded.Lock
                    else -> Icons.Rounded.CloudUpload
                },
                container = tone.container,
                content = tone.content,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = uploadDetail(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == UploadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusBadge(tone)
        }

        if (active && task.totalBytes > 0) {
            Spacer(Modifier.height(10.dp))
            TransferProgress(fraction)
        }

        if (active) {
            RowActions {
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = downloadStatus(task)
    val fraction = if (task.totalBytes > 0) {
        (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100).toInt()
    val tone = when (status) {
        DownloadStatus.PENDING -> Tone(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "排队",
        )

        DownloadStatus.RUNNING -> Tone(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            if (task.totalBytes > 0) "$percent%" else "下载中",
        )

        DownloadStatus.PAUSED -> Tone(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "已暂停",
        )

        DownloadStatus.DONE -> Tone(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "已下载",
        )

        DownloadStatus.FAILED -> Tone(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "失败",
        )

        DownloadStatus.CANCELLED -> Tone(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "已取消",
        )
    }

    TransferCard(onDelete = onDelete) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransferIcon(
                icon = when (status) {
                    DownloadStatus.DONE -> Icons.Rounded.DownloadDone
                    DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline
                    DownloadStatus.PAUSED -> Icons.Rounded.Pause
                    DownloadStatus.CANCELLED -> Icons.Rounded.Close
                    else -> Icons.Rounded.Downloading
                },
                container = tone.container,
                content = tone.content,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = downloadDetail(task, status),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusBadge(tone)
        }

        if ((status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED) &&
            task.totalBytes > 0
        ) {
            Spacer(Modifier.height(10.dp))
            TransferProgress(fraction)
        }

        if (status != DownloadStatus.DONE) {
            RowActions {
                when (status) {
                    DownloadStatus.RUNNING -> TextButton(onClick = onPause) { Text("暂停") }
                    DownloadStatus.PAUSED -> TextButton(onClick = onResume) { Text("继续") }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                        TextButton(onClick = onRetry) { Text("重试") }

                    DownloadStatus.DONE, DownloadStatus.PENDING -> Unit
                }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

/** Outlined card that holds one task; long-press deletes a finished row. */
@Composable
private fun TransferCard(
    onDelete: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = {}, onLongClick = onDelete),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
private fun TransferIcon(
    icon: ImageVector,
    container: Color,
    content: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun StatusBadge(tone: Tone) {
    Surface(
        shape = CircleShape,
        color = tone.container,
        contentColor = tone.content,
    ) {
        Text(
            text = tone.label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun TransferProgress(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun RowActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun uploadDetail(task: UploadTask): String = when (task.status) {
    UploadStatus.QUEUED -> "等待开始"
    UploadStatus.RUNNING -> when (task.phase) {
        UploadPhase.ENCRYPTING -> "正在本机加密…"
        UploadPhase.UPLOADING ->
            "${formatBytes(task.doneBytes)} / ${formatBytes(task.totalBytes)}"
    }

    UploadStatus.DONE -> "已保存至 ${task.dir}"
    UploadStatus.FAILED -> task.error ?: "上传失败"
    UploadStatus.CANCELLED -> "已取消"
}

private fun downloadDetail(task: DownloadTaskEntity, status: DownloadStatus): String = when (status) {
    DownloadStatus.PENDING -> "等待中…"
    DownloadStatus.RUNNING ->
        "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"

    DownloadStatus.PAUSED ->
        "已暂停 · ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"

    DownloadStatus.DONE -> "已保存到本机 · ${formatBytes(task.totalBytes)}"
    DownloadStatus.FAILED -> task.error ?: "下载失败"
    DownloadStatus.CANCELLED -> "已取消"
}

private val ACTIVE_DOWNLOAD_STATUSES = setOf(
    DownloadStatus.PENDING,
    DownloadStatus.RUNNING,
    DownloadStatus.PAUSED,
)

private val FINISHED_DOWNLOAD_STATUSES = setOf(
    DownloadStatus.DONE,
    DownloadStatus.FAILED,
    DownloadStatus.CANCELLED,
)

private fun downloadStatus(task: DownloadTaskEntity): DownloadStatus =
    runCatching { DownloadStatus.valueOf(task.status) }.getOrDefault(DownloadStatus.PENDING)
