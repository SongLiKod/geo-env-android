package com.geoenv.browser.gecko

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.util.Locale
import kotlin.random.Random

/**
 * 指纹配置模型（需求 3.3）。
 *
 * 可配置项：UA、屏幕分辨率、设备型号、时区、语言、Canvas、WebGL、Audio、WebRTC、插件列表、客户端 Hint。
 * 序列化为 JSON 存入 `environment.fingerprintJson` 与 `fingerprint_snapshot.fingerprintJson`。
 */
data class FingerprintConfig(

    // ---------------- UA ----------------
    /** 完整 User-Agent 字符串；为空则由 [uaPlatform]/[uaVersion] 拼装 */
    val userAgent: String = "",

    // ---------------- 屏幕 ----------------
    val screenWidth: Int = 412,
    val screenHeight: Int = 915,
    /** 逻辑像素密度 DPI，影响 devicePixelRatio */
    val screenDpi: Int = 440,
    /** 物理像素（用于 JS 层 window.screen 宽高与 window.outerWidth 一致性） */
    val availWidth: Int = 412,
    val availHeight: Int = 915,
    val colorDepth: Int = 24,
    val pixelRatio: Double = 2.75,

    // ---------------- 设备型号 ----------------
    val deviceModel: String = "Pixel 7",
    val deviceManufacturer: String = "Google",
    /** UA 中的平台片段，如 "Linux; Android 13; Pixel 7" */
    val uaPlatform: String = "Linux; Android 13",
    /** UA 中的 Chrome 主版本，需与内核 UA 保持一致 */
    val uaChromeVersion: Int = 131,
    val uaMobile: Boolean = true,
    /** 客户端 Hint 平台/平台版本/模型/完整版本列表 */
    val chPlatform: String = "Android",
    val chPlatformVersion: String = "13.0.0",
    val chFullVersion: String = "131.0.0.0",
    val chModel: String = "Pixel 7",
    val chArchitecture: String = "arm",
    val chBitness: String = "64",
    val chDeviceMemory: Int = 8,
    val chUaFullVersionList: String = "Chromium/131.0.0.0; Not?A_Brand;24.0.0.0",

    // ---------------- 时区 / 语言 ----------------
    /** IANA 时区，如 Asia/Shanghai；同时用于 TZ 环境变量与 JS Intl */
    val timezone: String = "Asia/Shanghai",
    /** 语言标签列表，第一个为主语言；同时驱动 intl.accept_languages */
    val languages: List<String> = listOf("zh-CN", "zh", "en-US", "en"),

    // ---------------- Canvas 指纹 ----------------
    val canvasMode: String = MODE_NOISE,      // OFF / NOISE / BLOCK
    val canvasNoiseSeed: Long = 0L,
    val canvasNoiseStrength: Double = 0.02,

    // ---------------- WebGL 指纹 ----------------
    /** 伪装的 GPU 渲染器 / 厂商字符串；为空则保持默认 */
    val webglVendor: String = "Google Inc. (ARM)",
    val webglRenderer: String = "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader Driver)",
    /** 是否禁用 WEBGL_debug_renderer_info 泄露 */
    val webglMaskDebugInfo: Boolean = true,
    val webglMode: String = MODE_NOISE,

    // ---------------- Audio 指纹 ----------------
    val audioMode: String = MODE_NOISE,
    val audioNoiseSeed: Long = 0L,

    // ---------------- WebRTC ----------------
    /** DISABLE / DISABLE_NON_PROXIED_UDP / PUBLIC_ONLY / DEFAULT */
    val webrtcPolicy: String = "DISABLE",
    /** 是否屏蔽本地候选（host candidate）暴露内网 IP */
    val webrtcBlockLocalCandidates: Boolean = true,

    // ---------------- 插件列表 ----------------
    val pluginMode: String = MODE_EMPTY,      // DEFAULT / EMPTY / CUSTOM
    val customPlugins: List<PluginEntry> = emptyList(),

    // ---------------- 杂项 ----------------
    /** 屏蔽 hardwareConcurrency 伪装值；<=0 表示不伪装 */
    val hardwareConcurrency: Int = 8,
    /** 屏蔽 maxTouchPoints */
    val maxTouchPoints: Int = 5,
    /** 是否伪装 Do Not Track */
    val doNotTrack: Boolean = false,
    /** 屏蔽/伪装 navigator.vendor */
    val vendor: String = "Google Inc.",
    /** 屏蔽/伪装 navigator.appVersion 片段 */
    val appVersion: String = "",
    /** 语言首选项在 JS navigator.language 的值（默认取 languages[0]） */
    val navigatorLanguage: String = "",
    /** 是否启用 Gecko 内置 fingerprinting protection（附加保护层） */
    val geckoFingerprintingProtection: Boolean = false,
    /** 自定义附加 JS Hook（在标准 Hook 之后执行） */
    val extraJs: String = "",
    /** Hook 脚本版本，跟随内核版本管理（开发规范 5） */
    val hookVersion: String = HookScript.HOOK_VERSION,
) {

    data class PluginEntry(val name: String, val filename: String, val description: String = "")

    /** 解析出最终 UA */
    fun resolveUserAgent(): String {
        if (userAgent.isNotBlank()) return userAgent
        val mobile = if (uaMobile) " Mobile" else ""
        return "Mozilla/5.0 ($uaPlatform) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$uaChromeVersion.0.0.0$mobile Safari/537.36"
    }

    fun resolveNavigatorLanguage(): String =
        navigatorLanguage.ifBlank { languages.firstOrNull() ?: "en-US" }

    /** UA 中的 Chrome 版本（用于 UA-CH 一致性校验） */
    fun uaChromeMajor(): Int {
        val ua = resolveUserAgent()
        return Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull() ?: uaChromeVersion
    }

    /** UA 平台片段（用于 UA 与 UA-CH 平台一致性校验） */
    fun uaPlatformToken(): String {
        val ua = resolveUserAgent()
        return when {
            ua.contains("Windows NT") -> "Windows"
            ua.contains("Android") -> "Android"
            ua.contains("iPhone") || ua.contains("iPad") -> "iOS"
            ua.contains("Mac OS X") -> "macOS"
            ua.contains("CrOS") -> "ChromeOS"
            ua.contains("Linux") -> "Linux"
            else -> ""
        }
    }

    companion object {
        const val MODE_OFF = "OFF"
        const val MODE_NOISE = "NOISE"
        const val MODE_BLOCK = "BLOCK"
        const val MODE_EMPTY = "EMPTY"
        const val MODE_DEFAULT = "DEFAULT"
        const val MODE_CUSTOM = "CUSTOM"

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun fromJson(json: String): FingerprintConfig = runCatching {
            gson.fromJson(json, FingerprintConfig::class.java)
        }.getOrNull() ?: FingerprintConfig()

        fun toJson(cfg: FingerprintConfig): String = gson.toJson(cfg)

        /**
         * 生成一套**默认自洽**的指纹（需求 3.1.1）。
         *
         * "自洽"指 UA / UA-CH / 屏幕 / DPI / 语言 / 时区 / 平台互相匹配，
         * 不会出现 "Android UA + Windows 屏幕尺寸" 这类冲突。
         */
        fun createDefault(random: Random = Random.Default): FingerprintConfig {
            val device = DEVICES.random(random)
            val tz = TIMEZONES.random(random)
            val lang = LANGUAGES.random(random)
            val chromeVer = CHROME_VERSIONS.random(random)
            return FingerprintConfig(
                userAgent = "",
                screenWidth = device.width,
                screenHeight = device.height,
                screenDpi = device.dpi,
                availWidth = device.width,
                availHeight = device.height - device.navBar,
                colorDepth = 24,
                pixelRatio = device.dpi / 160.0,
                deviceModel = device.model,
                deviceManufacturer = device.manufacturer,
                uaPlatform = "Linux; Android ${device.androidVersion}",
                uaChromeVersion = chromeVer,
                uaMobile = true,
                chPlatform = "Android",
                chPlatformVersion = "${device.androidVersion}.0.0",
                chFullVersion = "$chromeVer.0.0.0",
                chModel = device.model,
                chArchitecture = "arm",
                chBitness = "64",
                chDeviceMemory = device.ramGb,
                chUaFullVersionList = "Chromium/$chromeVer.0.0.0; Not?A_Brand;24.0.0.0",
                timezone = tz,
                languages = lang,
                canvasMode = MODE_NOISE,
                canvasNoiseSeed = random.nextLong(),
                canvasNoiseStrength = 0.02,
                webglVendor = device.webglVendor,
                webglRenderer = device.webglRenderer,
                webglMaskDebugInfo = true,
                webglMode = MODE_NOISE,
                audioMode = MODE_NOISE,
                audioNoiseSeed = random.nextLong(),
                webrtcPolicy = "DISABLE",
                webrtcBlockLocalCandidates = true,
                pluginMode = MODE_EMPTY,
                customPlugins = emptyList(),
                hardwareConcurrency = device.cores,
                maxTouchPoints = if (device.mobile) 5 else 0,
                vendor = "Google Inc.",
            )
        }

        /** 设备库：分辨率 / DPI / RAM / GPU 必须互相匹配 */
        data class DevicePreset(
            val model: String,
            val manufacturer: String,
            val androidVersion: String,
            val width: Int,
            val height: Int,
            val navBar: Int,
            val dpi: Int,
            val ramGb: Int,
            val cores: Int,
            val mobile: Boolean,
            val webglVendor: String,
            val webglRenderer: String,
        )

        val DEVICES = listOf(
            DevicePreset("Pixel 7", "Google", "13", 412, 915, 128, 440, 8, 8, true,
                "Google Inc. (ARM)",
                "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader Driver)"),
            DevicePreset("Pixel 8 Pro", "Google", "14", 448, 998, 134, 480, 12, 8, true,
                "Google Inc. (ARM)",
                "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader Driver)"),
            DevicePreset("SM-G991B", "samsung", "13", 384, 854, 116, 420, 8, 8, true,
                "Qualcomm",
                "Adreno (TM) 640"),
            DevicePreset("SM-S9180", "samsung", "14", 384, 854, 122, 420, 12, 8, true,
                "Qualcomm",
                "Adreno (TM) 740"),
            DevicePreset("M2102J20SG", "Xiaomi", "12", 393, 873, 122, 440, 6, 8, true,
                "Qualcomm",
                "Adreno (TM) 619"),
            DevicePreset("CPH2413", "OPPO", "13", 393, 873, 128, 440, 8, 8, true,
                "ARM",
                "Mali-G710 MC10"),
            DevicePreset("V2254A", "vivo", "13", 393, 873, 130, 440, 8, 8, true,
                "ARM",
                "Immortalis-G715"),
            DevicePreset("2304FPN6DC", "Xiaomi", "14", 393, 873, 124, 440, 8, 8, true,
                "Qualcomm",
                "Adreno (TM) 730"),
        )

        val TIMEZONES = listOf(
            "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Taipei", "Asia/Singapore",
            "Asia/Tokyo", "Asia/Seoul", "Europe/London", "Europe/Berlin",
            "Europe/Paris", "America/New_York", "America/Los_Angeles", "America/Sao_Paulo",
            "Australia/Sydney", "Asia/Kolkata", "Asia/Dubai",
        )

        val LANGUAGES: List<List<String>> = listOf(
            listOf("zh-CN", "zh", "en-US", "en"),
            listOf("zh-TW", "zh", "en-US", "en"),
            listOf("en-US", "en"),
            listOf("en-GB", "en"),
            listOf("ja-JP", "ja", "en-US", "en"),
            listOf("ko-KR", "ko", "en-US", "en"),
            listOf("de-DE", "de", "en-US", "en"),
            listOf("fr-FR", "fr", "en-US", "en"),
            listOf("pt-BR", "pt", "en-US", "en"),
            listOf("es-ES", "es", "en-US", "en"),
            listOf("hi-IN", "hi", "en-US", "en"),
            listOf("ru-RU", "ru", "en-US", "en"),
        )

        val CHROME_VERSIONS = listOf(131, 130, 129, 128, 127, 126, 125, 124, 123, 122, 121, 120)
    }
}

