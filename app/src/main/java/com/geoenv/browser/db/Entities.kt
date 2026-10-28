package com.geoenv.browser.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* =====================================================================================
 * 环境分组
 * ===================================================================================*/
@Entity(tableName = "env_group")
data class EnvGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 分组颜色（HEX，用于列表色标） */
    val color: String = "#5B8DEF",
    val sortOrder: Int = 0,
    val createTime: Long = System.currentTimeMillis(),
)

/* =====================================================================================
 * 环境表
 *  - profilePath: 独立 profile 目录（filesDir/profiles/env_<id>）
 *  - fingerprintJson: 当前生效的指纹配置（自洽默认值由 FingerprintFactory 生成）
 * ===================================================================================*/
@Entity(
    tableName = "environment",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["groupId"]),
        Index(value = ["status"]),
    ],
)
data class Environment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupId: Long? = null,
    /** 项目名称 / 用途备注 */
    val project: String = "",
    /** dev / test / prod */
    val stage: String = "test",
    /** 版本标记 */
    val versionTag: String = "",
    /** 用例备注 */
    val remark: String = "",
    /** 逗号分隔标签，用于搜索筛选 */
    val tags: String = "",
    val profilePath: String = "",
    val proxyId: Long? = null,
    val fingerprintSnapshotId: Long? = null,
    /** 当前指纹配置 JSON */
    val fingerprintJson: String = "",
    /** RUNNING / STOPPED / STARTING / ERROR */
    val status: String = STATUS_STOPPED,
    /** Marionette 开关与端口（0=随机分配） */
    val marionetteEnabled: Boolean = false,
    val marionettePort: Int = 0,
    /** 单环境 Hosts 劫持开关 */
    val hostsHijackEnabled: Boolean = false,
    /** HAR 录制开关 */
    val harRecording: Boolean = false,
    /** 后台保活开关 */
    val keepAlive: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    /** 最近一次启动时间 */
    val lastStartTime: Long? = null,
) {
    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_STOPPED = "STOPPED"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_ERROR = "ERROR"
    }
}

/* =====================================================================================
 * 账号台账（密码 AES-GCM 加密落盘）
 * ===================================================================================*/
