package com.geoenv.browser.slot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.geoenv.browser.db.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主进程 <-> 槽位进程（:gecko1 ~ :gecko5）的 IPC 客户端。
 *
 * 协议：Messenger（`Message.what` = 命令码，`data["payload"]` = JSON 字符串，
 * `data["cbId"]` = 一次性回调 ID，槽位通过 replyTo Messenger 回包）。
 *
 * 槽位进程的 [SlotHostService] 持有 GeckoRuntime / GeckoSession / Marionette；
 * 主进程通过本类下发启动、停止、导航、截图、执行 JS、设置代理等命令，
 * 并接收状态推送以刷新环境列表。
 */
class SlotBridge(private val context: Context) {

    /** 槽位运行时状态（主进程侧镜像） */
    data class SlotState(
        val slotIndex: Int = 0,
        val envId: Long? = null,
        val envName: String? = null,
        val status: String = Environment.STATUS_STOPPED,
        val url: String = "",
        val title: String = "",
        val progress: Int = 0,
        val marionettePort: Int = 0,
        val marionetteEnabled: Boolean = false,
        val harRecording: Boolean = false,
        val lastError: String? = null,
        val updatedAt: Long = 0L,
    )

    private val _states = MutableStateFlow<Map<Int, SlotState>>(emptyMap())
    val states: StateFlow<Map<Int, SlotState>> = _states.asStateFlow()

    private val messengers = ConcurrentHashMap<Int, Messenger>()
    private val pendingCallbacks = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val outbox = ConcurrentHashMap<Int, ArrayDeque<() -> Unit>>()
    private val seq = AtomicInteger(0)

