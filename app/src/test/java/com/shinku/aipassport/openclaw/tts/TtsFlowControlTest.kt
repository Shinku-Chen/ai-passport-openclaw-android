package com.shinku.aipassport.openclaw.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下行 TTS 流控单测(纯 JVM)。
 *
 * 约束(固件 `intercom-wire-protocol.md`:Phone must not run more than about 2 s of audio ahead):
 * App 领先设备的音频量 ≤ 2s,即最多 [TtsFlowControl.MAX_LEAD_FRAMES] = 33 帧未播完就得等;
 * 实际按 [TtsFlowControl.TARGET_LEAD_MS] = 1200ms 目标节流(设备解码队列只有 24 包 ≈1.44s)；
 * 同时用 [TtsFlowControl.MAX_INFLIGHT_FRAMES] 限制“在途未送达”的帧数。
 */
class TtsFlowControlTest {

    @Test
    fun lead_cap_is_two_seconds_and_33_frames() {
        assertEquals(60, TtsFlowControl.FRAME_MS)
        assertEquals(2_000, TtsFlowControl.MAX_LEAD_MS)
        assertEquals(33, TtsFlowControl.MAX_LEAD_FRAMES)
        // 33 帧仍在 2s 内,34 帧已超出:这就是「最多 33 帧未确认就等」的由来
        assertTrue(TtsFlowControl.MAX_LEAD_FRAMES * 60 <= TtsFlowControl.MAX_LEAD_MS)
        assertTrue((TtsFlowControl.MAX_LEAD_FRAMES + 1) * 60 > TtsFlowControl.MAX_LEAD_MS)
    }

    @Test
    fun lead_is_sent_audio_minus_elapsed_realtime() {
        // 推了 10 帧(600ms 音频),自 tts_start 只过了 100ms → 领先 500ms
        assertEquals(500L, TtsFlowControl.leadMs(10, 100))
        // 实时时间追平后领先为 0,再往后为负(设备反而在等手机)
        assertEquals(0L, TtsFlowControl.leadMs(10, 600))
        assertEquals(-400L, TtsFlowControl.leadMs(10, 1_000))
        assertEquals(10.0, TtsFlowControl.leadFrames(10, 0), 1e-9)
    }

    @Test
    fun wait_time_is_zero_until_target_lead_is_reached() {
        // 开局立刻可推:TTS 需要尽快在设备上出声
        assertEquals(0L, TtsFlowControl.waitMs(0, 0))
        // 目标领先量之内不等待(20 帧 = 1200ms 正好到点)
        assertEquals(0L, TtsFlowControl.waitMs(19, 0))
        assertEquals(0L, TtsFlowControl.waitMs(20, 0))
        // 超过目标后按差额等:21 帧(1260ms) - 1200ms = 60ms
        assertEquals(60L, TtsFlowControl.waitMs(21, 0))
        assertEquals(780L, TtsFlowControl.waitMs(33, 0))
        // 设备已经播到后面了(实时追平):不等
        assertEquals(0L, TtsFlowControl.waitMs(100, 6_000))
    }

    @Test
    fun hard_cap_boundary_matches_33_frames() {
        // 把目标当成硬上限:33 帧(1980ms)仍可推,34 帧(2040ms)必须等 40ms
        assertEquals(0L, TtsFlowControl.waitMs(33, 0, leadTargetMs = TtsFlowControl.MAX_LEAD_MS))
        assertEquals(40L, TtsFlowControl.waitMs(34, 0, leadTargetMs = TtsFlowControl.MAX_LEAD_MS))
        assertFalse(TtsFlowControl.atLeadCap(33, 0))
        assertTrue(TtsFlowControl.atLeadCap(34, 0))
        assertFalse("实时时间追上去后不再算到上限", TtsFlowControl.atLeadCap(34, 6_000))
    }

