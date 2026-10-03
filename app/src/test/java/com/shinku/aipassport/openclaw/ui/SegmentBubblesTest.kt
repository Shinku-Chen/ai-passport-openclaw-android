package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.gateway.BodyDelivery
import com.shinku.aipassport.openclaw.stt.XiaozhiReplyOutcome
import com.shinku.aipassport.openclaw.stt.XiaozhiReplyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **App 侧按段气泡**的 JVM 单测(纯逻辑 + [ConversationStore],不依赖 Android/网络)。
 *
 * 背景(本轮作者要求):设备屏已经是「每段一条」(A、B、C),App 侧却把**每一段都当成「替换同一个正文
 * 气泡」**(网关补正回调旧签名只有字符串,没有「这是新的一段还是本段的更新」的信息)→ 三轮段过后 App
 * 只剩最后一段。修法是把装配器([XiaozhiReplyText])知道的**段号 + 新段/本段更新**一路带到 UI,由
 * [planSegmentBody]/[applySegmentBody] 决定**追加**还是**就地替换该段那一条**。
 *
 * 组合逻辑直接跑真正的 [XiaozhiReplyText](真装配器)+ 真正的 [ConversationStore],与 `VoicePipeline`
 * 的两步同形:首段走 `publishReplyToApp`(本条测试里由 [AppSegmentBubbles] 直接登记为第 1 段),
 * 后续每一段/本段更新走 `resolveTurnBody` → `replaceBodyBubbles`。
 *
 * 注意:[ConversationStore] 是进程内单例,因此断言只看**本用例登记的那些 id**(由 [AppSegmentBubbles]
 * 持有),不依赖全局条数。
 */
class SegmentBubblesTest {

    /**
     * 与 `VoicePipeline` 同形的 App 侧记账:
     *  - 首条正文 = 第 1 段(等价 `publishReplyToApp` 正常气泡);
     *  - 之后每条 `changed` 的装配结果都按 [planSegmentBody] + [applySegmentBody] 落地。
     */
    private class AppSegmentBubbles {
        /** 本轮已写入的正文气泡 id → 文本(与 `TurnBodies.bubbles` 同一份)。 */
        val bubbles = mutableListOf<Pair<Long, String>>()

        /** 装配器交出的每一段(记录段号/新段标记,供断言). */
        val deliveries = mutableListOf<BodyDelivery>()

        val reply = XiaozhiReplyText(
            emit = { outcome ->
                if (outcome.changed && outcome.body.isNotBlank()) apply(outcome)
            },
        )

        private fun apply(outcome: XiaozhiReplyOutcome) {
            deliveries += BodyDelivery(
                text = outcome.body,
                isNewSegment = outcome.isNewSegment,
                segmentOrdinal = outcome.deliveryIndex,
            )
            if (bubbles.isEmpty()) {
                // 首段:与流水线一致,由 `publishReplyToApp` 写成正常气泡(`[流式]`)
                val id = ConversationStore.add(
                    "agent",
                    outcome.body,
                    ConversationStore.SOURCE_VOICE,
                    flag = bodyFlag(outcome.body, BodySource.STREAM),
                )
                bubbles += id to outcome.body
                return
            }
            val plan = planSegmentBody(
                bodyIds = bubbles.map { it.first },
                isNewSegment = outcome.isNewSegment,
                segmentOrdinal = outcome.deliveryIndex,
            )
            applySegmentBody(
                plan = plan,
                text = outcome.body,
                source = ConversationStore.SOURCE_VOICE,
                bodySource = BodySource.HISTORY,
                bubbles = bubbles,
            )
        }

        /** 推一串「一句话一段」的段流(每段 `sentence_start` 已带该句完整文本,`sentence_end` 再推一次)。 */
        fun driveSegments(vararg segments: String) {
            reply.onTurnStart()
            segments.forEach { text ->
                reply.onTtsState("sentence_start", text)
                reply.onTtsState("sentence_end", text)
            }
            reply.onTtsState("stop", "")
        }

        fun bubbleTexts(): List<String> = bubbles.map { it.second }

        /** 本条记在 ConversationStore 里的正常气泡(label == null)条数。 */
        fun normalBubbleCount(): Int =
            bubbles.count { (id, _) ->
                ConversationStore.messages.value.first { it.id == id }.label == null
            }
    }

    private fun textOf(id: Long): String =
        ConversationStore.messages.value.first { it.id == id }.text

    // ---- ① 三段流 → App 侧恰好三条(A、B、C 各一条) ----

