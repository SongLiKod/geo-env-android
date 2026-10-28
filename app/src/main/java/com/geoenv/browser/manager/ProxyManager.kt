package com.geoenv.browser.manager

import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.db.ProxyConfig
import com.geoenv.browser.proxy.ProxyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 代理管理（需求 3.4）：
 *  1. 单环境独立代理（HTTP / HTTPS / SOCKS5）——由 [com.geoenv.browser.slot.SlotEngine] 注入 Gecko；
 *  2. 连通性检测（OkHttp 走代理请求探测 URL，超时/非 2xx 记为失败并告警）；
 *  3. 代理池管理 + 批量分配 + 轮换。
 */
@Singleton
class ProxyManager @Inject constructor(
    private val proxyRepo: ProxyRepository,
    private val audit: AuditRepository,
) {

    /* ----------------------------- 连通检测 ----------------------------- */

    data class CheckResult(
        val proxyId: Long,
        val ok: Boolean,
        val latencyMs: Int,
        val error: String? = null,
        /** 经代理出口看到的 IP（用于确认"确实走了代理"） */
        val exitIp: String? = null,
    )

    private val _results = MutableSharedFlow<CheckResult>(extraBufferCapacity = 32)
    val results: SharedFlow<CheckResult> = _results.asSharedFlow()

    /** 检测单个代理。HTTP/HTTPS 走 OkHttp 正向代理，SOCKS5 走 JDK SOCKS 路由。 */
    suspend fun check(proxy: ProxyConfig, probeUrl: String = DEFAULT_PROBE): CheckResult =
        withContext(Dispatchers.IO) {
            val password = proxyRepo.decryptPassword(proxy)
            val start = System.currentTimeMillis()
            val result = try {
                val client = buildClient(proxy, password)
                val request = Request.Builder().url(probeUrl).get().build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val latency = (System.currentTimeMillis() - start).toInt()
                    if (resp.isSuccessful) {
                        CheckResult(
                            proxyId = proxy.id,
                            ok = true,
                            latencyMs = latency,
                            exitIp = extractIp(body),
                        )
                    } else {
                        CheckResult(
                            proxyId = proxy.id,
                            ok = false,
                            latencyMs = latency,
                            error = "HTTP ${resp.code}",
                        )
                    }
                }
            } catch (t: Throwable) {
                Timber.d(t, "proxy check failed %s:%d", proxy.host, proxy.port)
                CheckResult(
                    proxyId = proxy.id,
                    ok = false,
                    latencyMs = (System.currentTimeMillis() - start).toInt(),
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
            // 落库 + 审计 + 广播（recordCheckResult 内部已写审计）
            proxyRepo.recordCheckResult(proxy.id, result.ok, result.latencyMs, result.error)
            _results.tryEmit(result)
            result
        }

    /** 批量检测（每批 4 个并发），返回全部结果 */
    suspend fun checkAll(
        proxies: List<ProxyConfig>,
        probeUrl: String = DEFAULT_PROBE,
    ): List<CheckResult> = coroutineScope {
        proxies.chunked(4).flatMap { chunk ->
            chunk.map { p -> async { check(p, probeUrl) } }.awaitAll()
        }
    }

    private fun buildClient(proxy: ProxyConfig, password: String?): OkHttpClient {
        val type = if (proxy.type.equals(ProxyConfig.TYPE_SOCKS5, true)) Proxy.Type.SOCKS
        else Proxy.Type.HTTP
        val javaProxy = Proxy(type, InetSocketAddress(proxy.host, proxy.port))

        val builder = OkHttpClient.Builder()
            .proxy(javaProxy)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)

        // HTTP/HTTPS 上游代理认证：OkHttp 走 Proxy-Authorization；SOCKS 认证由 JVM 层处理
        if (!proxy.username.isNullOrBlank() && type == Proxy.Type.HTTP) {
            val cred = Credentials.basic(proxy.username, password ?: "")
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", cred)
                    .build()
            }
        }
        return builder.build()
    }

    private fun extractIp(body: String): String? =
        Regex("\"(?:query|ip)\"\\s*:\\s*\"([0-9a-fA-F.:]+)\"").find(body)?.groupValues?.get(1)
            ?: Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").find(body)?.value

    /* ----------------------------- 代理池 ----------------------------- */

    /**
     * 代理池批量分配。
     *
     * @param envIds   参与分配的环境
     * @param proxyIds 代理池（至少 1 个）
     * @param strategy [PoolStrategy.ROUND_ROBIN] 循环分配；
     *                 [PoolStrategy.STICKY] 环境序号固定绑定同一代理（出口稳定）
     * @return 环境 → 代理 的分配结果
     */
    enum class PoolStrategy { ROUND_ROBIN, STICKY }

    suspend fun assignPool(
        envIds: List<Long>,
        proxyIds: List<Long>,
        strategy: PoolStrategy = PoolStrategy.ROUND_ROBIN,
    ): Map<Long, Long> {
        require(proxyIds.isNotEmpty()) { "代理池为空" }
        require(envIds.isNotEmpty()) { "没有可分配的环境" }

        val mapping = LinkedHashMap<Long, Long>()
        envIds.forEachIndexed { index, envId ->
            val pid = when (strategy) {
                PoolStrategy.ROUND_ROBIN -> proxyIds[index % proxyIds.size]
                PoolStrategy.STICKY -> proxyIds[index.coerceAtMost(proxyIds.size - 1)]
            }
            mapping[envId] = pid
        }
        mapping.forEach { (envId, pid) -> proxyRepo.bindEnvironment(envId, pid) }

        audit.log(
            action = Action.SWITCH_PROXY,
            note = "代理池批量分配 ${mapping.size} 个环境 / ${proxyIds.size} 个代理（$strategy）",
            detail = mapOf(
                "envIds" to envIds,
                "proxyIds" to proxyIds,
                "strategy" to strategy.name,
            ),
        )
        return mapping
    }

    /** 把所有环境解绑代理 */
    suspend fun unbindAllEnvironments() {
        proxyRepo.unbindAll()
        audit.log(action = Action.SWITCH_PROXY, note = "解绑全部环境代理")
    }

    companion object {
        /**
         * 默认探测地址：IP 回显 + 代理标记，用于确认"确实走了代理"。
         * 内部工具；无外网环境时可在设置页替换为自建探测地址。
         */
        const val DEFAULT_PROBE =
            "http://ip-api.com/json?lang=zh-CN&fields=status,message,query,isp,proxy"

        private const val CONNECT_TIMEOUT_S = 8L
        private const val READ_TIMEOUT_S = 10L
    }
}
