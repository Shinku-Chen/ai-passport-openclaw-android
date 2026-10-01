package com.shinku.aipassport.openclaw.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shinku.aipassport.openclaw.gateway.GatewaySettings

/**
 * 开机自启：收到开机广播后按用户设置拉起前台服务。
 *
 * 两点需要注意：
 *  - Android 12+ 明确允许在收到 `BOOT_COMPLETED` 时启动前台服务（官方豁免之一），
 *    所以这里直接 `startForegroundService` 是合规的；
 *  - 但**系统可以不放行开机广播** —— 小米/HyperOS 必须由用户在本应用的「自启动」里打开，
 *    那是 OEM 侧白名单，App 无法代替（设置页里已给出提示）。
 *
 * 另外兼容 `QUICKBOOT_POWERON`（部分机型/HTC 系的重启广播名）。
 *
 * 还负责**应用更新后**（`MY_PACKAGE_REPLACED`）把桥恢复起来：更新会换掉 APK、杀掉进程、
 * 并可能清掉闹钟，若用户不主动打开 App，桥就一直不会回来。判据用「更新前用户是不是希望它跑」
 * （[KeepAliveState.bridgeWanted]），而不是开机自启开关 —— 更新不该按开机配置决定。
 * `MY_PACKAGE_REPLACED` 也是 Android 12+ **允许启动前台服务**的官方豁免之一。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 应用更新：按用户之前的意愿恢复，并补一次看门狗排程（更新可能清掉闹钟）
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            restoreAfterUpdate(context)
            return
        }
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) return
        val settings = GatewaySettings(context)
        if (!settings.bootAutoStart) {
            Log.i(TAG, "开机自启已关闭，跳过")
            return
        }
        Log.i(TAG, "开机自启：拉起语音桥服务")
        try {
            VoiceBridgeService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "开机自启启动服务失败：${e.message}")
        }
        // 无论服务是否被系统放行，看门狗都要排上（开机清空了所有闹钟）
        try {
            ServiceWatchdogReceiver.schedule(context)
        } catch (e: Exception) {
            Log.w(TAG, "开机自启：看门狗排程失败：${e.message}")
        }
    }

    /** 应用更新后：按用户更新前的意愿恢复桥，并补一次看门狗排程。 */
    private fun restoreAfterUpdate(context: Context) {
        val wanted = KeepAliveState(context).bridgeWanted
        Log.i(TAG, "应用已更新：更新前是否在运行=$wanted")
        if (wanted) {
            try {
                VoiceBridgeService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "应用更新后启动服务失败：${e.message}")
            }
        }
        try {
            ServiceWatchdogReceiver.schedule(context)
        } catch (e: Exception) {
            Log.w(TAG, "应用更新后看门狗排程失败：${e.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        /** 部分机型（及 HTC 系）的重启广播，与 BOOT_COMPLETED 同样处理。 */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
