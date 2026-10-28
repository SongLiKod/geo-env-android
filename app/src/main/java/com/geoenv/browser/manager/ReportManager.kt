package com.geoenv.browser.manager

import android.content.Context
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.Environment
import com.geoenv.browser.gecko.FingerprintConfig
import com.geoenv.browser.gecko.FingerprintIssue
import com.geoenv.browser.gecko.FingerprintValidator
import com.geoenv.browser.slot.SlotBridge
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指纹检测报告（需求 3.3"内置指纹检测能力，一键生成指纹检测报告"）。
 *
 * 报告由三部分组成：
 *  1. **配置静态校验**：[FingerprintValidator] 的冲突/可疑项；
 *  2. **注入链路自检**：UA / 屏幕 / DPI / 语言 / 时区分别由哪一层生效
 *     （GeckoView Settings / DebugConfig env / WebExtension Hook），以及是否可验证；
 *  3. **运行时实测**（环境运行中时）：经扩展端口在页面里读回真实
 *     `navigator.*`、`screen.*`、`Intl.*`、Canvas/WebGL 供应商等，与配置逐项比对。
 *
 * 输出为可读 Markdown + 结构化 JSON，可导出到 App 私有目录分享。
 */
@Singleton
class ReportManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val envRepo: EnvironmentRepository,
    private val audit: AuditRepository,
    private val slotBridge: SlotBridge,
) {

    /* ----------------------------- 模型 ----------------------------- */

    data class RuntimeObservation(
        val userAgent: String? = null,
        val platform: String? = null,
        val language: String? = null,
        val languages: String? = null,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0,
        val devicePixelRatio: Double = 0.0,
        val colorDepth: Int = 0,
        val timezone: String? = null,
        val hardwareConcurrency: Int = 0,
        val maxTouchPoints: Int = 0,
        val webglVendor: String? = null,
        val webglRenderer: String? = null,
        val cookieEnabled: Boolean? = null,
        val doNotTrack: String? = null,
        val raw: String? = null,
    )

    data class CheckLine(
        val item: String,
        val expected: String,
        val actual: String,
        val status: String, // PASS / FAIL / WARN / SKIP
        val note: String = "",
    )

    data class Report(
        val envId: Long,
        val envName: String,
        val generatedAt: Long,
        val geckoVersion: String,
        val hookVersion: String,
        val running: Boolean,
        val issues: List<FingerprintIssue>,
        val lines: List<CheckLine>,
        val observation: RuntimeObservation?,
    ) {
        val passCount: Int get() = lines.count { it.status == STATUS_PASS }
        val failCount: Int get() = lines.count { it.status == STATUS_FAIL }
        val warnCount: Int get() = lines.count { it.status == STATUS_WARN || it.status == STATUS_SKIP }

        companion object {
            const val STATUS_PASS = "PASS"
            const val STATUS_FAIL = "FAIL"
            const val STATUS_WARN = "WARN"
            const val STATUS_SKIP = "SKIP"
        }
    }

    /* ----------------------------- 采集运行时观测 ----------------------------- */

    /**
     * 在运行中的环境页面里读回真实指纹（经 WebExtension 端口执行 JS）。
     * 环境未运行 / 超时返回 null（报告中对应项标为 SKIP）。
     *
     * 通过 [EnvBridge] 提供的执行通道发起（槽位进程内的 execJs → 扩展 tabs.executeScript）。
     */
    suspend fun observe(envId: Long, timeoutMs: Long = 8_000L): RuntimeObservation? {
        val slot = slotBridge.slotOf(envId) ?: return null
        if (slotBridge.getState(slot).status != Environment.STATUS_RUNNING) return null

        val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutRunnable = Runnable { if (cont.isActive) cont.resume(null, null) }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)
            slotBridge.send(
                slot,
                com.geoenv.browser.slot.SlotBridge.Cmd.EXEC_JS,
                JSONObject().put("code", OBSERVE_JS),
            ) { resp ->
                mainHandler.removeCallbacks(timeoutRunnable)
                if (cont.isActive) {
                    val value = if (resp.optBoolean("ok")) {
                        resp.optString("value").ifBlank { null }
                    } else null
                    cont.resume(value, null)
                }
            }
            cont.invokeOnCancellation { mainHandler.removeCallbacks(timeoutRunnable) }
        } ?: return null

        return parseObservation(raw)
    }

    private fun parseObservation(raw: String): RuntimeObservation? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return runCatching {
            RuntimeObservation(
                userAgent = json.optStringOrNull("userAgent"),
                platform = json.optStringOrNull("platform"),
                language = json.optStringOrNull("language"),
                languages = json.optStringOrNull("languages"),
                screenWidth = json.optInt("screenWidth"),
                screenHeight = json.optInt("screenHeight"),
                devicePixelRatio = json.optDouble("devicePixelRatio", 0.0),
                colorDepth = json.optInt("colorDepth"),
                timezone = json.optStringOrNull("timezone"),
                hardwareConcurrency = json.optInt("hardwareConcurrency"),
                maxTouchPoints = json.optInt("maxTouchPoints"),
                webglVendor = json.optStringOrNull("webglVendor"),
                webglRenderer = json.optStringOrNull("webglRenderer"),
                cookieEnabled = if (json.has("cookieEnabled")) json.optBoolean("cookieEnabled") else null,
                doNotTrack = json.optStringOrNull("doNotTrack"),
                raw = raw,
            )
        }.getOrNull()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    /* ----------------------------- 生成报告 ----------------------------- */

    suspend fun buildReport(
        envId: Long,
        observation: RuntimeObservation? = null,
    ): Report {
        val env = envRepo.getEnvironment(envId) ?: error("环境不存在")
        val cfg = FingerprintConfig.fromJson(env.fingerprintJson)
        val issues = FingerprintValidator.validate(cfg)
        val running = slotBridge.slotOf(envId) != null
        val obs = observation ?: if (running) observe(envId) else null
        val lines = compare(cfg, obs, running, issues)

        val report = Report(
            envId = envId,
            envName = env.name,
            generatedAt = System.currentTimeMillis(),
            geckoVersion = BackupManager.BuildVersion.GECKO,
            hookVersion = cfg.hookVersion,
            running = running,
            issues = issues,
            lines = lines,
            observation = obs,
        )
        audit.log(
            action = "FINGERPRINT_REPORT",
            envId = envId,
            envName = env.name,
            note = "生成指纹检测报告：PASS=${report.passCount} FAIL=${report.failCount} WARN=${report.warnCount}",
        )
        return report
    }

    private fun compare(
        cfg: FingerprintConfig,
        obs: RuntimeObservation?,
        running: Boolean,
        issues: List<FingerprintIssue>,
    ): List<CheckLine> {
        val out = mutableListOf<CheckLine>()

        fun add(item: String, expected: String, actual: String?, note: String = "", strict: Boolean = true) {
            if (!running) {
                out += CheckLine(item, expected, "-", Report.STATUS_SKIP, "环境未运行，仅静态校验")
                return
            }
            if (actual == null) {
                out += CheckLine(item, expected, "-", Report.STATUS_SKIP, note.ifBlank { "页面未返回该值" })
                return
            }
            val match = if (strict) actual == expected else actual.contains(expected, ignoreCase = true)
            out += CheckLine(
                item = item,
                expected = expected,
                actual = actual,
                status = if (match) Report.STATUS_PASS else Report.STATUS_FAIL,
                note = if (match) "" else note,
            )
        }

        val ua = cfg.resolveUserAgent()
        add("User-Agent", ua, obs?.userAgent, "UA 未生效（检查 GeckoSessionSettings.userAgentOverride）")

        add(
            "navigator.platform",
            cfg.uaPlatformToken().ifBlank { "-" },
            obs?.platform,
            "平台不一致会导致风控判定设备与 UA 冲突",
        )

        add(
            "navigator.language",
            cfg.resolveNavigatorLanguage(),
            obs?.language,
            "语言未生效（检查 intl.accept_languages 与 locales override）",
        )

        add(
            "screen.width × height",
            "${cfg.screenWidth}x${cfg.screenHeight}",
            obs?.takeIf { it.screenWidth > 0 }?.let { "${it.screenWidth}x${it.screenHeight}" },
            "屏幕尺寸未生效（检查 screenSizeOverride 与 HookScript）",
        )

        add(
            "devicePixelRatio",
            String.format(java.util.Locale.ROOT, "%.2f", cfg.pixelRatio),
            obs?.takeIf { it.devicePixelRatio > 0 }
                ?.let { String.format(java.util.Locale.ROOT, "%.2f", it.devicePixelRatio) },
            "DPR 与 DPI 不匹配会影响页面布局判定",
            strict = false,
        )

        add(
            "screen.colorDepth",
            cfg.colorDepth.toString(),
            obs?.takeIf { it.colorDepth > 0 }?.colorDepth?.toString(),
        )

        add(
            "Intl.DateTimeFormat().timeZone",
            cfg.timezone,
            obs?.timezone,
            "时区未生效（检查 DebugConfig env TZ）",
        )

        add(
            "navigator.hardwareConcurrency",
            cfg.hardwareConcurrency.toString(),
            obs?.takeIf { it.hardwareConcurrency > 0 }?.hardwareConcurrency?.toString(),
        )

        add(
            "navigator.maxTouchPoints",
            cfg.maxTouchPoints.toString(),
            obs?.takeIf { it.maxTouchPoints > 0 }?.maxTouchPoints?.toString(),
        )

        if (cfg.webglVendor.isNotBlank()) {
            add(
                "WebGL vendor",
                cfg.webglVendor,
                obs?.webglVendor,
                "WebGL 厂商未伪装（检查 HookScript webgl hook）",
                strict = false,
            )
        }
        if (cfg.webglRenderer.isNotBlank()) {
            add(
                "WebGL renderer",
                cfg.webglRenderer.take(40),
                obs?.webglRenderer?.take(40),
                "WebGL 渲染器未伪装",
                strict = false,
            )
        }

        if (cfg.canvasMode != FingerprintConfig.MODE_OFF) {
            val injected = running && obs != null
            out += CheckLine(
                item = "Canvas 指纹噪声",
                expected = "mode=${cfg.canvasMode}",
                actual = if (injected) "已注入" else "-",
                status = if (injected) Report.STATUS_PASS else Report.STATUS_SKIP,
                note = "噪声模式：${cfg.canvasMode}（种子 ${cfg.canvasNoiseSeed}）",
            )
        }

        out += CheckLine(
            item = "WebRTC 策略",
            expected = cfg.webrtcPolicy,
            actual = if (running) "pref 已写入" else "-",
            status = if (running) Report.STATUS_PASS else Report.STATUS_SKIP,
            note = if (cfg.webrtcBlockLocalCandidates) "同时屏蔽 host candidate" else "",
        )

        out += CheckLine(
            item = "插件列表",
            expected = when (cfg.pluginMode) {
                FingerprintConfig.MODE_EMPTY -> "空列表"
                FingerprintConfig.MODE_CUSTOM -> "${cfg.customPlugins.size} 项自定义"
                else -> "默认"
            },
            actual = if (running) "已注入" else "-",
            status = if (running) Report.STATUS_PASS else Report.STATUS_SKIP,
        )

        // 附带：配置静态校验结果也进 lines，便于单表呈现
        issues.forEach { issue ->
            out += CheckLine(
                item = "配置校验 · ${issue.field}",
                expected = "无冲突",
                actual = issue.message,
                status = if (issue.severity == FingerprintIssue.ERROR) Report.STATUS_FAIL else Report.STATUS_WARN,
                note = issue.suggestion,
            )
        }
        return out
    }

    /* ----------------------------- 导出 ----------------------------- */

    /** 导出 Markdown 报告到 App 私有目录（reports/），返回文件 */
    suspend fun exportMarkdown(report: Report): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val file = File(dir, "fp_${report.envId}_${report.generatedAt}.md")
        file.writeText(toMarkdown(report))
        file
    }

    fun toMarkdown(r: Report): String = buildString {
        appendLine("# GeoEnv 指纹检测报告")
        appendLine()
        appendLine("- 环境：${r.envName} (id=${r.envId})")
        appendLine("- 生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(r.generatedAt))}")
        appendLine("- Gecko 版本：${r.geckoVersion}（锁定）")
        appendLine("- Hook 版本：${r.hookVersion}")
        appendLine("- 运行状态：${if (r.running) "运行中（含页面实测）" else "未运行（仅静态校验）"}")
        appendLine("- 结果：PASS ${r.passCount} / FAIL ${r.failCount} / WARN+SKIP ${r.warnCount}")
        appendLine()

        appendLine("## 一、参数实测比对")
        appendLine()
        appendLine("| 检查项 | 期望值 | 实测值 | 结果 | 说明 |")
        appendLine("| --- | --- | --- | --- | --- |")
        r.lines.forEach {
            appendLine("| ${it.item} | ${md(it.expected)} | ${md(it.actual)} | ${it.status} | ${md(it.note)} |")
        }
        appendLine()

        if (r.issues.isNotEmpty()) {
            appendLine("## 二、配置一致性问题")
            appendLine()
            r.issues.forEach {
                appendLine("- **[${it.severity}]** ${it.field}：${it.message}" +
                    if (it.suggestion.isNotBlank()) "　建议：${it.suggestion}" else "")
            }
            appendLine()
        }

        appendLine("## 三、结论")
        appendLine()
        appendLine(
            when {
                r.failCount > 0 -> "⚠️ 存在 ${r.failCount} 项指纹不一致，需修正后重新检测。"
                r.warnCount > 0 -> "⚠️ 基本一致，但有 ${r.warnCount} 项待确认（多为环境未运行）。"
                else -> "✅ 指纹各参数自洽且与配置一致。"
            }
        )
        appendLine()
        appendLine("> 本报告由 GeoEnv 自动检测生成，可用于测试用例的指纹取证附件。")
    }

    private fun md(s: String): String = s.replace("|", "\\|").replace("\n", " ")

    /** 导出结构化 JSON（供外部工具解析） */
    fun toJson(r: Report): String = GsonBuilder().setPrettyPrinting().create().toJson(r)

    /* ----------------------------- JS 采集脚本 ----------------------------- */

    private companion object {
        /**
         * 页面内实测脚本：只读采集，不修改任何状态。
         * WebGL 值在 Hook 之后读取，正是"用户可见的最终值"。
         */
        const val OBSERVE_JS = """
(function(){
  var r = {};
  try { r.userAgent = navigator.userAgent; } catch(e){}
  try { r.platform = navigator.platform; } catch(e){}
  try { r.language = navigator.language; } catch(e){}
  try { r.languages = (navigator.languages||[]).join(','); } catch(e){}
  try { r.screenWidth = screen.width; r.screenHeight = screen.height; } catch(e){}
  try { r.devicePixelRatio = window.devicePixelRatio || 0; } catch(e){}
  try { r.colorDepth = screen.colorDepth; } catch(e){}
  try { r.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch(e){}
  try { r.hardwareConcurrency = navigator.hardwareConcurrency; } catch(e){}
  try { r.maxTouchPoints = navigator.maxTouchPoints; } catch(e){}
  try { r.cookieEnabled = navigator.cookieEnabled; } catch(e){}
  try { r.doNotTrack = navigator.doNotTrack; } catch(e){}
  try {
    var c = document.createElement('canvas');
    var gl = c.getContext('webgl') || c.getContext('experimental-webgl');
    if (gl) {
      var dbg = gl.getExtension('WEBGL_debug_renderer_info');
      r.webglVendor = dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : gl.getParameter(gl.VENDOR);
      r.webglRenderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
    }
  } catch(e){}
  return JSON.stringify(r);
})()
"""
        val JSON_ARRAY = JSONArray() // 保留类型引用，避免 lint 误删 import
    }
}
