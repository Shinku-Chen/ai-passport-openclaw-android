package com.shinku.aipassport.openclaw.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LinkRetryPolicy] 的 JVM 单测（纯逻辑，不依赖 Android 与真机）。
 *
 * 核心回归：**设备关机重启后不能傻等 8 秒再扫描**。重启只需几秒，先直连记住的地址
 * 才是快路径；只有连续直连失败，才退回"退避 + 扫描"。
 */
class LinkRetryPolicyTest {

    @Test
    fun first_attempts_use_direct_connect_with_short_delay() {
        repeat(LinkRetryPolicy.DIRECT_RETRY_LIMIT) { attempt ->
            val d = LinkRetryPolicy.decide(directAttempts = attempt)
            assertTrue("第 ${attempt + 1} 次应先直连", d.direct)
            assertEquals(LinkRetryPolicy.DIRECT_RETRY_DELAY_MS, d.delayMs)
            assertTrue("快路必须明显快于慢路", d.delayMs < LinkRetryPolicy.SCAN_RETRY_DELAY_MS)
        }
    }

    @Test
    fun after_the_limit_it_falls_back_to_scan() {
        val d = LinkRetryPolicy.decide(directAttempts = LinkRetryPolicy.DIRECT_RETRY_LIMIT)
        assertFalse("直连连续失败后改走扫描（设备换地址/绑定被清除时只有扫描能重新发现）", d.direct)
        assertEquals(LinkRetryPolicy.SCAN_RETRY_DELAY_MS, d.delayMs)
    }

    @Test
    fun well_past_the_limit_still_scans() {
        val d = LinkRetryPolicy.decide(directAttempts = LinkRetryPolicy.DIRECT_RETRY_LIMIT + 7)
        assertFalse(d.direct)
        assertEquals(LinkRetryPolicy.SCAN_RETRY_DELAY_MS, d.delayMs)
    }

    @Test
    fun connect_timeout_is_bounded_and_larger_than_the_direct_delay() {
        assertTrue("必须有连接超时（Android 直连本身不超时）", LinkRetryPolicy.CONNECT_TIMEOUT_MS > 0)
        assertTrue(
            "超时应长于一次重连延迟，否则会把正在进行的连接当成失败",
            LinkRetryPolicy.CONNECT_TIMEOUT_MS > LinkRetryPolicy.DIRECT_RETRY_DELAY_MS,
        )
    }
}
