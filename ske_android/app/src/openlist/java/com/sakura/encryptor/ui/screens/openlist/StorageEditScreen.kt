package com.sakura.encryptor.ui.screens.openlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.core.alist.AListApi
import com.sakura.encryptor.core.embedded.EmbeddedServer
import com.sakura.encryptor.core.openlist.DriverInfo
import com.sakura.encryptor.core.openlist.DriverItem
import com.sakura.encryptor.core.openlist.StorageInfo
import com.sakura.encryptor.core.openlist.createStorage
import com.sakura.encryptor.core.openlist.driverInfos
import com.sakura.encryptor.core.openlist.storages
import com.sakura.encryptor.core.openlist.updateStorage
import com.sakura.encryptor.ui.components.LoadingState
import com.sakura.encryptor.ui.components.PrimaryButton
import com.sakura.encryptor.ui.components.SakuraCard
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.SectionHeader
import com.sakura.encryptor.ui.components.SwitchRow
import com.sakura.encryptor.ui.rememberAppViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class StorageEditState(
    val loading: Boolean = true,
    /** True once the driver definition and the storage (if any) were read. */
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val isNew: Boolean = true,
    val driver: String = "",
    /** Driver-level fields; the mount-level ones below are edited separately. */
    val items: List<DriverItem> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val mountPath: String = "",
    val order: String = "0",
    val cacheExpiration: String = "30",
    val remark: String = "",
)

class StorageEditViewModel(
    private val api: AListApi,
    private val json: Json,
    private val embeddedServer: EmbeddedServer,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageEditState())
    val state: StateFlow<StorageEditState> = _state.asStateFlow()

    private var existing: StorageInfo? = null
    private var driverInfo: DriverInfo? = null

    fun load(storageId: Int?, driverName: String) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val (server, token) = embeddedServer.requireSession()
            val current = storageId?.let { id ->
                api.storages(server, token).firstOrNull { it.id == id }
                    ?: error("这条存储不存在，可能已被删除")
            }
            val driver = current?.driver ?: driverName
            check(driver.isNotBlank()) { "没有指定驱动" }
            val info = api.driverInfos(server, token)[driver] ?: error("驱动 $driver 不可用")

            existing = current
            driverInfo = info

            val stored = current?.addition.orEmpty().let { raw ->
                if (raw.isBlank()) null else runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            }
            StorageEditState(
                loading = false,
                loaded = true,
                isNew = current == null,
                driver = driver,
                items = info.additional,
                values = info.additional.associate { item ->
                    item.name to (stored?.readValue(item) ?: item.default)
                },
                mountPath = current?.mountPath.orEmpty(),
                order = (current?.order ?: 0).toString(),
                cacheExpiration = (current?.cacheExpiration ?: DEFAULT_CACHE_EXPIRATION).toString(),
                remark = current?.remark.orEmpty(),
            )
        }.onSuccess { loaded ->
            _state.value = loaded
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                error = error.message ?: "读取驱动参数失败",
            )
        }
    }

    fun setMountPath(value: String) = _state.value.let { _state.value = it.copy(mountPath = value, error = null) }

    fun setOrder(value: String) = _state.value.let { _state.value = it.copy(order = value, error = null) }

    fun setCacheExpiration(value: String) =
        _state.value.let { _state.value = it.copy(cacheExpiration = value, error = null) }

    fun setRemark(value: String) = _state.value.let { _state.value = it.copy(remark = value, error = null) }

    fun setValue(name: String, value: String) {
        _state.value = _state.value.let {
            it.copy(values = it.values + (name to value), error = null)
        }
    }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        val current = _state.value
        if (current.saving) return@launch

        val mountPath = current.mountPath.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (current.mountPath.isBlank()) {
            _state.value = current.copy(error = "挂载路径不能为空")
            return@launch
        }

        val missing = current.items.filter { item ->
            item.required && !item.isBoolean && current.values[item.name].orEmpty().isBlank()
        }
        if (missing.isNotEmpty()) {
            _state.value = current.copy(error = "请填写：${missing.joinToString("、") { it.name }}")
            return@launch
        }
        val badNumber = current.items.firstOrNull { item ->
            item.isNumber && current.values[item.name].orEmpty().let { it.isNotBlank() && it.toDoubleOrNull() == null }
        }
        if (badNumber != null) {
            _state.value = current.copy(error = "${badNumber.name} 需要填数字")
            return@launch
        }

        _state.value = current.copy(saving = true, error = null)
        runCatching {
            val (server, token) = embeddedServer.requireSession()
            val addition = buildAddition(current)
            val base = existing
            val defaultWebdav = driverInfo?.common?.firstOrNull { it.name == "webdav_policy" }?.default.orEmpty()
            val payload = if (base != null) {
                base.copy(
                    mountPath = mountPath,
                    order = current.order.trim().toIntOrNull() ?: base.order,
                    cacheExpiration = current.cacheExpiration.trim().toIntOrNull() ?: base.cacheExpiration,
                    addition = addition,
                    remark = current.remark.trim(),
                )
            } else {
                StorageInfo(
                    mountPath = mountPath,
                    order = current.order.trim().toIntOrNull() ?: 0,
                    driver = current.driver,
                    cacheExpiration = current.cacheExpiration.trim().toIntOrNull()
                        ?: DEFAULT_CACHE_EXPIRATION,
                    status = "work",
                    addition = addition,
                    remark = current.remark.trim(),
                    webdavPolicy = defaultWebdav,
                    webProxy = driverInfo?.common?.firstOrNull { it.name == "web_proxy" }?.default == "true",
                    disableIndex = driverInfo?.common?.firstOrNull { it.name == "disable_index" }?.default == "true",
                    enableSign = driverInfo?.common?.firstOrNull { it.name == "enable_sign" }?.default == "true",
                    seedPolicy = "inherit",
                )
            }
            if (base != null) {
                api.updateStorage(server, token, payload)
            } else {
                api.createStorage(server, token, payload)
            }
        }.onSuccess {
            _state.value = _state.value.copy(saving = false)
            onSaved()
        }.onFailure { error ->
            _state.value = _state.value.copy(
                saving = false,
                error = error.message ?: "保存失败",
            )
        }
    }

    /** Types the driver declared are what the Go struct expects back. */
    private fun buildAddition(state: StorageEditState): String = buildJsonObject {
        state.items.forEach { item ->
            val raw = state.values[item.name].orEmpty()
            when {
                item.isBoolean -> put(item.name, raw == "true")
                item.isNumber -> {
                    // Go decodes these straight into int64/float64 fields. An
                    // integral 0.0 is rejected by int fields ("can not decode
                    // float as int"), and the reflected "number" type hides
                    // which fields are ints, so whole numbers always go out as
                    // integers.
                    val number = raw.trim().toDoubleOrNull()
                    if (number != null) {
                        if (number.isFinite() && number % 1.0 == 0.0) {
                            put(item.name, number.toLong())
                        } else {
                            put(item.name, number)
                        }
                    }
                }
                else -> put(item.name, raw)
            }
        }
    }.toString()

    private fun JsonObject.readValue(item: DriverItem): String? {
        val element = this[item.name] ?: return null
        return (element as? JsonPrimitive)?.content
    }

    companion object {
        const val DEFAULT_CACHE_EXPIRATION = 30
    }
}

