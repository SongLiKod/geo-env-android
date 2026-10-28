package com.geoenv.browser.slot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.geoenv.browser.MainActivity
import com.geoenv.browser.R
import com.geoenv.browser.db.Environment
import com.geoenv.browser.db.HostsRule
import com.geoenv.browser.db.ProxyConfig
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.gecko.FingerprintConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * 槽位进程的 IPC 服务宿主（每进程一个子类：SlotHostService1 ~ SlotHostService5）。
 *
 * 协议（与 [SlotBridge] 严格对应）：
 *  - 入站 `Message.what` = [SlotBridge.Cmd]，`data["payload"]` = JSON 字符串，
 *    `data["reply"]` = 主进程 reply Messenger，`data["cbId"]` = 一次性回调 ID；
 *  - 出站回包 `Message.what` = slotIndex，`data["payload"]` = JSON，
 *    `data["cbId"]` = 原样带回（无回调时为 -1）；
 *  - 状态推送（STATE_PUSH / 任意状态变更）同样走 reply Messenger，
 *    payload 含 `status/envId/...`，[SlotBridge] 据此合并镜像状态。
 *
 * 生命周期：`onStartCommand` 收到 `action=START` 立即 `startForeground`（避免
 * 前台服务超时），`action=STOP` 则停止引擎并 `stopSelf`。
 */
abstract class BaseSlotHostService : android.app.Service() {

    protected abstract val slotIndex: Int

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: SlotEngine

    /** 主进程 reply Messenger（HELLO 时携带；其后每条命令自带） */
    @Volatile
    private var replyMessenger: Messenger? = null

