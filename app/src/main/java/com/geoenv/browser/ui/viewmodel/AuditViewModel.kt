package com.geoenv.browser.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.db.AuditLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 审计日志 ViewModel（需求 3.7）：筛选、只追加展示、CSV 导出。
 *
 * 审计表由 SQLite TRIGGER 禁止 UPDATE/DELETE，本类也只暴露读与导出。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val repo: AuditRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class Filter(
        val query: String = "",
        val action: String? = null,
        val envId: Long? = null,
        val from: Long? = null,
        val to: Long? = null,
    )

    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    fun setQuery(q: String) { _filter.value = _filter.value.copy(query = q) }
    fun setAction(a: String?) { _filter.value = _filter.value.copy(action = a) }
    fun setEnv(envId: Long?) { _filter.value = _filter.value.copy(envId = envId) }
    fun setTimeRange(from: Long?, to: Long?) { _filter.value = _filter.value.copy(from = from, to = to) }
    fun clearFilters() { _filter.value = Filter() }

    val logs: StateFlow<List<AuditLog>> =
        _filter.flatMapLatest { f ->
            if (f.query.isBlank() && f.action == null && f.envId == null && f.from == null && f.to == null) {
                repo.observeRecent(2000)
            } else {
                repo.search(
                    query = f.query.ifBlank { null },
                    action = f.action,
                    envId = f.envId,
                    from = f.from,
                    to = f.to,
                    limit = 2000,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可选操作类型（去重） */
    val actions: StateFlow<List<String>> = repo.distinctActionsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<Int> = logs.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /* ----------------------------- CSV 导出 ----------------------------- */

    data class ExportResult(val path: String, val rows: Int)

    fun exportCsv(onDone: (Result<ExportResult>) -> Unit) {
        val f = _filter.value
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val rows = repo.export(
                        query = f.query.ifBlank { null },
                        action = f.action,
                        envId = f.envId,
                        from = f.from,
                        to = f.to,
                    )
                    val dir = File(context.filesDir, "export").apply { mkdirs() }
                    val file = File(dir, "audit_${System.currentTimeMillis()}.csv")
                    file.writeText(buildString {
                        appendLine("id,time,datetime,operator,action,envId,envName,note,detail")
                        rows.forEach { r ->
                            appendLine(
                                listOf(
                                    r.id.toString(),
                                    r.time.toString(),
                                    ISO.format(java.util.Date(r.time)),
                                    csvEscape(r.operator),
                                    csvEscape(r.action),
                                    r.envId?.toString() ?: "",
                                    csvEscape(r.envName ?: ""),
                                    csvEscape(r.note),
                                    csvEscape(r.detailJson ?: ""),
                                ).joinToString(","),
                            )
                        }
                    })
                    ExportResult(file.absolutePath, rows.size)
                }
            }
            onDone(result)
        }
    }

    private fun csvEscape(s: String): String {
        val needs = s.contains(',') || s.contains('"') || s.contains('\n')
        val escaped = s.replace("\"", "\"\"")
        return if (needs) "\"$escaped\"" else escaped
    }

    private val ISO = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)

    companion object {
        /** 审计动作可读名（详情页/筛选下拉） */
        val ACTION_LABELS = mapOf(
            "CREATE" to "新建环境",
            "OPEN" to "打开环境",
            "CLOSE" to "关闭环境",
            "MODIFY_FINGERPRINT" to "修改指纹",
            "SWITCH_PROXY" to "切换代理",
            "SNAPSHOT" to "保存快照",
            "RESTORE_SNAPSHOT" to "还原快照",
            "DELETE" to "删除",
            "CLONE" to "克隆",
            "IMPORT" to "导入环境包",
            "EXPORT" to "导出环境包",
            "BACKUP" to "自动备份",
            "MODIFY_ACCOUNT" to "修改账号",
            "MODIFY_THEME" to "修改主题",
            "MARIONETTE" to "Marionette 变更",
            "PROXY_CHECK" to "代理检测",
            "HOSTS" to "Hosts 变更",
            "HAR" to "HAR 抓包",
            "SETTINGS" to "设置变更",
            "LOGIN" to "登录",
            "ERROR" to "错误",
            "BOOT_RESTORE" to "开机恢复",
            "FINGERPRINT_REPORT" to "指纹检测报告",
        )
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun AuditRepository.distinctActionsFlow() =
    // 审计只追加：日志列表变化即重新计算可选动作
    observeRecent(100).mapLatest { distinctActions() }
