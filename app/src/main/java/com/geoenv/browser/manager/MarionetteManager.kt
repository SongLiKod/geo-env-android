package com.geoenv.browser.manager

import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.slot.SlotBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marionette 自动化管理（需求 3.5）。
 *
 * 设计要点：
 *  - **每实例独立端口**：Marionette 端口是 Gecko *进程级* 配置（`marionette.port` pref +
 *    `MOZ_MARIONETTE` 环境变量），因此由 [SlotEngine][com.geoenv.browser.slot.SlotEngine]
 *    在创建 runtime 前写入 DebugConfig YAML；变更需重启环境生效。
 *  - **端口分配**：默认随机空闲端口；也支持用户自定义（校验 1-65535、未被占用、未被其它环境占用）。
 *  - **仅本机回环**：Gecko 的 `nsIServerSocket` 以 `LoopbackOnly` 打开，从内核层面保证
 *    不对外网暴露；这里再做一层端口占用与回环绑定校验。
 *  - **开关**：关闭时不写 `MOZ_MARIONETTE`，端口置 0。
 *
 * 参见 `docs/marionette-sdk-guide.md`。
 */
@Singleton
class MarionetteManager @Inject constructor(
    private val envRepo: EnvironmentRepository,
    private val audit: AuditRepository,
    private val slotBridge: SlotBridge,
) {

    /* ----------------------------- 端口分配 ----------------------------- */

    /** 申请一个可用的回环端口（未被本机、未被其它环境占用） */
    suspend fun allocatePort(exclude: Set<Int> = emptySet()): Int = withContext(Dispatchers.IO) {
        val used = usedPorts() + exclude
        var attempts = 0
        while (attempts++ < 64) {
            // 动态端口区间内随机取，避免与常用服务冲突
            val candidate = (MIN_PORT..MAX_PORT).random()
            if (candidate in used) continue
            if (isLoopbackBindable(candidate).not()) continue
            return@withContext candidate
        }
        // 兜底：让内核分配
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            .takeIf { it !in used } ?: error("无法分配 Marionette 端口")
    }

    /** 校验自定义端口是否可用（回环可绑定、且未被其它环境占用） */
    suspend fun validateCustomPort(port: Long, selfEnvId: Long? = null): PortCheck =
        withContext(Dispatchers.IO) {
            when {
                port !in 1..65535 -> PortCheck.Invalid("端口必须在 1-65535")
                !isLoopbackBindable(port.toInt()) ->
                    PortCheck.Invalid("端口 ${port} 已被本机进程占用")
                usedPortsExcluding(selfEnvId).contains(port.toInt()) ->
                    PortCheck.Invalid("端口 ${port} 已被其它环境占用")
                else -> PortCheck.Ok
            }
        }

    sealed interface PortCheck {
        data object Ok : PortCheck
        data class Invalid(val reason: String) : PortCheck
    }

    /** 所有已配置（非 0）的 Marionette 端口 */
    private suspend fun usedPorts(): Set<Int> =
        envRepo.listEnvironments()
            .filter { it.marionetteEnabled && it.marionettePort > 0 }
            .map { it.marionettePort }
            .toSet()

    private suspend fun usedPortsExcluding(envId: Long?): Set<Int> =
        envRepo.listEnvironments()
            .filter { it.marionetteEnabled && it.marionettePort > 0 && it.id != envId }
            .map { it.marionettePort }
            .toSet()

    private fun isLoopbackBindable(port: Int): Boolean = try {
        // 只测回环：即便 Gecko 用 LoopbackOnly，也确保本机上没有别的进程占用
        ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).use { true }
    } catch (_: Throwable) {
        false
    }

    /* ----------------------------- 开关 / 端口 ----------------------------- */

    /**
     * 设置环境的 Marionette 开关与端口（持久化 + 审计）。
     * 变更为 Gecko 进程级配置，需重启环境生效（由调用方提示/触发）。
     *
     * @return 生效后的端口（关闭时为 0）
     */
    suspend fun configure(
        envId: Long,
        enabled: Boolean,
        requestedPort: Int = 0,
    ): Int {
        val env = envRepo.getEnvironment(envId) ?: error("环境不存在")
        val port = if (!enabled) {
            0
        } else if (requestedPort in 1..65535) {
            when (val check = validateCustomPort(requestedPort.toLong(), envId)) {
                PortCheck.Ok -> requestedPort
                is PortCheck.Invalid -> error(check.reason)
            }
        } else {
            allocatePort(exclude = usedPortsExcluding(envId))
        }

        envRepo.setMarionette(envId, enabled, port)
        val running = slotBridge.slotOf(envId) != null
        if (running) {
            audit.log(
                action = Action.MARIONETTE,
                envId = envId,
                envName = env.name,
                note = "Marionette 端口变更，环境需重启生效 port=$port",
            )
        }
        return port
    }

    /** 环境是否处于可被外部连接的状态（运行中且已开启） */
    fun endpointOf(envId: Long): MarionetteEndpoint? {
        val st = slotBridge.getState(slotBridge.slotOf(envId) ?: return null)
        if (!st.marionetteEnabled || st.marionettePort <= 0) return null
        if (st.status != com.geoenv.browser.db.Environment.STATUS_RUNNING) return null
        return MarionetteEndpoint(
            host = LOOPBACK,
            port = st.marionettePort,
            envId = envId,
            envName = st.envName ?: "",
        )
    }

    /** 全部运行中的 Marionette 端点（供 SDK 页展示连接串） */
    fun activeEndpoints(): List<MarionetteEndpoint> =
        slotBridge.runningSlots().mapNotNull { st ->
            val envId = st.envId ?: return@mapNotNull null
            if (!st.marionetteEnabled || st.marionettePort <= 0) return@mapNotNull null
            MarionetteEndpoint(
                host = LOOPBACK,
                port = st.marionettePort,
                envId = envId,
                envName = st.envName ?: "",
            )
        }

    data class MarionetteEndpoint(
        val host: String,
        val port: Int,
        val envId: Long,
        val envName: String,
    ) {
        /** Python 客户端示例：`Marionette('127.0.0.1', 2828)` */
        fun pythonSnippet(): String =
            "from marionette_driver.marionette import Marionette\n" +
                "client = Marionette('$host', $port)\n" +
                "client.start_session()\n" +
                "client.navigate('https://example.com')\n" +
                "print(client.get_url())"

        /** adb 端口转发（真机联调）：`adb forward tcp:$port tcp:$port` */
        fun adbForwardCommand(): String =
            "adb forward tcp:$port tcp:$port"
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"

        /** 动态端口随机选取区间（避开 0-1023 与常见固定端口） */
        private const val MIN_PORT = 20000
        private const val MAX_PORT = 60000

        /** 文档示例端口（新环境默认值参考） */
        const val DEFAULT_PORT_HINT = 2828
    }
}
