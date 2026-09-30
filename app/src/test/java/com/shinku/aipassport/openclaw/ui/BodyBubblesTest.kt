package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.gateway.RawEntry
import com.shinku.aipassport.openclaw.gateway.RawKind
import com.shinku.aipassport.openclaw.gateway.RawLabel
import com.shinku.aipassport.openclaw.gateway.SUSPECTED_STATUS_TALK_FLAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **同一轮只有一个正常正文气泡**的纯逻辑 + 组合逻辑单测(文字输入路径的「正文补正就地替换」)。
 *
 * 背景(同事上次报告的遗留项):语音路径补正用 `replaceById` 就地替换,文字输入路径却是追加一个
 * `[来自历史]` 气泡 → 一轮里出现「`[流式]` + `[来自历史]`」两个正文气泡。
 *
 * 核心回归:**补正后同一轮的正常气泡数量不因补正而增加**、补正前写入的消息条数不增加。
 * 组合逻辑直接跑真正的 [ConversationStore](它不依赖 Context:`persist` 在未 `init` 时是空操作),
 * 与 `ChatFragment.send()` 的写法一致(占位气泡即正文气泡槽位 + [writeReplyDisplay] + [writeBodyCorrection])。
 *
 * 注意:[ConversationStore] 是进程内单例,因此断言只看**本用例新增的那些 id**(快照 `drop(before)`),
 * 不依赖全局条数,测试之间互不影响。
 */
class BodyBubblesTest {

    private companion object {
        const val STREAMED = "已回复完毕，当前无进行中的 exec 会话或子代理。"
        const val CORRECTED = "北京明天晴，20℃/10℃。"
    }

    private fun rawAssistant(text: String) =
        RawEntry(RawKind.ASSISTANT, RawLabel.ASSISTANT, text)

    private fun allSince(before: Int): List<ConversationStore.Message> =
        ConversationStore.messages.value.drop(before)

    private fun normalBubblesSince(before: Int): List<ConversationStore.Message> =
        allSince(before).filter { it.label == null }

    private fun messageOf(since: List<ConversationStore.Message>, id: Long) =
        since.first { it.id == id }

    // ---- 纯判定:补正该「就地替换」还是「新增」 ----

    @Test
    fun correction_replaces_in_place_when_body_bubble_exists() {
        val bodies = BodyBubbles()
        bodies.onBodyWritten(11L)
        bodies.onBodyWritten(22L)

        val plan = bodies.onCorrection()

        assertEquals(BodyCorrectionPlan.ReplaceInPlace(11L, listOf(22L)), plan)
        assertTrue("补正已落地,后到的流式正文要降级", bodies.isCorrected)
    }

    @Test
    fun correction_appends_when_no_body_bubble_yet() {
        val bodies = BodyBubbles()

        assertEquals(BodyCorrectionPlan.Append, bodies.onCorrection())
    }

    @Test
    fun body_ids_keep_write_order_and_ignore_duplicate_registration() {
        val bodies = BodyBubbles()
        bodies.onBodyWritten(7L)
        bodies.onBodyWritten(7L)   // 占位气泡被「写入展示项」再登记一次:只能算一条
        bodies.onBodyWritten(8L)

        assertEquals(listOf(7L, 8L), bodies.bodyIds())
        assertFalse(bodies.isCorrected)
    }

    // ---- 组合逻辑(与 ChatFragment 相同的两步):核心回归 ----

    /** 文字输入:流式正文先上屏,补正后到达 → 同一个气泡就地替换,条数不增加。 */
    @Test
    fun typed_chat_correction_replaces_in_place_and_adds_no_bubble() {
        val before = ConversationStore.messages.value.size
        val bodies = BodyBubbles()
        val placeholder = ConversationStore.add("agent", "…", ConversationStore.SOURCE_TEXT)
        bodies.onBodyWritten(placeholder)

        // 首批展示项(与 ChatFragment 同):body 在前、raw 弱化条目在后
        writeReplyDisplay(
            replyDisplayEntries(
                listOf(STREAMED),
                listOf(rawAssistant(STREAMED)),
                showRaw = true,
            ),
            placeholder,
            bodies,
            ConversationStore.SOURCE_TEXT,
        )
        val afterStream = allSince(before)
        assertEquals("补正前:只有一个正常正文气泡", 1, afterStream.count { it.label == null })
        assertEquals(STREAMED, messageOf(afterStream, placeholder).text)
        // 状态话术是网关的行为,App 只加标记:来源标记在前、疑似在后
        assertEquals("[流式]$SUSPECTED_STATUS_TALK_FLAG", messageOf(afterStream, placeholder).flag)

        // 历史补正到达
        writeBodyCorrection(bodies.onCorrection(), CORRECTED, ConversationStore.SOURCE_TEXT)

        val afterCorrection = allSince(before)
        // 核心回归:消息条数不变(就地替换,不是追加一个 [来自历史] 气泡)
        assertEquals("补正不新增任何消息", afterStream.size, afterCorrection.size)
        assertEquals(
            "补正后正常气泡数量不变(仍为 1)",
            1,
            afterCorrection.count { it.label == null },
        )
        val body = messageOf(afterCorrection, placeholder)
        assertEquals("正文就地替换成历史答案", CORRECTED, body.text)
        assertEquals("[来自历史]", body.flag)
        // raw 弱化条目照常保留
        assertTrue(
            "raw 弱化条目仍在",
            afterCorrection.any { it.label == RawLabel.ASSISTANT && it.text == STREAMED },
        )
    }

