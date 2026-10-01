package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮没识别出结果 → 重连重放」的纯逻辑单测。
 *
 * 背景:小智服务端闲置约 60s 后静默废弃连接上的识别会话(传输层 ping/pong 仍通、不报 error),
 * 此时整轮音频会被发进死会话 → 等满超时也只能回「无语音」,用户说的话就丢了。
 * 因此:本轮音频留缓存,「已上送音频却一条 stt 都没等到」时重连一次并重放(每轮最多一次)。
 */
class TurnRecoveryTest {

    @Test
    fun retry_when_audio_was_sent_but_no_stt_arrived() {
        assertTrue(
            "已上送音频、一条 stt 都没收到 → 必须重连重放(否则用户白说一句)",
            TurnRecovery.shouldRetry(bufferedFrames = 37, alreadyRetried = false, sawAnyStt = false),
        )
    }

    @Test
    fun no_retry_without_any_buffered_audio() {
        assertFalse(
            "本轮没有音频可重放 → 不重连(空手重放没有意义,只会白连一次)",
            TurnRecovery.shouldRetry(bufferedFrames = 0, alreadyRetried = false, sawAnyStt = false),
        )
    }

    @Test
    fun at_most_one_retry_per_turn() {
        assertFalse(
            "每轮最多补救一次,不能变成重连死循环",
            TurnRecovery.shouldRetry(bufferedFrames = 37, alreadyRetried = true, sawAnyStt = false),
        )
    }

    @Test
    fun no_retry_when_stt_already_arrived() {
        assertFalse(
            "已经收到过 stt 说明链路是好的 → 不打断它",
            TurnRecovery.shouldRetry(bufferedFrames = 37, alreadyRetried = false, sawAnyStt = true),
        )
    }

    @Test
    fun buffer_has_a_hard_cap() {
        assertFalse(TurnRecovery.bufferFull(0))
        assertFalse(TurnRecovery.bufferFull(TurnRecovery.MAX_BUFFER_FRAMES - 1))
        assertTrue("到上限就不再缓存(防异常长按吃内存)", TurnRecovery.bufferFull(TurnRecovery.MAX_BUFFER_FRAMES))
        assertTrue(TurnRecovery.bufferFull(TurnRecovery.MAX_BUFFER_FRAMES + 1))
    }

    @Test
    fun buffer_capacity_covers_a_long_hold() {
        // 60ms/帧:400 帧 ≈ 24s 语音,足够覆盖最长一次按住说话
        val seconds = TurnRecovery.MAX_BUFFER_FRAMES * 60L / 1000L
        assertTrue("缓存只够 $seconds s,太短", seconds >= 20L)
    }

    @Test
    fun first_wait_is_much_longer_than_measured_recognition_latency() {
        // 真机正常一轮:turn_end → stt 实测约 0.2s;第一段等待必须明显长于它,避免误判成"没结果"
        assertTrue(
            "第一段等待 ${TurnRecovery.FIRST_WAIT_MS}ms 太短,正常识别会被误判并触发无谓重连",
            TurnRecovery.FIRST_WAIT_MS >= 800L,
        )
        assertTrue(
            "第一段等待 ${TurnRecovery.FIRST_WAIT_MS}ms 太长,失败时会拖慢补救",
            TurnRecovery.FIRST_WAIT_MS <= 3_000L,
        )
    }

    @Test
    fun total_recovery_budget_stays_user_tolerable() {
        val worst = TurnRecovery.FIRST_WAIT_MS +
            TurnRecovery.RECONNECT_WAIT_MS +
            TurnRecovery.REPLAY_WAIT_MS
        // 松手 → 出结果的最坏情况(重连也超时):仍应控制在 15s 内(设备侧没有结果超时,但用户会等)
        assertTrue("最坏补救耗时 ${worst}ms 过长", worst <= 15_000L)
    }

    @Test
    fun default_budget_matches_production_constants() {
        val budget = TurnRecovery.Budget()
        assertEquals(TurnRecovery.FIRST_WAIT_MS, budget.firstWaitMs)
        assertEquals(TurnRecovery.RECONNECT_WAIT_MS, budget.reconnectWaitMs)
        assertEquals(TurnRecovery.REPLAY_WAIT_MS, budget.replayWaitMs)
    }

    @Test
    fun budget_can_be_shortened_for_tests() {
        val budget = TurnRecovery.Budget(firstWaitMs = 300L, reconnectWaitMs = 1_500L, replayWaitMs = 500L)
        assertEquals(300L, budget.firstWaitMs)
        assertEquals(1_500L, budget.reconnectWaitMs)
        assertEquals(500L, budget.replayWaitMs)
    }
}
