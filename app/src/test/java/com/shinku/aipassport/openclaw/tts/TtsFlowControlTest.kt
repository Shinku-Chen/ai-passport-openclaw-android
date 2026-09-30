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
 * 实际按 [TtsFlowControl.TARGET_LEAD_MS] = 800ms 目标节流(设备解码队列只有 24 包 ≈1.44s)。
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
        // 800ms 目标内不等待
        assertEquals(0L, TtsFlowControl.waitMs(13, 0))
        // 领先超过 800ms 后按差额等:14 帧(840ms) - 800ms = 40ms
        assertEquals(40L, TtsFlowControl.waitMs(14, 0))
        assertEquals(1_180L, TtsFlowControl.waitMs(33, 0))
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
}
