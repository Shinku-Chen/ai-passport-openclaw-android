package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮正文取哪一路」的 JVM 单测(纯逻辑,无网络/无 Android)。
 *
 * 被测的 [XiaozhiReplyText] 就是 `XiaozhiSession` 里 `llm`/`tts` 两条下行的装配点:会话层只把
 * **`text` 字段**喂进来(`emotion` 一律不喂),`emit` 收到的字符串就是流水线 `sendText('A', …)`
 * 上屏到设备屏的正文。四个用例对应真机问题的验收点:
 *  1. `llm` 是表情时,上屏的是 `tts` 两句拼接(不是表情);
 *  2. 整轮没有 tts 文本 → 用 `llm.text` 兜底;
 *  3. 只有 emotion、没有任何文本 → 交出空串(调用方给可读原因),**绝不**上屏表情;
 *  4. `sentence_start` 与 `sentence_end` 都带同一句文本时只算一次。
 */
class XiaozhiReplyTextTest {

    /** 记录 [XiaozhiReplyText.emit] 交出的每一条正文。 */
    private class Recorder {
        val emitted = ArrayList<String>()
        val reply = XiaozhiReplyText(emit = { emitted += it })
    }

    /** 验收点 1:llm 的正文是表情(真机形态)→ 以 tts 两句拼接为准,表情不上屏。 */
    @Test
    fun llm_emoji_is_replaced_by_tts_sentence_concat() {
        val r = Recorder()
        r.reply.onTurnStart()
        // 真机报文形态:llm 带 emotion(表情),text 也是那个表情 —— 会话层只把 text 喂进来。
        r.reply.onLlm("😊")
        // tts 句级文本 = 用户实际听到的内容(这里模拟 sentence_start/sentence_end 各带一次首句)。
        r.reply.onTtsState("sentence_start", "气温大概十八到二十三度，")
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度，")
        r.reply.onTtsState("sentence_end", "出门记得带把伞喔。")
        assertTrue("整段在 stop 之前不上屏(方案 A:一条回复一个气泡)", r.emitted.isEmpty())

        r.reply.onTtsState("stop", "")
        assertEquals(listOf("气温大概十八到二十三度，出门记得带把伞喔。"), r.emitted)
        assertFalse("表情绝不能进正文", r.emitted.any { it.contains("😊") })
    }

