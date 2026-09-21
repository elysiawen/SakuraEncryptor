package com.sakura.encryptor.ui.screens.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.sakura.encryptor.core.alist.AListSession
import com.sakura.encryptor.core.profile.ProfileRepository
import com.sakura.encryptor.data.db.ProfileEntity
import com.sakura.encryptor.ui.components.PrimaryButton
import com.sakura.encryptor.ui.components.SakuraTopBar
import com.sakura.encryptor.ui.components.StateMessage
import com.sakura.encryptor.ui.rememberAppViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(
    private val profileRepository: ProfileRepository,
    private val session: AListSession,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> = profileRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long?> = profileRepository.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun activate(id: Long) {
        viewModelScope.launch { profileRepository.activateStored(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { profileRepository.delete(id) }
    }
}

@Composable
fun ProfilesScreen(
    onEditProfile: (Long?) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = rememberAppViewModel { container ->
        ProfilesViewModel(container.profileRepository, container.aListSession)
    }

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ProfileEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SakuraTopBar(
            title = "云端配置",
            subtitle = if (profiles.isEmpty()) "暂无配置" else "共 ${profiles.size} 个配置",
            onBack = onBack,
            actions = {
                IconButton(onClick = { onEditProfile(null) }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新增配置")
                }
            },
        )

        if (profiles.isEmpty()) {
            StateMessage(
                icon = Icons.Rounded.Cloud,
                title = "还没有云端配置",
                message = "一个配置 = 一套 AList 账号 + 一套加密密码。可以添加多个并随时切换；只用本地文件则无需配置。",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                PrimaryButton(
                    text = "新增配置",
                    onClick = { onEditProfile(null) },
                    icon = Icons.Rounded.Add,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onSelect = { viewModel.activate(profile.id) },
                        onEdit = { onEditProfile(profile.id) },
                        onDelete = { pendingDelete = profile },
                    )
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton(
                        text = "新增配置",
                        onClick = { onEditProfile(null) },
                        icon = Icons.Rounded.Add,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除配置？") },
            text = {
                Text(
                    "将删除「${profile.name}」及其保存的密码。\n" +
                        "云端已加密的文件不会被删除，重新添加相同配置即可恢复访问。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(profile.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = if (isActive) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (profile.hasServer) {
                        profile.server + if (profile.username.isNotBlank()) " · ${profile.username}" else ""
                    } else {
                        "未填写服务器地址"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isActive) {
                        MiniBadge(text = "使用中", highlighted = true)
                    }
                    MiniBadge(
                        text = if (profile.canAutoUnlock) "已记住密码" else "切换时需输密码",
                        icon = Icons.Rounded.LockOpen,
                    )
                    if (profile.hasServer) {
                        MiniBadge(
                            text = if (profile.canAutoLogin) "自动登录" else "需手动登录",
                            icon = Icons.Rounded.Key,
                        )
                    }
                }
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun MiniBadge(
    text: String,
    highlighted: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
