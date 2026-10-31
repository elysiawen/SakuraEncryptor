package com.sakura.encryptor.ui.screens.browse

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.sakura.encryptor.core.alist.DecryptedEntry
import com.sakura.encryptor.core.media.PreviewCache
import com.sakura.encryptor.core.player.MediaKind
import com.sakura.encryptor.core.player.MediaTypes
import com.sakura.encryptor.ui.appContainer
import com.sakura.encryptor.ui.components.LoadingState
import com.sakura.encryptor.ui.components.MediaTile
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.components.TaskProgressDialog
import com.sakura.encryptor.ui.components.TileBadges
import com.sakura.encryptor.ui.components.UnlockPanel
import com.sakura.encryptor.ui.components.ViewModeToggle
import com.sakura.encryptor.ui.navigation.PlaybackSource
import com.sakura.encryptor.ui.rememberAppViewModel
import com.sakura.encryptor.ui.util.formatBytes

@Composable
fun BrowseScreen(
    onOpenPlayer: (uri: String, displayName: String, source: PlaybackSource, dir: String) -> Unit,
    onOpenDrawer: () -> Unit,
    onManageProfiles: () -> Unit,
    onGoLocal: () -> Unit,
) {
    val container = appContainer()
    val viewModel = rememberAppViewModel { c ->
        BrowseViewModel(
            repository = c.aListRepository,
            session = c.aListSession,
            vault = c.vault,
            downloadManager = c.downloadManager,
            runner = c.cryptoTaskRunner,
            profileRepository = c.profileRepository,
            cipherBlockSourceFactory = c.cipherBlockSourceFactory,
            previews = c.previewLoader,
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val breadcrumbs by viewModel.breadcrumbs.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val rememberDefault by viewModel.rememberDefault.collectAsStateWithLifecycle()
    val unlocked by container.vault.isUnlockedFlow.collectAsStateWithLifecycle()
    val restoreFinished by container.vault.restoreFinished.collectAsStateWithLifecycle()
    val loggedIn by container.aListSession.isLoggedInFlow.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Hoisted out of the LazyColumn branch: while data reloads the list leaves
    // the composition, and a state held inside it would be rebuilt empty — the
    // position survived neither entering the player nor coming back. With the
    // state living here, the scroll position rides through reloads untouched.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Remembered across launches, and shared with the local screen.
    val grid by container.settings.gridView.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    val activeProfile = remember(profiles, activeProfileId) {
        profiles.firstOrNull { it.id == activeProfileId }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var encryptNewFolderName by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<DecryptedEntry?>(null) }
    // Deleting cloud files is permanent and there is no undo, so the toolbar icon
    // only stages the selection; the actual delete waits for this dialog.
    var deleteTargets by remember { mutableStateOf<List<DecryptedEntry>>(emptyList()) }

    // Downloads land in the shared Downloads folder (Download/SakuraEncryptor/).
    // Android 10+ writes through MediaStore and needs no permission; Android 9
    // and older need WRITE_EXTERNAL_STORAGE first, so the tap is parked here
    // until the prompt is answered.
    var pendingDownload by remember { mutableStateOf<List<DecryptedEntry>>(emptyList()) }

    val legacyWritePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val queued = pendingDownload
        pendingDownload = emptyList()
        if (queued.isNotEmpty()) {
            if (!granted) {
                Toast.makeText(context, "未授予存储权限，文件将保存到应用私有目录", Toast.LENGTH_LONG).show()
            }
            // Runs either way: the manager falls back when the grant is missing.
            viewModel.download(queued)
        }
    }

    val startDownload: (List<DecryptedEntry>) -> Unit = { entries ->
        val needsGrant = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsGrant) {
            pendingDownload = entries
            legacyWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.download(entries)
        }
    }

    // Uploads: pick local documents first, then confirm (and choose whether to
    // encrypt) in a dialog, mirroring the web client's flow.
    var pendingUpload by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var encryptUpload by remember { mutableStateOf(true) }

    val uploadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            encryptUpload = true
            pendingNames = viewModel.suggestedUploadNames(uris)
            pendingUpload = uris
        }
    }

    // Re-list whenever the vault, the profile or the AList session changes.
    LaunchedEffect(activeProfileId, unlocked, loggedIn) { viewModel.syncSession() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowseViewModel.Event.OpenPlayer ->
                    onOpenPlayer(event.uri, event.name, event.source, state.path)

                is BrowseViewModel.Event.Message ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val ready = unlocked && loggedIn

    BackHandler(enabled = ready && state.inSelectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = ready && !state.inSelectionMode && state.path != "/") { viewModel.goUp() }

    val goUpAction: (() -> Unit)? = if (ready && state.path != "/") {
        { viewModel.goUp() }
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = when {
                state.inSelectionMode -> "已选择 ${state.selected.size} 项"
                !ready -> activeProfile?.name ?: "云端媒体库"
                state.path == "/" -> activeProfile?.name ?: "云端媒体库"
                // Use the decrypted folder name, never the encrypted path segment.
                else -> breadcrumbs.lastOrNull()?.second ?: activeProfile?.name ?: "云端媒体库"
            },
            subtitle = when {
                !ready -> "未连接"
                else -> breadcrumbs.joinToString(" / ") { it.second }.ifBlank { "/" }
            },
            onBack = if (state.inSelectionMode) viewModel::clearSelection else goUpAction,
            onMenu = onOpenDrawer,
            actions = {
                if (ready && state.inSelectionMode) {
                    val selectedEntries = state.entries.filter { it.raw.name in state.selected }

                    // Renaming only makes sense for a single entry.
                    if (selectedEntries.size == 1) {
                        IconButton(onClick = { renameTarget = selectedEntries.first() }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "重命名")
                        }
                    }
                    IconButton(onClick = { startDownload(selectedEntries) }) {
                        Icon(Icons.Rounded.Download, contentDescription = "下载")
                    }
                    IconButton(onClick = { deleteTargets = selectedEntries }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除")
                    }
                } else if (ready) {
                    if (state.entries.isNotEmpty()) {
                        ViewModeToggle(
                            grid = grid,
                            onToggle = { scope.launch { container.settings.setGridView(!grid) } },
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                    IconButton(onClick = onManageProfiles) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = "切换配置")
                    }
                } else if (profiles.isNotEmpty()) {
                    IconButton(onClick = onManageProfiles) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = "管理配置")
                    }
                }
            },
        )

        if (ready && state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            // Still trying a remembered password: avoid flashing the prompt.
            !restoreFinished && !unlocked -> LoadingState("正在准备…")

            activeProfile == null -> StateMessage(
                icon = Icons.Rounded.Add,
                title = if (profiles.isEmpty()) "还没有云端配置" else "未选择云端配置",
                message = "添加一个 AList 配置后即可浏览云端加密文件；只想播放本地文件可以跳过。",
                actionLabel = "管理配置",
                onAction = onManageProfiles,
            )

            !unlocked -> UnlockPanel(
                title = "输入「${activeProfile.name}」的密码",
                message = "每个云端配置拥有独立的加密密码，用于解密该配置下的文件。",
                initialRemember = rememberDefault,
                onSubmit = { password, remember -> viewModel.unlockActive(password, remember) },
                onUnlocked = { viewModel.syncSession() },
                secondaryAction = {
                    TextButton(onClick = onManageProfiles) { Text("切换或编辑配置") }
                },
            )

            !loggedIn -> StateMessage(
                icon = Icons.Rounded.CloudOff,
                title = "「${activeProfile.name}」尚未连接",
                message = if (activeProfile.canAutoLogin) {
                    "点击连接，使用已保存的账号信息登录 AList。"
                } else {
                    "该配置还没有保存 AList 密码，请先在配置中填写。"
                },
                actionLabel = "连接",
                onAction = { viewModel.connectActive() },
            )

            else -> Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> LoadingState("正在读取加密目录…")

                    state.error != null -> StateMessage(
                        icon = Icons.Rounded.CloudOff,
                        title = "无法加载目录",
                        message = state.error,
                        actionLabel = "重试",
                        onAction = viewModel::refresh,
                    )

                    state.entries.isEmpty() -> StateMessage(
                        icon = Icons.Rounded.FolderOpen,
                        title = "这里是空的",
                        message = "目录中没有文件，或加密文件尚未上传。",
                    )

                    else -> if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(104.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                // Clears the upload FAB so the last row stays reachable.
                                bottom = 88.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            gridItems(items = state.entries, key = { it.raw.name }) { entry ->
                                val kind = MediaTypes.kindOf(entry.displayName)
                                MediaTile(
                                    name = entry.displayName,
                                    icon = iconFor(entry, kind),
                                    // Unlock state is part of the key so the
                                    // tile retries once the password is known.
                                    cacheKey = PreviewCache.key(
                                        identity = "${state.path.trimEnd('/')}/${entry.raw.name}",
                                        unlocked = unlocked && entry.isSke,
                                    ),
                                    loadPreview = { viewModel.previewOf(entry) },
                                    isSelected = entry.raw.name in state.selected,
                                    onOpen = {
                                        if (state.inSelectionMode) {
                                            viewModel.toggleSelection(entry.raw.name)
                                        } else if (entry.isDir) {
                                            viewModel.openFolder(entry)
                                        } else {
                                            viewModel.play(entry)
                                        }
                                    },
                                    onLongPress = { viewModel.toggleSelection(entry.raw.name) },
                                    badges = {
                                        TileBadges(
                                            locked = entry.isSke,
                                            video = kind == MediaKind.VIDEO,
                                            sizeText = if (!entry.isDir && entry.size > 0) {
                                                formatBytes(entry.size)
                                            } else {
                                                null
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    } else LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Clears the upload FAB so the last row stays reachable.
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(
                            items = state.entries,
                            key = { it.raw.name },
                        ) { entry ->
                            FileRow(
                                entry = entry,
                                isSelected = entry.raw.name in state.selected,
                                onClick = {
                                    if (state.inSelectionMode) {
                                        viewModel.toggleSelection(entry.raw.name)
                                    } else if (entry.isDir) {
                                        viewModel.openFolder(entry)
                                    } else {
                                        viewModel.play(entry)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(entry.raw.name) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 76.dp),
                            )
                        }
                    }
                }

                if (state.isPreparing) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            LoadingState("正在获取解密地址…")
                        }
                    }
                }

                // Upload is the one action the listing itself can host, so it
                // floats over the grid instead of crowding the top bar.
                if (!state.inSelectionMode && !state.isLoading && state.error == null) {
                    FloatingActionButton(
                        onClick = { uploadPicker.launch(arrayOf("*/*")) },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp),
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = "上传")
                    }
                }
            }
        }

        // Local users should never feel stuck on this tab.
        if (activeProfile == null || !unlocked) {
            Box(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = onGoLocal) { Text("只想播放本地文件？前往本地") }
            }
        }
    }

    renameTarget?.let { entry ->
        RenameDialog(
            entry = entry,
            onDismiss = { renameTarget = null },
            onConfirm = { newName, encryptName ->
                viewModel.rename(entry, newName, encryptName)
                renameTarget = null
            },
        )
    }

    if (deleteTargets.isNotEmpty()) {
        val single = deleteTargets.singleOrNull()
        val hasFolder = deleteTargets.any { it.isDir }

        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = {
                Text(
                    if (single != null) {
                        if (single.isDir) "删除文件夹？" else "删除文件？"
                    } else {
                        "删除 ${deleteTargets.size} 项？"
                    }
                )
            },
            text = {
                Text(
                    buildString {
                        // Name what goes away: with decrypted names shown in the
                        // list, "所选项目" would be harder to check than the name.
                        if (single != null) {
                            append("将永久删除「${single.displayName}」")
                            if (single.isDir) append("及其中全部内容")
                        } else {
                            append("将永久删除所选的 ${deleteTargets.size} 项")
                            if (hasFolder) append("（文件夹内的全部内容一并删除）")
                        }
                        append("。云端数据删除后无法恢复。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(deleteTargets)
                    deleteTargets = emptyList()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = emptyList() }) { Text("取消") }
            },
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建文件夹") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("文件夹名称") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    EncryptNameToggle(
                        checked = encryptNewFolderName,
                        onCheckedChange = { encryptNewFolderName = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createFolder(newFolderName, encryptNewFolderName)
                    newFolderName = ""
                    showCreateDialog = false
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            },
        )
    }

    if (pendingUpload.isNotEmpty()) {
        val folderLabel = breadcrumbs.lastOrNull()?.second ?: "/"
        val namesReady = pendingUpload.indices.all {
            pendingNames.getOrElse(it) { "" }.isNotBlank()
        }
        val clearPending = {
            pendingUpload = emptyList()
            pendingNames = emptyList()
        }
        AlertDialog(
            onDismissRequest = clearPending,
            title = { Text("上传文件") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("将 ${pendingUpload.size} 个文件上传到「$folderLabel」")
                    Spacer(Modifier.height(8.dp))
                    pendingUpload.indices.forEach { index ->
                        OutlinedTextField(
                            value = pendingNames.getOrElse(index) { "" },
                            onValueChange = { value ->
                                pendingNames = pendingNames.toMutableList()
                                    .also { it[index] = value }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    if (pendingUpload.size == 1) "文件名" else "文件名 ${index + 1}",
                                )
                            },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    EncryptNameToggle(
                        checked = encryptUpload,
                        onCheckedChange = { encryptUpload = it },
                        title = "加密后上传",
                        onText = "内容与文件名一并加密为 .ske，仅本应用可读",
                        offText = "以明文上传，其他客户端可直接打开",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = namesReady,
                    onClick = {
                        viewModel.upload(pendingUpload, encryptUpload, pendingNames)
                        clearPending()
                    },
                ) { Text("上传") }
            },
            dismissButton = {
                TextButton(onClick = clearPending) { Text("取消") }
            },
        )
    }

    state.uploadProgress?.let { progress ->
        TaskProgressDialog(
            title = "正在上传",
            detail = "${progress.fileName}  (${progress.index}/${progress.count}) · ${progress.stage}",
            fraction = progress.fraction,
            onSendToBackground = viewModel::dismissUploadUi,
        )
    }
}

