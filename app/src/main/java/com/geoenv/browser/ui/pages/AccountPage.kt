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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.AccountListViewModel
import com.geoenv.browser.ui.viewmodel.AccountRow

/**
 * 账号台账（需求 3.2）：列出全部环境绑定的账号，快捷查看/编辑，密码加密存储。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPage(
    onOpenDetail: (Long) -> Unit,
    vm: AccountListViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AccountRow?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("账号台账") }) },
        floatingActionButton = {
            if (rows.isNotEmpty()) {
                FloatingActionButton(onClick = { editing = rows.firstOrNull { it.password == null } ?: rows.first() }) {
                    Icon(Icons.Filled.Add, contentDescription = "补录密码")
                }
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyView("还没有绑定账号的环境")
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 88.dp),
        ) {
            items(rows, key = { it.envId }) { row ->
                AccountCard(
                    row = row,
                    onClick = { onOpenDetail(row.envId) },
                    onEdit = { editing = row },
                )
            }
        }
    }

    editing?.let { row ->
        AccountEditDialog(
            username = row.username,
            envName = row.envName,
            hasPassword = row.password != null,
            onDismiss = { editing = null },
            onSave = { username, password, remark, owner, status ->
                vm.save(row.envId, username, password, remark, owner, status)
                editing = null
            },
        )
    }
}

@Composable
private fun AccountCard(
    row: AccountRow,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.username.ifBlank { "未填写账号" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.envName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Tag(
                            when (row.status) {
                                "ACTIVE" -> "使用中"
                                "FROZEN" -> "冻结"
                                else -> "待启用"
                            },
                            when (row.status) {
                                "ACTIVE" -> GeoEnvSemantic.Running
                                "FROZEN" -> GeoEnvSemantic.Failed
                                else -> GeoEnvSemantic.Starting
                            },
                        )
                    }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "负责人：${row.owner.ifBlank { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "密码：${if (row.password != null) "已加密存储" else "未设置"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.password != null) GeoEnvSemantic.Running
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.remark.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    row.remark,
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
private fun AccountEditDialog(
    username: String,
    envName: String,
    hasPassword: Boolean,
    onDismiss: () -> Unit,
    onSave: (username: String, password: String?, remark: String, owner: String, status: String) -> Unit,
) {
    var name by remember { mutableStateOf(username) }
    var pwd by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ACTIVE") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑账号 · $envName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("账号") }, singleLine = true)
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text(if (hasPassword) "新密码（留空则不修改）" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("AES-256-GCM 加密后入库，绝不存明文") },
                )
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("负责人") }, singleLine = true)
                OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("备注") })
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("PENDING" to "待启用", "ACTIVE" to "使用中", "FROZEN" to "冻结").forEach { (k, v) ->
                        FilterChip(selected = status == k, onClick = { status = k }, label = { Text(v) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name, pwd.ifBlank { null }, remark, owner, status)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
