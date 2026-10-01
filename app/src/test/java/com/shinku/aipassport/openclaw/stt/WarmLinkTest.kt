package com.shinku.aipassport.openclaw.stt

import com.shinku.aipassport.openclaw.stt.WarmLink.WarmDecision
import com.shinku.aipassport.openclaw.stt.WarmLink.WarmLinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别通道常驻预热」(热连接)的纯逻辑单测。
 *
 * 背景:每轮都重新跟小智云端握手 + `hello` 往返实测 0.5–2s,设备「按下→可说话」有明显空窗。
 * 热连接复用后按下即可 `listen.start`,但三个判定必须严格:
 *  - 可用 → **复用**(不重连,按下毫秒级就绪);
 *  - 已断开 → **重连**(退回既有「按下才连」);
 *  - 闲置超时 → 关掉并**重新建连**(不长期占资源);
 *  - 复用失败 → 允许**一次**兜底重连(不让预热问题毁掉整轮识别);
 *  - `onReady`(设备变绿)只在 `listen.start` **真的发出**后触发。
 */
class WarmLinkTest {

    private val now = 1_000_000L

    private fun connected(activeAt: Long = now) = WarmLinkState(connected = true, lastActiveAtMs = activeAt)

    @Test
    fun available_warm_link_is_reused_without_reconnect() {
        val state = connected(activeAt = now - 1_000L)
        assertEquals(WarmDecision.REUSE, WarmLink.decide(state, now))
        assertTrue("可用热连接必须复用(不重连)", WarmLink.shouldReuse(state, now))
    }

    @Test
    fun disconnected_warm_link_reconnects() {
        val state = WarmLinkState(connected = false, lastActiveAtMs = now)
        assertEquals(WarmDecision.RECONNECT_DISCONNECTED, WarmLink.decide(state, now))
        assertFalse(WarmLink.shouldReuse(state, now))
        assertEquals("已断开", WarmDecision.RECONNECT_DISCONNECTED.reason)
    }

    @Test
    fun idle_timeout_reconnects() {
        // 刚好到点就算超时(>= 语义,与「闲置满 IDLE_TIMEOUT_MS 就关」一致)
        val atTimeout = connected(activeAt = now - WarmLink.IDLE_TIMEOUT_MS)
        assertEquals(WarmDecision.RECONNECT_IDLE_TIMEOUT, WarmLink.decide(atTimeout, now))
        assertFalse(WarmLink.shouldReuse(atTimeout, now))
        // 差 1ms 还没到点 → 仍然复用
        val justBefore = connected(activeAt = now - WarmLink.IDLE_TIMEOUT_MS + 1)
        assertEquals(WarmDecision.REUSE, WarmLink.decide(justBefore, now))
        assertEquals("闲置超时", WarmDecision.RECONNECT_IDLE_TIMEOUT.reason)
    }

    @Test
    fun idle_timeout_is_configurable() {
        val state = connected(activeAt = now - 5_000L)
        assertEquals(WarmDecision.RECONNECT_IDLE_TIMEOUT, WarmLink.decide(state, now, idleTimeoutMs = 3_000L))
        assertEquals(WarmDecision.REUSE, WarmLink.decide(state, now, idleTimeoutMs = 60_000L))
    }

    @Test
    fun disabled_or_zero_idle_timeout_always_reuses_connected_link() {
        val state = connected(activeAt = now - 10L * 365L * 24L * 3600L * 1000L)
        assertEquals(WarmDecision.REUSE, WarmLink.decide(state, now, idleTimeoutMs = 0L))
        assertEquals(WarmDecision.REUSE, WarmLink.decide(state, now, idleTimeoutMs = -1L))
    }

    @Test
    fun clock_rollback_does_not_kill_warm_link() {
        // 系统时间被往回调(now < lastActiveAtMs):按「没超时」处理,不能误杀热连接
        val state = connected(activeAt = now + 60_000L)
        assertEquals(WarmDecision.REUSE, WarmLink.decide(state, now))
    }

    @Test
    fun ready_only_after_listen_start_is_sent() {
        assertTrue("listen.start 已发出 = 识别会话真的建立", WarmLink.sessionEstablished(true))
        assertFalse("listen.start 发不出去时绝不能报就绪(设备不能变绿)", WarmLink.sessionEstablished(false))
    }

    @Test
    fun reuse_failure_allows_exactly_one_fallback_reconnect() {
        assertTrue("复用失败后允许一次兜底重连(保底仍能识别)", WarmLink.shouldFallbackReconnect(0))
        assertFalse("已重连过一次,不再重试", WarmLink.shouldFallbackReconnect(1))
        assertFalse(WarmLink.shouldFallbackReconnect(2))
    }

    @Test
    fun default_idle_timeout_is_below_the_server_session_lifetime() {
        // 真机实测:在本设备上闲置 ≤53.8s 识别正常,≥64.0s 全部静默失败(服务端会话被废弃、
        // 传输层 ping/pong 却仍通)。所以热连接必须在 ~60s 之前就换掉:上界取 55s 留出余量,
        // 下界 30s 避免把连接换得太勤(每次都多一次握手与服务器会话)。
        assertTrue(
            "闲置超时 ${WarmLink.IDLE_TIMEOUT_MS}ms 太接近服务端 ~60s 的会话寿命,会漏进死会话",
            WarmLink.IDLE_TIMEOUT_MS <= 55_000L,
        )
        assertTrue(
            "闲置超时 ${WarmLink.IDLE_TIMEOUT_MS}ms 太短,连接会被无谓地反复重建",
            WarmLink.IDLE_TIMEOUT_MS >= 30_000L,
        )
    }
}