    @Test
    fun three_segment_stream_yields_exactly_three_bubbles() {
        val app = AppSegmentBubbles()
        app.driveSegments("第一段。", "第二段。", "第三段。")

        assertEquals("三段各一条,不是只剩最后一段", 3, app.bubbles.size)
        assertEquals(listOf("第一段。", "第二段。", "第三段。"), app.bubbleTexts())
        assertEquals("段号依次 1/2/3", listOf(1, 2, 3), app.deliveries.map { it.segmentOrdinal })
        assertEquals(
            "每段都是「新段」(App 侧追加)",
            listOf(true, true, true),
            app.deliveries.map { it.isNewSegment },
        )
        assertEquals("没有累计正文(AB/ABC 重复气泡的根因)", 3, app.bubbleTexts().distinct().size)
        assertTrue("三条都是正常气泡(不是弱化小字)", app.normalBubbleCount() == 3)
    }

    // ---- ② 段内更新(半句 → 整句)→ 仍是三条,只有该段被替换 ----

    @Test
    fun in_segment_update_replaces_only_that_segment() {
        val app = AppSegmentBubbles()
        app.reply.onTurnStart()
        app.reply.onTtsState("sentence_start", "半句")
        val firstId = app.bubbles.single().first
        assertEquals("首段立即上屏", "半句", textOf(firstId))

        // 第 1 段的同一句变完整:`sentence_end` 更新本段(段号不变)
        app.reply.onTtsState("sentence_end", "半句变完整了。")
        // 后续两段照常各追加一条
        app.reply.onTtsState("sentence_start", "第二段。")
        app.reply.onTtsState("sentence_end", "第二段。")
        app.reply.onTtsState("sentence_start", "第三段。")
        app.reply.onTtsState("sentence_end", "第三段。")
        app.reply.onTtsState("stop", "")

        assertEquals("段内更新不新增气泡", 3, app.bubbles.size)
        assertEquals(listOf("半句变完整了。", "第二段。", "第三段。"), app.bubbleTexts())
        assertEquals("第 1 段是同一条气泡被**就地替换**", firstId, app.bubbles[0].first)
        assertEquals("该段(且只有该段)被替换", "半句变完整了。", textOf(firstId))
        assertEquals(
            "第二条交付是本段更新(段号仍是 1)",
            BodyDelivery("半句变完整了。", isNewSegment = false, segmentOrdinal = 1),
            app.deliveries[1],
        )
    }

    // ---- ③ 收尾(tts.stop)不产生第 4 条 ----

    @Test
    fun settle_after_all_segments_produces_no_extra_bubble() {
        val app = AppSegmentBubbles()
        app.driveSegments("A", "B", "C")
        val before = app.bubbles.size

        // 再结算一次(等价 `stop` 重复到达 / 收尾补缺的判定):各段都已上屏 → 什么都不发
        app.reply.onTtsState("stop", "")
        assertEquals("收尾不产生第 4 条", before, app.bubbles.size)
        assertEquals(listOf("A", "B", "C"), app.bubbleTexts())
    }

    // ---- ④ 某段缺失时的补上 → 按装配器口径是**新段**(追加) ----

    @Test
    fun missing_segment_supplement_appends_as_a_new_segment() {
        val app = AppSegmentBubbles()
        app.reply.onTurnStart()
        app.reply.onTtsState("sentence_start", "第一段。")
        app.reply.onTtsState("sentence_end", "第一段。")
        // 第二段只拿到模板(清洗后不可上屏)→ 跳过、不占段号
        app.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\")")
        // 结算点才拿到第二段的真文本 → 只补这一段
        app.reply.onTtsState("stop", "第二段在结算点才到。")

        assertEquals("缺少的那一段被补成**新段**(追加)", 2, app.bubbles.size)
        assertEquals(listOf("第一段。", "第二段在结算点才到。"), app.bubbleTexts())
        val supplement = app.deliveries.last()
        assertEquals("补上的那一段是新段", true, supplement.isNewSegment)
        assertEquals("段号 2(被跳过的模板句不占段号)", 2, supplement.segmentOrdinal)
        assertEquals("补上时绝不累计第一段(不出现 AB)", "第二段在结算点才到。", supplement.text)
    }

    // ---- ⑤ 新一轮/打断不误删已完成的历史 ----

    @Test
    fun new_turn_keeps_finished_bubbles_untouched() {
        val app = AppSegmentBubbles()
        app.driveSegments("A", "B", "C")
        val finished = app.bubbles.map { it.first }
        val snapshot = ConversationStore.messages.value.filter { it.id in finished }
        assertEquals(3, snapshot.size)

        // 打断 / 新一轮:装配器清零、App 侧重建本轮记账(旧轮的气泡**不删**)
        app.reply.onTurnStart()
        val next = AppSegmentBubbles()
        next.driveSegments("D")

        val after = ConversationStore.messages.value
        snapshot.forEach { old ->
            assertEquals(
                "已完成的历史气泡一条都不能变(文本/label/flag)",
                old,
                after.first { it.id == old.id },
            )
        }
        assertEquals("新一轮只有它自己的段", listOf("D"), next.bubbleTexts())
    }
}
