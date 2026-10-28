package com.geoenv.browser.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvironmentDao {

    @Query("SELECT * FROM environment ORDER BY updateTime DESC")
    fun observeAll(): Flow<List<Environment>>

    @Query("SELECT * FROM environment ORDER BY updateTime DESC")
    suspend fun getAll(): List<Environment>

    @Query("SELECT * FROM environment WHERE id = :id")
    suspend fun getById(id: Long): Environment?

    @Query("SELECT * FROM environment WHERE id = :id")
    fun observeById(id: Long): Flow<Environment?>

    @Query("SELECT * FROM environment WHERE status = 'RUNNING'")
    suspend fun getRunning(): List<Environment>

    @Query("SELECT COUNT(*) FROM environment WHERE status = 'RUNNING'")
    fun observeRunningCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM environment")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(env: Environment): Long

    @Update
    suspend fun update(env: Environment)

    @Delete
    suspend fun delete(env: Environment)

    @Query("DELETE FROM environment WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM environment WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Environment?

    @Query(
        """SELECT * FROM environment
           WHERE (:query IS NULL OR name LIKE '%'||:query||'%'
                  OR project LIKE '%'||:query||'%'
                  OR tags LIKE '%'||:query||'%'
                  OR remark LIKE '%'||:query||'%')
             AND (:groupId IS NULL OR groupId = :groupId)
             AND (:stage IS NULL OR stage = :stage)
             AND (:status IS NULL OR status = :status)
           ORDER BY updateTime DESC"""
    )
    fun search(
        query: String? = null,
        groupId: Long? = null,
        stage: String? = null,
        status: String? = null,
    ): Flow<List<Environment>>

    @Query("UPDATE environment SET status = :status, updateTime = :time WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE environment SET fingerprintJson = :json, updateTime = :time WHERE id = :id")
    suspend fun updateFingerprint(id: Long, json: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE environment SET proxyId = :proxyId, updateTime = :time WHERE id = :id")
    suspend fun updateProxy(id: Long, proxyId: Long?, time: Long = System.currentTimeMillis())
}

@Dao
interface EnvGroupDao {
    @Query("SELECT * FROM env_group ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<EnvGroup>>

    @Query("SELECT * FROM env_group ORDER BY sortOrder, name")
    suspend fun getAll(): List<EnvGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: EnvGroup): Long

    @Update
    suspend fun update(group: EnvGroup)

    @Delete
    suspend fun delete(group: EnvGroup)

    @Query("DELETE FROM env_group WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM env_group WHERE id = :id")
    suspend fun getById(id: Long): EnvGroup?
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY updateTime DESC")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE envId = :envId LIMIT 1")
    fun observeByEnv(envId: Long): Flow<Account?>

    @Query("SELECT * FROM account WHERE envId = :envId LIMIT 1")
    suspend fun getByEnv(envId: Long): Account?

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun getById(id: Long): Account?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("DELETE FROM account WHERE envId = :envId")
    suspend fun deleteByEnv(envId: Long)

    @Query("SELECT COUNT(*) FROM account")
    fun observeCount(): Flow<Int>
}

@Dao
interface FingerprintSnapshotDao {
    @Query("SELECT * FROM fingerprint_snapshot WHERE envId = :envId ORDER BY createTime DESC")
    fun observeByEnv(envId: Long): Flow<List<FingerprintSnapshot>>

    @Query("SELECT * FROM fingerprint_snapshot ORDER BY createTime DESC")
    fun observeAll(): Flow<List<FingerprintSnapshot>>

    @Query("SELECT * FROM fingerprint_snapshot WHERE id = :id")
    suspend fun getById(id: Long): FingerprintSnapshot?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: FingerprintSnapshot): Long

    @Delete
    suspend fun delete(snapshot: FingerprintSnapshot)

    @Query("DELETE FROM fingerprint_snapshot WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM fingerprint_snapshot WHERE envId = :envId ORDER BY createTime DESC")
    suspend fun getByEnvOnce(envId: Long): List<FingerprintSnapshot>

    @Query("SELECT COUNT(*) FROM fingerprint_snapshot")
    fun observeCount(): Flow<Int>
}

@Dao
interface ProxyDao {
    @Query("SELECT * FROM proxy ORDER BY updateTime DESC")
    fun observeAll(): Flow<List<ProxyConfig>>

    @Query("SELECT * FROM proxy ORDER BY updateTime DESC")
    suspend fun getAll(): List<ProxyConfig>

    @Query("SELECT * FROM proxy WHERE id = :id")
    suspend fun getById(id: Long): ProxyConfig?

    @Query("SELECT * FROM proxy WHERE id = :id")
    fun observeById(id: Long): Flow<ProxyConfig?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(proxy: ProxyConfig): Long

    @Update
    suspend fun update(proxy: ProxyConfig)

    @Delete
    suspend fun delete(proxy: ProxyConfig)

    @Query("DELETE FROM proxy WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM proxy")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM environment WHERE proxyId = :proxyId")
    suspend fun countEnvsUsing(proxyId: Long): Int

    @Query("SELECT * FROM proxy WHERE status = 'OK'")
    suspend fun getHealthy(): List<ProxyConfig>

    @Query(
        """UPDATE proxy SET status = :status, latencyMs = :latency, lastCheckTime = :time,
           lastError = :error, updateTime = :time WHERE id = :id"""
    )
    suspend fun updateCheckResult(
        id: Long,
        status: String,
        latency: Int,
        time: Long = System.currentTimeMillis(),
        error: String? = null,
    )
}
