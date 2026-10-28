package com.geoenv.browser.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.R
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SearchField
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.StatusBadge
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.EnvListViewModel
import com.geoenv.browser.ui.viewmodel.SettingsViewModel

/**
 * 环境列表（需求 3.1.2/3.1.3）：
 * 名称、分组、状态、绑定账号、代理状态、内核版本；搜索 + 分组/阶段/标签/状态筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvListPage(
    onOpenDetail: (Long) -> Unit,
    onOpenGroups: () -> Unit,
    onOpenPopups: () -> Unit,
    onOpenHar: () -> Unit,
    onOpenMarionette: () -> Unit,
    vm: EnvListViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val tags by vm.allTags.collectAsStateWithLifecycle()
    val running by vm.runningCount.collectAsStateWithLifecycle()
    val limit by vm.concurrencyLimit.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EnvListViewModel.EnvListItem?>(null) }
    var menuFor by remember { mutableStateOf<EnvListViewModel.EnvListItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResourceSafe(R.string.app_name))
                        Text(
                            "运行 $running / $limit · Gecko ${com.geoenv.browser.manager.BackupManager.BuildVersion.GECKO.take(3)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenPopups) {
                        Icon(Icons.Filled.Notifications, contentDescription = "弹窗面板")
                    }
                    IconButton(onClick = onOpenHar) {
                        Icon(Icons.Filled.List, contentDescription = "HAR")
                    }
                    IconButton(onClick = onOpenMarionette) {
                        Icon(Icons.Filled.Tune, contentDescription = "Marionette")
                    }
                    IconButton(onClick = onOpenGroups) {
                        Icon(Icons.Filled.Folder, contentDescription = "分组")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建环境")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            SearchField(
                value = filter.query,
                onValueChange = vm::setQuery,
                placeholder = "搜索名称 / 项目 / 标签 / 账号",
            )
            Spacer(Modifier.height(8.dp))

            // 状态筛选
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EnvListViewModel.StatusFilter.entries.forEach { s ->
                    FilterChip(
                        selected = filter.status == s,
                        onClick = { vm.setStatus(s) },
                        label = {
                            Text(
                                when (s) {
                                    EnvListViewModel.StatusFilter.ALL -> "全部"
                                    EnvListViewModel.StatusFilter.RUNNING -> "运行中"
                                    EnvListViewModel.StatusFilter.STOPPED -> "已停止"
                                },
                            )
                        },
                    )
                }
                EnvListViewModel.SortBy.entries.forEach { s ->
                    FilterChip(
                        selected = filter.sortBy == s,
                        onClick = { vm.setSort(s) },
                        label = {
                            Text(
                                when (s) {
                                    EnvListViewModel.SortBy.RECENT -> "按更新"
                                    EnvListViewModel.SortBy.NAME -> "按名称"
                                    EnvListViewModel.SortBy.STATUS -> "按状态"
                                },
                            )
                        },
                    )
                }
            }

            // 分组 / 阶段 / 标签
            if (groups.isNotEmpty() || tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = filter.groupId == null,
                            onClick = { vm.setGroup(null) },
                            label = { Text("全部分组") },
                        )
                    }
                    items(groups) { g ->
                        FilterChip(
                            selected = filter.groupId == g.id,
                            onClick = { vm.setGroup(if (filter.groupId == g.id) null else g.id) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                runCatching {
                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(g.color))
                        }.getOrDefault(MaterialTheme.colorScheme.primary),
                                            ),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(g.name)
                                }
                            },
                        )
                    }
                    items(listOf("dev", "test", "prod")) { stage ->
                        FilterChip(
                            selected = filter.stage == stage,
                            onClick = { vm.setStage(if (filter.stage == stage) null else stage) },
                            label = { Text(SettingsViewModel.stageLabel(stage)) },
                        )
                    }
                    items(tags) { tag ->
                        FilterChip(
                            selected = filter.tag == tag,
                            onClick = { vm.setTag(if (filter.tag == tag) null else tag) },
                            label = { Text("#$tag") },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                EmptyView(
                    if (filter.query.isBlank() && filter.groupId == null && filter.status == EnvListViewModel.StatusFilter.ALL)
                        "还没有环境，点右下角 + 新建"
                    else "没有匹配的环境",
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                ) {
                    items(items, key = { it.env.id }) { item ->
                        EnvCard(
                            item = item,
                            onClick = { onOpenDetail(item.env.id) },
                            onToggle = {
                                if (item.running) vm.stop(item.env.id) else vm.start(item.env.id)
                            },
                            onOpenBrowser = { vm.openBrowser(item.env.id) },
                            onMenu = { menuFor = item },
                        )
                    }
                }
            }
        }
    }

    // 行内菜单
    menuFor?.let { item ->
        DropdownMenu(
            expanded = true,
            onDismissRequest = { menuFor = null },
        ) {
            DropdownMenuItem(
                text = { Text("打开浏览器") },
                onClick = { menuFor = null; vm.openBrowser(item.env.id) },
            )
            DropdownMenuItem(
                text = { Text("克隆环境") },
                onClick = { menuFor = null; vm.clone(item.env.id) },
            )
            DropdownMenuItem(
                text = { Text("详情 / 指纹 / 代理") },
                onClick = { menuFor = null; onOpenDetail(item.env.id) },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { menuFor = null; pendingDelete = item },
            )
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "删除环境",
            message = "确认删除「${item.env.name}」？\n将同时删除其 profile 目录（登录态、缓存），且不可恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.delete(item.env.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showCreate) {
        CreateEnvDialog(
            groups = groups.map { it.id to it.name },
            onDismiss = { showCreate = false },
            onCreate = { name, groupId, stage, project, tagsStr, remark ->
                showCreate = false
                vm.createDefault(name, groupId, stage, project, tagsStr, remark) { id ->
                    onOpenDetail(id)
                }
            },
        )
    }
}

@Composable
private fun EnvCard(
    item: EnvListViewModel.EnvListItem,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onOpenBrowser: () -> Unit,
    onMenu: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.env.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.harRecording) {
                            Spacer(Modifier.width(6.dp))
                            Tag("HAR", GeoEnvSemantic.Warning)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(if (item.running) "RUNNING" else item.env.status)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            SettingsViewModel.stageLabel(item.env.stage),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.groupName?.let {
                            Spacer(Modifier.width(10.dp))
                            Tag(it, MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaCell("账号", item.accountUsername ?: "未绑定")
                MetaCell("代理", item.proxyName ?: "直连")
                if (item.marionettePort > 0 && item.running) {
                    MetaCell("Marionette", "127.0.0.1:${item.marionettePort}")
                } else if (item.env.project.isNotBlank()) {
                    MetaCell("项目", item.env.project)
                }
            }

            if (item.env.tags.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.env.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(4)
                        .forEach { Tag("#$it", MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenBrowser) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                    Text("浏览器")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onToggle) {
                    Icon(
                        if (item.running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        Modifier.size(16.dp),
                    )
                    Text(if (item.running) "停止" else "启动")
                }
            }
        }
    }
}

@Composable
private fun MetaCell(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(90.dp),
        )
    }
}

/** 新建环境对话框 */
@Composable
private fun CreateEnvDialog(
    groups: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onCreate: (name: String, groupId: Long?, stage: String, project: String, tags: String, remark: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("test") }
    var groupId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建环境") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("环境名称 *") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                )
                OutlinedTextField(value = project, onValueChange = { project = it }, label = { Text("项目名称") }, singleLine = true)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("dev", "test", "prod").forEach { s ->
                        FilterChip(
                            selected = stage == s,
                            onClick = { stage = s },
                            label = { Text(SettingsViewModel.stageLabel(s)) },
                        )
                    }
                }

                if (groups.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        groups.forEach { (id, gname) ->
                            FilterChip(
                                selected = groupId == id,
                                onClick = { groupId = if (groupId == id) null else id },
                                label = { Text(gname) },
                            )
                        }
                    }
                }

                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("标签（逗号分隔）") }, singleLine = true)
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("用例备注") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = "名称不能为空"; return@TextButton }
                onCreate(name.trim(), groupId, stage, project.trim(), tags.trim(), remark.trim())
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 便捷读字符串（避免在多处 import res） */
@Composable
private fun stringResourceSafe(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
