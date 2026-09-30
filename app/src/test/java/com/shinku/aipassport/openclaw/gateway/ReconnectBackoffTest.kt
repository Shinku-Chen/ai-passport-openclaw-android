package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReconnectBackoff] 的 JVM 单测:验证 2s→4s→8s→16s→30s 封顶的退避序列。
 *
 * 对应线上问题:连接监控原来固定每 6s 重连一次,网关不可达时无限循环新建 socket。
 */
class ReconnectBackoffTest {

    @Test
    fun delay_sequence_doubles_then_caps_at_30s() {
        val backoff = ReconnectBackoff()
        val delays = (1..6).map { backoff.nextDelayMs() }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun attempts_counts_queued_retries() {
        val backoff = ReconnectBackoff()
        assertEquals(0, backoff.attempts)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertEquals(2, backoff.attempts)
    }

    @Test
    fun reset_restarts_from_initial_delay() {
        val backoff = ReconnectBackoff()
        repeat(4) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(0, backoff.attempts)
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test
    fun custom_bounds_are_respected() {
        val backoff = ReconnectBackoff(initialMs = 500L, maxMs = 1_500L)
        val delays = (1..4).map { backoff.nextDelayMs() }
        assertEquals(listOf(500L, 1_000L, 1_500L, 1_500L), delays)
    }
}