    /** 验收点 2:整轮没有任何 tts 文本 → 用 `llm.text` 兜底(stop 到来即结算,不拖)。 */
    @Test
    fun llm_text_is_the_fallback_when_tts_has_no_text() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("今天晴，20 度。")
        // 服务端照常推 tts 生命周期,但一句文本也没有。
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("今天晴，20 度。"), r.emitted)
    }

    /** 服务端完全不推 tts 报文(纯 llm)时,也要能兜底交出正文 —— 但只在长窗口后,不抢跑。 */
    @Test
    fun llm_only_server_emits_after_the_long_grace() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("只有 llm 的正文")
        assertEquals(
            "纯 llm 服务端:窗口要足够长(表情 llm 与 tts 首条之间可能隔几秒)",
            XiaozhiReplyText.LLM_ONLY_FALLBACK_GRACE_MS,
            r.reply.pendingIdleGraceMs(),
        )
        r.reply.onIdle()
        assertEquals(listOf("只有 llm 的正文"), r.emitted)
    }

    /** 只要来了一条 tts 报文(哪怕不带文本),「纯 llm 兜底」的长窗口就作废,改走正常路径。 */
    @Test
    fun any_tts_message_cancels_the_llm_only_grace() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("start", "")
        assertNull("tts 已经开始,正文等它的句级文本/stop", r.reply.pendingIdleGraceMs())
        r.reply.onIdle()   // 即使定时器被误触发,也不许把表情当正文交出
        assertTrue(r.emitted.isEmpty())
        r.reply.onTtsState("sentence_end", "真正的回复。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("真正的回复。"), r.emitted)
    }

    /** 验收点 3:只有 emotion、没有任何文本 → 交空串(调用方可读原因),不空等、不上屏表情。 */
    @Test
    fun emotion_only_turn_emits_empty_body_not_emoji() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("")            // llm 报文只有 emotion,没有 text
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf(""), r.emitted)
        assertFalse(r.emitted.any { it.contains("😊") })
    }

    /** 验收点 4:`sentence_start` 与 `sentence_end` 带同一句 → 只算一次;下一句照常追加。 */
    @Test
    fun same_sentence_on_start_and_end_counts_once() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "第一句。")
        r.reply.onTtsState("sentence_end", "第一句。")
        r.reply.onTtsState("sentence_start", "第二句。")
        r.reply.onTtsState("sentence_end", "第二句。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("第一句。第二句。"), r.emitted)
    }

    /** 句子在增长(start 给部分文本、end 给完整文本)时取更完整的那份,而不是两边都拼上。 */
    @Test
    fun growing_sentence_text_replaces_instead_of_duplicating() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "气温大概")
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("气温大概十八到二十三度。"), r.emitted)
    }

    /** 反过来(end 比 start 短 / 少一个标点)也不能把同一句拼两遍。 */
    @Test
    fun shorter_sentence_end_text_does_not_duplicate() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "气温大概十八到二十三度。")
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("气温大概十八到二十三度。"), r.emitted)
    }

    /** 没有 `stop` 的服务端:最后一句之后无新增即结算(有 tts 文本时才挂这个窗口)。 */
    @Test
    fun sentences_are_flushed_when_stop_never_arrives() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "只有这一句。")
        assertEquals(
            XiaozhiReplyText.SENTENCE_IDLE_GRACE_MS,
            r.reply.pendingIdleGraceMs(),
        )
        r.reply.onIdle()
        assertEquals(listOf("只有这一句。"), r.emitted)
    }

    /** 还没有任何文本时没有可结算候选(不挂定时器、不交出任何东西)。 */
    @Test
    fun nothing_pending_before_any_text() {
        val r = Recorder()
        r.reply.onTurnStart()
        assertNull(r.reply.pendingIdleGraceMs())
        r.reply.onIdle()
        r.reply.onLlm("")
        assertTrue(r.emitted.isEmpty())
        assertNull(r.reply.pendingIdleGraceMs())
    }

    /** 每轮只结算一次:结算后迟到的文本不再追加/再次上屏(一条回复一个气泡)。 */
    @Test
    fun turn_emits_exactly_once_and_ignores_late_text() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("兜底候选")
        r.reply.onTtsState("sentence_end", "第一句。")
        r.reply.onTtsState("stop", "")
        r.reply.onTtsState("sentence_end", "迟到的第二句。")
        r.reply.onIdle()
        assertEquals(listOf("第一句。"), r.emitted)
        assertNull(r.reply.pendingIdleGraceMs())
    }

    /** 新一轮从零开始:上一轮的记账不会带进去。 */
    @Test
    fun turn_start_resets_previous_turn() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "上一句。")
        r.reply.onTurnStart()                 // 新一轮:上一轮的句级文本作废
        r.reply.onTtsState("sentence_end", "这一句。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("这一句。"), r.emitted)
    }

    /**
     * 验收点:上屏正文要剔掉服务端工具模板残留(`% get_weather…`、`<tool_call>…</tool_call>`、占位)。
     *
     * 真机形态:模板可能**跨句级事件**到达(`sentence_start` 给开标签、`sentence_end` 给闭标签)——
     * 所以清洗必须发生在「整段拼接完成之后」,按整段文本处理([XiaozhiReplySanitizer])。
     */
    @Test
    fun server_tool_template_residue_is_stripped_before_emitting() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("sentence_start", "<tool_call>")
        r.reply.onTtsState("sentence_end", "{\"name\":\"get_weather\"}</tool_call>")
        r.reply.onTtsState("sentence_start", "北京今天晴，二十度。")
        r.reply.onTtsState("sentence_end", "% get_weather(city=\"北京\")")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("北京今天晴，二十度。"), r.emitted)
    }

    /** 整段只剩模板/占位 → 交空串(走「本轮没有可上屏正文」的可读原因路径,不上屏噪声)。 */
    @Test
    fun noise_only_turn_emits_empty_body() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "<tool_call>{\"name\":\"get_weather\"}</tool_call>")
        r.reply.onTtsState("sentence_end", "% get_weather(city=\"北京\")")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf(""), r.emitted)
    }
}
