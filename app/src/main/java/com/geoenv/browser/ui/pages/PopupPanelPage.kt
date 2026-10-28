package com.geoenv.browser.ui.pages

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.PopupEvent
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.PopupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 弹窗与验证码聚合面板（需求 3.9.3）：
 * 多环境同时运行时，把所有环境的 alert/confirm/prompt/popup/redirect/auth
 * 与验证码候选汇聚展示，支持按环境分组、筛选、标记处理与验证码一键回填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopupPanelPage(
    onBack: () -> Unit,
    vm: PopupViewModel = hiltViewModel(),
) {
    val grouped by vm.grouped.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()
    val hideHandled by vm.hideHandled.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    var showClear by remember { mutableStateOf(false) }
    var fillTarget by remember { mutableStateOf<PopupEvent?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("弹窗与验证码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showClear = true }) { Text("清空") }
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

            // 搜索
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索标题 / 内容 / 环境") },
            )

            Spacer(Modifier.height(6.dp))

            // 筛选
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PopupViewModel.TypeFilter.entries.forEach { f ->
                    FilterChip(
                        selected = typeFilter == f,
                        onClick = { vm.setTypeFilter(f) },
                        label = {
                            Text(
                                when (f) {
                                    PopupViewModel.TypeFilter.ALL -> "全部"
                                    PopupViewModel.TypeFilter.CAPTCHA -> "验证码"
                                    PopupViewModel.TypeFilter.DIALOG -> "对话框"
                                    PopupViewModel.TypeFilter.NAVIGATION -> "弹窗/跳转"
                                },
                            )
                        },
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("隐藏已处理", style = MaterialTheme.typography.labelSmall)
                Switch(checked = hideHandled, onCheckedChange = vm::setHideHandled)
            }

            Spacer(Modifier.height(8.dp))

            if (grouped.isEmpty()) {
                EmptyView("暂无弹窗事件（运行环境后自动聚合）")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                ) {
                    grouped.forEach { group ->
                        item(key = "h_${group.envId}") {
                            SectionCard(title = "${group.envName}（${group.events.size}）") {
                                Text(
                                    "共 ${group.events.size} 条事件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(group.events, key = { it.id }) { event ->
                            EventCard(
                                event = event,
                                onHandled = { vm.markHandled(event.id) },
                                onIgnored = { vm.markIgnored(event.id) },
                                onNew = { vm.markNew(event.id) },
                                onDelete = { vm.delete(event.id) },
                                onFill = { fillTarget = event },
                            )
                        }
                    }
                }
            }
        }
    }

    fillTarget?.let { event ->
        FillCaptchaDialog(
            initial = extractCode(event),
            onDismiss = { fillTarget = null },
            onFill = { code ->
                vm.fillCaptcha(event.envId, code)
                vm.markHandled(event.id)
                fillTarget = null
            },
        )
    }

    if (showClear) {
        ConfirmDialog(
            title = "清空弹窗记录",
            message = "删除当前列出的全部弹窗事件？（审计日志不受影响）",
            confirmText = "清空",
            danger = true,
            onConfirm = { vm.clearAll(); showClear = false },
            onDismiss = { showClear = false },
        )
    }
}

private val ISO = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

/** 从验证码事件里抽取候选文本（content script 启发式结果存在 message 里） */
private fun extractCode(event: PopupEvent): String {
    val m = Regex("\\b([0-9A-Za-z]{4,8})\\b").find(event.message)
    return m?.groupValues?.get(1) ?: event.message.take(8)
}

@Composable
private fun EventCard(
    event: PopupEvent,
    onHandled: () -> Unit,
    onIgnored: () -> Unit,
    onNew: () -> Unit,
    onDelete: () -> Unit,
    onFill: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(
                    when (event.type) {
                        "CAPTCHA" -> "验证码"
                        "ALERT", "CONFIRM", "PROMPT", "BEFORE_UNLOAD" -> "对话框"
                        "AUTH" -> "认证"
                        else -> "弹窗"
                    },
                    when {
                        event.type == "CAPTCHA" -> MaterialTheme.colorScheme.primary
                        event.type == "AUTH" -> GeoEnvSemantic.Warning
                        else -> GeoEnvSemantic.Starting
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ISO.format(Date(event.createTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                when (event.status) {
                    PopupEvent.STATUS_HANDLED -> Tag("已处理", GeoEnvSemantic.Running)
                    PopupEvent.STATUS_IGNORED -> Tag("已忽略", GeoEnvSemantic.Stopped)
                    else -> Tag("NEW", GeoEnvSemantic.Warning)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        Modifier.height(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (event.title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.message.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.url.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    event.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (event.type == "CAPTCHA") {
                    TextButton(onClick = onFill) { Text("回填验证码") }
                }
                when (event.status) {
                    PopupEvent.STATUS_NEW -> {
                        TextButton(onClick = onHandled) { Text("标记已处理") }
                        TextButton(onClick = onIgnored) { Text("忽略") }
                    }
                    else -> TextButton(onClick = onNew) { Text("恢复为未处理") }
                }
            }
        }
    }
}

@Composable
private fun FillCaptchaDialog(
    initial: String,
    onDismiss: () -> Unit,
    onFill: (String) -> Unit,
) {
    var code by remember { mutableStateOf(initial) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回填验证码") },
        text = {
            Column {
                Text(
                    "将把验证码填入该环境当前页面的输入框（经扩展执行 JS）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("验证码") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (code.isNotBlank()) onFill(code.trim()) }) { Text("回填") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
