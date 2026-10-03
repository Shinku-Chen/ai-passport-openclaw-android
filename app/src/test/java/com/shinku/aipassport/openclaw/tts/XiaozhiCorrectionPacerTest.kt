package com.shinku.aipassport.openclaw.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **正文上屏让路**([XiaozhiCorrectionPacer])与上屏取证行([XiaozhiPacingLog])的 JVM 单测:
 * 纯逻辑,无 Android/BLE/coroutines 依赖。
 *
 * 被测契约(见 `docs/design/xiaozhi-ai-gateway.md` §4.6 与 [XiaozhiCorrectionPacer] 的类注释;
 * 按段播放之后本类已被简化,见下):
 *  1. **首句不经这里**(它在服务侧就被放行;按段播放里它就是第 1 段)—— 本类的每个入口都只在
 *     「下一段的字幕」上被调用;
 *  2. **音频不紧才写**:垫底 < 阈值 / 在途积压 / 距上次上屏太近 → **暂缓**;
 *  3. **合并且不设次数上限**:同一轮的多次上屏请求攒成**最新的一条**,时机到了一起发;
 *     **没有**「一轮最多 N 次」—— 按段播放里每一段的字幕都是它那一节音频的前置条件,
 *     攒到收尾等于让那几段音频也停在那里;
 *  4. **收尾那次无条件**:本段音频推完([XiaozhiCorrectionPacer.flush])一定把攒着的正文交出去
 *     («文字最终一定完整»这条硬约束);兜底时限到也一样;
 *  5. **新一轮丢弃**:`turn_start` 攒着的文案作废,绝不带到下一轮;
 *  6. 没有在推的音频(直通未接线 / 非小智 / 一段还没开播 / 开关关着)→ **一秒都不拦**。
 */
class XiaozhiCorrectionPacerTest {

    /** 默认「很宽裕」的音频快照:垫底 2s、无在途、刚写过一帧。 */
    private fun pacing(
        active: Boolean = true,
        leadMs: Long = 2_000,
        inflight: Int = 0,
        sinceWrite: Long = 60L,
        queue: Int = 100,
        pushedAudioMs: Long = 0L,
    ) = XiaozhiAudioPacing(
        active = active,
        leadMs = leadMs,
        leadFrames = leadMs.toDouble() / TtsFlowControl.FRAME_MS,
        inflightFrames = inflight,
        msSinceLastFrameWrite = sinceWrite,
        queueFrames = queue,
        pushedAudioMs = pushedAudioMs,
    )

    /** 参数取值本身要站得住(阈值/间隔/兜底都必须落在文档给出的安全范围里)。 */
    @Test
    fun parameters_stay_in_the_documented_safe_range() {
        // 垫底阈值:必须「够高」(>= 一次渲染停顿 + 一次写抖动的量级,否则等于不拦)、
        // 又必须严格小于流控目标领先量(否则每次让路都要等到垫底饱和,文案白晚 5–6s)
        assertTrue(XiaozhiCorrectionPacer.LEAD_HOLD_MS >= 400L)
        assertTrue(XiaozhiCorrectionPacer.LEAD_HOLD_MS < TtsFlowControl.TARGET_LEAD_MS)
        // 在途阈值:严格小于在途上限(否则它永远不生效 —— 在途到上限时流控自己已经在等了)
        assertTrue(XiaozhiCorrectionPacer.INFLIGHT_HOLD_FRAMES < TtsFlowControl.MAX_INFLIGHT_FRAMES)
        assertTrue(XiaozhiCorrectionPacer.INFLIGHT_HOLD_FRAMES >= 2)
        // 最小间隔:至少几帧音频(设备要把 RX 排空),又不能长到让文案停滞一整段
        assertTrue(XiaozhiCorrectionPacer.MIN_CORRECTION_GAP_MS >= 300L)
        assertTrue(XiaozhiCorrectionPacer.MIN_CORRECTION_GAP_MS <= 1_000L)
        // 兜底时限:必须长于任何一种「正常等法」的量级(一段音频推完最多 ~10s)
        assertTrue(XiaozhiCorrectionPacer.MAX_HOLD_MS >= 3 * XiaozhiTailStop.TAIL_IDLE_MS)
    }

    /** 没有在播的音频(直通没接线 / 本轮还没开段 / 开关关着)→ 一次都不拦。 */
    @Test
    fun no_running_audio_never_defers() {
        val pacer = XiaozhiCorrectionPacer()
        assertEquals(
            XiaozhiCorrectionPacer.Decision.SEND_NOW,
            pacer.offer("第二句", nowMs = 0L, pacing = null),
        )
        assertEquals(
            XiaozhiCorrectionPacer.Decision.SEND_NOW,
            pacer.offer("第三句", nowMs = 0L, pacing = pacing(active = false, leadMs = -5_000)),
        )
        assertNull("直通没在推,就没有「待补」这回事", pacer.pendingBody)
    }