    /**
     * 按 [TtsFlowControl.waitMs] 节流后,领先量**恒不超过 2s**(也不会超过目标 + 1 帧):
     * 这是「不下发超过 33 帧未播完的音频」的可执行证明。
     */
    @Test
    fun honoring_wait_never_exceeds_the_two_second_cap() {
        var sent = 0
        var elapsedMs = 0L
        var maxLeadMs = Long.MIN_VALUE
        var maxFramesAhead = 0
        repeat(200) {
            elapsedMs += TtsFlowControl.waitMs(sent, elapsedMs)
            maxLeadMs = maxOf(maxLeadMs, TtsFlowControl.leadMs(sent, elapsedMs))
            maxFramesAhead = maxOf(maxFramesAhead, ((sent * 60L - elapsedMs + 59) / 60).toInt())
            sent++   // 发送本身不耗时(BLE 写队列异步吸收)
        }
        assertTrue("领先量 $maxLeadMs ms 超出 ≤2s 上限", maxLeadMs <= TtsFlowControl.MAX_LEAD_MS)
        assertTrue("领先超过 34 帧:${maxFramesAhead}", maxFramesAhead <= TtsFlowControl.MAX_LEAD_FRAMES + 1)
        assertTrue(
            "实际节流应贴着 800ms 目标,而不是贴 2s 硬上限:${maxLeadMs}",
            maxLeadMs <= TtsFlowControl.TARGET_LEAD_MS + TtsFlowControl.FRAME_MS,
        )
    }

    /** 目标领先必须明显小于硬上限(设备解码队列 24 包 ≈1.44s,贴 2s 会溢出丢包)。 */
    @Test
    fun target_lead_leaves_room_for_the_device_queue() {
        assertTrue(TtsFlowControl.TARGET_LEAD_MS < TtsFlowControl.MAX_LEAD_MS)
        assertTrue(
            "目标领先 ${TtsFlowControl.TARGET_LEAD_MS}ms 应小于设备 24 包 ≈1440ms 的解码队列",
            TtsFlowControl.TARGET_LEAD_MS < 24 * TtsFlowControl.FRAME_MS,
        )
    }

    @Test
    fun in_flight_backlog_is_capped() {
        // 已交队列 20 帧、只送达 5 帧 → 在途 15 帧 > 8 → 必须等(否则领先量只存在于手机队列里)
        assertTrue(TtsFlowControl.inFlightExceeds(sentFrames = 20, deliveredFrames = 5))
        assertTrue(TtsFlowControl.inFlightExceeds(sentFrames = 9, deliveredFrames = 0))
        // 刚好 8 帧在途不算超;送达追上后立即放行
        assertFalse(TtsFlowControl.inFlightExceeds(sentFrames = 8, deliveredFrames = 0))
        assertFalse(TtsFlowControl.inFlightExceeds(sentFrames = 20, deliveredFrames = 15))
    }

    @Test
    fun target_lead_stays_below_the_device_decode_queue() {
        // 设备解码队列 24 包 ≈1.44s:目标领先量必须小于它,又不能小到让设备饿着
        assertTrue(TtsFlowControl.TARGET_LEAD_MS < 24 * TtsFlowControl.FRAME_MS)
        assertTrue(TtsFlowControl.TARGET_LEAD_MS >= 1_000)
        // 在途上限必须远小于目标领先量,否则真正到设备的缓冲会被挤没
        assertTrue(TtsFlowControl.MAX_INFLIGHT_FRAMES * TtsFlowControl.FRAME_MS <=
            TtsFlowControl.TARGET_LEAD_MS / 2)
    }

    // ---- 小智直通的推送节奏:开播预充 + 与领先量无关的帧间下限([pushWaitMs])----

