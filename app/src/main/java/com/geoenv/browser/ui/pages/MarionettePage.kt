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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoenv.browser.manager.MarionetteManager
import com.geoenv.browser.ui.components.EmptyView
import com.geoenv.browser.ui.components.SectionCard
import com.geoenv.browser.ui.components.Tag
import com.geoenv.browser.ui.theme.GeoEnvSemantic
import com.geoenv.browser.ui.viewmodel.EnvListViewModel

/**
 * Marionette 自动化总览页（需求 3.5）：
 * 每实例独立端口、仅 127.0.0.1 回环监听；列出当前可连接的端点、
 * SDK 连接串、adb forward 命令与安全说明。
 *
 * 端口配置入口在各环境详情页（变更需重启环境生效）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarionettePage(
    onBack: () -> Unit,
    vm: EnvListViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val enabled = items.filter { it.env.marionetteEnabled }
    val active = items.filter { it.running && it.marionettePort > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marionette 自动化") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            /* 说明 */
            item {
                SectionCard(title = "接入说明") {
                    Text(
                        "· 每个环境的 Marionette 端口独立（Gecko 进程级配置，变更需重启环境）。\n" +
                            "· Gecko 的 nsIServerSocket 以 LoopbackOnly 打开，只监听 127.0.0.1，不对外网暴露。\n" +
                            "· 真机联调可用 adb forward 把设备端口转到主机。\n" +
                            "· 客户端示例见 docs/marionette-sdk-guide.md。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            /* 当前可连接端点 */
            item {
                SectionCard(title = "运行中的端点（${active.size}）") {
                    if (active.isEmpty()) {
                        Text(
                            "暂无运行中且已开启 Marionette 的环境。先在环境详情开启并启动环境。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(active, key = { it.env.id }) { item ->
                EndpointCard(
                    name = item.env.name,
                    port = item.marionettePort,
                    clipboardText = { clipboard.setText(AnnotatedString(it)) },
                )
            }

            /* 已配置但未运行 */
            val configured = enabled.filter { !(it.running && it.marionettePort > 0) }
            if (configured.isNotEmpty()) {
                item {
                    SectionCard(title = "已配置（未运行，${configured.size}）") {
                        configured.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item.env.name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (item.marionettePort > 0) ":${item.marionettePort}" else "随机端口",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Text(
                            "启动环境后生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            /* 快速参考 */
            item {
                SectionCard(title = "快速参考") {
                    CodeBlock(
                        "Python（marionette_driver）",
                        "from marionette_driver.marionette import Marionette\n" +
                            "client = Marionette('127.0.0.1', <port>)\n" +
                            "client.start_session()\n" +
                            "client.navigate('https://example.com')\n" +
                            "print(client.get_url())",
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    CodeBlock(
                        "adb 端口转发（真机 → 主机）",
                        "adb forward tcp:<port> tcp:<port>",
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    CodeBlock(
                        "截图验证连通",
                        "client.execute_script(\"return navigator.userAgent\")",
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointCard(
    name: String,
    port: Int,
    clipboardText: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${MarionetteManager.LOOPBACK}:$port",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GeoEnvSemantic.Running,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Tag("LISTEN", GeoEnvSemantic.Running)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clipboardText("Marionette('${MarionetteManager.LOOPBACK}', $port)")
                }) { Text("复制连接串") }
                TextButton(onClick = {
                    clipboardText("adb forward tcp:$port tcp:$port")
                }) { Text("复制 adb forward") }
            }
        }
    }
}

@Composable
private fun CodeBlock(title: String, code: String, onCopy: (String) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onCopy(code) }) { Text("复制") }
        }
        Text(
            code,
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
