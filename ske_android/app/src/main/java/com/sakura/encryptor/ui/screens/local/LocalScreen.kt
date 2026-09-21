package com.sakura.encryptor.ui.screens.local

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakura.encryptor.core.local.LocalSkeStore
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.LoadingState
import com.sakura.encryptor.ui.components.LocalUnlockDialog
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.components.TaskProgressDialog
import com.sakura.encryptor.ui.navigation.PlaybackSource
import com.sakura.encryptor.ui.rememberAppViewModel
import com.sakura.encryptor.ui.util.formatBytes

/**
 * Local file browser: lists a folder, plays encrypted media and exports
 * decrypted copies. Creating new `.ske` files lives on its own screen.
 */
@Composable
fun LocalScreen(
    onOpenPlayer: (uri: String, displayName: String, source: PlaybackSource, dir: String) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = rememberAppViewModel { container ->
        LocalViewModel(
            store = container.localSkeStore,
            settings = container.settings,
            runner = container.cryptoTaskRunner,
            localVault = container.localVault,
            keyStore = container.keyStore,
            notifications = container.cryptoNotifications,
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val rememberDefault by viewModel.rememberDefault.collectAsStateWithLifecycle()
    val container = appContainer()
    val unlocked by container.localVault.isUnlockedFlow.collectAsStateWithLifecycle()

    var decryptTarget by remember { mutableStateOf<LocalSkeStore.LocalEntry?>(null) }
    var showUnlockDialog by remember { mutableStateOf(false) }

    // An action waiting for the vault to be unlocked first.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // A file the user tried to play before unlocking; played once names resolve.
    var pendingPlay by remember { mutableStateOf<LocalSkeStore.LocalEntry?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.onTreeSelected(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // Once the names are decrypted, honour a queued playback.
    LaunchedEffect(state.displayNames, pendingPlay) {
        val target = pendingPlay ?: return@LaunchedEffect
        val resolved = state.displayNames[target.uri.toString()] ?: return@LaunchedEffect
        pendingPlay = null
        if (MediaTypes.kindOf(resolved) == MediaKind.OTHER) {
            Toast.makeText(context, "「$resolved」不是可播放的媒体文件", Toast.LENGTH_SHORT).show()
        } else {
            onOpenPlayer(
                target.uri.toString(),
                resolved,
                PlaybackSource.LOCAL,
                state.treeUri.orEmpty(),
            )
        }
    }

    /** Runs [action] straight away when unlocked, otherwise asks for the password first. */
    fun requireUnlocked(action: () -> Unit) {
        if (unlocked) {
            action()
        } else {
            pendingAction = action
            showUnlockDialog = true
        }
    }

    val treeLabel = state.treeUri
        ?.let { Uri.decode(it).substringAfterLast(':').substringAfterLast('/') }
        ?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = "本地文件",
            subtitle = treeLabel ?: "尚未选择目录",
            onMenu = onOpenDrawer,
            actions = {
                if (state.treeUri != null) {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
                IconButton(onClick = { treePicker.launch(null) }) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = "选择目录")
                }
            },
        )

        when {
            state.treeUri == null -> StateMessage(
                icon = Icons.Rounded.FolderOpen,
                title = "选择一个目录",
                message = "授权一个文件夹后即可播放其中的媒体：.ske 需要密码，普通文件直接播放。需要加密新文件请到「加密」页。",
                actionLabel = "选择目录",
                onAction = { treePicker.launch(null) },
            )

            state.isLoading -> LoadingState("正在扫描目录…")

            state.error != null -> StateMessage(
                icon = Icons.Rounded.FolderOpen,
                title = "无法读取目录",
                message = state.error,
                actionLabel = "重新选择",
                onAction = { treePicker.launch(null) },
            )

            state.entries.isEmpty() -> StateMessage(
                icon = Icons.Rounded.InsertDriveFile,
                title = "目录是空的",
                message = "这里还没有文件。要加密新文件，请到「加密」页。",
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (!unlocked) {
                    item {
                        UnlockHintCard(onUnlock = { showUnlockDialog = true })
                    }
                }

                items(items = state.entries, key = { it.uri.toString() }) { entry ->
                    val nameIsLocked = !unlocked && entry.isSke

                    LocalFileRow(
                        entry = entry,
                        displayName = if (nameIsLocked) {
                            "加密文件"
                        } else {
                            viewModel.displayNameOf(entry)
                        },
                        nameIsLocked = nameIsLocked,
                        onClick = {
                            if (!entry.isSke) {
                                // Plain media plays straight away — no password needed.
                                if (MediaTypes.kindOf(entry.name) == MediaKind.OTHER) {
                                    Toast.makeText(
                                        context,
                                        "「${entry.name}」不是可播放的媒体文件",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    onOpenPlayer(
                                        entry.uri.toString(),
                                        entry.name,
                                        PlaybackSource.LOCAL,
                                        state.treeUri.orEmpty(),
                                    )
                                }
                                return@LocalFileRow
                            }
                            if (unlocked) {
                                val resolved = viewModel.displayNameOf(entry)
                                if (MediaTypes.kindOf(resolved) == MediaKind.OTHER) {
                                    Toast.makeText(context, "「$resolved」不是可播放的媒体文件", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOpenPlayer(
                                        entry.uri.toString(),
                                        resolved,
                                        PlaybackSource.LOCAL,
                                        state.treeUri.orEmpty(),
                                    )
                                }
                            } else {
                                pendingPlay = entry
                                showUnlockDialog = true
                            }
                        },
                        onLongClick = {
                            if (entry.isSke) {
                                requireUnlocked { decryptTarget = entry }
                            }
                        },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 76.dp),
                    )
                }
            }
        }
    }

    if (showUnlockDialog) {
        LocalUnlockDialog(
            initialRemember = rememberDefault,
            onDismiss = {
                showUnlockDialog = false
                pendingAction = null
                pendingPlay = null
            },
            onSubmit = { password, remember -> viewModel.unlock(password, remember) },
            onUnlocked = {
                showUnlockDialog = false
                viewModel.onUnlocked()
                pendingAction?.invoke()
                pendingAction = null
            },
        )
    }

    val progress = state.progress
    if (state.busy) {
        TaskProgressDialog(
            title = progress?.title ?: "正在处理",
            detail = if (progress != null) {
                "${progress.fileName}  (${progress.index}/${progress.count})"
            } else {
                "准备中…"
            },
            fraction = progress?.fraction ?: 0f,
            onSendToBackground = viewModel::cancelProgressUi,
        )
    }

    decryptTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { decryptTarget = null },
            title = { Text("解密导出") },
            text = {
                Text(
                    "将「${viewModel.displayNameOf(entry)}」解密并保存到当前目录。\n" +
                        "注意：解密后的明文文件不再受保护。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.decrypt(listOf(entry.uri))
                    decryptTarget = null
                }) { Text("解密") }
            },
            dismissButton = {
                TextButton(onClick = { decryptTarget = null }) { Text("取消") }
            },
        )
    }
}

