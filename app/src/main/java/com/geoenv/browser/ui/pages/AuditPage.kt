package com.geoenv.browser.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SearchField
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.AuditViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 审计日志（需求 3.7）：只追加、不可删除，支持筛选与 CSV 导出。
 *
 * 本页 UI 上**没有任何删除入口**——数据层由 SQLite TRIGGER 强制禁止 UPDATE/DELETE。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditPage(
    onBack: () -> Unit,
    vm: AuditViewModel = hiltViewModel(),
) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()

    var exportResult by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("审计日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        vm.exportCsv { r ->
                            exportResult = r.fold(
                                onSuccess = { "已导出 ${it.rows} 条 → ${it.path}" },
                                onFailure = { "导出失败：${it.message}" },
                            )
                        }
                    }) { Text("导出CSV") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SearchField(value = filter.query, onValueChange = vm::setQuery, placeholder = "搜索备注 / 环境 / 操作类型")

            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val scroll = androidx.compose.foundation.rememberScrollState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filter.action == null,
                        onClick = { vm.setAction(null) },
                        label = { Text("全部类型") },
                    )
                    actions.forEach { a ->
                        FilterChip(
                            selected = filter.action == a,
                            onClick = { vm.setAction(if (filter.action == a) null else a) },
                            label = { Text(AuditViewModel.ACTION_LABELS[a] ?: a) },
                        )
                    }
                }
            }

            exportResult?.let {
                Spacer(Modifier.height(6.dp))
                SectionCard(title = "导出结果") {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { exportResult = null }) { Text("关闭") }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${logs.size} 条（只追加，不可修改删除）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (filter.query.isNotBlank() || filter.action != null) {
                    TextButton(onClick = { vm.clearFilters() }) { Text("清除筛选") }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (logs.isEmpty()) {
                EmptyView("暂无审计记录")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(logs, key = { it.id }) { log ->
                        AuditRow(log)
                    }
                }
            }
        }
    }
}

private val ISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

@Composable
private fun AuditRow(log: com.geoenv.browser.db.AuditLog) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(
                AuditViewModel.ACTION_LABELS[log.action] ?: log.action,
                actionColor(log.action),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ISO.format(Date(log.time)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                log.operator,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!log.envName.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                "环境：${log.envName} (${log.envId})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        if (log.note.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                log.note,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun actionColor(action: String) = when (action) {
    "ERROR", "DELETE" -> GeoEnvSemantic.Failed
    "OPEN", "CREATE" -> GeoEnvSemantic.Running
    "EXPORT", "IMPORT", "BACKUP" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

