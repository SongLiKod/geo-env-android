package com.geoenv.browser.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移与结构钩子。
 *
 * 规范（docs/geo-env-tech.md §9）：数据库变更必须写迁移脚本。
 * V1 为初始版本；后续版本在 [ALL] 追加。
 *
 * 审计日志"只追加"由三层保证：
 *  1. DAO 层不提供 update / delete 方法；
 *  2. 数据库层 TRIGGER 拦截 UPDATE / DELETE（见 [installAuditTriggers]）；
 *  3. UI 层不暴露删除入口。
 */
object DatabaseMigrations {

    val ALL: Array<Migration> = arrayOf()

    /**
     * 建库/升级后安装结构钩子。
     *
     * Room 的 Migration 不会在 version 不变时执行，因此触发器的安装放在
     * `onCreate` / `onOpen` 回调中，保证每次打开数据库都处于"只追加"保护下。
     */
    fun installHooks(db: SupportSQLiteDatabase) {
        installAuditTriggers(db)
    }

    /**
     * 审计日志只追加触发器：
     *  - 禁止 UPDATE；
     *  - 禁止 DELETE。
     * 任何尝试都会抛 SQLiteException，保证审计链不可篡改。
     */
    fun installAuditTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS audit_log_no_update
            BEFORE UPDATE ON audit_log
            BEGIN
                SELECT RAISE(ABORT, '审计日志只追加，禁止修改');
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS audit_log_no_delete
            BEFORE DELETE ON audit_log
            BEGIN
                SELECT RAISE(ABORT, '审计日志只追加，禁止删除');
            END
            """.trimIndent()
        )
    }

    /** 供未来升级使用的示例模板（勿删除注释，便于对照规范）。 */
    @Suppress("unused")
    private val TEMPLATE_MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE environment ADD COLUMN newColumn TEXT DEFAULT ''")
        }
    }
}
