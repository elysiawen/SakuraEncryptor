package com.sakura.encryptor.ui.screens.openlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.embedded.EmbeddedServer
import com.sakura.encryptor.core.openlist.StorageInfo
import com.sakura.encryptor.core.openlist.deleteStorage
import com.sakura.encryptor.core.openlist.driverInfos
import com.sakura.encryptor.core.openlist.setStorageEnabled
import com.sakura.encryptor.core.openlist.storages
import com.sakura.encryptor.ui.components.LoadingState
import com.sakura.encryptor.ui.components.PrimaryButton
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.rememberAppViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageListState(
    val loading: Boolean = true,
    val storages: List<StorageInfo> = emptyList(),
    val drivers: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = !loading && error == null
}

class StorageListViewModel(
    private val api: AListApi,
    private val embeddedServer: EmbeddedServer,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageListState())
    val state: StateFlow<StorageListState> = _state.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val (server, token) = embeddedServer.requireSession()
            api.storages(server, token)
        }.onSuccess { list ->
            _state.value = _state.value.copy(loading = false, storages = list.sortedBy { it.order })
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                error = error.message ?: "读取存储列表失败",
            )
        }
    }

    /** Only used by the "add storage" picker; loaded once per screen. */
    fun loadDrivers() {
        if (_state.value.drivers.isNotEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                val (server, token) = embeddedServer.requireSession()
                api.driverInfos(server, token).keys.sorted()
            }.onSuccess { names ->
                _state.value = _state.value.copy(busy = false, drivers = names)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    busy = false,
                    error = error.message ?: "读取驱动列表失败",
                )
            }
        }
    }

    fun setEnabled(storage: StorageInfo, enabled: Boolean) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        runCatching {
            val (server, token) = embeddedServer.requireSession()
            api.setStorageEnabled(server, token, storage.id, enabled)
            api.storages(server, token).sortedBy { it.order }
        }.onSuccess { list ->
            _state.value = _state.value.copy(busy = false, storages = list)
        }.onFailure { error ->
            _state.value = _state.value.copy(
                busy = false,
                error = error.message ?: "切换存储状态失败",
            )
        }
    }

    fun delete(storage: StorageInfo) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        runCatching {
            val (server, token) = embeddedServer.requireSession()
            api.deleteStorage(server, token, storage.id)
            api.storages(server, token).sortedBy { it.order }
        }.onSuccess { list ->
            _state.value = _state.value.copy(busy = false, storages = list)
        }.onFailure { error ->
            _state.value = _state.value.copy(
                busy = false,
                error = error.message ?: "删除存储失败",
            )
        }
    }
}

@Composable
fun StorageScreen(
    onBack: () -> Unit,
    onAddStorage: (driver: String) -> Unit,
    onEditStorage: (id: Int) -> Unit,
) {
    val viewModel = rememberAppViewModel { container ->
        StorageListViewModel(container.aListApi, container.embeddedServer)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showDriverPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StorageInfo?>(null) }

    // Re-runs when the screen re-enters composition, i.e. after an edit returns.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(title = "存储管理", subtitle = "内置服务器", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "挂载",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.storages.isNotEmpty()) {
                    Text(
                        text = "${state.storages.size} 个 · ${state.storages.count { !it.disabled }} 个已启用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PrimaryButton(
                text = "添加存储",
                onClick = {
                    viewModel.loadDrivers()
                    showDriverPicker = true
                },
                icon = Icons.Rounded.Add,
                enabled = state.ready,
            )

            Spacer(Modifier.height(14.dp))

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            when {
                state.loading -> LoadingState("正在读取存储…")
                state.storages.isEmpty() -> StateMessage(
                    icon = Icons.Rounded.FolderOpen,
                    title = "还没有存储",
                    message = "添加一个存储后，「云端」页面才能浏览文件。",
                )
                else -> state.storages.forEach { storage ->
                    StorageCard(
                        storage = storage,
                        busy = state.busy,
                        onEdit = { onEditStorage(storage.id) },
                        onToggle = { enabled -> viewModel.setEnabled(storage, enabled) },
                        onDelete = { pendingDelete = storage },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showDriverPicker) {
        DriverPickerDialog(
            drivers = state.drivers,
            loading = state.busy && state.drivers.isEmpty(),
            onPick = { driver ->
                showDriverPicker = false
                onAddStorage(driver)
            },
            onDismiss = { showDriverPicker = false },
        )
    }

    pendingDelete?.let { storage ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除存储") },
            text = { Text("确定删除「${storage.mountPath}」吗？只删除挂载，不会删除存储里的文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(storage)
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StorageCard(
    storage: StorageInfo,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val enabled = !storage.disabled

    SakuraCard(
        onClick = onEdit,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StorageGlyph(enabled = enabled)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = storage.mountPath,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DriverTag(driver = storage.driver)
                    Spacer(Modifier.width(8.dp))
                    StatusDot(enabled = enabled)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (enabled) "已启用" else "已停用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (storage.remark.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = storage.remark,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
            )
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !busy,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Rounded tile with the mount's icon, tinted by the current accent. */
@Composable
private fun StorageGlyph(enabled: Boolean) {
    val iconColor =
        if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    val brush = if (enabled) {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.secondaryContainer,
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The driver name as a small tonal chip. */
@Composable
private fun DriverTag(driver: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
    ) {
        Text(
            text = driver.ifBlank { "未知驱动" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            ),
    )
}

@Composable
private fun DriverPickerDialog(
    drivers: List<String>,
    loading: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(drivers, query) {
        if (query.isBlank()) drivers
        else drivers.filter { it.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择驱动") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索驱动") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                when {
                    loading -> LoadingState("正在读取驱动…")
                    filtered.isEmpty() -> Text(
                        text = "没有匹配的驱动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        filtered.forEach { name ->
                            TextButton(
                                onClick = { onPick(name) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = name,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
