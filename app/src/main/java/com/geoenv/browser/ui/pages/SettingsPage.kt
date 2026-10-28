package com.geoenv.browser.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.R
import com.geoenv.browser.ui.components.ClickRow
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.LabeledField
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * 设置页（需求 3.6 / 3.8 / 4）：
 * 主题自定义、并发上限、保活与电池优化、环境包导入导出、自动备份、审计入口、关于。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onOpenMarionette: () -> Unit,
    onOpenPopups: () -> Unit,
    onOpenHar: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenAudit: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val limit by vm.concurrencyLimit.collectAsStateWithLifecycle()
    val keepAlive by vm.keepAlive.collectAsStateWithLifecycle()
    val wakeLock by vm.wakeLock.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()

    var snackbar by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar = it }
    }

    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showThemeExport by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 32.dp),
    ) {
        item { Text("外观（仅管理面板，不影响网页指纹）", style = MaterialTheme.typography.titleSmall) }

        // 主题模式
        item {
            SectionCard(title = "主题模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsViewModel.THEME_MODES_LABEL.entries.forEach { (k, v) ->
                        FilterChip(
                            selected = theme.mode == k,
                            onClick = { vm.setThemeMode(k) },
                            label = { Text(v) },
                        )
                    }
                }
            }
        }

        // 主色
        item {
            SectionCard(
                title = "自定义主色",
                action = {
                    TextButton(onClick = { showThemeExport = true }) { Text("导出/导入") }
                },
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsViewModel.PRESET_COLORS.forEach { (hex, name) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.setPrimaryHex(hex) }.padding(4.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        runCatching {
                                            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex))
                                        }.getOrDefault(MaterialTheme.colorScheme.primary),
                                    ),
                            )
                            Text(name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                var hex by remember(theme.primaryHex) { mutableStateOf(theme.primaryHex) }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("HEX 色值") },
                    singleLine = true,
                    supportingText = { Text("格式 #1E6FFF，回车应用") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = { vm.setPrimaryHex(hex) }) { Text("应用主色") }
            }
        }

        // 密度
        item {
            SectionCard(title = "布局密度") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsViewModel.DENSITY_LABEL.entries.forEach { (k, v) ->
                        FilterChip(
                            selected = theme.density == k,
                            onClick = { vm.setDensity(k) },
                            label = { Text(v) },
                        )
                    }
                }
            }
        }

        item { Text("性能与并发", style = MaterialTheme.typography.titleSmall) }

        item {
            SectionCard(title = "并发环境上限") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前上限：$limit 个", fontWeight = FontWeight.Medium)
                        Text(
                            "默认 3 个（需求建议值），可放开到 5 个；超过上限会拒绝启动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = limit >= 5, onCheckedChange = { vm.setConcurrencyRelaxed(it) })
                }
            }
        }

        item {
            SectionCard(title = "后台保活") {
                ClickRow(
                    title = "前台服务常驻",
                    subtitle = "保持 GeoEnv 与运行中的环境不被系统回收",
                    trailing = {
                        Switch(checked = keepAlive, onCheckedChange = { vm.setKeepAlive(it) })
                    },
                )
                HorizontalDivider()
                ClickRow(
                    title = "保持唤醒（WakeLock）",
                    subtitle = "环境长时间自动化时避免休眠断连",
                    trailing = {
                        Switch(checked = wakeLock, onCheckedChange = { vm.setWakeLock(it) })
                    },
                )
                HorizontalDivider()
                val ignoring = remember { vm.isIgnoringBatteryOptimizations() }
                ClickRow(
                    title = if (ignoring) "电池优化：已豁免" else "加入电池优化白名单",
                    subtitle = if (ignoring) "系统不会限制本应用后台运行" else "点击前往系统设置，关闭对 GeoEnv 的限制",
                    onClick = {
                        if (!ignoring) vm.requestIgnoreBatteryOptimizations {}
                    },
                )
            }
        }

        item { Text("数据安全", style = MaterialTheme.typography.titleSmall) }

        item {
            SectionCard(title = "加密环境包", action = { if (busy) Text("处理中…", style = MaterialTheme.typography.labelSmall) }) {
                Text(
                    "导出为 AES-256-GCM 加密的 .geoenv 包，导入需同一口令；\n" +
                        "字段级密码由设备 Keystore 派生密钥加密，跨设备需重新录入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showExport = true }, enabled = !busy) { Text("导出环境包") }
                    Button(onClick = { showImport = true }, enabled = !busy) { Text("导入环境包") }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                ClickRow(
                    title = "立即本地自动备份",
                    subtitle = "打包全部环境配置到应用私有目录（保留最近 7 份）",
                    onClick = { if (!busy) vm.runAutoBackup() },
                )
                if (backups.isNotEmpty()) {
                    backups.take(5).forEach { b ->
                        Text(
                            "· ${b.filePath.substringAfterLast('/')} · ${b.envCount} 环境 · ${b.sizeBytes / 1024} KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = { vm.refreshBackups() }) { Text("刷新备份列表") }
            }
        }

        item {
            SectionCard(title = "存储占用") {
                Text(vm.storageSummary(), style = MaterialTheme.typography.bodySmall)
            }
        }

        item { Text("工具", style = MaterialTheme.typography.titleSmall) }

        item {
            SectionCard(title = "辅助工具") {
                ClickRow(title = "审计日志", subtitle = "只追加操作记录，可导出 CSV", onClick = onOpenAudit)
                HorizontalDivider()
                ClickRow(title = "分组管理", subtitle = "项目 / 测试环境分组", onClick = onOpenGroups)
                HorizontalDivider()
                ClickRow(title = "弹窗与验证码聚合", subtitle = "汇总所有环境的弹窗事件", onClick = onOpenPopups)
                HorizontalDivider()
                ClickRow(title = "HAR 抓包记录", subtitle = "查看与导出网络抓包", onClick = onOpenHar)
                HorizontalDivider()
                ClickRow(title = "Marionette 自动化", subtitle = "端口分配与 SDK 接入说明", onClick = onOpenMarionette)
            }
        }

        item { Text("关于", style = MaterialTheme.typography.titleSmall) }

        item {
            SectionCard(title = "版本信息") {
                val about = vm.about
                InfoRow("应用版本", "${about.versionName} (${about.versionCode})")
                InfoRow("Gecko 内核", about.geckoVersion + "（锁定）")
                InfoRow("指纹 Hook", about.hookVersion)
                InfoRow("最低系统", "Android 8.0 (API ${about.minSdk})")
                InfoRow("ABI", about.abi)
            }
        }

        item {
            SectionCard(title = "架构说明") {
                Text(
                    "GeckoRuntime 在单进程内只允许存在一个实例，且代理 / Marionette 端口 / " +
                        "时区 / 语言等 prefs 为进程级生效。为满足「每环境独立代理、独立端口、独立 profile」" +
                        "与并发上限要求，GeoEnv 采用 5 个独立槽位进程（:gecko1 ~ :gecko5），" +
                        "与 docs/geo-env-tech.md 中「全部实例同一进程」的表述存在偏离，详见 README。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snackbar?.let { msg ->
            item {
                SectionCard(title = "提示") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { snackbar = null }) { Text("知道了") }
                    }
                }
            }
        }
    }

    if (showExport) {
        ExportDialog(
            onDismiss = { showExport = false },
            onConfirm = { pass, profiles, audit ->
                showExport = false
                vm.exportPackage(pass, profiles, audit, emptyList()) { }
            },
        )
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            vm = vm,
        )
    }

    if (showThemeExport) {
        ThemeTransferDialog(vm = vm, onDismiss = { showThemeExport = false })
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            key,
            Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, includeProfiles: Boolean, includeAudit: Boolean) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出加密环境包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(
                    label = "加密口令（至少 8 位）",
                    value = pass,
                    onValueChange = { pass = it; error = null },
                    placeholder = "用于导入解密",
                    error = error,
                )
                LabeledField(label = "确认口令", value = pass2, onValueChange = { pass2 = it })
                ClickRow(
                    title = "携带 Profile（登录态 / 缓存）",
                    subtitle = "体积更大；不勾选则只导配置，登录态需重新登录",
                    trailing = { Switch(checked = profiles, onCheckedChange = { profiles = it }) },
                )
                ClickRow(
                    title = "携带审计日志",
                    trailing = { Switch(checked = audit, onCheckedChange = { audit = it }) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pass.length < 8 -> error = "口令至少 8 位"
                    pass != pass2 -> error = "两次口令不一致"
                    else -> onConfirm(pass, profiles, audit)
                }
            }) { Text("导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    vm: SettingsViewModel,
) {
    var path by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("input") } // input / preview / done
    var preview by remember { mutableStateOf<com.geoenv.browser.manager.BackupManager.ImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入加密环境包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (stage) {
                    "input" -> {
                        LabeledField(
                            label = "包文件路径（.geoenv）",
                            value = path,
                            onValueChange = { path = it },
                            placeholder = "/data/data/.../export/geoenv_xxx.geoenv",
                            supporting = "可先用系统文件选择器或导出结果里的路径",
                        )
                        LabeledField(
                            label = "解密口令",
                            value = pass,
                            onValueChange = { pass = it; error = null },
                            error = error,
                        )
                        Text(
                            "如需浏览并选择文件，可在「文件」应用中打开导出目录，或直接粘贴完整路径。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "preview" -> {
                        preview?.let { p ->
                            Text("包内 ${p.envCount} 个环境 / ${p.accountCount} 账号 / ${p.snapshotCount} 快照", fontWeight = FontWeight.Medium)
                            Text("Gecko 版本：${p.geckoVersion}", style = MaterialTheme.typography.bodySmall)
                            if (p.hasProfiles) Text("✓ 携带 Profile（登录态）", style = MaterialTheme.typography.bodySmall)
                            Text("环境列表：", style = MaterialTheme.typography.labelMedium)
                            p.names.take(10).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                            if (p.names.size > 10) Text("… 共 ${p.names.size} 个", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "同名环境将自动加前缀「导入_」保留。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> Text(result ?: "完成", fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            when (stage) {
                "input" -> TextButton(onClick = {
                    if (path.isBlank()) { error = "路径不能为空"; return@TextButton }
                    if (pass.isBlank()) { error = "口令不能为空"; return@TextButton }
                    scope.launch {
                        vm.previewPackage(java.io.File(path.trim()), pass.trim()) { r ->
                            r.onSuccess { preview = it; stage = "preview"; error = null }
                            r.onFailure { error = it.message ?: "解密失败" }
                        }
                    }
                }) { Text("预览") }
                "preview" -> TextButton(onClick = {
                    val p = preview ?: return@TextButton
                    vm.importPackage(java.io.File(path.trim()), pass.trim()) { r ->
                        result = r.fold(
                            onSuccess = { "导入成功：${it.importedEnvs} 个环境" },
                            onFailure = { "导入失败：${it.message}" },
                        )
                        stage = "done"
                    }
                }) { Text("确认导入") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (stage == "preview") TextButton(onClick = { stage = "input" }) { Text("返回") }
            else TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ThemeTransferDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf("export") }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题配置导出 / 导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mode == "export", onClick = { mode = "export"; error = null }, label = { Text("导出") })
                    FilterChip(selected = mode == "import", onClick = { mode = "import"; content = "" }, label = { Text("导入") })
                }
                if (mode == "export") {
                    OutlinedTextField(
                        value = remember { vm.defaultThemeJson() },
                        onValueChange = {},
                        readOnly = true,
                        minLines = 5,
                        label = { Text("theme.json") },
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(vm.defaultThemeJson()))
                        error = "已复制到剪贴板"
                    }) { Text("复制 JSON") }
                } else {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        minLines = 5,
                        label = { Text("粘贴 theme.json 内容") },
                        isError = error != null && content.isBlank(),
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (mode == "import") {
                    if (content.isBlank()) { error = "内容不能为空"; return@TextButton }
                    vm.importTheme(content) { ok, msg ->
                        if (ok) { onDismiss() } else { error = msg ?: "解析失败" }
                    }
                } else onDismiss()
            }) { Text(if (mode == "import") "导入应用" else "关闭") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
