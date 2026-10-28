package com.geoenv.browser.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.EnvGroup
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.viewmodel.EnvListViewModel

/**
 * 环境分组管理（需求 3.1.2）：新建 / 重命名 / 上色 / 删除分组。
 * 删除分组不删环境，环境回到「未分组」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsPage(
    onBack: () -> Unit,
    vm: EnvListViewModel = hiltViewModel(),
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()

    var showEdit by remember { mutableStateOf<EnvGroup?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EnvGroup?>(null) }

    // 分组 → 环境数
    val counts = remember(items) {
        items.groupingBy { it.env.groupId ?: -1L }.eachCount()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分组管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建分组")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "分组用于环境列表的筛选归类；删除分组不会删除环境。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (groups.isEmpty()) {
                EmptyView("还没有分组，点右下角 + 新建")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                ) {
                    item {
                        GroupCard(
                            name = "未分组",
                            color = "#9AA0A6",
                            count = counts[-1L] ?: 0,
                            onEdit = null,
                            onDelete = null,
                        )
                    }
                    items(groups, key = { it.id }) { g ->
                        GroupCard(
                            name = g.name,
                            color = g.color,
                            count = counts[g.id] ?: 0,
                            onEdit = { showEdit = g },
                            onDelete = { pendingDelete = g },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        GroupEditDialog(
            title = "新建分组",
            initialName = "",
            initialColor = PRESET_COLORS.first(),
            onDismiss = { showCreate = false },
            onSave = { name, color ->
                showCreate = false
                vm.createGroup(name, color)
            },
        )
    }

    showEdit?.let { g ->
        GroupEditDialog(
            title = "编辑分组",
            initialName = g.name,
            initialColor = g.color,
            onDismiss = { showEdit = null },
            onSave = { name, color ->
                showEdit = null
                vm.renameGroup(g.id, name, color)
            },
        )
    }

    pendingDelete?.let { g ->
        ConfirmDialog(
            title = "删除分组",
            message = "删除「${g.name}」？\n组内 ${counts[g.id] ?: 0} 个环境将变为「未分组」，环境本身不受影响。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteGroup(g.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

private val PRESET_COLORS = listOf(
    "#5B8DEF", "#188038", "#E37400", "#D93025", "#7B61FF", "#00A870", "#9AA0A6",
)

@Composable
private fun GroupCard(
    name: String,
    color: String,
    count: Int,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "$count 个环境",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onEdit != null) TextButton(onClick = onEdit) { Text("编辑") }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorDot(colorHex: String) {
    val c = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(MaterialTheme.colorScheme.primary)
    androidx.compose.foundation.layout.Box(
        Modifier.size(16.dp).clip(CircleShape).background(c),
    )
}

@Composable
private fun GroupEditDialog(
    title: String,
    initialName: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onSave: (name: String, color: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("分组名称 *") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                Text("分组颜色", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_COLORS.forEach { hex ->
                        val selected = color.equals(hex, true)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .padding(6.dp)
                                .clickable { color = hex },
                        ) {
                            ColorDot(hex)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = "名称不能为空"; return@TextButton }
                onSave(name.trim(), color)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
