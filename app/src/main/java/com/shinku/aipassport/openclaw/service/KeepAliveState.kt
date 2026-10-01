package com.shinku.aipassport.openclaw.service

import android.content.Context

/**
 * 保活状态的落盘存取：语音桥「想不想运行」与「前台服务是否被系统拒绝」。
 *
 * 为什么要落盘（不能只放内存）：
 *  - 看门狗可能在**服务已经死掉的进程**里被闹钟拉起，进程内的静态标记那时全是空的；
 *  - 设置页要在服务没跑的时候也能告诉用户「后台被系统限制了」。
 *
 * 语义约束：
 *  - [bridgeWanted] 只在**用户主动启动/停止**时改（`VoiceBridgeService.start/stop` 与 `ACTION_STOP`）；
 *    服务被系统停掉时**不动**它 —— 否则看门狗就失去「该不该拉回来」的依据。
 *  - [foregroundDenied] 由 `VoiceBridgeService.verifyForegroundState` 每次校验后写入，
 *    只用于「提示用户」，不参与任何自动决策以外的判断。
 */
class KeepAliveState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否希望语音桥运行（显式停止后为 false，看门狗据此不重启）。 */
    var bridgeWanted: Boolean
        get() = prefs.getBoolean(KEY_WANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_WANTED, value).apply()

    /** 最近一次前台服务校验的结论是不是「被系统静默拒绝」。 */
    var foregroundDenied: Boolean
        get() = prefs.getBoolean(KEY_DENIED, false)
        set(value) = prefs.edit().putBoolean(KEY_DENIED, value).apply()

    /** 最近一次前台服务校验的时刻（0 = 从未校验过）。 */
    var foregroundCheckedAtMs: Long
        get() = prefs.getLong(KEY_CHECKED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CHECKED_AT, value).apply()

    /** 看门狗最近一次真的动手（拉起服务 / 重试前台服务）的时刻，仅用于排查。 */
    var watchdogActedAtMs: Long
        get() = prefs.getLong(KEY_WATCHDOG_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_WATCHDOG_AT, value).apply()

    private companion object {
        /** 与 `MainActivity` 的电池优化引导共用同一个 prefs 文件（都是「保活」这件事）。 */
        const val PREFS = "keepalive"
        const val KEY_WANTED = "bridge_wanted"
        const val KEY_DENIED = "fg_denied"
        const val KEY_CHECKED_AT = "fg_checked_at"
        const val KEY_WATCHDOG_AT = "watchdog_acted_at"
    }
}
