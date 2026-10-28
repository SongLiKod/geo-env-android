package com.geoenv.browser.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.slot.SlotBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 开机自启（需求 4「环境常驻」）。
 *
 * 收到 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 后：
 *  1. 启动 [KeepAliveService] 前台保活；
 *  2. 重启所有 `keepAlive = true` 且上次状态为运行中的环境。
 *
 * `directBootAware=false`：SQLCipher 密钥存于凭据加密区，解锁后才可用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Timber.i("BootReceiver: %s", action)

        // 仅在用户开启过「后台保活」时才拉起前台服务
        val keepAliveOn = context.getSharedPreferences("geoenv_settings", Context.MODE_PRIVATE)
            .getBoolean("keep_alive", false)
        if (keepAliveOn) {
            KeepAliveService.start(context)
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val locator = ServiceLocator.peek() ?: return@launch
                val envs = locator.database.envDao().getRunning()
                    .filter { it.keepAlive }
                Timber.i("BootReceiver: restoring %d keep-alive environments", envs.size)
                if (envs.isNotEmpty()) {
                    val bridge = locator.slotBridge
                    bridge.bindAll()
                    // 按空闲槽位分配（尊重并发上限与已占用槽位）
                    val busy = bridge.states.value.values
                        .filter {
                            it.status == "RUNNING" || it.status == "STARTING" || it.status == "ERROR"
                        }
                        .map { it.slotIndex }
                        .toSet()
                    val freeSlots = (1..SlotBridge.SLOTS).filter { it !in busy }
                    envs.take(freeSlots.size).forEachIndexed { index, env ->
                        val slot = freeSlots[index]
                        bridge.mergeState(
                            slot,
                            org.json.JSONObject()
                                .put("status", "STARTING")
                                .put("envId", env.id)
                                .put("envName", env.name),
                        )
                        SlotBridge.startSlotService(context, slot, env.id)
                    }
                    locator.auditRepository.log(
                        action = "BOOT_RESTORE",
                        note = "开机重启保活环境 ${envs.size} 个",
                        envId = null,
                        envName = null,
                    )
                }
            } catch (t: Throwable) {
                Timber.w(t, "BootReceiver restore failed")
            } finally {
                pending.finish()
            }
        }
    }
}
