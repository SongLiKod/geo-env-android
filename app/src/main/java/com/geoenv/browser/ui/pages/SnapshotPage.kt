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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.db.FingerprintSnapshot
import com.geoenv.browser.ui.components.ConfirmDialog
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.viewmodel.EnvDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指纹快照页（需求 3.3）：保存当前整套指纹为命名快照，一键还原、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotPage(
    envId: Long,
    onBack: () -> Unit,
    vm: EnvDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(envId) { vm.setEnv(envId) }
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var showSave by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<FingerprintSnapshot?>(null) }
    var pendingDelete by remember { mutableStateOf<FingerprintSnapshot?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("指纹快照") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSave = true }) {
                Icon(Icons.Filled.Add, contentDescription = "保存快照")
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
            Text(
                "快照保存整套指纹参数，可随时一键还原；还原后重启环境生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (snapshots.isEmpty()) {
                EmptyView("暂无快照，点右下角 + 保存当前指纹")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                ) {
                    items(snapshots, key = { it.id }) { snap ->
                        SnapshotCard(
                            snap = snap,
                            onRestore = { pendingRestore = snap },
                            onDelete = { pendingDelete = snap },
                        )
                    }
                }
            }
        }
    }

    if (showSave) {
        SaveSnapshotDialog(
            onDismiss = { showSave = false },
            onSave = { name, remark ->
                showSave = false
                vm.saveSnapshot(name, remark)
            },
        )
    }

    pendingRestore?.let { snap ->
        ConfirmDialog(
            title = "还原快照",
            message = "用快照「${snap.name}」覆盖当前指纹配置？\n运行中的环境需重启生效。",
            confirmText = "还原",
            onConfirm = { vm.restoreSnapshot(snap.id); pendingRestore = null },
            onDismiss = { pendingRestore = null },
        )
    }

    pendingDelete?.let { snap ->
        ConfirmDialog(
            title = "删除快照",
            message = "删除快照「${snap.name}」？不影响当前指纹配置。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteSnapshot(snap.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

private val ISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

@Composable
private fun SnapshotCard(
    snap: FingerprintSnapshot,
    onRestore: () -> Unit,
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
                    Text(snap.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        ISO.format(Date(snap.createTime)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRestore) { Text("还原") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
            if (snap.remark.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    snap.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "指纹 JSON ${snap.fingerprintJson.length} 字节",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveSnapshotDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, remark: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存指纹快照") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("快照名称 *") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("备注") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = "名称不能为空"; return@TextButton }
                onSave(name.trim(), remark.trim())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
