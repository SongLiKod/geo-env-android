package com.geoenv.browser.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 审计日志 DAO。
 *
 * 故意**不提供** update / delete 方法：审计表只追加。
 * 数据库层还有 TRIGGER 强制拦截 UPDATE/DELETE（见 [DatabaseMigrations]），
 * 即使有人绕过本 DAO 直接写 SQL 也会失败。
 */
@Dao
interface AuditLogDao {

    @Query("SELECT * FROM audit_log ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int = 1000): Flow<List<AuditLog>>

    @Query("SELECT * FROM audit_log ORDER BY time DESC LIMIT :limit")
    suspend fun recent(limit: Int = 1000): List<AuditLog>

    @Query(
        """SELECT * FROM audit_log
           WHERE (:query IS NULL
                  OR action LIKE '%'||:query||'%'
                  OR note LIKE '%'||:query||'%'
                  OR envName LIKE '%'||:query||'%')
             AND (:action IS NULL OR action = :action)
             AND (:envId IS NULL OR envId = :envId)
             AND (:from IS NULL OR time >= :from)
             AND (:to IS NULL OR time <= :to)
           ORDER BY time DESC LIMIT :limit"""
    )
    fun search(
        query: String? = null,
        action: String? = null,
        envId: Long? = null,
        from: Long? = null,
        to: Long? = null,
        limit: Int = 5000,
    ): Flow<List<AuditLog>>

    @Query(
        """SELECT * FROM audit_log
           WHERE (:query IS NULL
                  OR action LIKE '%'||:query||'%'
                  OR note LIKE '%'||:query||'%'
                  OR envName LIKE '%'||:query||'%')
             AND (:action IS NULL OR action = :action)
             AND (:envId IS NULL OR envId = :envId)
             AND (:from IS NULL OR time >= :from)
             AND (:to IS NULL OR time <= :to)
           ORDER BY time DESC"""
    )
    suspend fun searchOnce(
        query: String? = null,
        action: String? = null,
        envId: Long? = null,
        from: Long? = null,
        to: Long? = null,
    ): List<AuditLog>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AuditLog): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(logs: List<AuditLog>)

    @Query("SELECT COUNT(*) FROM audit_log")
    fun observeCount(): Flow<Int>

    @Query("SELECT DISTINCT action FROM audit_log ORDER BY action")
    suspend fun distinctActions(): List<String>
}

@Dao
interface ThemeConfigDao {
    @Query("SELECT * FROM theme_config WHERE id = 1")
    fun observe(): Flow<ThemeConfig?>

    @Query("SELECT * FROM theme_config WHERE id = 1")
    suspend fun get(): ThemeConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ThemeConfig)
}

@Dao
interface HostsRuleDao {
    @Query("SELECT * FROM hosts_rule WHERE envId = :envId ORDER BY domain")
    fun observeByEnv(envId: Long): Flow<List<HostsRule>>

    @Query("SELECT * FROM hosts_rule ORDER BY envId, domain")
    fun observeAll(): Flow<List<HostsRule>>

    @Query("SELECT * FROM hosts_rule WHERE envId = :envId AND enabled = 1")
    suspend fun getEnabledForEnv(envId: Long): List<HostsRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: HostsRule): Long

    @Query("DELETE FROM hosts_rule WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM hosts_rule WHERE envId = :envId")
    suspend fun deleteByEnv(envId: Long)

    @Query("SELECT COUNT(*) FROM hosts_rule")
    fun observeCount(): Flow<Int>
}

@Dao
interface HarDao {
    @Query("SELECT * FROM har_record WHERE envId = :envId ORDER BY startTime DESC")
    fun observeByEnv(envId: Long): Flow<List<HarRecord>>

    @Query("SELECT * FROM har_record ORDER BY startTime DESC")
    fun observeAll(): Flow<List<HarRecord>>

    @Query("SELECT * FROM har_record WHERE id = :id")
    suspend fun getById(id: Long): HarRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HarRecord): Long

    @Query("DELETE FROM har_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM har_record WHERE envId = :envId")
    suspend fun deleteByEnv(envId: Long)

    @Query(
        """UPDATE har_record SET endTime = :endTime, entryCount = :entries,
           sizeBytes = :size WHERE id = :id"""
    )
    suspend fun finish(id: Long, endTime: Long, entries: Int, size: Long)
}

@Dao
interface PopupDao {
    @Query(
        """SELECT * FROM popup_event
           WHERE (:status IS NULL OR status = :status)
           ORDER BY createTime DESC LIMIT :limit"""
    )
    fun observe(status: String? = null, limit: Int = 500): Flow<List<PopupEvent>>

    @Query("SELECT * FROM popup_event ORDER BY createTime DESC LIMIT :limit")
    fun observeAll(limit: Int = 500): Flow<List<PopupEvent>>

    @Query("SELECT * FROM popup_event WHERE id = :id")
    suspend fun getById(id: Long): PopupEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PopupEvent): Long

    @Query("UPDATE popup_event SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM popup_event WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM popup_event WHERE envId = :envId")
    suspend fun deleteByEnv(envId: Long)

    @Query("SELECT COUNT(*) FROM popup_event WHERE status = 'NEW'")
    fun observeNewCount(): Flow<Int>
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_record ORDER BY createTime DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<BackupRecord>>

    @Query("SELECT * FROM backup_record ORDER BY createTime DESC LIMIT :limit")
    suspend fun observeRecentOnce(limit: Int = 50): List<BackupRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BackupRecord): Long

    @Query("DELETE FROM backup_record WHERE id = :id")
    suspend fun deleteById(id: Long)
}
