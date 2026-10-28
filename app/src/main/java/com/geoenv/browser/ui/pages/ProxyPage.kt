package com.geoenv.browser.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.ProxyConfig
import com.geoenv.browser.manager.ProxyManager
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.ProxyViewModel

/**
 * 代理管理（需求 3.4）：代理 CRUD + 连通检测 + 代理池批量分配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyPage(vm: ProxyViewModel = hiltViewModel()) {
    val proxies by vm.proxies.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val strategy by vm.strategy.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProxyConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<ProxyConfig?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var onlyHealthy by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("代理管理") },
                actions = {
                    IconButton(onClick = { vm.checkAll() }, enabled = proxies.isNotEmpty()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "批量检测")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增代理")
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

            // 代理池操作
            SectionCard(
                title = "代理池批量分配",
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("仅可用", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(4.dp))
                        Switch(checked = onlyHealthy, onCheckedChange = { onlyHealthy = it })
                    }
                },
            ) {
                val poolScroll = androidx.compose.foundation.rememberScrollState()
                Row(
                    Modifier.horizontalScroll(poolScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProxyManager.PoolStrategy.entries.forEach { s ->
                        FilterChip(
                            selected = strategy == s,
                            onClick = { vm.setStrategy(s) },
                            label = {
                                Text(if (s == ProxyManager.PoolStrategy.ROUND_ROBIN) "轮询分配" else "固定绑定")
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val ids = if (selected.isEmpty()) proxies.map { it.id } else selected.toList()
                            vm.assignPool(ids, onlyHealthy)
                        },
                        enabled = proxies.isNotEmpty(),
                    ) { Text("分配给全部环境") }
                    TextButton(
                        onClick = { vm.unbindAll() },
                        enabled = proxies.isNotEmpty(),
                    ) { Text("全部解绑") }
                    TextButton(
                        onClick = {
                            selected = if (selected.size == proxies.size) emptySet()
                            else proxies.map { it.id }.toSet()
                        },
                    ) { Text(if (selected.size == proxies.size) "取消全选" else "全选") }
                }
                Text(
                    "已选 ${selected.size} / 共 ${proxies.size} 个代理作为池",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (proxies.isEmpty()) {
                EmptyView("还没有代理，点右下角 + 新增")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                ) {
                    items(proxies, key = { it.id }) { p ->
                        ProxyCard(
                            proxy = p,
                            checking = p.id in checking,
                            selected = p.id in selected,
                            onSelectToggle = {
                                selected = if (p.id in selected) selected - p.id else selected + p.id
                            },
                            onCheck = { vm.checkOne(p.id) },
                            onEdit = { editing = p },
                            onDelete = { pendingDelete = p },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        ProxyEditDialog(
            title = "新增代理",
            onDismiss = { showAdd = false },
            onSave = { name, type, host, port, user, pwd, remark ->
                vm.add(name, type, host, port, user, pwd, remark)
                showAdd = false
            },
        )
    }

    editing?.let { p ->
        ProxyEditDialog(
            title = "编辑代理",
            initial = p,
            onDismiss = { editing = null },
            onSave = { name, type, host, port, user, pwd, remark ->
                vm.update(p.id, name, type, host, port, user, pwd, remark)
                editing = null
            },
        )
    }

    pendingDelete?.let { p ->
        ConfirmDialog(
            title = "删除代理",
            message = "确认删除「${p.name}」？",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.delete(p.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ProxyCard(
    proxy: ProxyConfig,
    checking: Boolean,
    selected: Boolean,
    onSelectToggle: () -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            proxy.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Tag(proxy.type, MaterialTheme.colorScheme.primary)
                        if (checking) {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${proxy.host}:${proxy.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = selected, onCheckedChange = { onSelectToggle() })
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (proxy.status) {
                    ProxyConfig.STATUS_OK -> {
                        Tag("可用 ${proxy.latencyMs}ms", GeoEnvSemantic.Running)
                        proxy.lastError?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall, color = GeoEnvSemantic.Running)
                        }
                    }
                    ProxyConfig.STATUS_FAIL -> Tag("检测失败", GeoEnvSemantic.Failed)
                    else -> Tag("未检测", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (!checking) TextButton(onClick = onCheck) { Text("检测") }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (proxy.username.isNullOrBlank().not()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "需认证：${proxy.username ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (proxy.remark.isNotBlank()) {
                Text(
                    proxy.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProxyEditDialog(
    title: String,
    initial: ProxyConfig? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, type: String, host: String, port: Int, user: String?, pwd: String?, remark: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ProxyConfig.TYPE_HTTP) }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pwd by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf(initial?.remark ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称 *") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ProxyConfig.TYPE_HTTP, ProxyConfig.TYPE_HTTPS, ProxyConfig.TYPE_SOCKS5).forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) })
                    }
                }
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("地址 *") }, singleLine = true)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口 *") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("用户名（可选）") }, singleLine = true)
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("密码（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("密码 AES-256-GCM 加密存储") },
                )
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("备注 / 地域") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = port.toIntOrNull()
                if (name.isBlank()) { error = "名称不能为空"; return@TextButton }
                if (host.isBlank()) { error = "地址不能为空"; return@TextButton }
                if (p == null || p !in 1..65535) { error = "端口必须在 1-65535"; return@TextButton }
                onSave(name.trim(), type, host.trim(), p, user.ifBlank { null }, pwd.ifBlank { null }, remark.trim())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
