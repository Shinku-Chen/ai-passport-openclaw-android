package com.shinku.aipassport.openclaw.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 看门狗：定时确认语音桥还在跑，不在就把它拉回来。
 *
 * 为什么必须有它：系统（尤其 MIUI/HyperOS）会在 App 闲置满 60s 后停掉**没拿到前台服务**的服务，
 * 而后台启动路径上 `startForeground` 会被**静默拒绝**（App 收不到异常，见 [ServiceGuard] 的真机实测）。
 * 用户不重新打开 App，设备就一直用不了 —— 看门狗是「用户没站在手机前也能自愈」的那条路径。
 *
 * 两个安全约束：
 *  - 只在用户希望语音桥运行时才动手（[KeepAliveState.bridgeWanted]）：显式停止服务后不会被拉回来；
 *  - 服务活着但前台服务被拒时**不重启**（重启也拿不到），改为让服务原地重试一次（[ServiceGuard.WatchdogAction.SYNC_FOREGROUND]）。
 *
 * 闹钟用 `setAndAllowWhileIdle`（不需要任何特殊权限，Doze 下系统会顺延），
 * 且是**一次性**的：每次巡检结束都得再排下一次，否则看门狗只会生效一次。
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val state = KeepAliveState(context)
        val wanted = state.bridgeWanted
        val alive = VoiceBridgeService.isRunning
        val foreground = VoiceBridgeService.lastForegroundState
        val action = ServiceGuard.watchdogAction(wanted, alive, foreground)
        Log.i(
            TAG,
            "看门狗巡检：wanted=$wanted 服务在跑=$alive 前台服务=$foreground → $action",
        )
        when (action) {
            ServiceGuard.WatchdogAction.START -> {
                state.watchdogActedAtMs = System.currentTimeMillis()
                Log.w(TAG, "语音桥服务不在了,重新拉起(前台服务被系统停掉的兜底)")
                VoiceBridgeService.start(context)
            }
            ServiceGuard.WatchdogAction.SYNC_FOREGROUND -> {
                state.watchdogActedAtMs = System.currentTimeMillis()
                Log.w(TAG, "服务在跑但前台服务被拒,让它重试一次 startForeground")
                VoiceBridgeService.syncForeground(context)
            }
            ServiceGuard.WatchdogAction.NONE -> Unit
        }
        // 自己续下一次:服务没被拉起来(系统拒了)时也得继续排,不然看门狗就断了
        if (wanted) schedule(context)
    }

    companion object {
        private const val TAG = "ServiceWatchdog"

        /** 排下一次巡检（幂等：同一个 PendingIntent，重复排程只是把触发时间往后推）。 */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ServiceWatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ServiceGuard.WATCHDOG_INTERVAL_MS,
                    pending,
                )
                Log.d(TAG, "看门狗已排程:${ServiceGuard.WATCHDOG_INTERVAL_MS / 1000}s 后巡检")
            } catch (e: Exception) {
                Log.w(TAG, "看门狗排程失败:${e.message}")
            }
        }

        private const val REQUEST_CODE = 1001
    }
}