/** Inline banner shown while the local password has not been entered yet. */
@Composable
private fun UnlockHintCard(onUnlock: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "尚未输入本地密码",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "输入后即可显示文件名并播放，密码可记住",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TextButton(onClick = onUnlock) { Text("输入密码") }
        }
    }
}

@Composable
private fun LocalFileRow(
    entry: LocalSkeStore.LocalEntry,
    displayName: String,
    nameIsLocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconFor(entry, displayName, nameIsLocked),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    entry.isSke && nameIsLocked -> "加密文件 · ${formatBytes(entry.size)} · 需解锁"
                    entry.isSke -> "加密文件 · ${formatBytes(entry.size)}"
                    entry.size > 0 -> "${MediaTypes.labelOf(displayName)} · ${formatBytes(entry.size)}"
                    else -> "未加密"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (entry.isSke) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "已加密",
                tint = if (nameIsLocked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private fun iconFor(
    entry: LocalSkeStore.LocalEntry,
    displayName: String,
    nameIsLocked: Boolean,
): ImageVector {
    if (entry.isSke) {
        if (nameIsLocked) return Icons.Rounded.Lock
        return when (MediaTypes.kindOf(displayName)) {
            MediaKind.VIDEO -> Icons.Rounded.Movie
            MediaKind.AUDIO -> Icons.Rounded.MusicNote
            MediaKind.IMAGE -> Icons.Rounded.Image
            MediaKind.OTHER -> Icons.Rounded.Lock
        }
    }
    return Icons.Rounded.InsertDriveFile
}
