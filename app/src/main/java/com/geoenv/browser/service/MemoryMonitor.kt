package com.geoenv.browser.service

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import com.geoenv.browser.GeoEnvApp
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存监控（docs/geo-env-tech.md §6.5）。
 *
 *  - 每 5s 采样一次 App 堆占用与系统可用内存；
 *  - 超过 [HIGH_THRESHOLD] 触发 [onMemoryHigh]（提示用户关闭闲置环境）；
 *  - 超过 [CRITICAL_THRESHOLD] 触发 [onMemoryCritical]（强制回收闲置槽位）。
 */
class MemoryMonitor(private val context: Context) {

    fun interface Listener {
        fun onMemory(sample: Sample)
    }

    data class Sample(
        val usedHeapMb: Long,
        val maxHeapMb: Long,
        val systemAvailableMb: Long,
        val level: Level,
    )

    enum class Level { NORMAL, HIGH, CRITICAL }

    @Volatile
    var listener: Listener? = null

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "geoenv-memmon").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @Volatile
    var lastSample: Sample? = null
        private set

    fun start(intervalMs: Long = 5_000L) {
        if (!running.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({ safeSample() }, intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        Timber.i("MemoryMonitor started")
    }

    fun stop() {
        running.set(false)
    }

    /** 立即采样一次（供 UI 手动刷新）。 */
    fun sampleNow(): Sample = sample()

    private fun safeSample() {
        if (!running.get()) return
        runCatching {
            val s = sample()
            lastSample = s
            val l = listener ?: return@runCatching
            if (s.level != Level.NORMAL) {
                mainHandler.post { l.onMemory(s) }
            }
        }.onFailure { Timber.w(it, "memory sample failed") }
    }

    private fun sample(): Sample {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb = rt.maxMemory() / MB
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val availMb = info.availMem / MB

        val heapRatio = usedMb.toDouble() / maxMb.coerceAtLeast(1)
        val level = when {
            heapRatio >= CRITICAL_HEAP_RATIO || availMb < CRITICAL_SYS_MB -> Level.CRITICAL
            heapRatio >= HIGH_HEAP_RATIO || availMb < HIGH_SYS_MB -> Level.HIGH
            else -> Level.NORMAL
        }
        return Sample(usedMb, maxMb, availMb, level)
    }

    /** 当前原生堆（用于调试输出）。 */
    fun nativeHeapMb(): Long = Debug.getNativeHeapAllocatedSize() / MB

    companion object {
        private const val MB = 1024L * 1024L

        /** 堆占用 >= 80% 视为高内存 */
        const val HIGH_HEAP_RATIO = 0.80

        /** 堆占用 >= 92% 视为临界 */
        const val CRITICAL_HEAP_RATIO = 0.92

        /** 系统可用内存 < 400MB 高 */
        const val HIGH_SYS_MB = 400L

        /** 系统可用内存 < 200MB 临界 */
        const val CRITICAL_SYS_MB = 200L

        /** 由 GeoEnvApp 持有，供各组件读取 */
        @Volatile
        var current: MemoryMonitor? = null
            internal set
    }
}

/** 便捷入口：读取当前内存监控单例。 */
val Context.memoryMonitor: MemoryMonitor
    get() = (applicationContext as GeoEnvApp).memoryMonitor
