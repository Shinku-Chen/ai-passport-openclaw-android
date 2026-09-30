package com.shinku.aipassport.openclaw.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一段正文该不该发给设备」的纯决策单测(JVM,无 Android / 协程)。
 *
 * 背景:流式帧只推本轮**最后一条** assistant 消息,常常是「已回复完毕…」这类状态话术,
 * 真答案只在 `chat.history` 里。状态话术不该出现在设备屏上,所以**先缓发**,
 * 等历史补正/宽限窗给出结论;非状态话术的正文行为完全不变(立即下发)。
 */
class BodyDispatchTest {

    private companion object {
        /** 实测状态话术(命中 looksLikeStatusTalk)。 */
        const val STATUS_TALK = "新疆天气已回复完毕，当前无进行中的 exec 会话或子代理。"

        /** 真正的答案。 */
        const val ANSWER = "新疆以首府乌鲁木齐为例：多云，14.6℃，微风，AQI 54（良）。"
    }

    /** 流式=状态话术 + 历史有答案 → 只发历史答案(设备屏只有真答案)。 */
    @Test
    fun status_talk_uses_history_answer_when_available() {
        val action = bodyDispatch(STATUS_TALK, ANSWER, correctedAvailable = true)

        assertEquals(BodyAction.ReplaceWithCorrected(ANSWER), action)
    }

    /** 流式=状态话术,历史判定还没出结论 → 先缓发(不下发设备)。 */
    @Test
    fun status_talk_is_held_until_history_decision() {
        val action = bodyDispatch(STATUS_TALK, null, correctedAvailable = false)

        assertEquals(BodyAction.Hold(STATUS_TALK), action)
    }

    /** 流式=状态话术 + 历史无答案 → 补发缓存的流式 body(设备不能空着)。 */
    @Test
    fun status_talk_falls_back_to_streamed_body_when_history_has_no_answer() {
        assertEquals(
            BodyAction.SendHeld(STATUS_TALK),
            bodyDispatch(STATUS_TALK, null, correctedAvailable = true),
        )
        // 历史返回空白正文等同于「没有更好的正文」
        assertEquals(
            BodyAction.SendHeld(STATUS_TALK),
            bodyDispatch(STATUS_TALK, "   ", correctedAvailable = true),
        )
    }

    /** 流式=正常答案 → 立即发(不受影响,时延不增加)。 */
    @Test
    fun normal_body_is_sent_immediately() {
        assertEquals(
            BodyAction.SendNow(ANSWER),
            bodyDispatch(ANSWER, null, correctedAvailable = false),
        )
        assertEquals(
            BodyAction.SendNow(ANSWER),
            bodyDispatch(ANSWER, null, correctedAvailable = true),
        )
    }

    /** 不支持历史补正的通道(Hermes / Echo / 自定义 OpenAI 兼容)绝不缓发:没人会来给出结论。 */
    @Test
    fun unsupported_channel_never_holds() {
        assertEquals(
            BodyAction.SendNow(STATUS_TALK),
            bodyDispatch(STATUS_TALK, null, correctedAvailable = false, historyCorrectionSupported = false),
        )
    }

    /** 补正后的正文优先于「立即下发」:即使流式正文不是状态话术,也以历史答案为准。 */
    @Test
    fun corrected_body_wins_over_streamed_body() {
        assertEquals(
            BodyAction.ReplaceWithCorrected(ANSWER),
            bodyDispatch("北京明天晴，20℃/10℃。", ANSWER, correctedAvailable = true),
        )
    }

    /** 只有「有结论」时才可能使用补正正文。 */
    @Test
    fun corrected_body_without_decision_is_not_used() {
        val action = bodyDispatch(STATUS_TALK, ANSWER, correctedAvailable = false)
        assertTrue("判定未完成时不能直接用历史正文", action is BodyAction.Hold)
    }
}
