package com.geoenv.browser.data.repo

import com.geoenv.browser.db.AuditLog
import com.geoenv.browser.db.AuditLogDao
import com.geoenv.browser.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 审计日志仓储。
 *
 * 只追加（append-only）：这里只暴露 insert / query，**没有任何 update / delete**。
 * 数据库层另有 TRIGGER 兜底（见 DatabaseMigrations.installAuditTriggers）。
 */
class AuditRepository(private val db: AppDatabase) {

    private val dao: AuditLogDao get() = db.auditDao()

    fun observeRecent(limit: Int = 1000): Flow<List<AuditLog>> = dao.observeRecent(limit)

    fun search(
        query: String? = null,
        action: String? = null,
        envId: Long? = null,
        from: Long? = null,
        to: Long? = null,
        limit: Int = 5000,
    ): Flow<List<AuditLog>> = dao.search(query, action, envId, from, to, limit)

    suspend fun export(
        query: String? = null,
        action: String? = null,
        envId: Long? = null,
        from: Long? = null,
        to: Long? = null,
    ): List<AuditLog> = dao.searchOnce(query, action, envId, from, to)

    suspend fun count(): Int = dao.observeCount().first()

    suspend fun distinctActions(): List<String> = dao.distinctActions()

    /** 写审计。任何异常都不能影响主流程（审计失败只记 logcat）。 */
    suspend fun log(
        action: String,
        envId: Long? = null,
        envName: String? = null,
        note: String = "",
        detail: Any? = null,
        operator: String = DEFAULT_OPERATOR,
    ) {
        runCatching {
            val detailJson = detail?.let { runCatching { GSON.toJson(it) }.getOrNull() }
            dao.insert(
                AuditLog(
                    time = System.currentTimeMillis(),
                    envId = envId,
                    envName = envName,
                    operator = operator,
                    action = action,
                    note = note,
                    detailJson = detailJson,
                )
            )
        }.onFailure {
            android.util.Log.w("GeoEnv", "审计写入失败: action=$action", it)
        }
    }

    companion object {
        const val DEFAULT_OPERATOR = "local"

        /** 操作类型常量（与需求 §3.7 对齐） */
        object Action {
            const val CREATE = "CREATE"
            const val OPEN = "OPEN"
            const val CLOSE = "CLOSE"
            const val MODIFY_FINGERPRINT = "MODIFY_FINGERPRINT"
            const val SWITCH_PROXY = "SWITCH_PROXY"
            const val SNAPSHOT = "SNAPSHOT"
            const val RESTORE_SNAPSHOT = "RESTORE_SNAPSHOT"
            const val DELETE = "DELETE"
            const val CLONE = "CLONE"
            const val IMPORT = "IMPORT"
            const val EXPORT = "EXPORT"
            const val BACKUP = "BACKUP"
            const val MODIFY_ACCOUNT = "MODIFY_ACCOUNT"
            const val MODIFY_THEME = "MODIFY_THEME"
            const val MARIONETTE = "MARIONETTE"
            const val PROXY_CHECK = "PROXY_CHECK"
            const val HOSTS = "HOSTS"
            const val HAR = "HAR"
            const val SETTINGS = "SETTINGS"
            const val LOGIN = "LOGIN"
            const val ERROR = "ERROR"
        }

        private val GSON = com.google.gson.Gson()
    }
}
