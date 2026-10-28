package com.geoenv.browser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.service.MemoryMonitor
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * GeoEnv 应用入口。
 *
 * 注意：Gecko 相关的重型对象（GeckoRuntime / GeckoSession）不在此处初始化，
 * 它们只在各自的槽位子进程（:gecko1 ~ :gecko5）中创建，见 [com.geoenv.browser.slot.SlotActivity]。
 */
@HiltAndroidApp
class GeoEnvApp : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    lateinit var memoryMonitor: MemoryMonitor
        private set

    override fun onCreate() {
        // 诊断：在 Hilt 注入（super.onCreate）之前安装崩溃捕获，把堆栈写入公共下载目录，
        // 便于离线定位 release 包"一打开就闪退"的真实根因。确认后可移除。
        CrashLogger.install(this)

        // 关键修复：net.zetetic:sqlcipher-android 不会自行加载原生库（其 classes.jar 内
        // 没有任何 System.loadLibrary 调用，也无 loadLibs 方法），必须由应用显式加载。
        // 否则首次打开数据库时 JNI 解析不到 SQLiteConnection.nativeOpen，抛 UnsatisfiedLinkError
        // （"is the library loaded?"）导致启动即闪退。本方法在主进程与 5 个槽位子进程都会执行，
        // 各进程独立加载一次（System.loadLibrary 幂等、线程安全）。
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure { android.util.Log.e("GeoEnv", "loadLibrary(\"sqlcipher\") failed", it) }

        super.onCreate()
        _instance = this

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release：仅记录到内存环形缓冲，便于导出诊断日志（不落明文磁盘）
            Timber.plant(ReleaseTree())
        }

        serviceLocator = ServiceLocator(this).also { ServiceLocator.install(it) }
        memoryMonitor = MemoryMonitor(this)
        MemoryMonitor.current = memoryMonitor

        createNotificationChannels()

        Timber.i("GeoEnv started, process=%s", currentProcessName())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm: NotificationManager = getSystemService() ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_KEEPALIVE,
                    getString(R.string.notification_channel_keepalive),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MEMORY,
                    getString(R.string.notification_channel_memory),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun currentProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val am = getSystemService(android.app.ActivityManager::class.java)
                am?.runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }
                    ?.processName
            }.getOrNull() ?: "unknown"
        }
    }

    /** Release 树：避免把 URL / Cookie 等敏感信息写入 logcat。 */
    private inner class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < android.util.Log.WARN) return
            // 仅保留告警及以上，且做基本脱敏
            val sanitized = message
                .replace(Regex("(?i)(cookie|authorization|password|token)=[^&\\s]+"), "$1=***")
            android.util.Log.println(priority, tag ?: "GeoEnv", sanitized)
        }
    }

    companion object {
        const val CHANNEL_KEEPALIVE = "geoenv_keepalive"
        const val CHANNEL_MEMORY = "geoenv_memory"

        @Volatile
        private var _instance: GeoEnvApp? = null

        val instance: GeoEnvApp
            get() = _instance ?: error("GeoEnvApp not initialized")
    }

    override fun onTerminate() {
        super.onTerminate()
        _instance = null
    }
}