    /** 槽位进程回包用的 Messenger（本进程唯一，槽位按 cbId 分发） */
    private val replyMessenger = Messenger(
        object : android.os.Handler(android.os.Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val slotIndex = msg.what
                val bundle = msg.data ?: return
                val cbId = bundle.getInt("cbId", -1)
                val payload = runCatching { JSONObject(bundle.getString("payload") ?: "{}") }
                    .getOrDefault(JSONObject())
                if (cbId > 0) {
                    pendingCallbacks.remove(cbId)?.invoke(payload)
                }
                if (payload.has("status") || payload.has("envId")) {
                    mergeState(slotIndex, payload)
                }
            }
        }
    )

    private val connections = ConcurrentHashMap<Int, ServiceConnection>()

    /** 绑定指定槽位宿主服务（幂等） */
    fun bind(slotIndex: Int) {
        if (messengers.containsKey(slotIndex)) return
        if (connections.containsKey(slotIndex)) return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Timber.i("slot %d service connected", slotIndex)
                messengers[slotIndex] = Messenger(binder)
                // 发送握手，让槽位记住回复通道
                sendRaw(slotIndex, Cmd.HELLO, JSONObject().put("replyTo", slotIndex))
                // 冲刷待发队列
                outbox.remove(slotIndex)?.forEach { runCatching { it() } }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Timber.w("slot %d service disconnected", slotIndex)
                messengers.remove(slotIndex)
                connections.remove(slotIndex)
            }
        }
        connections[slotIndex] = conn
        val intent = Intent().setComponent(
            ComponentName(context.packageName, serviceClassName(slotIndex))
        )
        runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Timber.w(it, "bindService failed slot=%d", slotIndex)
            connections.remove(slotIndex)
        }
    }

    fun bindAll() = (1..SLOTS).forEach { bind(it) }

    fun unbindAll() {
        connections.forEach { (idx, conn) ->
            runCatching { context.unbindService(conn) }
                .onFailure { Timber.w(it, "unbind failed slot=%d", idx) }
        }
        connections.clear()
        messengers.clear()
    }

    /** 发送命令（未连接时入队，连接后自动冲刷） */
    fun send(
        slotIndex: Int,
        cmd: Int,
        payload: JSONObject = JSONObject(),
        callback: ((JSONObject) -> Unit)? = null,
    ) {
        val task = fun() {
            sendRaw(slotIndex, cmd, payload, callback)
        }
        if (messengers.containsKey(slotIndex)) {
            task()
        } else {
            bind(slotIndex)
            outbox.getOrPut(slotIndex) { ArrayDeque() }.add(task)
            // 兜底：3s 后仍未连接则回调失败
            if (callback != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (pendingCallbacks.values.any { it === callback }) {
                        pendingCallbacks.entries.removeIf { it.value === callback }
                        callback(JSONObject().put("ok", false).put("error", "connect_timeout"))
                    }
                }, 3000L)
            }
        }
    }

    private fun sendRaw(slotIndex: Int, cmd: Int, payload: JSONObject, callback: ((JSONObject) -> Unit)? = null) {
        val messenger = messengers[slotIndex] ?: run {
            callback?.invoke(JSONObject().put("ok", false).put("error", "slot_unavailable"))
            return
        }
        try {
            val msg = Message.obtain(null, cmd)
            val bundle = Bundle()
            bundle.putString("payload", payload.toString())
            bundle.putBinder("reply", replyMessenger.binder)
            if (callback != null) {
                val id = seq.incrementAndGet()
                pendingCallbacks[id] = callback
                bundle.putInt("cbId", id)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            Timber.w(e, "send failed slot=%d cmd=%d", slotIndex, cmd)
            callback?.invoke(JSONObject().put("ok", false).put("error", e.message ?: "remote"))
        }
    }

    /** 槽位主动推送状态 */
    fun mergeState(slotIndex: Int, json: JSONObject) {
        val cur = _states.value[slotIndex] ?: SlotState(slotIndex = slotIndex)
        val envId = if (json.has("envId") && !json.isNull("envId")) json.getLong("envId") else cur.envId
        val next = cur.copy(
            slotIndex = slotIndex,
            envId = envId,
            envName = json.optString("envName", cur.envName ?: "").ifBlank { null },
            status = json.optString("status", cur.status),
            url = json.optString("url", cur.url),
            title = json.optString("title", cur.title),
            progress = json.optInt("progress", cur.progress),
            marionettePort = json.optInt("marionettePort", cur.marionettePort),
            marionetteEnabled = json.optBoolean("marionetteEnabled", cur.marionetteEnabled),
            harRecording = json.optBoolean("harRecording", cur.harRecording),
            lastError = json.optString("error", "").ifBlank { null },
            updatedAt = System.currentTimeMillis(),
        )
        val map = _states.value.toMutableMap()
        map[slotIndex] = next
        _states.value = map
    }

    fun getState(slotIndex: Int): SlotState =
        _states.value[slotIndex] ?: SlotState(slotIndex = slotIndex)

    /** 环境当前占用的槽位号（未运行返回 null） */
    fun slotOf(envId: Long): Int? = _states.value.values
        .firstOrNull { it.envId == envId && it.status == Environment.STATUS_RUNNING }
        ?.slotIndex

    fun runningSlots(): List<SlotState> =
        _states.value.values.filter { it.status == Environment.STATUS_RUNNING }

    fun runningCount(): Int = runningSlots().size

    /** IPC 命令码（主进程与槽位服务共用） */
    object Cmd {
        const val HELLO = 1000
        const val START = 1001
        const val STOP = 1002
        const val NAVIGATE = 1003
        const val RELOAD = 1004
        const val GO_BACK = 1005
        const val GO_FORWARD = 1006
        const val SCREENSHOT = 1007
        const val EXEC_JS = 1008
        const val SET_FINGERPRINT = 1009
        const val SET_PROXY = 1010
        const val START_MARIONETTE = 1011
        const val STOP_MARIONETTE = 1012
        const val GET_STATE = 1013
        const val CLEAR_STORAGE = 1014
        const val START_HAR = 1015
        const val STOP_HAR = 1016
        const val SHUTDOWN = 1018
        const val SET_HOSTS = 1019
        const val READ_COOKIE = 1020
        const val WRITE_COOKIE = 1021
        const val STATE_PUSH = 2001
    }

    companion object {
        const val SLOTS = 5

        fun serviceClassName(slotIndex: Int): String =
            "com.geoenv.browser.slot.SlotHostService$slotIndex"

        fun activityClassName(slotIndex: Int): String =
            "com.geoenv.browser.slot.SlotActivity$slotIndex"

        /** 打开某个槽位的浏览器界面 */
        fun openSlot(context: Context, slotIndex: Int, envId: Long, envName: String) {
            val intent = Intent(context, Class.forName(activityClassName(slotIndex))).apply {
                putExtra("envId", envId)
                putExtra("envName", envName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { Timber.w(it, "open slot failed %d", slotIndex) }
        }

        fun startSlotService(context: Context, slotIndex: Int, envId: Long) {
            val intent = Intent(context, Class.forName(serviceClassName(slotIndex))).apply {
                action = "START"
                putExtra("envId", envId)
            }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "start slot service failed %d", slotIndex) }
        }

        fun stopSlotService(context: Context, slotIndex: Int) {
            val intent = Intent(context, Class.forName(serviceClassName(slotIndex))).apply {
                action = "STOP"
            }
            runCatching { context.stopService(intent) }
        }
    }
}
