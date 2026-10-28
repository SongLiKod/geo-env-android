package com.geoenv.browser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.geoenv.browser.GeoEnvApp
import com.geoenv.browser.MainActivity
import com.geoenv.browser.R
import com.geoenv.browser.slot.SlotBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancel
import timber.log.Timber
import javax.inject.Inject

/**
 * 保活前台服务（需求 4「环境常驻」）。
 *
 * 职责：
 *  1. 以 `specialUse` 前台类型常驻，避免槽位进程被 LMK 直接回收；
 *  2. 持有 `PARTIAL_WAKE_LOCK`（可选，用户开启「保持唤醒」时）；
 *  3. 驱动 [MemoryMonitor]，内存 HIGH/CRITICAL 时通知用户 / 释放闲置槽位；
 *  4. 绑定全部槽位服务，维持 IPC 通道与状态镜像。
 *
 * 仅在主进程运行（Manifest 未加 `android:process`）。
 */
@AndroidEntryPoint
class KeepAliveService : Service() {

    @Inject
    lateinit var slotBridge: SlotBridge

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        runCatching { slotBridge.bindAll() }
        setupMemoryMonitor()
        Timber.i("KeepAliveService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_WAKE_LOCK -> if (intent.getBooleanExtra("enabled", false)) acquireWakeLock() else releaseWakeLock()
            ACTION_REFRESH -> runCatching { slotBridge.bindAll() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { (application as? GeoEnvApp)?.memoryMonitor?.let { it.stop() } }
        scope.cancel()
        super.onDestroy()
        Timber.w("KeepAliveService destroyed")
    }

    /* ----------------------------- 内存监控 ----------------------------- */

    private fun setupMemoryMonitor() {
        val monitor = (application as GeoEnvApp).memoryMonitor
        monitor.listener = MemoryMonitor.Listener { sample ->
            when (sample.level) {
                MemoryMonitor.Level.HIGH -> notifyMemory(
                    getString(R.string.notif_memory_high_title),
                    getString(R.string.notif_memory_high_text, sample.usedHeapMb, sample.maxHeapMb),
                    IMPORTANCE_DEFAULT,
                )
                MemoryMonitor.Level.CRITICAL -> {
                    notifyMemory(
                        getString(R.string.notif_memory_critical_title),
                        getString(R.string.notif_memory_critical_text),
                        IMPORTANCE_HIGH,
                    )
                    releaseIdleSlots()
                }
                MemoryMonitor.Level.NORMAL -> Unit
            }
        }
        monitor.start()
    }

    /** 临界内存时：停掉非前台（无 Activity）槽位，保留最多 MIN_KEEP 个运行中的环境 */
    private fun releaseIdleSlots() {
        val running = runCatching { slotBridge.runningSlots() }.getOrDefault(emptyList())
        if (running.size <= MIN_KEEP) return
        // 从最久未更新的开始停
        running.sortedBy { it.updatedAt }.dropLast(MIN_KEEP).forEach { st ->
            Timber.w("memory critical: stopping slot %d env=%s", st.slotIndex, st.envName)
            runCatching { SlotBridge.stopSlotService(this, st.slotIndex) }
        }
    }

    private fun notifyMemory(title: String, text: String, importance: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                GeoEnvApp.CHANNEL_MEMORY,
                getString(R.string.notification_channel_memory),
                importance,
            )
            nm.createNotificationChannel(ch)
        }
        val n = Notification.Builder(this, GeoEnvApp.CHANNEL_MEMORY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFY_MEMORY_ID, n) }
    }

    /* ----------------------------- 前台与唤醒锁 ----------------------------- */

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                GeoEnvApp.CHANNEL_KEEPALIVE,
                getString(R.string.notification_channel_keepalive),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, GeoEnvApp.CHANNEL_KEEPALIVE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_keepalive_text))
            .setContentIntent(content)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFY_KEEPALIVE_ID, notification)
        } catch (t: Throwable) {
            Timber.w(t, "keepalive startForeground failed")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoEnv::KeepAlive").apply {
            setReferenceCounted(false)
            acquire()
        }
        Timber.i("wake lock acquired")
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_SET_WAKE_LOCK = "SET_WAKE_LOCK"
        const val ACTION_REFRESH = "REFRESH"
        private const val NOTIFY_KEEPALIVE_ID = 4001
        private const val NOTIFY_MEMORY_ID = 4002
        private const val IMPORTANCE_DEFAULT = android.app.NotificationManager.IMPORTANCE_DEFAULT
        private const val IMPORTANCE_HIGH = android.app.NotificationManager.IMPORTANCE_HIGH

        /** 临界内存时至少保留的运行环境数 */
        private const val MIN_KEEP = 1

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { Timber.w(it, "start KeepAliveService failed") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }

        fun setWakeLock(context: Context, enabled: Boolean) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_SET_WAKE_LOCK
                putExtra("enabled", enabled)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }
}
