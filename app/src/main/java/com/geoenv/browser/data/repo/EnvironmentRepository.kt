package com.geoenv.browser.data.repo

import android.content.Context
import com.geoenv.browser.crypto.FieldCipher
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.db.Account
import com.geoenv.browser.db.AppDatabase
import com.geoenv.browser.db.Environment
import com.geoenv.browser.db.EnvGroup
import com.geoenv.browser.db.FingerprintSnapshot
import com.geoenv.browser.db.HostsRule
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 环境（含分组 / 账号 / 快照 / Hosts 规则）仓储。
 *
 * 职责：
 *  - 环境 CRUD、克隆、状态流转；
 *  - 独立 Profile 目录的分配与销毁（filesDir/profiles/env_<id>）；
 *  - 账号密码字段级加解密（AES-256-GCM，绝不落明文）；
 *  - 所有敏感操作写审计。
 */
class EnvironmentRepository(
    private val db: AppDatabase,
    private val fieldCipher: FieldCipher,
    private val audit: AuditRepository,
    private val context: Context? = null,
) {

    private val envDao get() = db.envDao()
    private val groupDao get() = db.groupDao()
    private val accountDao get() = db.accountDao()
    private val snapshotDao get() = db.snapshotDao()
    private val hostsDao get() = db.hostsDao()

    // ------------------------------------------------------------------ 环境

    fun observeEnvironments(): Flow<List<Environment>> = envDao.observeAll()

    fun observeEnvironment(id: Long): Flow<Environment?> = envDao.observeById(id)

    fun observeRunningCount(): Flow<Int> = envDao.observeRunningCount()

    suspend fun getEnvironment(id: Long): Environment? = envDao.getById(id)

    suspend fun getEnvironmentByName(name: String): Environment? = envDao.getByName(name)

    suspend fun listEnvironments(): List<Environment> = envDao.getAll()

    fun search(
        query: String? = null,
        groupId: Long? = null,
        stage: String? = null,
        status: String? = null,
    ): Flow<List<Environment>> = envDao.search(query, groupId, stage, status)

    /**
     * 新建环境：分配独立 Profile 目录 + 生成自洽默认指纹。
     * 幂等：同名环境抛 IllegalArgumentException。
     */
    suspend fun createEnvironment(
        env: Environment,
        defaultFingerprintJson: String,
    ): Long {
        require(env.name.isNotBlank()) { "环境名称不能为空" }
        require(envDao.getByName(env.name) == null) { "环境名称已存在：${env.name}" }

        val id = envDao.insert(
            env.copy(
                profilePath = "",
                fingerprintJson = defaultFingerprintJson,
                status = Environment.STATUS_STOPPED,
            )
        )
        // Profile 目录在插入拿到 id 之后才能确定路径
        val profilePath = profileDir(id).absolutePath
        envDao.update(envDao.getById(id)!!.copy(id = id, profilePath = profilePath))
        ensureProfileDir(id)

        audit.log(
            action = Action.CREATE,
            envId = id,
            envName = env.name,
            note = "新建环境，profile=$profilePath",
        )
        return id
    }

    suspend fun updateEnvironment(env: Environment) {
        envDao.update(env.copy(updateTime = System.currentTimeMillis()))
    }

    suspend fun renameEnvironment(id: Long, name: String) {
        require(name.isNotBlank()) { "环境名称不能为空" }
        val dup = envDao.getByName(name)
        require(dup == null || dup.id != id) { "环境名称已存在：$name" }
        val old = envDao.getById(id) ?: return
        envDao.update(old.copy(name = name, updateTime = System.currentTimeMillis()))
        audit.log(Action.MODIFY_FINGERPRINT, id, name, "重命名环境 ${old.name} -> $name")
    }

    suspend fun deleteEnvironment(id: Long) {
        val env = envDao.getById(id) ?: return
        if (env.status == Environment.STATUS_RUNNING) {
            error("请先关闭环境再删除")
        }
        envDao.deleteById(id)
        deleteProfileDir(id)
        audit.log(Action.DELETE, id, env.name, "删除环境及其 profile")
    }

    /** 克隆：复制配置 + 指纹 + 账号 + 快照 + Hosts 规则，分配全新 profile 目录。 */
    suspend fun cloneEnvironment(sourceId: Long, newName: String): Long {
        val src = envDao.getById(sourceId) ?: error("源环境不存在")
        require(envDao.getByName(newName) == null) { "环境名称已存在：$newName" }

        val newId = envDao.insert(
            src.copy(
                id = 0,
                name = newName,
                profilePath = "",
                status = Environment.STATUS_STOPPED,
                lastStartTime = null,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis(),
            )
        )
        val profilePath = profileDir(newId).absolutePath
        envDao.update(envDao.getById(newId)!!.copy(profilePath = profilePath))
        ensureProfileDir(newId)

        // 账号
        accountDao.getByEnv(sourceId)?.let { acc ->
            accountDao.insert(acc.copy(id = 0, envId = newId))
        }
        // 快照
        snapshotDao.getByEnvOnce(sourceId).forEach { snap ->
            snapshotDao.insert(snap.copy(id = 0, envId = newId))
        }
        // Hosts 规则
        hostsDao.getEnabledForEnv(sourceId).forEach { rule ->
            hostsDao.insert(rule.copy(id = 0, envId = newId))
        }

        audit.log(Action.CLONE, newId, newName, "克隆自环境 ${src.name}(id=$sourceId)")
        return newId
    }

    suspend fun updateStatus(id: Long, status: String) {
        envDao.updateStatus(id, status)
    }

    suspend fun setProxy(id: Long, proxyId: Long?) {
        envDao.updateProxy(id, proxyId)
    }

    suspend fun setMarionette(id: Long, enabled: Boolean, port: Int) {
        val env = envDao.getById(id) ?: return
        envDao.update(
            env.copy(
                marionetteEnabled = enabled,
                marionettePort = port,
                updateTime = System.currentTimeMillis(),
            )
        )
        audit.log(Action.MARIONETTE, id, env.name, "Marionette enabled=$enabled port=$port")
    }

    // ------------------------------------------------------------------ 指纹

    suspend fun updateFingerprint(id: Long, json: String) {
        val env = envDao.getById(id) ?: return
        envDao.updateFingerprint(id, json)
        audit.log(
            Action.MODIFY_FINGERPRINT,
            id,
            env.name,
            "修改指纹配置",
            detail = mapOf("length" to json.length),
        )
    }

    // ------------------------------------------------------------------ 快照

    suspend fun saveSnapshot(envId: Long, name: String, fingerprintJson: String, remark: String = ""): Long {
        val env = envDao.getById(envId) ?: error("环境不存在")
        val id = snapshotDao.insert(
            FingerprintSnapshot(envId = envId, name = name, fingerprintJson = fingerprintJson, remark = remark)
        )
        envDao.update(env.copy(fingerprintSnapshotId = id, updateTime = System.currentTimeMillis()))
        audit.log(Action.SNAPSHOT, envId, env.name, "保存指纹快照 [$name]")
        return id
    }

    suspend fun restoreSnapshot(snapshotId: Long) {
        val snap = snapshotDao.getById(snapshotId) ?: error("快照不存在")
        val env = envDao.getById(snap.envId) ?: error("环境不存在")
        envDao.updateFingerprint(snap.envId, snap.fingerprintJson)
        envDao.update(
            env.copy(
                fingerprintJson = snap.fingerprintJson,
                fingerprintSnapshotId = snap.id,
                updateTime = System.currentTimeMillis(),
            )
        )
        audit.log(Action.RESTORE_SNAPSHOT, snap.envId, env.name, "还原指纹快照 [${snap.name}]")
    }

    suspend fun deleteSnapshot(snapshotId: Long) {
        val snap = snapshotDao.getById(snapshotId) ?: return
        snapshotDao.deleteById(snapshotId)
        audit.log(Action.SNAPSHOT, snap.envId, null, "删除指纹快照 [${snap.name}]")
    }

    suspend fun listSnapshots(envId: Long): List<FingerprintSnapshot> = snapshotDao.getByEnvOnce(envId)

    // ------------------------------------------------------------------ 账号

    fun observeAccount(envId: Long): Flow<Account?> = accountDao.observeByEnv(envId)

    suspend fun getAccount(envId: Long): Account? = accountDao.getByEnv(envId)

    /** 保存账号：密码字段加密后落盘。 */
    suspend fun saveAccount(account: Account, plainPassword: String?) {
        val enc = plainPassword?.let { fieldCipher.encrypt(it, aad = "account:${account.envId}") }
        val env = envDao.getById(account.envId)
        val existing = accountDao.getByEnv(account.envId)
        if (existing != null) {
            accountDao.update(
                existing.copy(
                    username = account.username,
                    passwordEnc = enc ?: existing.passwordEnc,
                    remark = account.remark,
                    owner = account.owner,
                    status = account.status,
                    updateTime = System.currentTimeMillis(),
                )
            )
        } else {
            accountDao.insert(account.copy(passwordEnc = enc))
        }
        audit.log(
            Action.MODIFY_ACCOUNT,
            account.envId,
            env?.name,
            "保存账号 ${account.username}",
            detail = mapOf("status" to account.status),
        )
    }

    /** 读取账号明文密码（仅 UI 解密展示用，不写日志）。 */
    suspend fun getAccountPassword(envId: Long): String? {
        val acc = accountDao.getByEnv(envId) ?: return null
        return runCatching {
            fieldCipher.decrypt(acc.passwordEnc, aad = "account:$envId")
        }.getOrNull()
    }

    suspend fun deleteAccount(envId: Long) {
        accountDao.deleteByEnv(envId)
        audit.log(Action.MODIFY_ACCOUNT, envId, null, "删除账号台账")
    }

    // ------------------------------------------------------------------ 分组

    fun observeGroups(): Flow<List<EnvGroup>> = groupDao.observeAll()

    suspend fun listGroups(): List<EnvGroup> = groupDao.getAll()

    suspend fun createGroup(name: String, color: String = "#5B8DEF"): Long {
        val id = groupDao.insert(EnvGroup(name = name, color = color))
        audit.log(Action.CREATE, null, null, "新建分组 [$name]")
        return id
    }

    suspend fun updateGroup(group: EnvGroup) = groupDao.update(group)

    suspend fun deleteGroup(id: Long) {
        // 分组删除后环境的 groupId 置空（环境保留）
        envDao.getAll().filter { it.groupId == id }.forEach { env ->
            envDao.update(env.copy(groupId = null))
        }
        groupDao.deleteById(id)
        audit.log(Action.DELETE, id, null, "删除分组")
    }

    // ------------------------------------------------------------------ Hosts

    fun observeHostsRules(envId: Long): Flow<List<HostsRule>> = hostsDao.observeByEnv(envId)

    fun observeAllHostsRules(): Flow<List<HostsRule>> = hostsDao.observeAll()

    suspend fun addHostsRule(rule: HostsRule): Long {
        val id = hostsDao.insert(rule)
        val env = envDao.getById(rule.envId)
        audit.log(Action.HOSTS, rule.envId, env?.name, "新增 Hosts 规则 ${rule.domain} -> ${rule.ip}")
        return id
    }

    suspend fun deleteHostsRule(id: Long) = hostsDao.deleteById(id)

    suspend fun hostsRulesFor(envId: Long): List<HostsRule> = hostsDao.getEnabledForEnv(envId)

    // ------------------------------------------------------------------ Profile 目录

    /** profile 根目录：filesDir/profiles/ */
    fun profileRoot(): File {
        val ctx = context ?: error("EnvironmentRepository 需要 Context 才能访问 profile 目录")
        return File(ctx.filesDir, "profiles")
    }

    /** 单环境 profile 目录：filesDir/profiles/env_<id> */
    fun profileDir(envId: Long): File = File(profileRoot(), "env_$envId")

    fun ensureProfileDir(envId: Long): File {
        val dir = profileDir(envId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 删除环境时清理 profile（cookie / localStorage / 缓存全部销毁）。 */
    fun deleteProfileDir(envId: Long) {
        runCatching { profileDir(envId).deleteRecursively() }
            .onFailure { android.util.Log.w("GeoEnv", "删除 profile 失败 env=$envId", it) }
    }

    suspend fun listProfileDirs(): List<File> =
        context?.let { File(it.filesDir, "profiles").listFiles()?.filter { it.isDirectory } } ?: emptyList()
}
