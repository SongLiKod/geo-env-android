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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.geoenv.browser.gecko.FingerprintConfig
import com.geoenv.browser.gecko.FingerprintIssue
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.EnvDetailViewModel

/**
 * 指纹配置页（需求 3.3）：全参数表单 + 实时一致性校验 + 保存。
 *
 * 存在 ERROR 级冲突时保存按钮禁用（需求："禁止冲突参数"）；
 * 一键生成默认自洽指纹 / 恢复快照入口在本页顶部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintPage(
    envId: Long,
    onBack: () -> Unit,
    onOpenSnapshots: (Long) -> Unit,
    vm: EnvDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(envId) {
        vm.setEnv(envId)
        vm.loadDraft()
    }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    val env by vm.environment.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var showReset by remember { mutableStateOf(false) }

    val hasError = issues.any { it.severity == FingerprintIssue.ERROR }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("指纹配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onOpenSnapshots(envId) }) { Text("快照") }
                    TextButton(onClick = { showReset = true }) { Text("默认指纹") }
                    Button(
                        onClick = { vm.saveFingerprint() },
                        enabled = draft != null && !hasError,
                    ) { Text("保存") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val cfg = draft
        if (cfg == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "正在加载指纹配置…",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
        ) {
            /* --------------------- 校验面板（置顶） --------------------- */
            item {
                SectionCard(
                    title = if (hasError) "存在冲突，禁止保存" else if (issues.isEmpty()) "校验通过" else "校验警告",
                    action = {
                        Tag(
                            if (hasError) "ERROR" else if (issues.isNotEmpty()) "WARN" else "PASS",
                            if (hasError) GeoEnvSemantic.Failed
                            else if (issues.isNotEmpty()) GeoEnvSemantic.Warning
                            else GeoEnvSemantic.Running,
                        )
                    },
                ) {
                    if (issues.isEmpty()) {
                        Text(
                            "UA / 屏幕 / DPI / 语言 / 时区 / 客户端 Hint 全部自洽。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        issues.forEach { issue ->
                            IssueRow(issue)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Text(
                        "保存后运行中的环境需重启才生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            /* --------------------- UA / 设备 --------------------- */
            item {
                SectionCard(title = "User-Agent 与设备") {
                    Labeled("完整 UA（留空按平台片段自动拼装）", cfg.userAgent) {
                        vm.updateDraft { d -> d.copy(userAgent = it) }
                    }
                    Labeled("UA 平台片段", cfg.uaPlatform) {
                        vm.updateDraft { d -> d.copy(uaPlatform = it) }
                    }
                    LabeledInt("UA Chrome 主版本", cfg.uaChromeVersion) { v ->
                        vm.updateDraft { d -> d.copy(uaChromeVersion = v) }
                    }
                    Labeled("设备型号", cfg.deviceModel) {
                        vm.updateDraft { d -> d.copy(deviceModel = it, chModel = it.ifBlank { d.chModel }) }
                    }
                    Labeled("厂商", cfg.deviceManufacturer) {
                        vm.updateDraft { d -> d.copy(deviceManufacturer = it) }
                    }
                    SwitchRow("UA 含 Mobile 标识", cfg.uaMobile) {
                        vm.updateDraft { d -> d.copy(uaMobile = it) }
                    }
                }
            }

            /* --------------------- 客户端 Hint --------------------- */
            item {
                SectionCard(title = "客户端 Hint (UA-CH)") {
                    Labeled("CH 平台", cfg.chPlatform) {
                        vm.updateDraft { d -> d.copy(chPlatform = it) }
                    }
                    Labeled("CH 平台版本", cfg.chPlatformVersion) {
                        vm.updateDraft { d -> d.copy(chPlatformVersion = it) }
                    }
                    Labeled("CH 完整版本", cfg.chFullVersion) {
                        vm.updateDraft { d -> d.copy(chFullVersion = it) }
                    }
                    Labeled("CH 模型", cfg.chModel) {
                        vm.updateDraft { d -> d.copy(chModel = it) }
                    }
                    LabeledInt("CH 架构位数", cfg.chBitness.toIntOrNull() ?: 64) { v ->
                        vm.updateDraft { d -> d.copy(chBitness = v.toString()) }
                    }
                    LabeledInt("CH Device Memory (GB)", cfg.chDeviceMemory) { v ->
                        vm.updateDraft { d -> d.copy(chDeviceMemory = v) }
                    }
                    Labeled("CH UA-Full-Version-List", cfg.chUaFullVersionList) {
                        vm.updateDraft { d -> d.copy(chUaFullVersionList = it) }
                    }
                }
            }

            /* --------------------- 屏幕 --------------------- */
            item {
                SectionCard(title = "屏幕与显示") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInt("宽 (dp)", cfg.screenWidth, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(screenWidth = v) }
                        }
                        LabeledInt("高 (dp)", cfg.screenHeight, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(screenHeight = v) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInt("可用宽", cfg.availWidth, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(availWidth = v) }
                        }
                        LabeledInt("可用高", cfg.availHeight, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(availHeight = v) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInt("DPI", cfg.screenDpi, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(screenDpi = v, pixelRatio = if (v > 0) v / 160.0 else d.pixelRatio) }
                        }
                        LabeledDouble("pixelRatio", cfg.pixelRatio, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(pixelRatio = v) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInt("色深", cfg.colorDepth, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(colorDepth = v) }
                        }
                        LabeledInt("hardwareConcurrency", cfg.hardwareConcurrency, Modifier.weight(1f)) { v ->
                            vm.updateDraft { d -> d.copy(hardwareConcurrency = v) }
                        }
                    }
                    LabeledInt("maxTouchPoints", cfg.maxTouchPoints) { v ->
                        vm.updateDraft { d -> d.copy(maxTouchPoints = v) }
                    }
                }
            }

            /* --------------------- 时区 / 语言 --------------------- */
            item {
                SectionCard(title = "时区与语言") {
                    Labeled("IANA 时区", cfg.timezone, supporting = "如 Asia/Shanghai，同时写入进程 TZ") {
                        vm.updateDraft { d -> d.copy(timezone = it) }
                    }
                    Labeled(
                        "语言列表（逗号分隔）",
                        cfg.languages.joinToString(","),
                        supporting = "首项为 navigator.language，同时驱动 intl.accept_languages",
                    ) { text ->
                        vm.updateDraft { d ->
                            d.copy(languages = text.split(",").map { it.trim() }.filter { it.isNotBlank() })
                        }
                    }
                }
            }

            /* --------------------- Canvas / WebGL / Audio --------------------- */
            item {
                SectionCard(title = "Canvas / WebGL / Audio") {
                    ModeRow("Canvas", cfg.canvasMode) { v ->
                        vm.updateDraft { d -> d.copy(canvasMode = v) }
                    }
                    LabeledDouble("Canvas 噪声强度 (0-1)", cfg.canvasNoiseStrength) { v ->
                        vm.updateDraft { d -> d.copy(canvasNoiseStrength = v) }
                    }
                    LabeledLong("Canvas 噪声种子", cfg.canvasNoiseSeed) { v ->
                        vm.updateDraft { d -> d.copy(canvasNoiseSeed = v) }
                    }
                    ModeRow("WebGL", cfg.webglMode) { v ->
                        vm.updateDraft { d -> d.copy(webglMode = v) }
                    }
                    Labeled("WebGL Vendor", cfg.webglVendor) {
                        vm.updateDraft { d -> d.copy(webglVendor = it) }
                    }
                    Labeled("WebGL Renderer", cfg.webglRenderer, singleLine = false) {
                        vm.updateDraft { d -> d.copy(webglRenderer = it) }
                    }
                    SwitchRow("屏蔽 WEBGL_debug_renderer_info", cfg.webglMaskDebugInfo) {
                        vm.updateDraft { d -> d.copy(webglMaskDebugInfo = it) }
                    }
                    ModeRow("Audio", cfg.audioMode) { v ->
                        vm.updateDraft { d -> d.copy(audioMode = v) }
                    }
                    LabeledLong("Audio 噪声种子", cfg.audioNoiseSeed) { v ->
                        vm.updateDraft { d -> d.copy(audioNoiseSeed = v) }
                    }
                }
            }

            /* --------------------- WebRTC / 插件 --------------------- */
            item {
                SectionCard(title = "WebRTC 与插件") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "DISABLE" to "禁用",
                            "DISABLE_NON_PROXIED_UDP" to "禁非代理UDP",
                            "PUBLIC_ONLY" to "仅公网",
                            "DEFAULT" to "默认",
                        ).forEach { (k, v) ->
                            FilterChip(
                                selected = cfg.webrtcPolicy == k,
                                onClick = { vm.updateDraft { d -> d.copy(webrtcPolicy = k) } },
                                label = { Text(v) },
                            )
                        }
                    }
                    SwitchRow("屏蔽本地候选（防内网 IP 泄露）", cfg.webrtcBlockLocalCandidates) {
                        vm.updateDraft { d -> d.copy(webrtcBlockLocalCandidates = it) }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            FingerprintConfig.MODE_DEFAULT to "默认",
                            FingerprintConfig.MODE_EMPTY to "空列表",
                            FingerprintConfig.MODE_CUSTOM to "自定义",
                        ).forEach { (k, v) ->
                            FilterChip(
                                selected = cfg.pluginMode == k,
                                onClick = { vm.updateDraft { d -> d.copy(pluginMode = k) } },
                                label = { Text(v) },
                            )
                        }
                    }
                    if (cfg.pluginMode == FingerprintConfig.MODE_CUSTOM) {
                        cfg.customPlugins.forEach { p ->
                            Text(
                                "· ${p.name} (${p.filename})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "自定义插件通过额外 JS 注入，当前 ${cfg.customPlugins.size} 项；空列表会被校验拦截。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            /* --------------------- 杂项 --------------------- */
            item {
                SectionCard(title = "杂项") {
                    Labeled("navigator.vendor", cfg.vendor) {
                        vm.updateDraft { d -> d.copy(vendor = it) }
                    }
                    SwitchRow("伪装 Do Not Track", cfg.doNotTrack) {
                        vm.updateDraft { d -> d.copy(doNotTrack = it) }
                    }
                    SwitchRow("启用 Gecko 内置反指纹保护", cfg.geckoFingerprintingProtection) {
                        vm.updateDraft { d -> d.copy(geckoFingerprintingProtection = it) }
                    }
                    Labeled("附加自定义 JS（在标准 Hook 之后执行）", cfg.extraJs, singleLine = false) {
                        vm.updateDraft { d -> d.copy(extraJs = it) }
                    }
                    Text(
                        "Hook 版本：${cfg.hookVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            /* --------------------- 保存按钮（底部） --------------------- */
            item {
                Button(
                    onClick = { vm.saveFingerprint() },
                    enabled = !hasError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (hasError) "存在冲突，无法保存" else "保存指纹配置")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { vm.resetDraftToDefault() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("生成一套默认自洽指纹") }
                env?.let { e ->
                    Text(
                        "当前环境指纹大小 ${e.fingerprintJson.length} 字节",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showReset) {
        ConfirmDialog(
            title = "生成默认指纹",
            message = "将用一套随机但自洽的指纹覆盖当前草稿（尚未保存，可再手改）。",
            confirmText = "生成",
            onConfirm = { vm.resetDraftToDefault(); showReset = false },
            onDismiss = { showReset = false },
        )
    }
}

/* ============================ 局部组件 ============================ */

@Composable
private fun IssueRow(issue: FingerprintIssue) {
    Row(verticalAlignment = Alignment.Top) {
        Tag(
            issue.severity,
            if (issue.severity == FingerprintIssue.ERROR) GeoEnvSemantic.Failed else GeoEnvSemantic.Warning,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${issue.field}：${issue.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (issue.severity == FingerprintIssue.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (issue.suggestion.isNotBlank()) {
                Text(
                    "建议：${issue.suggestion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Labeled(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = supporting?.let { { Text(it) } },
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LabeledInt(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toIntOrNull()?.let { v -> onChange(v) }
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LabeledDouble(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toDoubleOrNull()?.let { v -> onChange(v) }
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LabeledLong(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    onChange: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.trim().toLongOrNull()?.let { v -> onChange(v) }
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ModeRow(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            FingerprintConfig.MODE_OFF to "OFF",
            FingerprintConfig.MODE_NOISE to "NOISE",
            FingerprintConfig.MODE_BLOCK to "BLOCK",
        ).forEach { (k, v) ->
            FilterChip(selected = value == k, onClick = { onChange(k) }, label = { Text(v) })
        }
    }
    Spacer(Modifier.height(8.dp))
}
