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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.HostsRule
import com.geoenv.browser.ui.components.ClickRow
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.EnvDetailViewModel

/**
 * 单环境 Hosts 劫持页（需求 3.4 辅助测试）：
 * 环境级开关 + 域名→IP 规则，热更新到运行中的槽位（无需重启）。
 *
 * 实现方式：槽位进程内 [com.geoenv.browser.proxy.LocalProxyServer] 接管该环境的
 * 出站连接并按规则改写目标地址，仅影响本环境。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsPage(
    envId: Long,
    onBack: () -> Unit,
    vm: EnvDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(envId) { vm.setEnv(envId) }
    val env by vm.environment.collectAsStateWithLifecycle()
    val rules by vm.hostsRules.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HostsRule?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hosts 劫持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (env?.hostsHijackEnabled == true) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "新增规则")
                }
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

            SectionCardSwitch(
                enabled = env?.hostsHijackEnabled == true,
                onToggle = { vm.setHostsEnabled(it) },
            )

            Spacer(Modifier.height(8.dp))

            if (env?.hostsHijackEnabled != true) {
                EmptyView("Hosts 劫持未开启")
            } else if (rules.isEmpty()) {
                EmptyView("暂无规则，点右下角 + 新增")
            } else {
                Text(
                    "共 ${rules.size} 条规则（仅对本环境生效，运行中热更新）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                ) {
                    items(rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            onDelete = { pendingDelete = rule },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { domain, ip, remark ->
                showAdd = false
                vm.addHostsRule(domain, ip, enabled = true, remark = remark)
            },
        )
    }

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "删除规则",
            message = "删除 ${rule.domain} → ${rule.ip} ？",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteHostsRule(rule.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SectionCardSwitch(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            ClickRow(
                title = "启用本环境 Hosts 劫持",
                subtitle = "通过本地代理改写域名解析，仅影响本环境的出站请求",
                trailing = { Switch(checked = enabled, onCheckedChange = onToggle) },
            )
            HorizontalDivider()
            Text(
                "典型用途：把生产域名指向测试服、屏蔽上报域名、模拟地区差异。\n" +
                    "规则经槽位内 LocalProxyServer 生效，运行中可热更新。",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleCard(rule: HostsRule, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.domain, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "→ ${rule.ip}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (rule.enabled) {
                    Text("启用", style = MaterialTheme.typography.labelSmall, color = GeoEnvSemantic.Running)
                } else {
                    Text("停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
            if (rule.remark.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    rule.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (domain: String, ip: String, remark: String) -> Unit,
) {
    var domain by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增 Hosts 规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it; error = null },
                    label = { Text("域名 *") },
                    placeholder = { Text("api.example.com 或 *.example.com") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it; error = null },
                    label = { Text("目标 IP *") },
                    placeholder = { Text("10.0.0.1 / 127.0.0.1") },
                    singleLine = true,
                )
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("备注") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = domain.trim()
                val i = ip.trim()
                when {
                    d.isBlank() -> error = "域名不能为空"
                    i.isBlank() -> error = "IP 不能为空"
                    !Regex("^[0-9a-fA-F.:]+$").matches(i) -> error = "IP 格式不正确"
                    else -> onAdd(d, i, remark.trim())
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
