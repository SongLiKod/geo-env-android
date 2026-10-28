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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.Account
import com.geoenv.browser.db.Environment
import com.geoenv.browser.gecko.FingerprintConfig
import com.geoenv.browser.gecko.FingerprintValidator
import com.geoenv.browser.manager.BackupManager
import com.geoenv.browser.ui.components.ClickRow
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.StatusBadge
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.EnvDetailViewModel
import com.geoenv.browser.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环境详情（需求 3.1）：启停、基本信息、账号台账、代理绑定、
 * 指纹 / 快照 / Hosts / Marionette / HAR / 检测报告入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvDetailPage(
    envId: Long,
    onBack: () -> Unit,
    onOpenFingerprint: (Long) -> Unit,
    onOpenSnapshots: (Long) -> Unit,
    onOpenHosts: (Long) -> Unit,
    onOpenReport: (Long) -> Unit,
    vm: EnvDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(envId) { vm.setEnv(envId) }

    val env by vm.environment.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val slot by vm.slotState.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val proxies by vm.proxies.collectAsStateWithLifecycle()
    val hostsRules by vm.hostsRules.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var passwordShown by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(env?.name ?: "环境详情", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        env?.let {
                            Text(
                                "${SettingsViewModel.stageLabel(it.stage)} · Gecko ${BackupManager.BuildVersion.GECKO.take(3)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showEdit = true }, enabled = env != null) {
                        Text("编辑", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { showDelete = true }, enabled = env != null) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val e = env
        if (e == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "环境不存在或已删除",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val running = slot?.status == Environment.STATUS_RUNNING || e.status == Environment.STATUS_RUNNING

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            /* ------------------------- 运行控制 ------------------------- */
            item {
                SectionCard(title = "运行状态") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(if (running) "RUNNING" else e.status)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (slot != null) "槽位 ${slot?.slotIndex} · ${slot?.title?.ifBlank { slot?.url ?: "" } ?: ""}"
                            else "未占用槽位",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (slot?.lastError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "错误：${slot?.lastError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.toggleRunning() }) {
                            Icon(
                                if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                Modifier.height(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (running) "停止" else "启动")
                        }
                        Button(onClick = { vm.openBrowser() }, enabled = !running || slot != null) {
                            Text("打开浏览器")
                        }
                        TextButton(onClick = {
                            vm.screenshot { path ->
                                scope.launch {
                                    snackbar.showSnackbar(if (path != null) "截图已保存：$path" else "截图失败（环境未运行？）")
                                }
                            }
                        }) { Text("截图") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.navigate("about:blank") }, enabled = running) { Text("空白页") }
                        TextButton(onClick = { vm.clearStorage() }, enabled = running) { Text("清理站点数据") }
                        TextButton(onClick = { onOpenReport(e.id) }) { Text("检测报告") }
                    }
                }
            }

            /* ------------------------- 基本信息 ------------------------- */
            item {
                SectionCard(title = "基本信息") {
                    InfoLine("环境名称", e.name)
                    if (e.project.isNotBlank()) InfoLine("项目", e.project)
                    InfoLine("阶段", SettingsViewModel.stageLabel(e.stage))
                    if (e.versionTag.isNotBlank()) InfoLine("版本", e.versionTag)
                    if (e.tags.isNotBlank()) InfoLine("标签", e.tags)
                    if (e.remark.isNotBlank()) InfoLine("备注", e.remark)
                    InfoLine("分组", groups.firstOrNull { it.id == e.groupId }?.name ?: "未分组")
                    InfoLine("Profile", e.profilePath.substringAfterLast('/'))
                    e.lastStartTime?.let {
                        InfoLine("上次启动", ISO.format(Date(it)))
                    }
                }
            }

            /* ------------------------- 账号台账 ------------------------- */
            item {
                SectionCard(
                    title = "账号台账",
                    action = { TextButton(onClick = { showAccount = true }) { Text(if (account == null) "绑定" else "编辑") } },
                ) {
                    if (account == null) {
                        Text(
                            "未绑定账号。密码将以 AES-256-GCM 加密存储，数据库中无明文。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val acc = account!!
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(acc.username, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Tag(
                                when (acc.status) {
                                    Account.STATUS_ACTIVE -> "使用中"
                                    Account.STATUS_FROZEN -> "冻结"
                                    else -> "待启用"
                                },
                                when (acc.status) {
                                    Account.STATUS_ACTIVE -> GeoEnvSemantic.Running
                                    Account.STATUS_FROZEN -> GeoEnvSemantic.Failed
                                    else -> GeoEnvSemantic.Starting
                                },
                            )
                        }
                        if (acc.owner.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            InfoLine("负责人", acc.owner)
                        }
                        if (acc.remark.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            InfoLine("备注", acc.remark)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                vm.revealPassword { pwd -> passwordShown = pwd }
                            }) { Text("查看密码") }
                            TextButton(onClick = { vm.deleteAccount() }) { Text("删除账号", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            /* ------------------------- 代理 ------------------------- */
            item {
                SectionCard(title = "代理绑定") {
                    val cur = proxies.firstOrNull { it.id == e.proxyId }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cur?.let { "${it.type}://${it.host}:${it.port}" } ?: "直连（不走代理）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        cur?.let {
                            Tag(
                                when (it.status) {
                                    "OK" -> "可用 ${it.latencyMs}ms"
                                    "FAIL" -> "不可用"
                                    else -> "未检测"
                                },
                                when (it.status) {
                                    "OK" -> GeoEnvSemantic.Running
                                    "FAIL" -> GeoEnvSemantic.Failed
                                    else -> GeoEnvSemantic.Starting
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = e.proxyId == null,
                            onClick = { vm.bindProxy(null) },
                            label = { Text("直连") },
                        )
                        proxies.forEach { p ->
                            FilterChip(
                                selected = e.proxyId == p.id,
                                onClick = { vm.bindProxy(p.id) },
                                label = { Text(p.name) },
                            )
                        }
                    }
                    if (proxies.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "还没有代理，去「代理」页新增并检测",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "代理为运行时配置，切换后需重启环境生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            /* ------------------------- 指纹 ------------------------- */
            item {
                val cfg = remember(e.fingerprintJson) { FingerprintConfig.fromJson(e.fingerprintJson) }
                val issues = remember(cfg) { FingerprintValidator.validate(cfg) }
                SectionCard(
                    title = "指纹配置",
                    action = {
                        TextButton(onClick = { onOpenFingerprint(e.id) }) { Text("配置") }
                    },
                ) {
                    InfoLine("UA", cfg.resolveUserAgent())
                    InfoLine("屏幕", "${cfg.screenWidth}×${cfg.screenHeight} @${cfg.screenDpi}dpi")
                    InfoLine("机型", "${cfg.deviceManufacturer} ${cfg.deviceModel}")
                    InfoLine("时区", cfg.timezone)
                    InfoLine("语言", cfg.languages.joinToString())
                    InfoLine(
                        "校验",
                        if (issues.isEmpty()) "通过"
                        else "${issues.count { it.severity == "ERROR" }} 错误 / ${issues.count { it.severity == "WARN" }} 警告",
                    )
                    if (issues.isNotEmpty()) {
                        issues.take(4).forEach {
                            Text(
                                "· [${it.severity}] ${it.field}: ${it.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (it.severity == "ERROR") MaterialTheme.colorScheme.error
                                else GeoEnvSemantic.Warning,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpenSnapshots(e.id) }) { Text("快照 (${snapshots.size})") }
                        TextButton(onClick = { onOpenHosts(e.id) }) { Text("Hosts (${hostsRules.size})") }
                    }
                }
            }

            /* ------------------------- Marionette ------------------------- */
            item {
                var portText by remember(e.marionettePort) { mutableStateOf(if (e.marionettePort > 0) e.marionettePort.toString() else "") }
                SectionCard(title = "Marionette 自动化") {
                    ClickRow(
                        title = "开启 Marionette",
                        subtitle = "每实例独立端口，仅监听 127.0.0.1；变更需重启环境",
                        trailing = {
                            Switch(
                                checked = e.marionetteEnabled,
                                onCheckedChange = { vm.configureMarionette(it, portText) },
                            )
                        },
                    )
                    if (e.marionetteEnabled) {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it },
                                label = { Text("端口（留空随机）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.configureMarionette(true, portText) }) { Text("应用") }
                        }
                        Text(
                            "已配置端口：${e.marionettePort.takeIf { it > 0 }?.toString() ?: "随机"}" +
                                (slot?.marionettePort?.takeIf { it > 0 }?.let { "（运行中 $it）" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            /* ------------------------- HAR / 便捷 ------------------------- */
            item {
                SectionCard(title = "HAR 抓包") {
                    val recording = slot?.harRecording == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (recording) "录制中…" else "未录制",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (recording) Tag("REC", GeoEnvSemantic.Failed)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { vm.startHar("har_${System.currentTimeMillis()}") },
                            enabled = running && !recording,
                        ) { Text("开始录制") }
                        TextButton(onClick = { vm.stopHar() }, enabled = recording) { Text("停止并落盘") }
                    }
                    Text(
                        "录制结果写入 filesDir/har/，可在「HAR 抓包记录」页查看导出。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            /* ------------------------- 危险操作 ------------------------- */
            item {
                SectionCard(title = "危险操作") {
                    ClickRow(
                        title = "删除环境",
                        subtitle = "同时删除 profile（登录态/缓存）与全部关联数据，不可恢复",
                        onClick = { showDelete = true },
                    )
                }
            }
        }
    }

    /* ------------------------- 对话框 ------------------------- */

    env?.let { e ->
        if (showEdit) {
            EditEnvDialog(
                env = e,
                groups = groups.map { it.id to it.name },
                onDismiss = { showEdit = false },
                onSave = { name, project, stage, versionTag, tags, remark, groupId ->
                    showEdit = false
                    if (name != e.name) vm.rename(name)
                    vm.updateEnvironment {
                        it.copy(
                            project = project,
                            stage = stage,
                            versionTag = versionTag,
                            tags = tags,
                            remark = remark,
                            groupId = groupId,
                        )
                    }
                },
            )
        }

        if (showDelete) {
            ConfirmDialog(
                title = "删除环境",
                message = "确认删除「${e.name}」？\n将同时删除 profile 目录与账号、快照、Hosts 关联数据，不可恢复。",
                confirmText = "删除",
                danger = true,
                onConfirm = {
                    showDelete = false
                    vm.delete()
                    onBack()
                },
                onDismiss = { showDelete = false },
            )
        }

        if (showAccount) {
            AccountBindDialog(
                account = account,
                onDismiss = { showAccount = false },
                onSave = { username, password, remark, owner, status ->
                    showAccount = false
                    vm.saveAccount(username, password, remark, owner, status)
                },
            )
        }

        passwordShown?.let { pwd ->
            AlertDialog(
                onDismissRequest = { passwordShown = null },
                title = { Text("账号密码") },
                text = {
                    Column {
                        Text(pwd, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "仅本次展示，不写入日志；数据库中为 AES-256-GCM 密文。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { passwordShown = null }) { Text("关闭") } },
            )
        }
    }
}

private val ISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

@Composable
private fun InfoLine(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            key,
            Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 基本信息编辑对话框 */
@Composable
private fun EditEnvDialog(
    env: Environment,
    groups: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onSave: (name: String, project: String, stage: String, versionTag: String, tags: String, remark: String, groupId: Long?) -> Unit,
) {
    var name by remember(env.id) { mutableStateOf(env.name) }
    var project by remember(env.id) { mutableStateOf(env.project) }
    var stage by remember(env.id) { mutableStateOf(env.stage) }
    var versionTag by remember(env.id) { mutableStateOf(env.versionTag) }
    var tags by remember(env.id) { mutableStateOf(env.tags) }
    var remark by remember(env.id) { mutableStateOf(env.remark) }
    var groupId by remember(env.id) { mutableStateOf(env.groupId) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑环境信息") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("环境名称 *") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(value = project, onValueChange = { project = it }, label = { Text("项目名称") }, singleLine = true)
                OutlinedTextField(value = versionTag, onValueChange = { versionTag = it }, label = { Text("版本标记") }, singleLine = true)
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("标签（逗号分隔）") }, singleLine = true)
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("用例备注") }, minLines = 2)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("dev", "test", "prod").forEach { s ->
                        FilterChip(selected = stage == s, onClick = { stage = s }, label = { Text(SettingsViewModel.stageLabel(s)) })
                    }
                }

                if (groups.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { (id, gname) ->
                            FilterChip(
                                selected = groupId == id,
                                onClick = { groupId = if (groupId == id) null else id },
                                label = { Text(gname) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = "名称不能为空"; return@TextButton }
                onSave(name.trim(), project.trim(), stage, versionTag.trim(), tags.trim(), remark.trim(), groupId)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 账号绑定 / 编辑对话框 */
@Composable
private fun AccountBindDialog(
    account: Account?,
    onDismiss: () -> Unit,
    onSave: (username: String, password: String?, remark: String, owner: String, status: String) -> Unit,
) {
    var username by remember(account?.id) { mutableStateOf(account?.username ?: "") }
    var password by remember(account?.id) { mutableStateOf("") }
    var remark by remember(account?.id) { mutableStateOf(account?.remark ?: "") }
    var owner by remember(account?.id) { mutableStateOf(account?.owner ?: "") }
    var status by remember(account?.id) { mutableStateOf(account?.status ?: Account.STATUS_PENDING) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "绑定账号" else "编辑账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("账号 *") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (account?.passwordEnc != null) "新密码（留空不改）" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("AES-256-GCM 加密入库，数据库无明文") },
                )
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("负责人") }, singleLine = true)
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("备注") }, singleLine = true)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        Account.STATUS_PENDING to "待启用",
                        Account.STATUS_ACTIVE to "使用中",
                        Account.STATUS_FROZEN to "冻结",
                    ).forEach { (k, v) ->
                        FilterChip(selected = status == k, onClick = { status = k }, label = { Text(v) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (username.isBlank()) { error = "账号不能为空"; return@TextButton }
                onSave(username.trim(), password.ifBlank { null }, remark.trim(), owner.trim(), status)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
