package com.geoenv.browser.manager

import android.content.Context
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.Environment
import com.geoenv.browser.slot.SlotBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 环境业务编排（需求 3.1）。
 *
 * 职责：
 *  - **并发闸门**：默认并发上限 [DEFAULT_CONCURRENCY_LIMIT]（3），用户可在设置里
 *    放开到 [MAX_CONCURRENCY_LIMIT]（5，需求 4.1）；超限直接拒绝并提示；
 *  - **槽位分配**：每个运行中的环境独占一个槽位（1..5），启动前抢占、停止后释放；
 *  - **启停编排**：向 [SlotBridge] 下发 START/STOP，等待状态回推，写审计；
 *  - **状态镜像**：把槽位状态流 + 数据库状态合并成 [EnvUiState] 供 UI 订阅；
 *  - **事件提示**：并发超限、启动失败等以 [EnvEvent] 形式广播给 UI。
 *
 * 本类只在主进程使用（槽位进程不创建它）。
 */
@Singleton
class EnvManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val envRepo: EnvironmentRepository,
    private val audit: AuditRepository,
    val slotBridge: SlotBridge,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /* ----------------------------- 并发配置 ----------------------------- */

    private val prefs by lazy {
        context.getSharedPreferences("geoenv_settings", Context.MODE_PRIVATE)
    }

    private val _concurrencyLimit = MutableStateFlow(
        // 启动时从持久化恢复（默认 3，可放开到 5）
        if (prefs.getBoolean(KEY_CONCURRENCY_RELAXED, false)) {
            MAX_CONCURRENCY_LIMIT
        } else {
            DEFAULT_CONCURRENCY_LIMIT
        },
    )
    val concurrencyLimit: StateFlow<Int> = _concurrencyLimit.asStateFlow()

    /** 放开上限（最大 5），持久化 + 审计；true 表示从 3 放开到 5 */
    suspend fun setConcurrencyRelaxed(relaxed: Boolean) {
        _concurrencyLimit.value = if (relaxed) MAX_CONCURRENCY_LIMIT else DEFAULT_CONCURRENCY_LIMIT
        prefs.edit().putBoolean(KEY_CONCURRENCY_RELAXED, relaxed).apply()
        audit.log(
            action = Action.SETTINGS,
            note = "并发上限调整为 ${_concurrencyLimit.value}",
            detail = mapOf("limit" to _concurrencyLimit.value),
        )
    }

    /* ----------------------------- 事件 ----------------------------- */

    sealed interface EnvEvent {
        data class Message(val text: String, val isError: Boolean = false) : EnvEvent
        data class Started(val envId: Long, val slotIndex: Int) : EnvEvent
        data class Stopped(val envId: Long, val slotIndex: Int) : EnvEvent
    }

    private val _events = MutableSharedFlow<EnvEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<EnvEvent> = _events.asSharedFlow()

    private fun emit(e: EnvEvent) {
        _events.tryEmit(e)
    }

    /* ----------------------------- 槽位状态 ----------------------------- */

    init {
        // 槽位状态 → 数据库状态同步（保证"环境关闭后列表状态正确"）
        scope.launch {
            slotBridge.states.collect { map ->
                map.values.forEach { st ->
                    val envId = st.envId ?: return@forEach
                    if (st.status == Environment.STATUS_RUNNING ||
                        st.status == Environment.STATUS_STOPPED ||
                        st.status == Environment.STATUS_ERROR
                    ) {
                        runCatching {
                            envRepo.updateStatus(envId, st.status)
                        }.onFailure { Timber.w(it, "sync env status failed") }
                    }
                }
            }
        }
    }

    /** 环境占用的槽位（1-based），未运行返回 null */
    fun slotOf(envId: Long): Int? = slotBridge.slotOf(envId)

    /** 当前运行数量 */
    fun runningCount(): Int = slotBridge.runningCount()

    /** 是否还能启动更多环境 */
    fun canStartMore(): Boolean = runningCount() < _concurrencyLimit.value

    /* ----------------------------- 启停 ----------------------------- */

    /**
     * 启动环境。
     *
     * @return 成功时返回分配的槽位号，失败返回 null（原因经 [EnvEvent.Message] 发出）
     */
    suspend fun start(envId: Long): Int? {
        val env = envRepo.getEnvironment(envId) ?: run {
            emit(EnvEvent.Message("环境不存在", isError = true)); return null
        }

        // 已在运行 → 直接返回现有槽位
        slotBridge.slotOf(envId)?.let { return it }

        if (runningCount() >= _concurrencyLimit.value) {
            val msg = "并发环境已达上限 ${_concurrencyLimit.value} 个，请先关闭闲置环境（设置中可放开到 5）"
            audit.log(Action.ERROR, envId, env.name, msg)
            emit(EnvEvent.Message(msg, isError = true))
            return null
        }

        val slot = allocateSlot() ?: run {
            emit(EnvEvent.Message("没有可用槽位（5 个槽位均被占用）", isError = true))
            return null
        }

        // 预占槽位（allocateSlot 内已原子加入 _reserved），避免并发启动竞态
        try {
            slotBridge.mergeState(
                slot,
                JSONObject()
                    .put("status", Environment.STATUS_STARTING)
                    .put("envId", envId)
                    .put("envName", env.name),
            )
            SlotBridge.startSlotService(context, slot, envId)
            // 等待槽位真正 RUNNING（最多 15s；Gecko 冷启动要求 ≤3s，留余量）
            val ok = waitForStatus(envId, Environment.STATUS_RUNNING, 15_000L)
            if (ok) {
                audit.log(Action.OPEN, envId, env.name, "启动环境（槽位 $slot）")
                emit(EnvEvent.Started(envId, slot))
            } else {
                val err = slotBridge.getState(slot).lastError
                // 关键：超时必须把槽位置为 ERROR 并停止服务，否则该槽位会
                // 永久停留在 STARTING（allocateSlot 视 STARTING 为占用 → 槽位泄漏）
                slotBridge.mergeState(
                    slot,
                    JSONObject()
                        .put("status", Environment.STATUS_ERROR)
                        .put("envId", envId)
                        .put("error", err ?: "start_timeout"),
                )
                SlotBridge.stopSlotService(context, slot)
                audit.log(Action.ERROR, envId, env.name, "启动超时：${err ?: "unknown"}")
                emit(EnvEvent.Message("环境启动超时：${err ?: "请检查内存与配置"}", isError = true))
            }
            return slot
        } finally {
            _reserved.remove(slot)
        }
    }

    /** 停止环境（释放槽位） */
    suspend fun stop(envId: Long) {
        val slot = slotBridge.slotOf(envId) ?: run {
            // 可能处于 STARTING：兜底按状态查找
            slotBridge.states.value.values.firstOrNull {
                it.envId == envId && it.status == Environment.STATUS_STARTING
            }?.slotIndex
        }
        if (slot == null) {
            emit(EnvEvent.Message("环境未在运行"))
            return
        }
        val name = envRepo.getEnvironment(envId)?.name ?: ""
        // 先标记停止，UI 立即反馈
        slotBridge.mergeState(slot, JSONObject().put("status", Environment.STATUS_STOPPED).put("envId", envId))
        envRepo.updateStatus(envId, Environment.STATUS_STOPPED)
        SlotBridge.stopSlotService(context, slot)
        audit.log(Action.CLOSE, envId, name, "停止环境（槽位 $slot）")
        emit(EnvEvent.Stopped(envId, slot))
    }

    /** 停止全部 */
    suspend fun stopAll() {
        slotBridge.runningSlots().forEach { st -> st.envId?.let { stop(it) } }
    }

    /* ----------------------------- 界面状态 ----------------------------- */

    data class EnvUiState(
        val env: Environment,
        val slotIndex: Int? = null,
        val running: Boolean = false,
        val marionettePort: Int = 0,
        val harRecording: Boolean = false,
        val accountUsername: String? = null,
        val proxyName: String? = null,
        val groupName: String? = null,
    )

    /* ----------------------------- 内部 ----------------------------- */

    /** 已被保留（启动中）但状态尚未回推的槽位 */
    private val _reserved = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * 分配并**原子预占**一个空闲槽位（失败返回 null）。
     *
     * 在同一临界区内完成「找空闲槽 + 加入 _reserved」，避免两个并发 start()
     * 各自看到同一空闲槽位而重复分配（竞态）。
     */
    private fun allocateSlot(): Int? = synchronized(_reserved) {
        val occupied = slotBridge.states.value.values
            .filter {
                it.status == Environment.STATUS_RUNNING ||
                    it.status == Environment.STATUS_STARTING ||
                    it.status == Environment.STATUS_ERROR
            }
            .map { it.slotIndex }
            .toSet() + _reserved
        val slot = (1..SlotBridge.SLOTS).firstOrNull { it !in occupied } ?: return null
        _reserved.add(slot)
        slot
    }

    private suspend fun waitForStatus(envId: Long, target: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val running = slotBridge.states.value.values.any {
                it.envId == envId && it.status == target
            }
            if (running) return true
            delay(250L)
        }
        return false
    }

    companion object {
        /** 需求 4.1：默认并发上限 3 */
        const val DEFAULT_CONCURRENCY_LIMIT = 3

        /** 需求 4.1：上限可放开到 5（对应 5 个槽位进程） */
        const val MAX_CONCURRENCY_LIMIT = 5

        /** 并发上限持久化键（存于 geoenv_settings prefs） */
        private const val KEY_CONCURRENCY_RELAXED = "concurrency_relaxed"
    }
}
