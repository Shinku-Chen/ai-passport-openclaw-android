package com.shinku.aipassport.openclaw.service

import com.shinku.aipassport.openclaw.service.ServiceGuard.ForegroundState
import com.shinku.aipassport.openclaw.service.ServiceGuard.WatchdogAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「前台服务被系统静默拒绝」的判定与补救策略单测。
 *
 * 背景（真机实测 HyperOS / Android 14）：后台启动路径上系统拒绝 `startForeground()` 却**不抛异常**，
 * 服务降级成普通后台服务，App 闲置满 60s 被停掉（60.379s 精确复现）；用户点图标启动则一切正常。
 * 所以：判定必须只认「通知上的前台服务标记」，判不出来时不许当成「被拒」（不能误报打扰用户）；
 * 看门狗只能在用户想运行时动手，且服务活着时不许重启（重启也拿不到，原地重试更省）。
 */
class ServiceGuardTest {

    // ---- 判定：通知标记 ----

    @Test
    fun foreground_marker_in_notification_flags_means_granted() {
        // 真机拿到前台服务时观测到的 flags=0x62（ONGOING | NO_CLEAR | FOREGROUND_SERVICE）
        assertTrue(ServiceGuard.flagsIndicateForeground(0x62))
        // 只有前台服务标记也算（0x40）
        assertTrue(ServiceGuard.flagsIndicateForeground(ServiceGuard.FLAG_FOREGROUND_SERVICE))
        assertTrue(ServiceGuard.flagsIndicateForeground(0x40 or 0x02))
    }

    @Test
    fun notification_without_foreground_marker_means_denied() {
        // 真机被静默拒绝时观测到的 flags=0x2（只有 ONGOING，没有前台服务标记）
        assertFalse(ServiceGuard.flagsIndicateForeground(0x02))
        assertEquals(ForegroundState.DENIED, ServiceGuard.classify(true, 0x02))
    }

    @Test
    fun granted_state_comes_only_from_the_marker() {
        assertEquals(ForegroundState.GRANTED, ServiceGuard.classify(true, 0x62))
        assertEquals(ForegroundState.GRANTED, ServiceGuard.classify(true, 0x40))
    }

    @Test
    fun missing_notification_is_unknown_not_denied() {
        // 通知可能被用户划掉、也可能还没贴出 —— 都不代表被系统拒绝，不能误报警告
        assertEquals(ForegroundState.UNKNOWN, ServiceGuard.classify(false, 0))
        assertEquals(ForegroundState.UNKNOWN, ServiceGuard.classify(false, 0x62))
        assertNull(ServiceGuard.warningText(ForegroundState.UNKNOWN))
    }

    // ---- 看门狗决策 ----

    @Test
    fun watchdog_does_nothing_when_user_stopped_the_bridge() {
        // 用户显式停止后，服务即使不在了也不许被拉回来
        assertEquals(
            WatchdogAction.NONE,
            ServiceGuard.watchdogAction(wanted = false, serviceAlive = false, foreground = ForegroundState.UNKNOWN),
        )
        assertEquals(
            WatchdogAction.NONE,
            ServiceGuard.watchdogAction(wanted = false, serviceAlive = false, foreground = ForegroundState.DENIED),
        )
    }

    @Test
    fun watchdog_restarts_a_dead_service() {
        // 进程被杀时静态标记自然为 false（前台状态无从判定）→ 拉起服务
        assertEquals(
            WatchdogAction.START,
            ServiceGuard.watchdogAction(wanted = true, serviceAlive = false, foreground = ForegroundState.UNKNOWN),
        )
        assertEquals(
            WatchdogAction.START,
            ServiceGuard.watchdogAction(wanted = true, serviceAlive = false, foreground = ForegroundState.DENIED),
        )
    }

    @Test
    fun watchdog_syncs_instead_of_restarting_when_foreground_is_denied() {
        // 服务活着但前台服务被拒：重启也拿不到（系统会按后台启动再拒一次），原地重试更省
        assertEquals(
            WatchdogAction.SYNC_FOREGROUND,
            ServiceGuard.watchdogAction(wanted = true, serviceAlive = true, foreground = ForegroundState.DENIED),
        )
    }

    @Test
    fun watchdog_does_nothing_when_service_is_healthy() {
        assertEquals(
            WatchdogAction.NONE,
            ServiceGuard.watchdogAction(wanted = true, serviceAlive = true, foreground = ForegroundState.GRANTED),
        )
        // 判不了（通知被划掉）时也同样不动手：健康时不打扰
        assertEquals(
            WatchdogAction.NONE,
            ServiceGuard.watchdogAction(wanted = true, serviceAlive = true, foreground = ForegroundState.UNKNOWN),
        )
    }

    // ---- 文案 ----

    @Test
    fun warning_text_only_when_denied() {
        val denied = ServiceGuard.warningText(ForegroundState.DENIED)
        assertNotNull("被拒时必须给用户一句可操作的话", denied)
        assertTrue("警告里要指明该去哪儿改", denied!!.contains("自启动"))
        assertNull(ServiceGuard.warningText(ForegroundState.GRANTED))
        assertNull(ServiceGuard.warningText(ForegroundState.UNKNOWN))
        assertTrue(ServiceGuard.WARNING_SUMMARY.isNotBlank())
    }

    @Test
    fun watchdog_interval_is_in_a_sane_range() {
        // 太短会费电，太长用户会在断线里干等
        assertTrue(
            "看门狗间隔 ${ServiceGuard.WATCHDOG_INTERVAL_MS}ms 不合理",
            ServiceGuard.WATCHDOG_INTERVAL_MS in 60_000L..15 * 60_000L,
        )
    }
}
