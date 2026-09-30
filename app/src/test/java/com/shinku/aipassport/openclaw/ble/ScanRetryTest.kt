package com.shinku.aipassport.openclaw.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanRetry] 的 JVM 单测(纯逻辑,不依赖 Android/真机)。
 *
 * 核心回归:真机上"设备断开后永远扫不到、必须重启 App"——
 * 成因是 code=1(SCAN_FAILED_ALREADY_STARTED) 被当成普通失败,退避后再 startScan,
 * 而 startScan 从不先 stopScan → 每次都 code=1 自锁。这里断言 code=1 必须先停扫再短退避。
 */
class ScanRetryTest {

    @Test
    fun already_started_requires_stop_scan_and_short_backoff() {
        val d = ScanRetry.decide(ScanRetry.ERROR_ALREADY_STARTED, alreadyStartedAttempts = 0)
        assertTrue("code=1 必须先 stopScan 清掉残留扫描", d.needsStopScan)
        assertEquals(ScanRetry.RETRY_ALREADY_STARTED_MS, d.retryDelayMs)
    }

    @Test
    fun already_started_keeps_retrying_with_longer_backoff_after_limit() {
        val d = ScanRetry.decide(
            ScanRetry.ERROR_ALREADY_STARTED,
            alreadyStartedAttempts = ScanRetry.MAX_ALREADY_STARTED_RETRIES,
        )
        assertTrue(d.needsStopScan)
        assertEquals("超限后拉长间隔但仍重试", ScanRetry.RETRY_OTHER_MS, d.retryDelayMs)
    }

    @Test
    fun already_started_does_not_bother_the_user_at_first() {
        assertFalse(
            "code=1 是可自愈问题,不应打扰用户",
            ScanRetry.shouldSurfaceToUser(ScanRetry.ERROR_ALREADY_STARTED, alreadyStartedAttempts = 0),
        )
        assertTrue(
            "连续超限后应把原因透出",
            ScanRetry.shouldSurfaceToUser(
                ScanRetry.ERROR_ALREADY_STARTED,
                alreadyStartedAttempts = ScanRetry.MAX_ALREADY_STARTED_RETRIES,
            ),
        )
    }

    @Test
    fun other_error_codes_use_long_backoff_and_surface() {
        val d = ScanRetry.decide(errorCode = 2, alreadyStartedAttempts = 0)
        assertTrue(d.needsStopScan)
        assertEquals(ScanRetry.RETRY_OTHER_MS, d.retryDelayMs)
        assertTrue(ScanRetry.shouldSurfaceToUser(errorCode = 2, alreadyStartedAttempts = 0))
    }

    @Test
    fun retry_delay_is_never_null_so_scan_recovers_without_user_action() {
        listOf(1, 2, 3, 4).forEach { code ->
            assertNotNull(
                "code=$code 必须给出重试延迟(否则又变成'必须重启 App')",
                ScanRetry.decide(code, alreadyStartedAttempts = 0).retryDelayMs,
            )
        }
    }
}
