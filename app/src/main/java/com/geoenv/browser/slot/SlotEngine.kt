package com.geoenv.browser.slot

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import com.geoenv.browser.db.Environment
import com.geoenv.browser.db.HostsRule
import com.geoenv.browser.db.PopupEvent
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.gecko.ExtensionFactory
import com.geoenv.browser.gecko.FingerprintConfig
import com.geoenv.browser.gecko.HookScript
import com.geoenv.browser.gecko.ProfileManager
import com.geoenv.browser.proxy.LocalProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 槽位进程内的 Gecko 引擎（GeckoRuntime / GeckoSession / 扩展 / 本地代理 / Marionette）。
 *
 * 每个槽位进程（:gecko1 ~ :gecko5）持有一个实例，同一进程内同时被
 * [BaseSlotHostService]（主进程 IPC 命令入口）与 [BaseSlotActivity]（渲染视图）使用。
 *
 * 启动序列（全部主线程执行，Gecko API 约束）：
 *  1. 生成 DebugConfig YAML（profile 隔离 / TZ / 语言 / 代理 / Marionette / 签名豁免）；
 *  2. 需要时启动本地代理（Hosts 劫持或上游认证），并把端口写入代理 prefs；
 *  3. 创建 GeckoRuntime（配置变化时先 shutdown 旧实例，等待 onShutdown 回调）；
 *  4. 安装本环境指纹扩展（动态生成 xpi → FROM_FILE），建立 native port；
 *  5. 创建 GeckoSession（contextId + UA 覆盖），挂视图、装载页面；
 *  6. 探测 profile 是否真正生效（降级保护），落库状态。
 *
 * Marionette / 代理 / 指纹等进程级配置的变更统一通过 [restart] 重建 runtime 生效。
 */
