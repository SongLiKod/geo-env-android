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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.HarRecord
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.HarViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HAR 抓包记录页（需求 3.9.2）：
 * 列出全部环境的抓包文件，导出 / 删除；录制入口在环境详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarPage(
    onBack: () -> Unit,
    vm: HarViewModel = hiltViewModel(),
) {
    val records by vm.records.collectAsStateWithLifecycle()
    val envs by vm.envs.collectAsStateWithLifecycle()
    val recording by vm.recordingEnvIds.collectAsStateWithLifecycle()

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var pendingDelete by remember { mutableStateOf<HarRecord?>(null) }

    val envName = remember(envs) { envs.associate { it.id to it.name } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HAR 抓包记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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

            if (recording.isNotEmpty()) {
                SectionCard(title = "录制中") {
                    recording.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                envName[id] ?: "环境 $id",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Tag("REC", GeoEnvSemantic.Failed)
                            Spacer(Modifier.width(6.dp))
                            TextButton(onClick = { vm.stopRecording(id) }) { Text("停止") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "共 ${records.size} 个抓包文件 · 录制入口在环境详情页「HAR 抓包」",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            if (records.isEmpty()) {
                EmptyView("暂无抓包记录")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                ) {
                    items(records, key = { it.id }) { record ->
                        HarCard(
                            record = record,
                            envName = envName[record.envId] ?: "环境 ${record.envId}",
                            onCopyPath = {
                                clipboard.setText(AnnotatedString(record.filePath))
                            },
                            onExport = {
                                vm.export(record) { r ->
                                    r.onSuccess { p ->
                                        clipboard.setText(AnnotatedString(p))
                                    }
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            r.fold(
                                                onSuccess = { "已导出并复制路径：$it" },
                                                onFailure = { "导出失败：${it.message}" },
                                            ),
                                        )
                                    }
                                }
                            },
                            onDelete = { pendingDelete = record },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { record ->
        ConfirmDialog(
            title = "删除抓包记录",
            message = "删除「${record.name}」及其 HAR 文件？",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.delete(record) { pendingDelete = null }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private val ISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

@Composable
private fun HarCard(
    record: HarRecord,
    envName: String,
    onCopyPath: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        record.name.ifBlank { "har_${record.id}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        envName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record.endTime == null) {
                    Tag("录制中", GeoEnvSemantic.Failed)
                } else {
                    Tag("${record.entryCount} 条", GeoEnvSemantic.Running)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "${ISO.format(Date(record.startTime))}" +
                    (record.endTime?.let { " → ${ISO.format(Date(it))}" } ?: "") +
                    " · ${record.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Text(
                record.filePath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onCopyPath) { Text("复制路径") }
                TextButton(onClick = onExport) { Text("导出") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