    /** 本服务注册到引擎的状态推送监听（onDestroy 时注销） */
    private var pushListener: ((SlotEngine) -> Unit)? = null

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val data = msg.data
            val payload = runCatching {
                JSONObject(data.getString("payload") ?: "{}")
            }.getOrDefault(JSONObject())
            val cbId = data.getInt("cbId", -1)
            (data.getBinder("reply") as? android.os.IBinder)?.let {
                replyMessenger = Messenger(it)
            }
            dispatch(msg.what, payload, cbId)
        }
    })

    override fun onCreate() {
        super.onCreate()
        engine = SlotEngine.get(slotIndex, this)
        pushListener = { e -> pushState(e) }
        engine.addStateListener(pushListener!!)
        Timber.i("slot%d: host service created", slotIndex)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                val envId = intent.getLongExtra("envId", -1L)
                if (envId > 0) startEnvironment(envId)
            }
            ACTION_STOP -> {
                handler.post {
                    runCatching { engine.stop() }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForegroundNotification()
        }
        // 服务被系统杀死后不自动重建（由主进程 / KeepAlive 决定是否再拉起）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pushListener?.let { engine.removeStateListener(it) }
        pushListener = null
        super.onDestroy()
    }

    /* ----------------------------- 命令分发 ----------------------------- */

    private fun dispatch(cmd: Int, payload: JSONObject, cbId: Int) {
        val reply: ((JSONObject) -> Unit)? = if (cbId > 0) {
            { json -> sendReply(cbId, json) }
        } else null

        when (cmd) {
            SlotBridge.Cmd.HELLO -> {
                replyMessenger?.let { r ->
                    // 立即回一次完整状态，让主进程镜像就绪
                    sendTo(r, cbId, stateJson())
                }
            }

            SlotBridge.Cmd.START -> {
                val envId = payload.optLong("envId", -1L)
                if (envId > 0) startEnvironment(envId, reply) else reply?.invoke(err("bad_env"))
            }

            SlotBridge.Cmd.STOP -> {
                handler.post {
                    runCatching { engine.stop() }
                    reply?.invoke(JSONObject().put("ok", true))
                }
            }

            SlotBridge.Cmd.NAVIGATE -> {
                val url = payload.optString("url")
                handler.post {
                    engine.navigate(url)
                    reply?.invoke(JSONObject().put("ok", true))
                }
            }

            SlotBridge.Cmd.RELOAD -> handler.post {
                engine.reload(); reply?.invoke(JSONObject().put("ok", true))
            }

            SlotBridge.Cmd.GO_BACK -> handler.post {
                engine.goBack(); reply?.invoke(JSONObject().put("ok", true))
            }

            SlotBridge.Cmd.GO_FORWARD -> handler.post {
                engine.goForward(); reply?.invoke(JSONObject().put("ok", true))
            }

            SlotBridge.Cmd.GET_STATE -> reply?.invoke(stateJson())

            SlotBridge.Cmd.CLEAR_STORAGE -> handler.post {
                engine.clearStorage(); reply?.invoke(JSONObject().put("ok", true))
            }

            SlotBridge.Cmd.SET_HOSTS -> {
                val arr = payload.optJSONArray("rules") ?: org.json.JSONArray()
                val rules = mutableListOf<HostsRule>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    rules += HostsRule(
                        envId = payload.optLong("envId", engine.envId ?: 0L),
                        domain = o.optString("domain"),
                        ip = o.optString("ip"),
                        enabled = o.optBoolean("enabled", true),
                    )
                }
                handler.post {
                    engine.setHosts(rules)
                    reply?.invoke(JSONObject().put("ok", true))
                }
            }

            SlotBridge.Cmd.EXEC_JS -> {
                val code = payload.optString("code")
                handler.post {
                    engine.execJs(code) { resp -> reply?.invoke(resp) }
                }
            }

            SlotBridge.Cmd.SCREENSHOT -> handler.post {
                engine.screenshot { bmp ->
                    val resp = JSONObject()
                    if (bmp == null) {
                        resp.put("ok", false).put("error", "no_view")
                    } else {
                        val path = saveScreenshot(bmp)
                        resp.put("ok", path != null)
                        if (path != null) resp.put("path", path)
                    }
                    reply?.invoke(resp)
                }
            }

            SlotBridge.Cmd.START_HAR -> {
                val name = payload.optString("name", "")
                handler.post { engine.startHar(name) { r -> reply?.invoke(r) } }
            }

            SlotBridge.Cmd.STOP_HAR -> handler.post {
                engine.stopHar { r -> reply?.invoke(r) }
            }

            SlotBridge.Cmd.READ_COOKIE -> {
                val url = payload.optString("url")
                handler.post { engine.readCookie(url) { r -> reply?.invoke(r) } }
            }

            SlotBridge.Cmd.WRITE_COOKIE -> {
                val cookie = payload.optJSONObject("cookie") ?: JSONObject()
                handler.post { engine.writeCookie(cookie) { r -> reply?.invoke(r) } }
            }

            SlotBridge.Cmd.SET_FINGERPRINT,
            SlotBridge.Cmd.SET_PROXY,
            SlotBridge.Cmd.START_MARIONETTE,
            SlotBridge.Cmd.STOP_MARIONETTE,
            -> {
                // 进程级配置变更：统一走重启生效
                val envId = payload.optLong("envId", engine.envId ?: -1L)
                if (envId > 0) startEnvironment(envId, reply) else reply?.invoke(err("bad_env"))
            }

            SlotBridge.Cmd.SHUTDOWN -> handler.post {
                runCatching { engine.stop() }
                reply?.invoke(JSONObject().put("ok", true))
                stopSelf()
            }

            else -> reply?.invoke(err("unknown_cmd:$cmd"))
        }
    }

    /* ----------------------------- 环境启动 ----------------------------- */

    /** 从主库装配 [SlotEngine.StartRequest] 并启动（在 IO 线程查库，主线程启动） */
    private fun startEnvironment(envId: Long, reply: ((JSONObject) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val loc = ServiceLocator.get()
                val dao = loc.database.envDao()
                val env = dao.getById(envId) ?: error("env_not_found")
                val fp = if (env.fingerprintJson.isBlank()) {
                    FingerprintConfig.createDefault()
                } else {
                    FingerprintConfig.fromJson(env.fingerprintJson)
                }

                var proxyHost: String? = null
                var proxyPort = 0
                var proxyType: String? = null
                var proxyUser: String? = null
                var proxyPass: String? = null
                env.proxyId?.let { pid ->
                    val p: ProxyConfig? = loc.database.proxyDao().getById(pid)
                    if (p != null) {
                        proxyHost = p.host
                        proxyPort = p.port
                        proxyType = p.type
                        proxyUser = p.username
                        proxyPass = p.passwordEnc?.let { enc ->
                            runCatching {
                                loc.fieldCipher.decrypt(enc, aad = "proxy:${p.name}")
                            }.getOrNull()
                        }
                    }
                }

                val hosts: List<HostsRule> = if (env.hostsHijackEnabled) {
                    loc.database.hostsDao().getEnabledForEnv(envId)
                } else emptyList()

                SlotEngine.StartRequest(
                    env = env,
                    fp = fp,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    proxyType = proxyType,
                    proxyUsername = proxyUser,
                    proxyPassword = proxyPass,
                    hostsRules = hosts,
                )
            }

            handler.post {
                result.fold(
                    onSuccess = { req ->
                        engine.start(req)
                        reply?.invoke(
                            JSONObject().put("ok", true)
                                .put("slot", slotIndex)
                                .put("envId", envId)
                        )
                    },
                    onFailure = { e ->
                        Timber.w(e, "slot%d: start env %d failed", slotIndex, envId)
                        reply?.invoke(err(e.message ?: "start_failed"))
                    },
                )
            }
        }
    }

    /* ----------------------------- 回包与推送 ----------------------------- */

    private fun sendReply(cbId: Int, json: JSONObject) {
        val target = replyMessenger ?: return
        sendTo(target, cbId, json)
    }

    private fun sendTo(target: Messenger, cbId: Int, json: JSONObject) {
        runCatching {
            val msg = Message.obtain(null, slotIndex)
            val b = android.os.Bundle()
            b.putString("payload", json.toString())
            b.putInt("cbId", cbId)
            msg.data = b
            target.send(msg)
        }.onFailure { Timber.w(it, "slot%d: reply failed", slotIndex) }
    }

    private fun pushState(e: SlotEngine) {
        val target = replyMessenger ?: return
        sendTo(target, -1, stateJson(e))
    }

    private fun stateJson(e: SlotEngine = engine): JSONObject = JSONObject()
        .put("slot", slotIndex)
        .put("envId", e.envId ?: JSONObject.NULL)
        .put("envName", e.envName)
        .put("status", e.status)
        .put("url", e.url)
        .put("title", e.title)
        .put("progress", e.progress)
        .put("marionetteEnabled", e.marionetteEnabled)
        .put("marionettePort", e.marionettePort)
        .put("harRecording", e.harRecording)
        .put("error", e.lastError ?: "")

    private fun err(msg: String): JSONObject =
        JSONObject().put("ok", false).put("error", msg)

    private fun saveScreenshot(bmp: android.graphics.Bitmap): String? = runCatching {
        val dir = java.io.File(filesDir, "shots").apply { mkdirs() }
        val f = java.io.File(dir, "slot${slotIndex}_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        f.absolutePath
    }.getOrNull()

    /* ----------------------------- 前台通知 ----------------------------- */

    private var foregroundStarted = false

    private fun startForegroundNotification() {
        if (foregroundStarted) return
        foregroundStarted = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_slot_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        val content = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_slot_title, slotIndex))
            .setContentText(getString(R.string.notif_slot_text))
            .setContentIntent(content)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID_BASE + slotIndex, notification)
        } catch (t: Throwable) {
            Timber.w(t, "slot%d: startForeground failed", slotIndex)
        }
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        private const val CHANNEL_ID = "geoenv_slot"
        private const val NOTIFICATION_ID_BASE = 4100
    }
}
