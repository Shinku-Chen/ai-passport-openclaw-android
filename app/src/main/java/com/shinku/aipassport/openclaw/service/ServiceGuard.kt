package com.shinku.aipassport.openclaw.service

/**
 * 「前台服务被系统静默拒绝」的判定与补救策略（纯逻辑，JVM 单测可覆盖）。
 *
 * 真机现象（HyperOS / Android 14 / targetSdk 35，实测记录）：
 *  - 从**非用户主动**路径启动（刚装完 APK、被强停之后、开机广播拉起）时，系统拒绝 startForeground：
 *    ```
 *    W/ActivityManager: Service.startForeground() not allowed due to bg restriction: service …/VoiceBridgeService
 *    ```
 *    而 **App 侧收不到任何异常**（系统只写一条日志、直接忽略这次请求），于是 App 以为自己是前台服务，
 *    实际只是普通后台服务；
 *  - 后果：App 闲置满 60s 后系统把它停掉（`Stopping service due to app idle: … -1m0s379ms`，
 *    两次实测都精确停在 60.379s）→ BLE / 网关 / 识别通道全断，设备彻底用不了，
 *    直到用户再打开一次 App；
 *  - 用户自己**点图标**启动时一切正常（`isForeground=true`、`types=00000010`（connectedDevice）），
 *    所以问题出在「后台启动路径」，不是前台服务本身配错。
 *
 * 两层防护（本文件只负责**判定**，Service / Receiver 负责执行）：
 *  1. 判定：系统只在**前台服务真的生效**时才给通知打上 `FLAG_FOREGROUND_SERVICE` ——
 *     用 `NotificationManager.getActiveNotifications()` 反查自己那条通知即可判定（无需废弃 API）。
 *     实测同一台机器：被拒时 `flags=0x2`，拿到时 `flags=0x62`；
 *  2. 补救：回到前台时重试 `startForeground`（这是唯一会被放行的时机，见 `MainActivity.onResume`）
 *     \+ 看门狗定时把被停掉的服务拉回来（见 [ServiceWatchdogReceiver]）。
 */
object ServiceGuard {

    /**
     * 看门狗巡检间隔：兜底把被系统停掉的服务拉回来。
     *
     * 5 分钟是「用户察觉不到太久的断线」与「别太费电」之间的折中；Doze 下系统会顺延
     * （`setAndAllowWhileIdle` 在深度 Doze 里最多约 9 分钟一次），属正常现象。
     */
    const val WATCHDOG_INTERVAL_MS: Long = 5 * 60 * 1000L

    /**
     * `Notification.FLAG_FOREGROUND_SERVICE` 的字面值（0x40）。
     *
     * 这里刻意不引用 Android 常量：本文件要保持**零 Android 依赖**才能在 JVM 单测里直接断言
     * （值本身是公开 API 常量，不会变）。
     */
    const val FLAG_FOREGROUND_SERVICE: Int = 0x40

    /** 前台服务的真实状态。前两种是能判定的，判不了的一律 [UNKNOWN]，不许猜。 */
    enum class ForegroundState {
        /** 真的拿到了前台服务（通知带前台服务标记）。 */
        GRANTED,

        /** 通知在、但没带前台服务标记 → 系统静默拒绝了 `startForeground`。 */
        DENIED,

        /** 判不了（通知被用户划掉 / 还没贴出 / 查询接口失败）—— 不能当成「被拒」去打扰用户。 */
        UNKNOWN,
    }

    /** 看门狗该做什么。 */
    enum class WatchdogAction {
        /** 什么都不用做。 */
        NONE,

        /** 服务不在了（被系统停掉 / 进程已重启）→ 重新拉起。 */
        START,

        /** 服务还在，但前台服务被拒 → 让服务原地重试一次 `startForeground`。 */
        SYNC_FOREGROUND,
    }

    /**
     * 通知的 flags 是否代表「前台服务已拿到」。
     *
     * 系统在前台服务生效时会给这条通知补上 [FLAG_FOREGROUND_SERVICE]（真机实测见类注释），
     * 这是 App 侧唯一可靠、且不依赖废弃 API 的判据。
     */
    fun flagsIndicateForeground(notificationFlags: Int): Boolean =
        (notificationFlags and FLAG_FOREGROUND_SERVICE) != 0

    /**
     * 分类前台服务状态。
     *
     * [notificationFound] 为 false 一律算 [ForegroundState.UNKNOWN]：通知可能被用户划掉、也可能还没贴出，
     * 这两种情况都**不代表**被拒绝（误报会让用户被无谓的警告打扰）。
     */
    fun classify(notificationFound: Boolean, notificationFlags: Int): ForegroundState = when {
        !notificationFound -> ForegroundState.UNKNOWN
        flagsIndicateForeground(notificationFlags) -> ForegroundState.GRANTED
        else -> ForegroundState.DENIED
    }

    /**
     * 看门狗决策。
     *
     * @param wanted 用户是否希望语音桥运行（显式停止后为 false：看门狗不许把它拉回来）
     * @param serviceAlive 服务是否还活着（进程内静态标记：进程被杀时自然为 false）
     * @param foreground 当前前台服务状态（[classify] 的结果）
     */
    fun watchdogAction(
        wanted: Boolean,
        serviceAlive: Boolean,
        foreground: ForegroundState,
    ): WatchdogAction = when {
        !wanted -> WatchdogAction.NONE
        !serviceAlive -> WatchdogAction.START
        // 活着但被拒：重启服务也拿不到（系统会按「后台启动」再拒一次），原地重试一次更省
        foreground == ForegroundState.DENIED -> WatchdogAction.SYNC_FOREGROUND
        else -> WatchdogAction.NONE
    }

    /** 通知里那条「后台受限」警告文案；前台服务正常时返回 null（不打扰）。 */
    fun warningText(state: ForegroundState): String? = when (state) {
        ForegroundState.DENIED ->
            "系统拒绝了前台服务,锁屏后可能被停:请在系统设置里允许本应用「自启动 / 后台无限制」"
        else -> null
    }

    /** 折叠通知里的一行警告前缀（展开正文用 [warningText] 全文）。 */
    const val WARNING_SUMMARY: String = "⚠️ 后台受限"
}