class SlotEngine private constructor(
    val slotIndex: Int,
    private val context: Context,
) {

    /* ----------------------------- 状态 ----------------------------- */

    @Volatile var envId: Long? = null
        private set
    @Volatile var envName: String = ""
        private set
    @Volatile var status: String = Environment.STATUS_STOPPED
    @Volatile var url: String = ""
    @Volatile var title: String = ""
    @Volatile var progress: Int = 0
    @Volatile var marionetteEnabled: Boolean = false
    @Volatile var marionettePort: Int = 0
    @Volatile var harRecording: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var canGoBack: Boolean = false
    @Volatile var canGoForward: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val profileManager = ProfileManager(context)

    /** 状态监听（多播：宿主服务用于 STATE_PUSH，槽位 Activity 用于刷新 UI） */
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(SlotEngine) -> Unit>()

    fun addStateListener(l: (SlotEngine) -> Unit) {
        stateListeners.addIfAbsent(l)
    }

    fun removeStateListener(l: (SlotEngine) -> Unit) {
        stateListeners.remove(l)
    }

    /* ------------------------- Gecko 对象 ------------------------- */

    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile private var session: GeckoSession? = null
    @Volatile private var geckoView: GeckoView? = null
    private val popupSessions = ArrayList<GeckoSession>()
    @Volatile private var runtimeSignature: String? = null
    @Volatile private var pendingStart: StartRequest? = null
    @Volatile private var runtimeShuttingDown = false

    @Volatile private var extension: WebExtension? = null
    @Volatile private var nativePort: WebExtension.Port? = null
    private val portOutbox = ArrayDeque<JSONObject>()

    @Volatile private var localProxy: LocalProxyServer? = null

    private val extSeq = AtomicInteger(1)
    private val extPending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()

    /** HAR 录制缓冲（后台扩展分页回传，停止时落盘） */
    @Volatile
    private var harBuffer = JSONArray()
    @Volatile private var harRecordId: Long = 0L
    @Volatile private var harName: String = ""
    @Volatile private var harStartAt: Long = 0L
    private val harFetchScheduled = AtomicInteger(0)

    private var currentRequest: StartRequest? = null
    private var lastProbeAt = 0L

    data class StartRequest(
        val env: Environment,
        val fp: FingerprintConfig,
        val proxyHost: String? = null,
        val proxyPort: Int = 0,
        val proxyType: String? = null,
        val proxyUsername: String? = null,
        val proxyPassword: String? = null,
        val hostsRules: List<HostsRule> = emptyList(),
        val initialUrl: String? = null,
    )

    /* =========================== 生命周期 =========================== */

    /** 启动（或重启）一个环境；若 runtime 需要重建则排队等待 shutdown 完成 */
    fun start(req: StartRequest) {
        assertUiThread()
        currentRequest = req
        envId = req.env.id
        envName = req.env.name
        marionetteEnabled = req.env.marionetteEnabled
        marionettePort = req.env.marionettePort
        lastError = null
        status = Environment.STATUS_STARTING
        pushState()

        val sig = signatureOf(req)
        val existing = runtime
        if (existing != null && sig == runtimeSignature && !runtimeShuttingDown) {
            // runtime 可复用：只重建 session（同 env 同配置）
            openSession(req)
        } else if (existing != null) {
            pendingStart = req
            if (!runtimeShuttingDown) {
                runtimeShuttingDown = true
                Timber.i("slot%d: shutting down runtime for reconfig", slotIndex)
                runCatching { existing.shutdown() }
                handler.postDelayed({
                    if (runtimeShuttingDown) {
                        // 兜底：shutdown 回调超时，强制继续
                        Timber.w("slot%d: runtime shutdown timeout, force recreate", slotIndex)
                        runtimeShuttingDown = false
                        runtime = null
                        runtimeSignature = null
                        pendingStart?.let { p -> pendingStart = null; createRuntime(p) }
                    }
                }, 8_000L)
            }
        } else {
            createRuntime(req)
        }
    }

    private fun signatureOf(req: StartRequest): String = buildString {
        append(req.env.id).append('|')
        append(req.fp.screenWidth).append('x').append(req.fp.screenHeight)
        append('@').append(req.fp.screenDpi).append('|')
        append(req.fp.languages.firstOrNull()).append('|')
        append(req.fp.timezone).append('|')
        append(req.proxyType ?: "DIRECT").append(':').append(req.proxyHost ?: "").append(':').append(req.proxyPort)
        append('|').append(req.proxyUsername ?: "")
        append('|').append(req.hostsRules.filter { it.enabled }.joinToString(",") { "${it.domain}=${it.ip}" })
        append('|').append(req.env.marionetteEnabled).append(':').append(req.env.marionettePort)
        append('|').append(req.fp.hookVersion)
    }

    private fun createRuntime(req: StartRequest) {
        assertUiThread()
        val fp = req.fp
        // 1) 本地代理（Hosts 劫持 / 上游代理认证时启用）
        val rules = req.hostsRules.filter { it.enabled }
            .associate { it.domain.lowercase() to it.ip }
        val needLocalProxy = rules.isNotEmpty() || (!req.proxyUsername.isNullOrBlank())
        if (needLocalProxy) {
            if (localProxy == null) {
                val up = req.proxyHost?.let {
                    LocalProxyServer.Upstream(
                        type = req.proxyType ?: "HTTP", host = it, port = req.proxyPort,
                        username = req.proxyUsername, password = req.proxyPassword,
                    )
                }
                localProxy = LocalProxyServer(rules, up).also { it.start() }
            } else {
                localProxy?.updateHosts(rules)
            }
        } else {
            localProxy?.stop(); localProxy = null
        }

        // 2) DebugConfig YAML
        val prefs = buildPrefs(req, localProxy?.takeIf { it.port > 0 }?.port ?: 0)
        val envVars = buildEnvVars(req)
        val yaml = profileManager.writeConfig(
            envId = req.env.id,
            prefs = prefs,
            env = envVars,
            profilePath = profileManager.ensureProfileDir(req.env.id).absolutePath,
        )

        // 3) GeckoRuntimeSettings
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(yaml.absolutePath)
            .displayDpiOverride(fp.screenDpi)
            .screenSizeOverride(fp.screenWidth, fp.screenHeight)
            .locales(fp.languages.toTypedArray())
            .javaScriptEnabled(true)
            .consoleOutput(com.geoenv.browser.BuildConfig.DEBUG)
            .build()

        Timber.i("slot%d: creating runtime for env=%d sig=%s", slotIndex, req.env.id, signatureOf(req))
        val rt = GeckoRuntime.create(context, settings)
        runtime = rt
        runtimeSignature = signatureOf(req)

        rt.setDelegate(object : GeckoRuntime.Delegate {
            override fun onShutdown() {
                handler.post {
                    Timber.i("slot%d: runtime shutdown complete", slotIndex)
                    runtimeShuttingDown = false
                    runtime = null
                    runtimeSignature = null
                    session = null
                    extension = null
                    nativePort = null
                    pendingStart?.let { p -> pendingStart = null; createRuntime(p) }
                }
            }
        })

        rt.webExtensionController.setPromptDelegate(autoInstallPromptDelegate)

        // 4) 指纹扩展
        installExtension(rt, req) {
            openSession(req)
            scheduleProfileProbe(req.env.id)
        }
    }

    private fun installExtension(rt: GeckoRuntime, req: StartRequest, onDone: () -> Unit) {
        val xpi = runCatching {
            ExtensionFactory.buildXpi(context, req.env.id, req.fp)
        }.getOrElse {
            Timber.w(it, "slot%d: build xpi failed", slotIndex)
            onDone(); return
        }
        val ctrl = rt.webExtensionController
        val proceed = {
            ctrl.install("file://${xpi.absolutePath}", WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
                .accept({ ext ->
                    Timber.i("slot%d: extension installed: %s", slotIndex, ext?.id)
                    extension = ext
                    ext?.setMessageDelegate(messageDelegate, ExtensionFactory.NATIVE_APP)
                    onDone()
                }, { e ->
                    Timber.w(e, "slot%d: extension install failed, retry as built-in", slotIndex)
                    // 降级：安装 APK assets 中的静态占位扩展（无环境配置，仅保通信）
                    rt.webExtensionController
                        .installBuiltIn("resource://android/assets/extensions/geoenv/base.xpi")
                        .accept({ ext2 ->
                            extension = ext2
                            ext2?.setMessageDelegate(messageDelegate, ExtensionFactory.NATIVE_APP)
                            onDone()
                        }, { e2 ->
                            Timber.e(e2, "slot%d: fallback extension install failed", slotIndex)
                            onDone()
                        })
                })
            Unit
        }
        // 已安装则先卸载（同 id），保证每次启动都拿到新的 Hook 源码
        ctrl.list().accept({ list ->
            val existing = list?.firstOrNull { it.id == ExtensionFactory.EXTENSION_ID }
            if (existing != null) {
                ctrl.uninstall(existing).accept({ _ -> proceed() }, { _ -> proceed() })
            } else {
                proceed()
            }
        }, { _ -> proceed() })
    }

    private fun openSession(req: StartRequest) {
        assertUiThread()
        val fp = req.fp
        runCatching { session?.close() }
            .onFailure { Timber.d("old session close: %s", it.message) }

        val settings = GeckoSessionSettings.Builder()
            .contextId("geoenv.${req.env.id}")
            .userAgentOverride(fp.resolveUserAgent())
            .allowJavascript(true)
            .build()
        val s = GeckoSession(settings)
        s.setContentDelegate(contentDelegate)
        s.setProgressDelegate(progressDelegate)
        s.setNavigationDelegate(navigationDelegate)
        s.setPromptDelegate(promptDelegate)
        s.setPermissionDelegate(permissionDelegate)
        session = s

        val rt = runtime
        if (rt == null) {
            fail("runtime_missing"); return
        }
        s.open(rt)
        geckoView?.let { gv ->
            try {
                gv.setSession(s)
            } catch (t: Throwable) {
                Timber.w(t, "slot%d: setView failed", slotIndex)
            }
        }
        if (!marionetteEnabled) marionettePort = 0

        status = Environment.STATUS_RUNNING
        updateDbStatus(req.env.id, Environment.STATUS_RUNNING, start = true)
        pushState()

        val target = req.initialUrl
            ?: url.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: HOME_URL
        s.loadUri(target)

        // 若环境打开即录制 HAR
        if (req.env.harRecording && !harRecording) startHarInternal("auto")
    }

    /** 环境停止（不销毁对象，可再次 start） */
    fun stop() {
        assertUiThread()
        val id = envId
        if (harRecording) stopHarInternal()
        runCatching { session?.close() }
        session = null
        runCatching { runtime?.shutdown() }
        runtime = null
        runtimeSignature = null
        runtimeShuttingDown = false
        pendingStart = null
        extension = null
        nativePort = null
        localProxy?.stop(); localProxy = null
        status = Environment.STATUS_STOPPED
        url = ""; title = ""; progress = 0
        if (id != null) updateDbStatus(id, Environment.STATUS_STOPPED, start = false)
        pushState()
    }

    /** 重启（配置变更后调用） */
    fun restart(req: StartRequest) {
        if (runtime == null) { start(req); return }
        status = Environment.STATUS_STARTING
        pushState()
        // start 内部会检测签名不一致 → shutdown → recreate
        start(req)
    }

    fun attachView(view: GeckoView?) {
        assertUiThread()
        geckoView = view
        if (view != null) {
            session?.let { s ->
                runCatching { view.setSession(s) }
                    .onFailure { Timber.w(it, "slot%d: attach view failed", slotIndex) }
            }
        }
    }

    fun currentSession(): GeckoSession? = session

    /* =========================== 命令实现 =========================== */

    fun navigate(target: String) {
        val s = session ?: return
        val uri = when {
            target.startsWith("about:") || target.contains("://") -> target
            target.isBlank() -> return
            else -> "https://$target"
        }
        url = uri
        pushState()
        s.loadUri(uri)
    }

    fun reload() {
        runCatching { session?.reload() }
            .onFailure { url.takeIf { it.isNotBlank() }?.let { navigate(it) } }
    }

    fun goBack() {
        runCatching { session?.goBack() }
    }

    fun goForward() {
        runCatching { session?.goForward() }
    }

    /** 通过扩展在当前页执行 JS（GeckoView 无公开 evaluateJs API） */
    fun execJs(code: String, callback: (JSONObject) -> Unit) {
        sendExt("eval" to JSONObject().put("code", code), callback)
    }

    /** 截取当前可见页面（需要 Activity 已挂载 GeckoView） */
    fun screenshot(callback: (Bitmap?) -> Unit) {
        val gv = geckoView
        if (gv == null) { callback(null); return }
        handler.post {
            runCatching {
                gv.capturePixels().accept({ bitmap -> callback(bitmap) }, { e ->
                    Timber.w(e, "capturePixels failed"); callback(null)
                })
            }.onFailure { callback(null) }
        }
    }

    fun setHosts(rules: List<HostsRule>) {
        val map = rules.filter { it.enabled }.associate { it.domain.lowercase() to it.ip }
        localProxy?.updateHosts(map)
        pushState()
    }

    fun startHar(name: String, callback: (JSONObject) -> Unit) = startHarInternal(name, callback)

    fun stopHar(callback: (JSONObject) -> Unit) = stopHarInternal(callback)

    fun readCookie(url: String, callback: (JSONObject) -> Unit) {
        sendExt("getCookies" to JSONObject().put("url", url), callback)
    }

    fun writeCookie(cookie: JSONObject, callback: (JSONObject) -> Unit) {
        sendExt("setCookie" to JSONObject().put("cookie", cookie), callback)
    }

    fun clearStorage() {
        val id = envId ?: return
        runCatching {
            runtime?.storageController?.clearDataForSessionContext("geoenv.$id")
            Timber.i("slot%d: storage cleared for env=%d", slotIndex, id)
        }
        pushState()
    }

    /* ------------------------- HAR ------------------------- */

    private fun startHarInternal(name: String, callback: ((JSONObject) -> Unit)? = null) {
        harBuffer = JSONArray()
        harRecordId = 0L
        harName = name.ifBlank { "har-${System.currentTimeMillis()}" }
        harStartAt = System.currentTimeMillis()
        sendExt("startHar" to JSONObject()) { resp ->
            if (resp.optBoolean("ok")) {
                harRecording = true
                insertHarRecord()
                scheduleHarFetch()
                pushState()
            }
            callback?.invoke(resp)
        }
    }

    private fun stopHarInternal(callback: ((JSONObject) -> Unit)? = null) {
        // 先拉完剩余条目，再让扩展停止
        fetchHarEntries(force = true) {
            sendExt("stopHar" to JSONObject()) { _ ->
                harRecording = false
                finalizeHarRecord()
                pushState()
                callback?.invoke(JSONObject().put("ok", true).put("entries", harBuffer.length()))
            }
        }
    }

    private fun insertHarRecord() {
        val id = envId ?: return
        scope.launch {
            runCatching {
                val db = ServiceLocator.get().database
                harRecordId = db.harDao().insert(
                    com.geoenv.browser.db.HarRecord(
                        envId = id,
                        name = harName,
                        filePath = harFilePath(id),
                        startTime = harStartAt,
                    )
                )
            }.onFailure { Timber.w(it, "insert har record failed") }
        }
    }

    private fun scheduleHarFetch() {
        if (!harRecording) return
        if (harFetchScheduled.getAndSet(1) == 1) return
        handler.postDelayed({
            harFetchScheduled.set(0)
            fetchHarEntries(force = false) { scheduleHarFetch() }
        }, 3_000L)
    }

    /** 分页拉取扩展缓冲（单事务大小受 binder 1MB 限制） */
    private fun fetchHarEntries(force: Boolean, done: () -> Unit) {
        if (extension == null) { done(); return }
        var offset = harBuffer.length()
        fun next() {
            sendExt(
                "getHar" to JSONObject().put("offset", offset).put("limit", 100)
            ) { resp ->
                if (!resp.optBoolean("ok")) { done(); return@sendExt }
                val arr = resp.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) harBuffer.put(arr.optJSONObject(i))
                offset = harBuffer.length()
                val total = resp.optInt("total", offset)
                if (offset < total && arr.length() > 0) next() else done()
            }
        }
        if (force || offset >= 0) next() else done()
    }

    private fun finalizeHarRecord() {
        val id = envId ?: return
        val recordId = harRecordId
        val entries = harBuffer
        val path = harFilePath(id)
        scope.launch {
            runCatching {
                val file = File(path)
                file.parentFile?.mkdirs()
                val har = JSONObject()
                    .put("version", "1.2")
                    .put("creator", JSONObject().put("name", "GeoEnv").put("version", HookScript.HOOK_VERSION))
                val log = JSONObject()
                    .put("version", "1.2")
                    .put("creator", JSONObject().put("name", "GeoEnv").put("version", "158"))
                    .put("entries", entries)
                har.put("log", log)
                file.writeText(har.toString())
                val db = ServiceLocator.get().database
                if (recordId > 0) {
                    db.harDao().finish(
                        recordId,
                        System.currentTimeMillis(),
                        entries.length(),
                        file.length(),
                    )
                } else {
                    db.harDao().insert(
                        com.geoenv.browser.db.HarRecord(
                            envId = id,
                            name = harName,
                            filePath = path,
                            startTime = harStartAt,
                            endTime = System.currentTimeMillis(),
                            entryCount = entries.length(),
                            sizeBytes = file.length(),
                        )
                    )
                }
                Timber.i("slot%d: HAR saved %d entries -> %s", slotIndex, entries.length(), path)
            }.onFailure { Timber.w(it, "save HAR failed") }
        }
    }

    private fun harFilePath(envId: Long): String =
        File(File(context.filesDir, "har"), "${envId}_${System.currentTimeMillis()}.har").absolutePath

    /* =========================== 内部细节 =========================== */

    private fun buildPrefs(req: StartRequest, localProxyPort: Int): Map<String, Any> {
        val fp = req.fp
        val prefs = mutableMapOf<String, Any>()

        // 语言
        prefs["intl.accept_languages"] = fp.languages.joinToString(", ")
        // UA（进程级兜底，会话级另有 userAgentOverride）
        prefs["general.useragent.override"] = fp.resolveUserAgent()
        // 签名豁免：环境动态生成的 xpi（内部签名），无人值守安装
        prefs["xpinstall.signatures.required"] = false
        // 时区（TZ env 变量为主，此 pref 辅助 Gecko 内部）
        prefs["intl.timezone"] = fp.timezone
        // 不做容器化重定向等外联
        prefs["browser.safebrowsing.malware.enabled"] = false
        prefs["browser.safebrowsing.phishing.enabled"] = false
        prefs["browser.safebrowsing.downloads.enabled"] = false
        // Gecko 内置指纹保护（可选附加层）
        if (fp.geckoFingerprintingProtection) {
            prefs["privacy.fingerprintingProtection.enabled"] = true
        }
        // WebRTC 策略
        when (fp.webrtcPolicy.uppercase()) {
            "DISABLE" -> prefs["media.peerconnection.enabled"] = false
            "DISABLE_NON_PROXIED_UDP" -> {
                prefs["media.peerconnection.ice.proxy_only"] = true
                prefs["media.peerconnection.ice.proxy_only_if_behind_proxy"] = true
            }
            "PUBLIC_ONLY" -> prefs["media.peerconnection.ice.default_address_only"] = true
        }
        // 弹窗默认放行（聚合面板记录，自动化不被阻塞）
        prefs["dom.disable_open_during_load"] = false

        // Marionette（每实例独立端口，仅回环由 nsIServerSocket LoopbackOnly 保证）
        if (req.env.marionetteEnabled && req.env.marionettePort > 0) {
            prefs["marionette.port"] = req.env.marionettePort
        }

        // 代理
        val lp = if (localProxyPort > 0) localProxyPort else 0
        if (lp > 0) {
            prefs["network.proxy.type"] = 1
            prefs["network.proxy.http"] = "127.0.0.1"
            prefs["network.proxy.http_port"] = lp
            prefs["network.proxy.ssl"] = "127.0.0.1"
            prefs["network.proxy.ssl_port"] = lp
            prefs["network.proxy.socks"] = "127.0.0.1"
            prefs["network.proxy.socks_port"] = lp
            prefs["network.proxy.socks_version"] = 5
            prefs["network.proxy.share_proxy_settings"] = true
            prefs["network.proxy.no_proxies_on"] = ""
        } else if (req.proxyHost != null) {
            prefs["network.proxy.type"] = 1
            prefs["network.proxy.http"] = req.proxyHost
            prefs["network.proxy.http_port"] = req.proxyPort
            prefs["network.proxy.ssl"] = req.proxyHost
            prefs["network.proxy.ssl_port"] = req.proxyPort
            prefs["network.proxy.socks"] = req.proxyHost
            prefs["network.proxy.socks_port"] = req.proxyPort
            prefs["network.proxy.socks_version"] =
                if (req.proxyType.equals("SOCKS5", true)) 5 else 4
            prefs["network.proxy.share_proxy_settings"] = true
            prefs["network.proxy.no_proxies_on"] = ""
            prefs["network.proxy.socks_remote_dns"] = true
        } else {
            prefs["network.proxy.type"] = 0
        }
        return prefs
    }

    private fun buildEnvVars(req: StartRequest): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["TZ"] = req.fp.timezone
        val lang = req.fp.languages.firstOrNull()
        if (lang != null) {
            val parts = lang.split('-')
            env["LANG"] = if (parts.size >= 2) "${parts[0]}_${parts[1]}.UTF-8" else "${parts[0]}.UTF-8"
        }
        // Marionette 开关是进程环境变量（DebugConfig env → putenv，在 Gecko 启动前生效）
        if (req.env.marionetteEnabled && req.env.marionettePort > 0) {
            env["MOZ_MARIONETTE"] = "1"
        }
        return env
    }

    private fun scheduleProfileProbe(id: Long) {
        handler.postDelayed({
            if (System.currentTimeMillis() - lastProbeAt > 60_000) {
                lastProbeAt = System.currentTimeMillis()
                val ok = profileManager.probeProfileEffective(id)
                if (!ok) {
                    Timber.w("slot%d: profile isolation probe failed env=%d, contextId fallback active", slotIndex, id)
                }
            }
        }, 5_000L)
    }

    private fun updateDbStatus(id: Long, status: String, start: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = ServiceLocator.get().database.envDao()
                if (start) dao.update(
                    dao.getById(id)?.copy(status = status, lastStartTime = System.currentTimeMillis())
                        ?: return@runCatching
                ) else dao.updateStatus(id, status)
            }.onFailure { Timber.w(it, "update env status failed") }
        }
    }

    private fun recordPopup(type: String, title: String, message: String, url: String) {
        val id = envId ?: return
        val name = envName
        scope.launch(Dispatchers.IO) {
            runCatching {
                ServiceLocator.get().database.popupDao().insert(
                    PopupEvent(
                        envId = id, envName = name, type = type,
                        title = title, message = message, url = url,
                    )
                )
            }.onFailure { Timber.w(it, "insert popup event failed") }
        }
    }

    private fun fail(msg: String) {
        lastError = msg
        status = Environment.STATUS_ERROR
        pushState()
    }

    fun pushState() {
        stateListeners.forEach { it.invoke(this) }
    }

    private fun assertUiThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Gecko API must run on UI thread" }
    }

    /* =========================== 扩展通信 =========================== */

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            handler.post {
                Timber.i("slot%d: extension native port connected", slotIndex)
                port.setDelegate(portDelegate)
                nativePort = port
                val queued = synchronized(portOutbox) {
                    val list = portOutbox.toList()
                    portOutbox.clear()
                    list
                }
                queued.forEach { port.postMessage(it) }
            }
        }
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            handler.post { handleExtMessage(message) }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            handler.post {
                if (nativePort === port) nativePort = null
            }
        }
    }

    private fun handleExtMessage(message: Any) {
        val json = message as? JSONObject ?: runCatching { JSONObject(message.toString()) }.getOrNull() ?: return
        val id = json.optInt("id", 0)
        if (id > 0) {
            extPending.remove(id)?.invoke(json)
            return
        }
        when (json.optString("event")) {
            "popup" -> recordPopup(
                json.optString("type", "POPUP"),
                json.optString("title"),
                json.optString("message"),
                json.optString("url"),
            )
            "har.entry" -> if (harRecording) harBuffer.put(json.optJSONObject("entry") ?: json)
        }
    }

    /** 发送命令到扩展（带排队与回调） */
    private fun sendExt(pair: Pair<String, JSONObject>, callback: (JSONObject) -> Unit) {
        val (cmd, body) = pair
        val id = extSeq.getAndIncrement()
        extPending[id] = callback
        val msg = JSONObject().put("id", id).put("cmd", cmd).apply {
            body.keys().forEach { k -> put(k, body.opt(k)) }
        }
        val port = nativePort
        if (port != null) {
            port.postMessage(msg)
        } else {
            synchronized(portOutbox) { portOutbox.addLast(msg) }
        }
        // 超时兜底
        handler.postDelayed({
            extPending.remove(id)?.invoke(JSONObject().put("ok", false).put("error", "timeout"))
        }, 10_000L)
    }

    /* =========================== Gecko 委托 =========================== */

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            this@SlotEngine.title = title ?: ""
            pushState()
        }

        override fun onCloseRequest(session: GeckoSession) {
            // 页面 window.close()：仅关闭视图，不停环境
            stateListeners.forEach { it.invoke(this@SlotEngine) }
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) = Unit
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            this@SlotEngine.url = url
            progress = 0
            pushState()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            progress = 100
            pushState()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            this@SlotEngine.progress = progress.coerceIn(0, 100)
            pushState()
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (url != null) {
                this@SlotEngine.url = url
                pushState()
            }
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny>? = null

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // 弹窗（window.open / target=_blank）：创建后台 session 执行页面脚本，
            // 不抢占当前视图；保存引用防止被 GC（GeckoView 文档要求）。
            val rt = runtime ?: return null
            return try {
                val popup = GeckoSession()
                popup.setPromptDelegate(promptDelegate)
                popup.open(rt)
                popupSessions.add(popup)
                if (popupSessions.size > 8) {
                    runCatching { popupSessions.removeAt(0).close() }
                }
                popup.loadUri(uri)
                recordPopup("POPUP_SESSION", "new-session", uri, uri)
                GeckoResult.fromValue(popup)
            } catch (t: Throwable) {
                Timber.w(t, "slot%d: onNewSession failed", slotIndex)
                null
            }
        }
    }

    /** 弹窗聚合：alert / confirm / prompt / beforeunload / popup 全部记录并自动放行 */
    private val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("ALERT", prompt.title ?: "", prompt.message ?: "", url)
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("PROMPT", prompt.title ?: "", prompt.message ?: "", url)
            // 自动返回默认值，避免阻塞自动化
            return GeckoResult.fromValue(prompt.confirm(prompt.defaultValue ?: ""))
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("CONFIRM", prompt.title ?: "", prompt.message ?: "", url)
            return GeckoResult.fromValue(
                prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
            )
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("BEFORE_UNLOAD", "beforeunload", "", url)
            // 自动继续导航（自动化优先）
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("POPUP", "window.open", prompt.targetUri ?: "", prompt.targetUri ?: url)
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }

        override fun onRedirectPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RedirectPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("REDIRECT", "redirect", prompt.targetUri ?: "", url)
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }

        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            recordPopup("AUTH", prompt.title ?: "authentication", "", url)
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())
    }

    /** 默认拒绝敏感权限（指纹一致性：不暴露真实设备权限状态） */
    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            callback.reject()
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? =
            GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }
    }

    /** 扩展安装权限自动批准（内部扩展，无人值守） */
    private val autoInstallPromptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<out String>,
            origins: Array<out String>,
            dataCollectionPermissions: Array<out String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            Timber.i("slot%d: auto-approve extension install %s perms=%s", slotIndex, extension.id, permissions.joinToString())
            return GeckoResult.fromValue(
                WebExtension.PermissionPromptResponse(true, true, false)
            )
        }

        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<out String>,
            newOrigins: Array<out String>,
            newDataCollectionPermissions: Array<out String>,
        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    companion object {
        const val HOME_URL = "about:blank"

        private val instances = ConcurrentHashMap<Int, SlotEngine>()

        fun get(slotIndex: Int, context: Context): SlotEngine =
            instances.getOrPut(slotIndex) {
                SlotEngine(slotIndex, context.applicationContext)
            }

        fun peek(slotIndex: Int): SlotEngine? = instances[slotIndex]
    }
}
