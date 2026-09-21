package com.sakura.encryptor.ui.screens.downloads

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.core.download.DownloadManager
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

class DownloadsViewModel(private val manager: DownloadManager) : ViewModel() {

    val tasks: StateFlow<List<DownloadTaskEntity>> = manager.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(id: String) = manager.pause(id)

    fun resume(id: String) = manager.start(id)

    fun retry(id: String) = manager.retry(id)

    fun cancel(id: String) = manager.cancel(id)

    fun delete(id: String) = manager.delete(id)

    fun clearFinished() = viewModelScope.launch { manager.clearFinished() }
}

@Composable
fun DownloadsScreen(onOpenDrawer: () -> Unit) {
    val viewModel = rememberAppViewModel { DownloadsViewModel(it.downloadManager) }
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    var cancelTarget by remember { mutableStateOf<DownloadTaskEntity?>(null) }

    val hasFinished = tasks.any {
        it.status == DownloadStatus.DONE.name ||
            it.status == DownloadStatus.FAILED.name ||
            it.status == DownloadStatus.CANCELLED.name
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = "下载",
            subtitle = if (tasks.isEmpty()) "暂无任务" else "${tasks.size} 个任务",
            onMenu = onOpenDrawer,
            actions = {
                if (hasFinished) {
                    IconButton(onClick = viewModel::clearFinished) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "清除已完成")
                    }
                }
            },
        )

        if (tasks.isEmpty()) {
            StateMessage(
                icon = Icons.Rounded.DownloadDone,
                title = "还没有下载任务",
                message = "在云端目录中长按选择加密文件，即可下载并解密到本地。",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = tasks, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onPause = { viewModel.pause(task.id) },
                        onResume = { viewModel.resume(task.id) },
                        onRetry = { viewModel.retry(task.id) },
                        onCancel = { cancelTarget = task },
                        onDelete = { viewModel.delete(task.id) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 76.dp),
                    )
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
                    viewModel.cancel(task.id)
                    cancelTarget = null
                }) { Text("取消下载") }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) { Text("继续下载") }
            },
        )
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = runCatching { DownloadStatus.valueOf(task.status) }.getOrDefault(DownloadStatus.PENDING)
    val fraction = if (task.totalBytes > 0) {
        (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }

    val showProgress = (status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED) &&
        task.totalBytes > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onDelete)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = when (status) {
                DownloadStatus.DONE -> MaterialTheme.colorScheme.primaryContainer
                DownloadStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                DownloadStatus.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (status) {
                        DownloadStatus.DONE -> Icons.Rounded.DownloadDone
                        DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline
                        DownloadStatus.PAUSED -> Icons.Rounded.Pause
                        DownloadStatus.CANCELLED -> Icons.Rounded.Close
                        else -> Icons.Rounded.Downloading
                    },
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = when (status) {
                        DownloadStatus.DONE -> MaterialTheme.colorScheme.onPrimaryContainer
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                        DownloadStatus.PAUSED -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = statusLine(task, status),
                style = MaterialTheme.typography.bodySmall,
                color = if (status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            when (status) {
                DownloadStatus.RUNNING -> {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Rounded.Pause, contentDescription = "暂停")
                    }
                }

                DownloadStatus.PAUSED -> {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "继续")
                    }
                }

                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Replay, contentDescription = "重试")
                    }
                }

                DownloadStatus.DONE, DownloadStatus.PENDING -> Unit
            }

            if (status != DownloadStatus.DONE) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "取消下载",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun statusLine(task: DownloadTaskEntity, status: DownloadStatus): String = when (status) {
    DownloadStatus.PENDING -> "等待中…"
    DownloadStatus.RUNNING ->
        "下载中 ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"

    DownloadStatus.PAUSED ->
        "已暂停 · ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"

    DownloadStatus.DONE -> "已完成 · ${formatBytes(task.totalBytes)}"
    DownloadStatus.FAILED -> task.error ?: "下载失败"
    DownloadStatus.CANCELLED -> "已取消"
}
