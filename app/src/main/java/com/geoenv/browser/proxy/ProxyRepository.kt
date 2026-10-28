package com.geoenv.browser.proxy

import com.geoenv.browser.crypto.FieldCipher
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.db.AppDatabase
import com.geoenv.browser.db.ProxyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 代理仓储：代理池 CRUD、连通检测结果回写、批量分配、审计。
 */
class ProxyRepository(
    private val db: AppDatabase,
    private val audit: AuditRepository,
    private val fieldCipher: FieldCipher,
) {

    private val dao get() = db.proxyDao()

    fun observeAll(): Flow<List<ProxyConfig>> = dao.observeAll()

    fun observe(id: Long): Flow<ProxyConfig?> = dao.observeById(id)

    suspend fun getAll(): List<ProxyConfig> = dao.getAll()

    suspend fun get(id: Long): ProxyConfig? = dao.getById(id)

    suspend fun count(): Int = dao.observeCount().first()

    suspend fun add(proxy: ProxyConfig, password: String? = null): Long {
        require(proxy.host.isNotBlank()) { "代理地址不能为空" }
        require(proxy.port in 1..65535) { "端口必须在 1-65535" }
        val enc = password?.let { fieldCipher.encrypt(it, aad = "proxy:${proxy.name}") }
        val id = dao.insert(
            proxy.copy(
                passwordEnc = enc ?: proxy.passwordEnc,
                authRequired = password != null || proxy.authRequired,
                status = ProxyConfig.STATUS_UNKNOWN,
            )
        )
        audit.log(
            Action.SWITCH_PROXY, null, null,
            "新增代理 ${proxy.type}://${proxy.host}:${proxy.port}",
            mapOf("name" to proxy.name),
        )
        return id
    }

    suspend fun update(proxy: ProxyConfig, password: String? = null) {
        val enc = password?.let { fieldCipher.encrypt(it, aad = "proxy:${proxy.name}") }
        dao.update(
            proxy.copy(
                passwordEnc = enc ?: proxy.passwordEnc,
                authRequired = password != null || proxy.authRequired,
                updateTime = System.currentTimeMillis(),
            )
        )
        audit.log(
            Action.SWITCH_PROXY, null, null,
            "修改代理 ${proxy.name}",
            mapOf("host" to proxy.host, "port" to proxy.port),
        )
    }

    suspend fun delete(id: Long) {
        val p = dao.getById(id) ?: return
        val users = dao.countEnvsUsing(id)
        require(users == 0) { "该代理仍被 $users 个环境使用，请先解绑" }
        dao.deleteById(id)
        audit.log(Action.SWITCH_PROXY, null, null, "删除代理 ${p.name}")
    }

    suspend fun recordCheckResult(id: Long, ok: Boolean, latencyMs: Int, error: String? = null) {
        dao.updateCheckResult(
            id = id,
            status = if (ok) ProxyConfig.STATUS_OK else ProxyConfig.STATUS_FAIL,
            latency = if (ok) latencyMs else -1,
            error = error?.take(300),
        )
        val p = dao.getById(id)
        audit.log(
            Action.PROXY_CHECK, null, null,
            if (ok) "代理检测通过 ${p?.name} (${latencyMs}ms)"
            else "代理检测失败 ${p?.name}: $error",
        )
    }

    /** 批量给环境分配代理（代理池轮询）。 */
    suspend fun assignToEnvironments(envIds: List<Long>, proxyIds: List<Long>) {
        if (proxyIds.isEmpty()) error("代理池为空")
        envIds.forEachIndexed { index, envId ->
            val proxyId = proxyIds[index % proxyIds.size]
            db.envDao().updateProxy(envId, proxyId)
            val env = db.envDao().getById(envId)
            audit.log(Action.SWITCH_PROXY, envId, env?.name, "批量分配代理 id=$proxyId")
        }
    }

    /** 绑定单个环境到指定代理（代理池分配用）。 */
    suspend fun bindEnvironment(envId: Long, proxyId: Long?) {
        val env = db.envDao().getById(envId) ?: return
        if (env.proxyId == proxyId) return
        db.envDao().updateProxy(envId, proxyId)
        val name = dao.getById(proxyId ?: -1)?.name
        audit.log(
            Action.SWITCH_PROXY, envId, env.name,
            if (proxyId != null) "绑定代理 ${name ?: "id=$proxyId"}" else "解除代理绑定",
        )
    }

    /** 清空所有环境的代理绑定。 */
    suspend fun unbindAll() {
        db.envDao().getAll().forEach { env ->
            if (env.proxyId != null) {
                db.envDao().updateProxy(env.id, null)
                audit.log(Action.SWITCH_PROXY, env.id, env.name, "解除代理绑定")
            }
        }
    }

    /** 解密代理密码（供 Gecko 代理配置与 OkHttp 检测使用）。 */
    fun decryptPassword(proxy: ProxyConfig): String? {
        if (proxy.passwordEnc.isNullOrEmpty()) return null
        return runCatching { fieldCipher.decrypt(proxy.passwordEnc, aad = "proxy:${proxy.name}") }
            .getOrNull()
    }

    companion object {
        /** 转成 Gecko 代理偏好使用的 URI：socks5://user:pass@host:port */
        fun toUri(proxy: ProxyConfig, password: String?): String {
            val auth = if (proxy.authRequired && !proxy.username.isNullOrEmpty()) {
                "${proxy.username}:${password ?: ""}@"
            } else ""
            val scheme = when (proxy.type) {
                ProxyConfig.TYPE_SOCKS5 -> "socks5"
                ProxyConfig.TYPE_HTTPS -> "https"
                else -> "http"
            }
            return "$scheme://$auth${proxy.host}:${proxy.port}"
        }
    }
}
