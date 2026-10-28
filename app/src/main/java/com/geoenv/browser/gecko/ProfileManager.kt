package com.geoenv.browser.gecko

import android.content.Context
import com.geoenv.browser.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * 独立 Profile 目录与 Gecko DebugConfig（YAML）管理。
 *
 * 隔离机制（对应 docs/geo-env-tech.md §6.2 与需求 3.1）：
 *  1. 每个环境一个独立 profile 目录 `filesDir/profiles/env_<id>`；
 *  2. 通过 `GeckoRuntimeSettings.configFilePath` 注入 DebugConfig YAML，其
 *     `args: [--profile, <dir>]` 让 Gecko 把该目录当作 profile 根（cookie / localStorage /
 *     缓存 / 登录状态全部落在此目录）；
 *  3. `prefs` 段注入代理、语言、时区、Marionette 端口等进程级配置；
 *  4. `env` 段注入 `MOZ_MARIONETTE=1`、`TZ=<timezone>` 等环境变量。
 *
 * 降级保护：Gecko 可能忽略 `--profile`（不同版本行为差异）。启动后运行 [probeProfileEffective]，
 * 检查目标目录是否真的出现了 `prefs.js` / `cookies.sqlite` 等 Gecko产物；若无效则记录告警并回退到
 * 默认 profile 目录 + `contextId` 会话级隔离（双保险的第二层）。
 */
class ProfileManager(private val context: Context) {

    /** profile 根目录 */
    fun profileRoot(): File = File(context.filesDir, "profiles").apply { if (!exists()) mkdirs() }

    /** 单环境 profile 目录 */
    fun profileDir(envId: Long): File = File(profileRoot(), "env_$envId")

    fun ensureProfileDir(envId: Long): File = profileDir(envId).apply {
        if (!exists()) mkdirs()
    }

    fun deleteProfile(envId: Long) {
        runCatching { profileDir(envId).deleteRecursively() }
            .onFailure { Timber.w(it, "delete profile failed env=$envId") }
    }

    /** 当前进程的 profile 配置文件路径 */
    fun configFileFor(envId: Long): File =
        File(profileDir(envId), "geckoview-config.yaml")

    /**
     * 生成 DebugConfig YAML。
     *
     * Schema（Gecko 158 `org.mozilla.gecko.util.DebugConfig`）：
     * ```
     * prefs:  { key: value }
     * env:    { KEY: VALUE }
     * args:   [ --profile, /abs/path ]
     * ```
     *
     * 注意 YAML 值需按字符串处理：Gecko 侧 `prefs` 的 value 是 `Object`，
     * 布尔/数字直接写裸值即可；含冒号或特殊字符的字符串必须加引号。
     */
    fun writeConfig(
        envId: Long,
        prefs: Map<String, Any>,
        env: Map<String, String>,
        profilePath: String,
    ): File {
        val dir = ensureProfileDir(envId)
        val file = configFileFor(envId)
        val sb = StringBuilder()
        sb.appendLine("# GeoEnv generated DebugConfig for env_$envId")
        sb.appendLine("# generatedAt=${System.currentTimeMillis()}")

        sb.appendLine("args:")
        // 必须使用绝对路径；--profile 决定 cookie/storage 落点
        sb.appendLine("  - \"--profile\"")
        sb.appendLine("  - \"${escapeYaml(profilePath)}\"")

        if (env.isNotEmpty()) {
            sb.appendLine("env:")
            env.entries.sortedBy { it.key }.forEach { (k, v) ->
                sb.appendLine("  \"$k\": \"${escapeYaml(v)}\"")
            }
        }

        if (prefs.isNotEmpty()) {
            sb.appendLine("prefs:")
            prefs.entries.sortedBy { it.key }.forEach { (k, v) ->
                val value = when (v) {
                    is Boolean, is Int, is Long, is Float, is Double -> v.toString()
                    else -> "\"${escapeYaml(v.toString())}\""
                }
                sb.appendLine("  \"$k\": $value")
            }
        }

        file.writeText(sb.toString())
        Timber.i("DebugConfig written: %s (%d bytes)", file.absolutePath, file.length())
        return file
    }

    private fun escapeYaml(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    /**
     * 运行时探测：`--profile` 是否真正生效。
     *
     * Gecko 在 profile 目录写入 `prefs.js`（至少一次写操作后）以及
     * `cookies.sqlite` / `webappsstore.sqlite` / `storage` 等。
     * 返回 true 表示确认生效。
     */
    fun probeProfileEffective(envId: Long): Boolean {
        val dir = profileDir(envId)
        if (!dir.exists()) return false
        val markers = listOf("prefs.js", "cookies.sqlite", "webappsstore.sqlite", "storage", "times.json")
        val hit = markers.firstOrNull { File(dir, it).exists() }
        return if (hit != null) {
            Timber.i("profile probe OK env=%d marker=%s", envId, hit)
            true
        } else {
            Timber.w("profile probe FAILED env=%d dir=%s", envId, dir.absolutePath)
            false
        }
    }

    /** 环境磁盘占用（MB），用于 UI 展示与清理建议。 */
    fun usageMb(envId: Long): Double {
        val dir = profileDir(envId)
        if (!dir.exists()) return 0.0
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return bytes / (1024.0 * 1024.0)
    }

    /** 全部 profile 目录占用（MB）。 */
    fun totalUsageMb(): Double {
        val root = profileRoot()
        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return bytes / (1024.0 * 1024.0)
    }

    /** 清理无主 profile（环境已删除但目录残留）。 */
    fun gcOrphanProfiles(knownEnvIds: Set<Long>): List<String> {
        val removed = mutableListOf<String>()
        profileRoot().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val id = dir.name.removePrefix("env_").toLongOrNull()
            if (id == null || id !in knownEnvIds) {
                if (dir.deleteRecursively()) removed += dir.name
            }
        }
        return removed
    }

    companion object {
        /** Gecko 版本锁定标识（需求 4.4 / 非功能） */
        const val GECKO_VERSION = "158.0.20261021001945"

        fun isDebugConfigSupported(): Boolean = true
    }
}