@Composable
fun StorageEditScreen(
    storageId: Int?,
    driverName: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel = rememberAppViewModel(
        key = "storage_edit:${storageId ?: -1}:$driverName",
    ) { container ->
        StorageEditViewModel(container.aListApi, container.json, container.embeddedServer)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load(storageId, driverName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = if (state.isNew) "添加存储" else "编辑存储",
            subtitle = state.driver.ifEmpty { null },
            onBack = onBack,
        )

        when {
            state.loading -> LoadingState("正在读取驱动参数…")
            !state.loaded -> StateError(state.error.orEmpty())
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (state.error != null) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                SectionHeader("挂载")
                SakuraCard {
                    OutlinedTextField(
                        value = state.mountPath,
                        onValueChange = viewModel::setMountPath,
                        label = { Text("挂载路径") },
                        placeholder = { Text("/115") },
                        singleLine = true,
                        supportingText = { Text("挂载到「云端」页面的路径，不能与已有挂载重复") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.order,
                        onValueChange = viewModel::setOrder,
                        label = { Text("排序") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.cacheExpiration,
                        onValueChange = viewModel::setCacheExpiration,
                        label = { Text("缓存时间（分钟）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.remark,
                        onValueChange = viewModel::setRemark,
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(22.dp))

                SectionHeader("驱动参数")
                SakuraCard {
                    if (state.items.isEmpty()) {
                        Text(
                            text = "该驱动没有额外参数",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.items.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            }
                            DriverField(
                                item = item,
                                value = state.values[item.name].orEmpty(),
                                onChange = { value -> viewModel.setValue(item.name, value) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                PrimaryButton(
                    text = "保存",
                    onClick = { viewModel.save(onSaved) },
                    loading = state.saving,
                    enabled = !state.loading,
                )

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun StateError(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DriverField(
    item: DriverItem,
    value: String,
    onChange: (String) -> Unit,
) {
    when {
        item.isBoolean -> SwitchRow(
            title = item.name,
            subtitle = item.help.ifBlank { null },
            checked = value == "true",
            onCheckedChange = { onChange(it.toString()) },
        )
        item.optionList.isNotEmpty() -> SelectField(item, value, onChange)
        else -> Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(item.name) },
                singleLine = !item.type.equals("text", ignoreCase = true),
                minLines = if (item.type.equals("text", ignoreCase = true)) 3 else 1,
                keyboardOptions = if (item.isNumber) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (item.help.isNotBlank()) {
                Text(
                    text = item.help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectField(
    item: DriverItem,
    value: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val shape = MaterialTheme.shapes.small
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value.ifEmpty { "未选择" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            item.optionList.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
    if (item.help.isNotBlank()) {
        Text(
            text = item.help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}