@Entity(
    tableName = "account",
    indices = [Index(value = ["envId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = Environment::class,
            parentColumns = ["id"],
            childColumns = ["envId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val envId: Long,
    val username: String,
    /** AES-256-GCM 密文，格式 v1:<iv>:<ct>，绝不存明文 */
    val passwordEnc: String? = null,
    val remark: String = "",
    val owner: String = "",
    /** PENDING / ACTIVE / FROZEN */
    val status: String = STATUS_PENDING,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_FROZEN = "FROZEN"
    }
}

/* =====================================================================================
 * 指纹快照（一键还原整套指纹参数）
 * ===================================================================================*/
@Entity(
    tableName = "fingerprint_snapshot",
    indices = [Index(value = ["envId"])],
    foreignKeys = [
        ForeignKey(
            entity = Environment::class,
            parentColumns = ["id"],
            childColumns = ["envId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FingerprintSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val envId: Long,
    val name: String,
    val fingerprintJson: String,
    val remark: String = "",
    val createTime: Long = System.currentTimeMillis(),
)

/* =====================================================================================
 * 代理池
 * ===================================================================================*/
@Entity(tableName = "proxy", indices = [Index(value = ["name"], unique = true)])
data class ProxyConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** HTTP / HTTPS / SOCKS5 */
    val type: String = TYPE_HTTP,
    val host: String,
    val port: Int,
    val username: String? = null,
    /** 代理账号密码（加密） */
    val passwordEnc: String? = null,
    /** 是否需要认证 */
    val authRequired: Boolean = false,
    /** OK / FAIL / UNKNOWN */
    val status: String = STATUS_UNKNOWN,
    val latencyMs: Int = -1,
    val lastCheckTime: Long? = null,
    val lastError: String? = null,
    /** 备注 / 地域标记 */
    val remark: String = "",
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_HTTP = "HTTP"
        const val TYPE_HTTPS = "HTTPS"
        const val TYPE_SOCKS5 = "SOCKS5"
        const val STATUS_OK = "OK"
        const val STATUS_FAIL = "FAIL"
        const val STATUS_UNKNOWN = "UNKNOWN"
    }
}

/* =====================================================================================
 * 审计日志：仅追加，由 SQLite TRIGGER 禁止 UPDATE / DELETE（见 DatabaseMigrations）
 * ===================================================================================*/
@Entity(tableName = "audit_log", indices = [Index(value = ["envId"]), Index(value = ["time"])])
data class AuditLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 操作时间 */
    val time: Long = System.currentTimeMillis(),
    val envId: Long? = null,
    val envName: String? = null,
    /** 操作人（设备本地用户标识） */
    val operator: String = "local",
    /** CREATE/OPEN/CLOSE/MODIFY_FINGERPRINT/SWITCH_PROXY/SNAPSHOT/DELETE/IMPORT/EXPORT/LOGIN/... */
    val action: String,
    val note: String = "",
    val detailJson: String? = null,
)

/* =====================================================================================
 * 主题配置（单行表，id 固定 = 1）
 * ===================================================================================*/
@Entity(tableName = "theme_config")
data class ThemeConfig(
    @PrimaryKey val id: Int = 1,
    /** LIGHT / DARK / SYSTEM */
    val mode: String = "SYSTEM",
    /** 自定义主色 HEX，如 #1E6FFF */
    val primaryHex: String = "#1E6FFF",
    /** 布局密度 COMPACT / DEFAULT */
    val density: String = "DEFAULT",
    val updateTime: Long = System.currentTimeMillis(),
) {
    companion object {
        const val MODE_LIGHT = "LIGHT"
        const val MODE_DARK = "DARK"
        const val MODE_SYSTEM = "SYSTEM"
        const val DENSITY_COMPACT = "COMPACT"
        const val DENSITY_DEFAULT = "DEFAULT"
        val DEFAULT = ThemeConfig()
    }
}

/* =====================================================================================
 * 单环境 Hosts 劫持规则
 * ===================================================================================*/
@Entity(
    tableName = "hosts_rule",
    indices = [Index(value = ["envId"])],
    foreignKeys = [
        ForeignKey(
            entity = Environment::class,
            parentColumns = ["id"],
            childColumns = ["envId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HostsRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val envId: Long,
    /** 域名，如 api.example.com 或 *.example.com */
    val domain: String,
    /** 目标 IP（v4/v6 均可） */
    val ip: String,
    val enabled: Boolean = true,
    val remark: String = "",
    val createTime: Long = System.currentTimeMillis(),
)

/* =====================================================================================
 * HAR 抓包记录
 * ===================================================================================*/
@Entity(
    tableName = "har_record",
    indices = [Index(value = ["envId"])],
    foreignKeys = [
        ForeignKey(
            entity = Environment::class,
            parentColumns = ["id"],
            childColumns = ["envId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HarRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val envId: Long,
    val name: String,
    /** filesDir/har/<envId>_<id>.har */
    val filePath: String = "",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val entryCount: Int = 0,
    val sizeBytes: Long = 0,
    val remark: String = "",
)

/* =====================================================================================
 * 弹窗 / 验证码事件（聚合面板数据源）
 * ===================================================================================*/
@Entity(
    tableName = "popup_event",
    indices = [Index(value = ["envId"]), Index(value = ["createTime"])],
    foreignKeys = [
        ForeignKey(
            entity = Environment::class,
            parentColumns = ["id"],
            childColumns = ["envId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PopupEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val envId: Long,
    val envName: String = "",
    /** ALERT / CONFIRM / PROMPT / BEFORE_UNLOAD / CAPTCHA / CUSTOM */
    val type: String,
    val title: String = "",
    val message: String = "",
    val url: String = "",
    /** 原始附加数据（如验证码 base64 截图路径） */
    val extraJson: String? = null,
    /** NEW / HANDLED / IGNORED */
    val status: String = STATUS_NEW,
    val createTime: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_NEW = "NEW"
        const val STATUS_HANDLED = "HANDLED"
        const val STATUS_IGNORED = "IGNORED"
    }
}

/* =====================================================================================
 * 自动备份记录
 * ===================================================================================*/
@Entity(tableName = "backup_record")
data class BackupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val sizeBytes: Long = 0,
    val envCount: Int = 0,
    val encrypted: Boolean = true,
    val createTime: Long = System.currentTimeMillis(),
)
