package com.geoenv.browser.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geoenv.browser.R
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.BackupRecord
import com.geoenv.browser.db.ThemeConfig
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.manager.BackupManager
import com.geoenv.browser.manager.EnvManager
import com.geoenv.browser.service.KeepAliveService
import com.google.gson.GsonBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 设置页 ViewModel（需求 3.6 / 3.8 / 4）：
 * 主题（模式/主色/密度/导入导出）、并发上限、保活与电池优化、自动备份与环境包导入导出。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locator: ServiceLocator,
    private val backupManager: BackupManager,
    private val audit: AuditRepository,
    val envManager: EnvManager,
) : ViewModel() {

    /* ----------------------------- 主题 ----------------------------- */

    private val _theme = MutableStateFlow(ThemeConfig.DEFAULT)
    val theme: StateFlow<ThemeConfig> = _theme.asStateFlow()

    init {
        viewModelScope.launch {
            locator.database.themeDao().observe().collect { cfg ->
                _theme.value = cfg ?: ThemeConfig.DEFAULT
            }
        }
    }

    fun setThemeMode(mode: String) = upsertTheme { it.copy(mode = mode) }

    fun setDensity(density: String) = upsertTheme { it.copy(density = density) }

    fun setPrimaryHex(hex: String) {
        if (!hex.matches(Regex("^#?[0-9a-fA-F]{6}$"))) return
        val normalized = if (hex.startsWith("#")) hex else "#$hex"
        upsertTheme { it.copy(primaryHex = normalized) }
    }

    private fun upsertTheme(transform: (ThemeConfig) -> ThemeConfig) {
        viewModelScope.launch {
            runCatching {
                val cur = locator.database.themeDao().get() ?: ThemeConfig.DEFAULT
                locator.database.themeDao().upsert(transform(cur).copy(updateTime = System.currentTimeMillis()))
                audit.log(action = Action.MODIFY_THEME, note = "主题配置变更")
            }
        }
    }

    /** 导出 theme.json */
    fun exportTheme(): File? = runCatching {
        val dir = File(context.filesDir, "export").apply { mkdirs() }
        val f = File(dir, "theme_${System.currentTimeMillis()}.json")
        f.writeText(GsonBuilder().setPrettyPrinting().create().toJson(_theme.value))
        f
    }.getOrNull()

    /** 导入 theme.json（内容不合法时抛错） */
    fun importTheme(json: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val cfg = GsonBuilder().create().fromJson(json, ThemeConfig::class.java)
                requireNotNull(cfg) { "内容不是有效的主题配置" }
                locator.database.themeDao().upsert(cfg.copy(id = 1, updateTime = System.currentTimeMillis()))
                audit.log(action = Action.MODIFY_THEME, note = "导入主题配置")
                onDone(true, null)
            }.onFailure { onDone(false, it.message) }
        }
    }

    fun defaultThemeJson(): String =
        GsonBuilder().setPrettyPrinting().create().toJson(_theme.value)

    /* ----------------------------- 并发上限 ----------------------------- */

    val concurrencyLimit: StateFlow<Int> = envManager.concurrencyLimit

    fun setConcurrencyRelaxed(relaxed: Boolean) {
        viewModelScope.launch { envManager.setConcurrencyRelaxed(relaxed) }
    }

    /* ----------------------------- 保活 / 电源 ----------------------------- */

    private val prefs by lazy {
        context.getSharedPreferences("geoenv_settings", Context.MODE_PRIVATE)
    }

    private val _keepAlive = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALIVE, false))
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    private val _wakeLock = MutableStateFlow(prefs.getBoolean(KEY_WAKE_LOCK, false))
    val wakeLock: StateFlow<Boolean> = _wakeLock.asStateFlow()

    fun setKeepAlive(enabled: Boolean) {
        _keepAlive.value = enabled
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
        if (enabled) {
            KeepAliveService.start(context)
        } else {
            KeepAliveService.stop(context)
        }
        viewModelScope.launch {
            audit.log(action = Action.SETTINGS, note = "后台保活 ${if (enabled) "开启" else "关闭"}")
        }
    }

    fun setWakeLock(enabled: Boolean) {
        _wakeLock.value = enabled
        prefs.edit().putBoolean(KEY_WAKE_LOCK, enabled).apply()
        KeepAliveService.setWakeLock(context, enabled)
        viewModelScope.launch {
            audit.log(action = Action.SETTINGS, note = "保持唤醒 ${if (enabled) "开启" else "关闭"}")
        }
    }

    /** 是否已忽略电池优化（未忽略时引导用户） */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 引导用户把本应用加入电池优化白名单（需求 4.3 保活引导） */
    fun requestIgnoreBatteryOptimizations(onDone: (Boolean) -> Unit) {
        onDone(runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return@runCatching true
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}"),
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false))
    }

    /* ----------------------------- 导入导出 / 备份 ----------------------------- */

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun msg(s: String) {
        _messages.tryEmit(s)
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun exportPackage(
        passphrase: String,
        includeProfiles: Boolean,
        includeAudit: Boolean,
        envIds: List<Long>,
        onDone: (Result<BackupManager.PackageStats>) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                val dir = File(context.filesDir, "export").apply { mkdirs() }
                val out = File(dir, "geoenv_${System.currentTimeMillis()}.geoenv")
                backupManager.export(
                    passphrase = passphrase,
                    outFile = out,
                    options = BackupManager.ExportOptions(
                        envIds = envIds,
                        includeProfiles = includeProfiles,
                        includeAudit = includeAudit,
                    ),
                )
            }
            _busy.value = false
            msg(
                result.fold(
                    onSuccess = { "已导出 ${it.sizeBytes / 1024} KB / ${it.envCount} 个环境" },
                    onFailure = { "导出失败：${it.message}" },
                ),
            )
            onDone(result)
        }
    }

    fun previewPackage(
        file: File,
        passphrase: String,
        onDone: (Result<BackupManager.ImportPreview>) -> Unit,
    ) {
        viewModelScope.launch { onDone(runCatching { backupManager.preview(file, passphrase) }) }
    }

    fun importPackage(
        file: File,
        passphrase: String,
        onDone: (Result<BackupManager.ImportResult>) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            val r = runCatching { backupManager.import(input = file, passphrase = passphrase) }
            _busy.value = false
            msg(r.fold(onSuccess = { "导入完成：${it.importedEnvs} 个环境" }, onFailure = { "导入失败：${it.message}" }))
            onDone(r)
        }
    }

    fun runAutoBackup() {
        viewModelScope.launch {
            _busy.value = true
            val f = backupManager.autoBackup()
            _busy.value = false
            msg(if (f != null) "自动备份完成：${f.name}" else "自动备份失败")
        }
    }

    private val _backups = MutableStateFlow<List<BackupRecord>>(emptyList())
    val backups: StateFlow<List<BackupRecord>> = _backups.asStateFlow()

    fun refreshBackups() {
        viewModelScope.launch {
            runCatching { _backups.value = backupManager.recentBackups() }
        }
    }

    /* ----------------------------- 关于 ----------------------------- */

    data class AboutInfo(
        val versionName: String,
        val versionCode: Int,
        val geckoVersion: String,
        val hookVersion: String,
        val minSdk: Int,
        val abi: String,
    )

    val about: AboutInfo = AboutInfo(
        versionName = runCatching { com.geoenv.browser.BuildConfig.VERSION_NAME }.getOrDefault("1.0.0"),
        versionCode = runCatching { com.geoenv.browser.BuildConfig.VERSION_CODE }.getOrDefault(1),
        geckoVersion = BackupManager.BuildVersion.GECKO,
        hookVersion = com.geoenv.browser.gecko.HookScript.HOOK_VERSION,
        minSdk = 26,
        abi = "arm64-v8a",
    )

    /** 存储占用概览 */
    fun storageSummary(): String = runCatching {
        fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val db = File(context.getDatabasePath("GeoEnv.db").parentFile, "GeoEnv.db")
        val profiles = File(context.filesDir, "profiles")
        val har = File(context.filesDir, "har")
        val backups = File(context.filesDir, "backups")
        val mb = 1024.0 * 1024.0
        "数据库 ${dirSize(db) / mb} MB · Profile ${dirSize(profiles) / mb} MB · " +
            "HAR ${dirSize(har) / mb} MB · 备份 ${dirSize(backups) / mb} MB"
    }.getOrDefault("—")

    companion object {
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_WAKE_LOCK = "wake_lock"

        val STAGES = listOf("dev", "test", "prod")
        val THEME_MODES_LABEL = mapOf(
            ThemeConfig.MODE_LIGHT to "亮色",
            ThemeConfig.MODE_DARK to "暗色",
            ThemeConfig.MODE_SYSTEM to "跟随系统",
        )
        val DENSITY_LABEL = mapOf(
            ThemeConfig.DENSITY_COMPACT to "紧凑",
            ThemeConfig.DENSITY_DEFAULT to "宽松",
        )
        val PRESET_COLORS = listOf(
            "#1E6FFF" to "标准蓝",
            "#00A870" to "商务绿",
            "#E37400" to "警示橙",
            "#D93025" to "正红",
            "#7B61FF" to "极光紫",
            "#111827" to "石墨黑",
        )

        fun stageLabel(stage: String): String = when (stage) {
            "dev" -> "开发 dev"
            "prod" -> "生产 prod"
            else -> "测试 test"
        }

        fun themeNameRes(): Int = R.string.nav_settings
    }
}