/* =====================================================================================
 * 指纹一致性校验（需求 3.3：内置指纹一致性校验，禁止冲突参数）
 * ===================================================================================*/
data class FingerprintIssue(
    /** ERROR = 冲突（必须阻止保存）；WARN = 可疑（提示但允许） */
    val severity: String,
    val field: String,
    val message: String,
    val suggestion: String = "",
) {
    companion object {
        const val ERROR = "ERROR"
        const val WARN = "WARN"
    }
}

object FingerprintValidator {

    /**
     * 校验指纹参数是否自洽。
     * @return 问题列表；包含 ERROR 时禁止保存。
     */
    fun validate(cfg: FingerprintConfig): List<FingerprintIssue> {
        val issues = mutableListOf<FingerprintIssue>()

        // 1. 屏幕尺寸
        if (cfg.screenWidth <= 0 || cfg.screenHeight <= 0) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "screen", "屏幕分辨率必须为正数", "使用默认 412x915"
            )
        }
        if (cfg.screenDpi < 96 || cfg.screenDpi > 1000) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "screenDpi", "DPI 范围应在 96-1000 之间", "常用值：320/440/480"
            )
        }
        // 分辨率与 DPI 的物理尺寸合理性（粗略）：ppi = sqrt(w^2+h^2)/inches
        val expectedRatio = cfg.screenDpi / 160.0
        val declaredRatio = cfg.pixelRatio
        if (declaredRatio > 0 && kotlin.math.abs(expectedRatio - declaredRatio) / expectedRatio > 0.25) {
            issues += FingerprintIssue(
                FingerprintIssue.WARN, "pixelRatio",
                "pixelRatio(${declaredRatio}) 与 DPI(${cfg.screenDpi}) 推算值(${String.format(Locale.ROOT, "%.2f", expectedRatio)})偏差较大",
                "建议 pixelRatio = DPI / 160"
            )
        }
        if (cfg.availWidth > cfg.screenWidth || cfg.availHeight > cfg.screenHeight) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "availWidth",
                "可用区域不能大于物理屏幕", "availWidth <= screenWidth 且 availHeight <= screenHeight"
            )
        }
        if (cfg.colorDepth !in setOf(16, 24, 30, 32)) {
            issues += FingerprintIssue(
                FingerprintIssue.WARN, "colorDepth", "非常见色深 $cfg.colorDepth", "建议 24 或 32"
            )
        }

        // 2. UA 自洽
        val ua = cfg.resolveUserAgent()
        if (ua.isBlank()) {
            issues += FingerprintIssue(FingerprintIssue.ERROR, "userAgent", "UA 不能为空")
        } else {
            if (!ua.startsWith("Mozilla/5.0")) {
                issues += FingerprintIssue(
                    FingerprintIssue.WARN, "userAgent", "UA 未以 Mozilla/5.0 开头", "浏览器 UA 标准前缀"
                )
            }
            val uaChrome = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull()
            if (uaChrome == null) {
                issues += FingerprintIssue(
                    FingerprintIssue.WARN, "userAgent",
                    "UA 中未找到 Chrome 版本号，与客户端 Hint 无法对齐", "UA 应包含 Chrome/<ver>"
                )
            } else if (uaChrome != cfg.uaChromeVersion) {
                issues += FingerprintIssue(
                    FingerprintIssue.ERROR, "uaChromeVersion",
                    "UA 中 Chrome/$uaChrome 与 uaChromeVersion=${cfg.uaChromeVersion} 冲突",
                    "两者必须一致，否则 UA 与 UA-CH 版本不匹配"
                )
            }
        }

        // 3. UA 与 UA-CH 一致性
        val uaPlatformToken = cfg.uaPlatformToken()
        if (uaPlatformToken.isNotBlank() && cfg.chPlatform.isNotBlank()) {
            val chToUa = when (cfg.chPlatform) {
                "Windows" -> "Windows"
                "Android" -> "Android"
                "iOS" -> "iOS"
                "macOS" -> "macOS"
                "ChromeOS" -> "ChromeOS"
                "Linux" -> "Linux"
                else -> ""
            }
            if (chToUa.isNotBlank() && chToUa != uaPlatformToken) {
                issues += FingerprintIssue(
                    FingerprintIssue.ERROR, "chPlatform",
                    "UA 平台($uaPlatformToken) 与 UA-CH 平台(${cfg.chPlatform}) 冲突",
                    "客户端 Hint 必须与 User-Agent 平台一致"
                )
            }
        }
        val chMajor = Regex("(\\d+)\\.").find(cfg.chFullVersion)?.groupValues?.get(1)?.toIntOrNull()
        if (chMajor != null && cfg.uaChromeVersion > 0 && chMajor != cfg.uaChromeVersion) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "chFullVersion",
                "UA-CH 完整版本($chMajor) 与 UA Chrome 版本(${cfg.uaChromeVersion}) 冲突",
                "两者必须同主版本"
            )
        }
        val chPlatformMajor = cfg.chPlatformVersion.substringBefore(".").toIntOrNull()
        val uaAndroidMajor = Regex("Android (\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull()
        if (chPlatformMajor != null && uaAndroidMajor != null && chPlatformMajor != uaAndroidMajor) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "chPlatformVersion",
                "UA 中 Android $uaAndroidMajor 与 CH 平台版本 ${cfg.chPlatformVersion} 冲突",
                "需一致"
            )
        }
        if (cfg.chModel.isNotBlank() && cfg.deviceModel.isNotBlank() && cfg.chModel != cfg.deviceModel) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "chModel",
                "CH model(${cfg.chModel}) 与 deviceModel(${cfg.deviceModel}) 冲突",
                "需一致"
            )
        }

        // 4. 时区
        if (cfg.timezone.isBlank()) {
            issues += FingerprintIssue(FingerprintIssue.ERROR, "timezone", "时区不能为空")
        } else {
            val valid = runCatching {
                java.util.TimeZone.getTimeZone(cfg.timezone).id == cfg.timezone
            }.getOrDefault(false)
            if (!valid) {
                issues += FingerprintIssue(
                    FingerprintIssue.ERROR, "timezone",
                    "无效的 IANA 时区: ${cfg.timezone}", "例如 Asia/Shanghai"
                )
            }
        }

        // 5. 语言
        if (cfg.languages.isEmpty()) {
            issues += FingerprintIssue(FingerprintIssue.ERROR, "languages", "语言列表不能为空")
        } else {
            cfg.languages.forEachIndexed { i, l ->
                if (!Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$").matches(l)) {
                    issues += FingerprintIssue(
                        FingerprintIssue.ERROR, "languages[$i]",
                        "非法语言标签: $l", "格式如 zh-CN / en-US"
                    )
                }
            }
            if (cfg.languages.distinct().size != cfg.languages.size) {
                issues += FingerprintIssue(
                    FingerprintIssue.WARN, "languages", "语言列表存在重复项"
                )
            }
        }

        // 6. WebRTC
        val validRtcPolicies = setOf("DEFAULT", "DISABLE", "DISABLE_NON_PROXIED_UDP", "PUBLIC_ONLY")
        if (cfg.webrtcPolicy !in validRtcPolicies) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "webrtcPolicy",
                "非法 WebRTC 策略: ${cfg.webrtcPolicy}", "可选: ${validRtcPolicies.joinToString()}"
            )
        }

        // 7. 模式枚举
        val modeFields = mapOf(
            "canvasMode" to cfg.canvasMode,
            "webglMode" to cfg.webglMode,
            "audioMode" to cfg.audioMode,
        )
        val validModes = setOf(FingerprintConfig.MODE_OFF, FingerprintConfig.MODE_NOISE, FingerprintConfig.MODE_BLOCK)
        modeFields.forEach { (f, v) ->
            if (v !in validModes) {
                issues += FingerprintIssue(FingerprintIssue.ERROR, f, "非法模式: $v", "OFF/NOISE/BLOCK")
            }
        }
        if (cfg.pluginMode !in setOf(
                FingerprintConfig.MODE_DEFAULT, FingerprintConfig.MODE_EMPTY, FingerprintConfig.MODE_CUSTOM
            )
        ) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "pluginMode", "非法插件模式: ${cfg.pluginMode}", "DEFAULT/EMPTY/CUSTOM"
            )
        }
        if (cfg.pluginMode == FingerprintConfig.MODE_CUSTOM && cfg.customPlugins.isEmpty()) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "customPlugins", "选择 CUSTOM 时必须提供至少一个插件"
            )
        }

        // 8. 数值范围
        if (cfg.hardwareConcurrency !in 0..128) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "hardwareConcurrency", "CPU 核数超出范围 0-128"
            )
        }
        if (cfg.maxTouchPoints !in 0..20) {
            issues += FingerprintIssue(FingerprintIssue.ERROR, "maxTouchPoints", "触点数超出范围 0-20")
        }
        if (cfg.canvasNoiseStrength < 0 || cfg.canvasNoiseStrength > 1) {
            issues += FingerprintIssue(
                FingerprintIssue.ERROR, "canvasNoiseStrength", "噪声强度必须在 0-1 之间"
            )
        }

        // 9. 语言与 UA 国家码一致性（软校验）
        val primary = cfg.resolveNavigatorLanguage()
        val uaCountry = Regex("en-([A-Z]{2})").find(ua)?.groupValues?.get(1)
        if (uaCountry != null && primary.startsWith("zh")) {
            issues += FingerprintIssue(
                FingerprintIssue.WARN, "languages",
                "UA 国家码 en-$uaCountry 与首选语言 $primary 不一致", "可接受，但建议统一"
            )
        }

        return issues
    }

    fun hasError(issues: List<FingerprintIssue>): Boolean =
        issues.any { it.severity == FingerprintIssue.ERROR }

    /** 汇总为人类可读报告 */
    fun summarize(issues: List<FingerprintIssue>): String {
        if (issues.isEmpty()) return "指纹参数校验通过，未发现冲突"
        return issues.joinToString("\n") {
            "[${it.severity}] ${it.field}: ${it.message}" +
                if (it.suggestion.isNotBlank()) "（建议：${it.suggestion}）" else ""
        }
    }
}