    /** 音频宽裕(垫底够、无在途、间隔够)→ 下一段的字幕立刻上屏。 */
    @Test
    fun comfortable_audio_sends_the_subtitle_right_away() {
        val pacer = XiaozhiCorrectionPacer()
        assertEquals(
            XiaozhiCorrectionPacer.Decision.SEND_NOW,
            pacer.offer("第一句。第二句。", nowMs = 5_000L, pacing = pacing(leadMs = 900L, inflight = 3)),
        )
        assertEquals(1, pacer.sentCount)
        assertNull(pacer.pendingBody)
    }

    /** 音频紧(垫底见底 / 在途积压 / 距上次太近)→ 暂缓;恢复后 [tick] 吐出来。 */
    @Test
    fun tight_audio_holds_and_tick_delivers_once_the_bottom_recovers() {
        val pacer = XiaozhiCorrectionPacer()
        // 上一段的上屏:音频宽裕 → 立刻发(同时把「上一次上屏时刻」建立起来)
        assertEquals(
            XiaozhiCorrectionPacer.Decision.SEND_NOW,
            pacer.offer("第一句。", nowMs = 1_000L, pacing = pacing()),
        )
        // 下一段的字幕:垫底见底 → 暂缓
        assertEquals(
            XiaozhiCorrectionPacer.Decision.HOLD,
            pacer.offer("第一句。第二句。", nowMs = 1_100L, pacing = pacing(leadMs = 300L)),
        )
        assertEquals("第一句。第二句。", pacer.pendingBody)
        assertNull("垫底还见底:继续攒", pacer.tick(nowMs = 1_200L, pacing = pacing(leadMs = 300L)))
        assertNull(
            "垫底恢复了但写队列在积压:还是不发",
            pacer.tick(nowMs = 1_300L, pacing = pacing(leadMs = 1_900L, inflight = 5)),
        )
        assertNull(
            "都不紧了但距上次上屏不足最小间隔:仍然不发",
            pacer.tick(nowMs = 1_000L + XiaozhiCorrectionPacer.MIN_CORRECTION_GAP_MS - 1, pacing = pacing()),
        )
        assertEquals(
            "全部条件满足:tick 把攒着的正文交出去",
            "第一句。第二句。",
            pacer.tick(nowMs = 1_000L + XiaozhiCorrectionPacer.MIN_CORRECTION_GAP_MS, pacing = pacing()),
        )
        assertEquals(2, pacer.sentCount)
    }

    /** 合并:同一轮多次上屏请求只留**最新**的一条,时机到了只发一次。 */
    @Test
    fun multiple_requests_are_coalesced_into_the_latest_one() {
        val pacer = XiaozhiCorrectionPacer()
        val tight = pacing(leadMs = -500L)
        assertEquals(XiaozhiCorrectionPacer.Decision.HOLD, pacer.offer("一。", nowMs = 1_000L, pacing = tight))
        assertEquals(XiaozhiCorrectionPacer.Decision.HOLD, pacer.offer("一。二。", nowMs = 1_500L, pacing = tight))
        assertEquals(XiaozhiCorrectionPacer.Decision.HOLD, pacer.offer("一。二。三。", nowMs = 2_000L, pacing = tight))
        assertEquals("攒的是最新最完整的那条", "一。二。三。", pacer.pendingBody)
        assertNull(pacer.tick(nowMs = 2_100L, pacing = tight))
        assertEquals("只发一次(中间那些短版本不单独上屏)", "一。二。三。", pacer.tick(nowMs = 2_200L, pacing = pacing()))
        assertEquals(1, pacer.sentCount)
    }

    /**
     * **不设次数上限**(按段播放的核心要求):同一轮里每一次「下一段的字幕」都要能放行 ——
     * 若像上一版那样「最多 3 次主动补正」,第 4 段以后的字幕会被攒到收尾,而那几段的音频
     * 正等着自己的字幕(段的开播闸门在 relay)才可能开播。
     */
    @Test
    fun corrections_are_never_capped_under_the_segment_model() {
        val pacer = XiaozhiCorrectionPacer()
        var now = 0L
        repeat(8) { i ->
            now += XiaozhiCorrectionPacer.MIN_CORRECTION_GAP_MS
            assertEquals(
                "第 ${i + 1} 段的字幕应当放行(音频宽裕)",
                XiaozhiCorrectionPacer.Decision.SEND_NOW,
                pacer.offer("正文-$i", nowMs = now, pacing = pacing()),
            )
        }
        assertEquals(8, pacer.sentCount)
        assertNull("每次都是立刻上屏,没有待补", pacer.pendingBody)
    }