@Composable
private fun RenameDialog(
    entry: DecryptedEntry,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var name by remember(entry.raw.name) { mutableStateOf(entry.displayName) }
    // Keep the entry's current form: an encrypted name stays encrypted, a plain
    // one stays plain unless the user opts in.
    var encryptName by remember(entry.raw.name) { mutableStateOf(entry.nameDecrypted) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isDir) "重命名文件夹" else "重命名文件") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                EncryptNameToggle(
                    checked = encryptName,
                    onCheckedChange = { encryptName = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, encryptName) },
                enabled = name.isNotBlank() && name != entry.displayName,
            ) { Text("重命名") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Optional "encrypt this" checkbox used by the create / rename / upload dialogs.
 *
 * The wording differs per flow — a folder name, a file name, or the whole
 * payload — so the labels are parameters with the name-only text as the default.
 */
@Composable
private fun EncryptNameToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String = "加密名称",
    onText: String = "云端保存为密文，仅本应用可读",
    offText: String = "取消勾选后以明文保存，其他客户端可直接看到",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (checked) onText else offText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: DecryptedEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kind = MediaTypes.kindOf(entry.displayName)
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconFor(entry, kind),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitleFor(entry, kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun iconFor(entry: DecryptedEntry, kind: MediaKind): ImageVector = when {
    entry.isDir -> Icons.Rounded.Folder
    kind == MediaKind.VIDEO -> Icons.Rounded.Movie
    kind == MediaKind.AUDIO -> Icons.Rounded.MusicNote
    kind == MediaKind.IMAGE -> Icons.Rounded.Image
    else -> Icons.Rounded.InsertDriveFile
}

private fun subtitleFor(entry: DecryptedEntry, kind: MediaKind): String = when {
    entry.isDir -> "文件夹"
    entry.size > 0 -> "${formatBytes(entry.size)} · ${MediaTypes.labelOf(entry.displayName)}"
    else -> MediaTypes.labelOf(entry.displayName)
}