    /**
     * 参数取值本身要站得住:预充是「十几帧量级」且不会顶到硬上限;帧间下限比实时(60ms)快,
     * 又允许落后于实测吞吐(≈54ms/帧)时它不成为新瓶颈。
     */
    @Test
    fun precharge_and_realtime_floor_parameters_stay_in_the_safe_range() {
        // 预充加厚后的安全区间:12–16 帧(≈0.7–1.0s,作者给的区间)
        assertTrue(
            "预充应当已从 6 帧加厚到 12–16 帧,实际 ${TtsFlowControl.PRECHARGE_FRAMES}",
            TtsFlowControl.PRECHARGE_FRAMES in 12..16,
        )
        // 下限:6 帧 ≈360ms 扣掉最坏在途(8 帧 = 480ms)后设备侧会见底 —— 真机 `欠载=23` 就是这么来的
        assertTrue(TtsFlowControl.PRECHARGE_FRAMES * TtsFlowControl.FRAME_MS >= 600L)
        // 上限:必须**严格小于**设备解码队列(24 包≈1.44s)的一半,给「设备起播 priming 期间仍在到达的帧」留位置
        // (真机开局日志里出现过 `解码队列满,已丢最旧的包`,溢出丢的是用户**还没听到的**音频)
        assertTrue(TtsFlowControl.PRECHARGE_FRAMES <= 24 / 2)
        assertTrue(TtsFlowControl.PRECHARGE_FRAMES * TtsFlowControl.FRAME_MS < TtsFlowControl.TARGET_LEAD_MS)
        // 帧间下限在用户给定的 50–55ms 区间内、且比实时(60ms)快
        assertTrue(TtsFlowControl.MIN_SEND_INTERVAL_MS in 50L..55L)
        assertTrue(TtsFlowControl.MIN_SEND_INTERVAL_MS < TtsFlowControl.FRAME_MS)
        // 且不能慢于实测吞吐(≈54ms/帧),否则它会变成段落中段的新瓶颈
        assertTrue(TtsFlowControl.MIN_SEND_INTERVAL_MS <= 54L)
        // 在途积压有界等待必须短于写回调超时(2s),否则超时先触发、上限没意义
        assertTrue(TtsFlowControl.MAX_INFLIGHT_WAIT_MS <= 2_000L)
    }

    /**
     * 加厚预充**不能拖后首帧**:「首句上屏 → 立刻出声」要求第一帧零等待,预充只影响第 2 帧之后的
     * 快速填充(它的等待只能来自领先量那一条,而开播瞬间领先量 ≈0)。
     */
    @Test
    fun thicker_precharge_does_not_delay_the_first_frame() {
        assertEquals("首帧必须零等待", 0L, TtsFlowControl.pushWaitMs(0, 0L))
        assertEquals("第 2 帧也不应被帧间下限拖后", 0L, TtsFlowControl.pushWaitMs(1, TtsFlowControl.FRAME_MS.toLong()))
        // 预充整整 12–16 帧都在「一口气推」的范围内(每一帧都不因下限而等)
        for (sent in 0 until TtsFlowControl.PRECHARGE_FRAMES) {
            assertEquals(
                "预充第 $sent 帧不应因帧间下限等待",
                0L,
                TtsFlowControl.pushWaitMs(sent, sent * 5L),
            )
        }
    }

    /** 预充:前 [PRECHARGE_FRAMES] 帧不受帧间下限约束(开播瞬时一口气推给设备垫底)。 */
    @Test
    fun precharge_frames_bypass_the_realtime_floor() {
        // 快链路(一帧一次 ATT 写、往返只要 5ms):预充阶段一个等待都不要
        for (sent in 0 until TtsFlowControl.PRECHARGE_FRAMES) {
            assertEquals(
                "预充第 $sent 帧不应因帧间下限等待",
                0L,
                TtsFlowControl.pushWaitMs(sent, sent * 5L),
            )
        }
        // 预充之后的下一帧就必须按帧间下限等
        val sent = TtsFlowControl.PRECHARGE_FRAMES
        val elapsed = sent * 5L
        assertEquals(
            sent * TtsFlowControl.MIN_SEND_INTERVAL_MS - elapsed,
            TtsFlowControl.pushWaitMs(sent, elapsed),
        )
    }

    /** 实时下限与领先量无关:领先量还没到目标(甚至是负的)也要按帧间下限等。 */
    @Test
    fun realtime_floor_is_independent_of_the_lead() {
        val sent = 30
        val elapsed = 1_000L   // 领先 = 30×60 − 1000 = 800ms < 目标 1200ms → 领先量那条不等待
        assertTrue(TtsFlowControl.leadMs(sent, elapsed) < TtsFlowControl.TARGET_LEAD_MS)
        assertEquals(0L, TtsFlowControl.waitMs(sent, elapsed))
        // 但 30 帧 × 50ms = 1500ms > 1000ms 已过时间 → 必须等 500ms
        assertEquals(500L, TtsFlowControl.pushWaitMs(sent, elapsed))
    }