    /**
     * 文字输入的竞态:补正回调先于 `chatMulti` 返回(占位气泡还在、流式正文尚未写入)
     * → 补正先落地为唯一正常气泡,后到的流式正文降级成弱化小字,**不能**再冒出第二个正常气泡。
     */
    @Test
    fun correction_arriving_before_streamed_body_keeps_single_normal_bubble() {
        val before = ConversationStore.messages.value.size
        val bodies = BodyBubbles()
        val placeholder = ConversationStore.add("agent", "…", ConversationStore.SOURCE_TEXT)
        bodies.onBodyWritten(placeholder)

        // 1) 补正先到
        writeBodyCorrection(bodies.onCorrection(), CORRECTED, ConversationStore.SOURCE_TEXT)
        assertEquals(CORRECTED, messageOf(allSince(before), placeholder).text)
        assertEquals("[来自历史]", messageOf(allSince(before), placeholder).flag)
        assertEquals(1, normalBubblesSince(before).size)

        // 2) 流式正文后到:降级成弱化小字,占位气泡里仍是历史答案
        writeReplyDisplay(
            replyDisplayEntries(
                listOf(STREAMED),
                listOf(rawAssistant(STREAMED)),
                showRaw = true,
            ),
            placeholder,
            bodies,
            ConversationStore.SOURCE_TEXT,
        )

        val since = allSince(before)
        assertEquals("核心回归:仍只有一个正常正文气泡", 1, normalBubblesSince(before).size)
        assertEquals("补正正文留在原气泡里(未被流式正文覆盖)", CORRECTED, messageOf(since, placeholder).text)
        assertEquals("[来自历史]", messageOf(since, placeholder).flag)
        val demoted = since.firstOrNull { it.text == STREAMED && it.label == RawLabel.ASSISTANT }
        assertNotNull("流式正文降级成弱化小字仍可看到", demoted)
        assertEquals("降级条目保留疑似状态话术标记", SUSPECTED_STATUS_TALK_FLAG, demoted!!.flag)
    }

    /** 两条流式正文(多 assistant 消息)+ 补正:多余的正文气泡降级,正常气泡只剩一条。 */
    @Test
    fun extra_streamed_body_bubbles_are_demoted_on_correction() {
        val before = ConversationStore.messages.value.size
        val bodies = BodyBubbles()
        val placeholder = ConversationStore.add("agent", "…", ConversationStore.SOURCE_TEXT)
        bodies.onBodyWritten(placeholder)

        writeReplyDisplay(
            replyDisplayEntries(listOf(STREAMED, "第二条正文"), emptyList(), showRaw = false),
            placeholder,
            bodies,
            ConversationStore.SOURCE_TEXT,
        )
        assertEquals("补正前有两条正文气泡(流式)", 2, normalBubblesSince(before).size)

        writeBodyCorrection(bodies.onCorrection(), CORRECTED, ConversationStore.SOURCE_TEXT)

        val since = allSince(before)
        assertEquals("补正后只剩一个正常正文气泡", 1, normalBubblesSince(before).size)
        assertEquals(CORRECTED, messageOf(since, placeholder).text)
        val demoted = since.first { it.text == "第二条正文" }
        assertEquals("多余正文气泡降级成弱化小字", RawLabel.ASSISTANT, demoted.label)
    }

    /**
     * 空 body（网关 `EmptyFinal`：`reply.messages` 为空但宽限窗内历史仍可能有答案）：
     * 占位气泡已经被换成了「(网关空回复)」，它**就是**本轮的正文气泡槽位 —— 补正到达时就地替换它，
     * 不会出现「(网关空回复) + [来自历史]」两个正常气泡。
     */
    @Test
    fun empty_streamed_body_with_history_answer_keeps_single_normal_bubble() {
        val before = ConversationStore.messages.value.size
        val bodies = BodyBubbles()
        val placeholder = ConversationStore.add("agent", "…", ConversationStore.SOURCE_TEXT)
        // `ChatFragment.send()` 一上来就登记：占位气泡即本轮正文气泡槽位
        bodies.onBodyWritten(placeholder)
        // 空 body 分支：占位气泡换成「(网关空回复)」
        ConversationStore.replaceById(placeholder, "(网关空回复)")

        writeBodyCorrection(bodies.onCorrection(), CORRECTED, ConversationStore.SOURCE_TEXT)

        val since = allSince(before)
        assertEquals("补正就地替换占位气泡，不新增第二个正常气泡", 1, normalBubblesSince(before).size)
        assertEquals(CORRECTED, messageOf(since, placeholder).text)
        assertEquals("[来自历史]", messageOf(since, placeholder).flag)
    }
}