    /** 收尾([flush])**无条件**:音频垫底还是负的也要把攒着的正文补上(«文字最终一定完整»)。 */
    @Test
    fun tail_flush_is_unconditional_even_while_the_audio_is_still_tight() {
        val pacer = XiaozhiCorrectionPacer()
        assertEquals(
            XiaozhiCorrectionPacer.Decision.HOLD,
            pacer.offer("最终完整正文", nowMs = 1_000L, pacing = pacing(leadMs = -1_000L, inflight = 8)),
        )
        assertEquals("最终完整正文", pacer.flush(nowMs = 1_100L))
        assertNull("没有待补时 flush 是空操作", pacer.flush(nowMs = 1_200L))
    }

    /** 兜底时限:音频那一路病态(一直紧)也不能让文案无限期地消失(否则那一段也永远等不到字幕)。 */
    @Test
    fun max_hold_deadline_eventually_delivers() {
        val pacer = XiaozhiCorrectionPacer()
        val alwaysTight = pacing(leadMs = -1_000L, inflight = 8)
        val t0 = 50_000L
        assertEquals(XiaozhiCorrectionPacer.Decision.HOLD, pacer.offer("最终完整正文", nowMs = t0, pacing = alwaysTight))
        assertNull(
            "还没到时限:继续攒",
            pacer.tick(nowMs = t0 + XiaozhiCorrectionPacer.MAX_HOLD_MS - 1, pacing = alwaysTight),
        )
        assertEquals(
            "时限到:无条件补上",
            "最终完整正文",
            pacer.tick(nowMs = t0 + XiaozhiCorrectionPacer.MAX_HOLD_MS, pacing = alwaysTight),
        )
    }

    /** 新一轮 / 打断:攒着的文案与记账一并作废(旧文案绝不带到下一轮)。 */
    @Test
    fun turn_start_drops_the_pending_body_and_resets_the_accounting() {
        val pacer = XiaozhiCorrectionPacer()
        pacer.offer("第一句。第二句。", nowMs = 1_000L, pacing = pacing(leadMs = 100L))
        assertNotNull(pacer.pendingBody)
        pacer.onTurnStart()
        assertNull(pacer.pendingBody)
        assertEquals(0, pacer.sentCount)
        assertNull("清空之后 tick 什么都不吐", pacer.tick(nowMs = 9_999L, pacing = pacing()))
        // 新一轮的第一次上屏不受上一轮的间隔影响
        assertEquals(
            XiaozhiCorrectionPacer.Decision.SEND_NOW,
            pacer.offer("新一轮正文", nowMs = 10_000L, pacing = pacing()),
        )
    }

    /** 空文本不进让路通道(它本来就该照旧上屏,不该被攒起来)。 */
    @Test
    fun blank_body_is_never_held() {
        val pacer = XiaozhiCorrectionPacer()
        assertEquals(
            XiaozhiCorrectionPacer.Decision.SEND_NOW,
            pacer.offer("   ", nowMs = 0L, pacing = pacing(leadMs = -9_000L)),
        )
        assertNull(pacer.pendingBody)
    }

    /** 取证日志行:格式钉死(真机就是按这一行核对「字幕落位时刻 / 该句起点 / 垫底」)。 */
    @Test
    fun pacing_log_line_has_the_agreed_shape() {
        val line = XiaozhiPacingLog.line(
            ordinal = 2,
            pacing = pacing(leadMs = 600L, inflight = 1, sinceWrite = 54L, queue = 37, pushedAudioMs = 1_800L),
            sentenceStartMs = 2_400L,
        )
        assertTrue("要有第几句字幕上屏:$line", line.contains("第 2 句字幕上屏"))
        assertTrue("要保留第几次上屏/补正:$line", line.contains("第 2 次上屏/补正"))
        assertTrue("要有已推送音频:$line", line.contains("已推送音频≈1800ms"))
        assertTrue("要有该句起点:$line", line.contains("该句起点≈2400ms"))
        assertTrue("要有垫底帧数与毫秒数:$line", line.contains("音频垫底≈10 帧(≈600ms)"))
        assertTrue("要有在途:$line", line.contains("在途=1"))
        assertTrue("要有距上一音频帧写入:$line", line.contains("距上一音频帧写入 54ms"))
        assertTrue("要有队列:$line", line.contains("队列=37"))

        // 本段还没写出过帧 → 明确写「无」,不要伪装成 0ms(那会看起来像「刚刚写过」)
        val head = XiaozhiPacingLog.line(1, pacing(sinceWrite = -1L))
        assertTrue(head.contains("距上一音频帧写入 无"))
        // 没有句起点估计 → 写「未知」,不编造一个 0
        assertTrue(XiaozhiPacingLog.line(3, pacing()).contains("该句起点未知"))
        // 直通未接线(非小智路径)→ 不能编造一个 0
        assertFalse(XiaozhiPacingLog.line(1, null).contains("垫底≈0"))
        assertTrue(XiaozhiPacingLog.line(1, null).contains("未接线"))
    }
}