    /** 预充 + 帧间下限的端到端节奏:不超速、不欠载、领先量不破 2s 硬上限。 */
    @Test
    fun precharge_plus_realtime_floor_keeps_the_device_fed_without_flooding_it() {
        // 快的链路:一帧一次 ATT 写只要 20ms(比实测的 ≈54ms 还快 —— 帧间下限会生效)
        val fast = simulate(writeMsPerFrame = 20L, frames = 120)
        assertEquals(
            "预充之后不允许超速(每帧至少隔 ${TtsFlowControl.MIN_SEND_INTERVAL_MS}ms)",
            0,
            fast.rateViolations,
        )
        assertTrue("设备侧垫底音频出现了负数(会被记成欠载):${fast.minLeadMs}", fast.minLeadMs >= 0)
        assertTrue("预充后垫底音频应持续增长:${fast.maxLeadMs}", fast.maxLeadMs >= TtsFlowControl.PRECHARGE_FRAMES * TtsFlowControl.FRAME_MS)
        assertTrue("领先量不得超过 2s 硬上限:${fast.maxLeadMs}", fast.maxLeadMs <= TtsFlowControl.MAX_LEAD_MS)

        // 实测慢链路(带响应写 ≈54ms/帧,比 50ms 的下限还慢):帧间下限不生效,靠预充 +
        // 每帧 6ms 的余量继续垫底 —— 下限取 50ms(20 帧/秒)正是为了让它在慢链路上不成为新瓶颈
        val measured = simulate(writeMsPerFrame = 54L, frames = 120)
        assertTrue("实测链路也不允许欠载:${measured.minLeadMs}", measured.minLeadMs >= 0)
        assertTrue("领先量不得超过 2s 硬上限:${measured.maxLeadMs}", measured.maxLeadMs <= TtsFlowControl.MAX_LEAD_MS)
    }

    /** 按帧循环模拟:每帧先按 [TtsFlowControl.pushWaitMs] 等,再花 [writeMsPerFrame] 交给 BLE。 */
    private class Rhythm(
        val minLeadMs: Long,
        val maxLeadMs: Long,
        val rateViolations: Int,
    )

    private fun simulate(writeMsPerFrame: Long, frames: Int): Rhythm {
        var sent = 0
        var elapsedMs = 0L
        var minLead = Long.MAX_VALUE
        var maxLead = Long.MIN_VALUE
        var violations = 0
        repeat(frames) {
            elapsedMs += TtsFlowControl.pushWaitMs(sent, elapsedMs)
            if (sent >= TtsFlowControl.PRECHARGE_FRAMES &&
                elapsedMs < sent * TtsFlowControl.MIN_SEND_INTERVAL_MS
            ) {
                violations++
            }
            elapsedMs += writeMsPerFrame
            sent++
            val lead = TtsFlowControl.leadMs(sent, elapsedMs)
            minLead = minOf(minLead, lead)
            maxLead = maxOf(maxLead, lead)
        }
        return Rhythm(minLead, maxLead, violations)
    }

    // ---- 在途积压的有界等待([backlogWaitMs])----

    @Test
    fun backlog_wait_polls_while_in_flight_and_releases_when_caught_up() {
        // 在途 15 帧 > 8 → 每次只等一个轮询间隔
        assertEquals(TtsFlowControl.INFLIGHT_POLL_MS, TtsFlowControl.backlogWaitMs(20, 5, waitedMs = 0))
        // 送达追上了 → 立刻放行
        assertEquals(0L, TtsFlowControl.backlogWaitMs(20, 15, waitedMs = 0))
        assertEquals(0L, TtsFlowControl.backlogWaitMs(8, 0, waitedMs = 0))
    }

    /** 有界等待:最长只等 [MAX_INFLIGHT_WAIT_MS],到点必须放行(否则链路半死时整段卡死)。 */
    @Test
    fun backlog_wait_is_bounded_and_then_releases() {
        var waited = 0L
        var polls = 0
        while (true) {
            val step = TtsFlowControl.backlogWaitMs(sentFrames = 20, deliveredFrames = 0, waitedMs = waited)
            if (step <= 0L) break
            waited += step
            polls++
            assertTrue("轮询必须有界", polls < 10_000)
        }
        assertEquals(TtsFlowControl.MAX_INFLIGHT_WAIT_MS, waited)
        assertEquals(TtsFlowControl.MAX_INFLIGHT_WAIT_MS / TtsFlowControl.INFLIGHT_POLL_MS, polls.toLong())
        // 到上限后即使还在途也放行(服务侧据此继续下发,并留一行警告日志)
        assertEquals(0L, TtsFlowControl.backlogWaitMs(20, 0, waitedMs = TtsFlowControl.MAX_INFLIGHT_WAIT_MS))
    }
}
