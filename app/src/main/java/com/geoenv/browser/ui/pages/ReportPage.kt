package com.geoenv.browser.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.geoenv.browser.manager.ReportManager
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.EnvDetailViewModel

/**
 * 指纹检测报告页（需求 3.3）：
 * 静态校验 + 注入链路自检 + 运行时实测（环境运行中时）比对，
 * 一键生成并可导出 Markdown 作为测试用例取证附件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportPage(
    envId: Long,
    onBack: () -> Unit,
    vm: EnvDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(envId) { vm.setEnv(envId) }
    val report by vm.report.collectAsStateWithLifecycle()
    val loading by vm.reportLoading.collectAsStateWithLifecycle()
    val slot by vm.slotState.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var exportedPath by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("指纹检测报告") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (report != null) {
                        TextButton(onClick = { vm.exportReport { exportedPath = it } }) { Text("导出MD") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            val running = slot != null
            SectionCard(title = "检测设置") {
                Text(
                    if (running) "环境运行中：将执行页面实测（读回真实 navigator/screen/Intl 值逐项比对）。"
                    else "环境未运行：仅执行静态校验；启动环境后再生成可获得实测数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.generateReport() }, enabled = !loading) {
                        Text(if (report == null) "一键生成报告" else "重新生成")
                    }
                    if (loading) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                }
                exportedPath?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已导出：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = GeoEnvSemantic.Running,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val r = report
            if (r == null) {
                EmptyView("尚未生成报告")
                return@Column
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            ) {
                /* 概览 */
                item {
                    SectionCard(title = "结果概览") {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatCell("PASS", r.passCount, GeoEnvSemantic.Running)
                            StatCell("FAIL", r.failCount, GeoEnvSemantic.Failed)
                            StatCell("WARN/SKIP", r.warnCount, GeoEnvSemantic.Warning)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "环境：${r.envName} · Gecko ${r.geckoVersion} · Hook ${r.hookVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (r.running) "含页面实测" else "仅静态校验",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                /* 比对表 */
                item {
                    SectionCard(title = "参数实测比对（${r.lines.size} 项）") {
                        HeaderRow()
                        r.lines.forEach { line ->
                            CheckLineRow(line)
                        }
                    }
                }

                /* 配置问题 */
                if (r.issues.isNotEmpty()) {
                    item {
                        SectionCard(title = "配置一致性问题（${r.issues.size}）") {
                            r.issues.forEach { issue ->
                                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                                    Tag(
                                        issue.severity,
                                        if (issue.severity == "ERROR") GeoEnvSemantic.Failed else GeoEnvSemantic.Warning,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "${issue.field}：${issue.message}",
                                            style = MaterialTheme.typography.bodySmall,
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
                        }
                    }
                }

                /* 结论 */
                item {
                    SectionCard(title = "结论") {
                        Text(
                            when {
                                r.failCount > 0 -> "⚠️ 存在 ${r.failCount} 项指纹不一致，需修正后重新检测。"
                                r.warnCount > 0 -> "⚠️ 基本一致，但有 ${r.warnCount} 项待确认（多为环境未运行）。"
                                else -> "✅ 指纹各参数自洽且与配置一致。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "报告可导出为 Markdown，作为测试用例的指纹取证附件。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("检查项", Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("期望", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("实测", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("结果", Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CheckLineRow(line: ReportManager.CheckLine) {
    val color = when (line.status) {
        ReportManager.Report.STATUS_PASS -> GeoEnvSemantic.Running
        ReportManager.Report.STATUS_FAIL -> GeoEnvSemantic.Failed
        else -> GeoEnvSemantic.Warning
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(vertical = 6.dp, horizontal = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(line.item, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                line.expected.take(46),
                Modifier.weight(1.2f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                line.actual.take(46),
                Modifier.weight(1.2f),
                style = MaterialTheme.typography.labelSmall,
                color = if (line.status == ReportManager.Report.STATUS_FAIL) GeoEnvSemantic.Failed
                else MaterialTheme.colorScheme.onSurface,
            )
            Tag(line.status, color)
        }
        if (line.note.isNotBlank()) {
            Text(
                line.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
