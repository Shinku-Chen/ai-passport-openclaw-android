package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮正文取哪一路 / 什么时候结算」的 JVM 单测(纯逻辑,无网络/无 Android)。
 *
 * 被测的 [XiaozhiReplyText] 就是 `XiaozhiSession` 里 `llm`/`tts` 两条下行的装配点:会话层只把
 * **`text` 字段**喂进来(`emotion` 一律不喂),`emit` 收到的正文字符串就是流水线 `sendText('A', …)`
 * 上屏到设备屏的正文。用例对应真机问题的验收点:
 *  1. `llm` 是表情时,上屏的是 `tts` 两句拼接(不是表情);
 *  2. 整轮没有 tts 文本 → 用 `llm.text` 兜底;
 *  3. 只有 emotion、没有任何文本 → 交出空串(调用方给可读原因),**绝不**上屏表情;
 *  4. `sentence_start` 与 `sentence_end` 都带同一句文本时只算一次;
 *  5. **真机回归(2026-10-05)**:tts 句级文本里混着 `% get_weather…` 与 emoji 时,
 *     只要有清洗后含文字的 tts 文本就**必须**上屏,不许退化成「没有可上屏正文」;
 *  6. **真机回归(2026-10-06,本轮主问题)**:「从小智获取的文本不是完整的」——
 *     `tts.stop` 是**唯一权威**结算点:工具调用静默期(tts 已有文本但清洗后不可上屏)在 `stop` 上
 *     **挂起不结算**;同一轮后续分段/迟到句级文本到达时必须把**完整正文**交出去(第二轮补正),
 *     绝不让「先上屏的兜底正文」把更完整的正文挡在门外。
 */
class XiaozhiReplyTextTest {

    /** 真机报文里的表情(UTF-16 下是 2 个 char,断言长度时要用它而不是手写的数字)。 */
    private val EMOJI = "😊"

    /** 一次结算的记录:正文、说明、触发者、是否真的产生了新正文、第几次交付、触发者描述。 */
    private data class Settle(
        val body: String,
        val detail: String,
        val trigger: XiaozhiReplyTrigger,
        val changed: Boolean,
        val deliveryIndex: Int,
        val triggerLabel: String,
    )

    /** 记录 [XiaozhiReplyText.emit] 交出的每一次结算(含「未变化」的诊断)。 */
    private class Recorder {
        val settles = ArrayList<Settle>()
        val reply = XiaozhiReplyText(emit = { outcome ->
            settles += Settle(
                outcome.body,
                outcome.detail,
                outcome.trigger,
                outcome.changed,
                outcome.deliveryIndex,
                outcome.triggerLabel,
            )
        })

        /** 真正上屏的正文(按顺序;`changed = false` 的诊断不进这里)。 */
        fun bodies(): List<String> = settles.filter { it.changed }.map { it.body }

        /** 最后一次结算的说明。 */
        fun lastDetail(): String = settles.last().detail

        /** 最后一次结算的触发者。 */
        fun lastTrigger(): XiaozhiReplyTrigger = settles.last().trigger

        /** 最后一次**真正上屏**的正文。 */
        fun lastBody(): String = bodies().last()
    }

    /** 验收点 1:llm 的正文是表情(真机形态)→ 以 tts 句级拼接为准,表情不上屏。 */
    @Test
    fun llm_emoji_is_replaced_by_tts_sentence_concat() {
        val r = Recorder()
        r.reply.onTurnStart()
        // 真机报文形态:llm 带 emotion(表情),text 也是那个表情 —— 会话层只把 text 喂进来。
        r.reply.onLlm("😊")
        // tts 句级文本 = 用户实际听到的内容(这里模拟 sentence_start/sentence_end 各带一次首句)。
        r.reply.onTtsState("sentence_start", "气温大概十八到二十三度，")
        assertEquals(
            "渐进交付(作者定稿:第一句就好):首句一清洗出来就交付一次,直通侧据此开播",
            listOf("气温大概十八到二十三度，"),
            r.bodies(),
        )
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度，")   // 同一句:不重复交付
        assertEquals(listOf("气温大概十八到二十三度，"), r.bodies())
        r.reply.onTtsState("sentence_end", "出门记得带把伞喔。")          // 第二句 → 补正
        assertEquals(
            listOf("气温大概十八到二十三度，", "气温大概十八到二十三度，出门记得带把伞喔。"),
            r.bodies(),
        )

        r.reply.onTtsState("stop", "")   // 最终结算点:与上次相同 → 不再交付
        assertEquals(
            listOf("气温大概十八到二十三度，", "气温大概十八到二十三度，出门记得带把伞喔。"),
            r.bodies(),
        )
        assertEquals(XiaozhiReplyTrigger.STOP, r.lastTrigger())
        assertFalse("stop 上没有新正文,只能记诊断", r.settles.last().changed)
        assertFalse("表情绝不能进正文", r.bodies().any { it.contains("😊") })
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
        assertEquals(listOf("今天晴，20 度。"), r.bodies())
    }

    /** 纯 llm 服务端(没有任何 tts 报文)时,也要能兜底交出正文 —— 但只在长窗口后,不抢跑。 */
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
        assertEquals(listOf("只有 llm 的正文"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.IDLE_LLM_FALLBACK, r.lastTrigger())
        assertTrue("结算说明里要看出取自 llm 兜底", r.lastDetail().contains("llm.text"))
    }

    /**
     * 只要来了一条 tts 报文(哪怕不带文本),「纯 llm 兜底」的短窗口就作废,改走「**等 `stop`**」:
     * 此时空闲窗口只剩一条很长的**强制兜底**(收过 tts 报文的服务端几乎总会发 `stop`)。
     */
    @Test
    fun any_tts_message_switches_to_the_forced_window_and_never_uses_llm_early() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("start", "")
        assertEquals(
            "收到过 tts 报文:空闲窗口退化为强制兜底(远长于旧实现的 2s,不得把真答案抢掉)",
            XiaozhiReplyText.FORCED_IDLE_GRACE_MS,
            r.reply.pendingIdleGraceMs(),
        )
        r.reply.onIdle()   // 强制兜底到点:只能交「没有可上屏正文」,**绝不能**把表情当正文交出
        assertEquals(listOf(""), r.bodies())
        assertFalse("表情绝不能进正文", r.bodies().any { it.contains("😊") })
        assertTrue("原因里要写清哪一层为空", r.lastDetail().contains("llm.text"))

        // 新一轮:真的正文到达后照常上屏
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "真正的回复。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("", "真正的回复。"), r.bodies())
    }

    /** 验收点 3:只有 emotion、没有任何文本 → 交空串(调用方可读原因),不空等、不上屏表情。 */
    @Test
    fun emotion_only_turn_emits_empty_body_not_emoji() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("")            // llm 报文只有 emotion,没有 text
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf(""), r.bodies())
        assertFalse(r.bodies().any { it.contains("😊") })
        assertTrue(
            "原因必须写清哪一层为空(真机排查要看这句)",
            r.lastDetail().contains("tts 句级文本 无") && r.lastDetail().contains("llm.text 无"),
        )
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
        assertEquals(
            "每次交付都是「当前累积的完整拼接」;同一句在被推两次时只算一次",
            listOf("第一句。", "第一句。第二句。"),
            r.bodies(),
        )
        assertEquals("最终正文里同一句不会拼两遍", "第一句。第二句。", r.lastBody())
    }

    /** 句子在增长(start 给部分文本、end 给完整文本)时取更完整的那份,而不是两边都拼上。 */
    @Test
    fun growing_sentence_text_replaces_instead_of_duplicating() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "气温大概")
        // 渐进交付:部分句也先上屏一次(作者允许「屏幕先短后长」),但**绝不能**把同一句拼两遍
        assertEquals(listOf("气温大概"), r.bodies())
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度。")
        assertEquals(
            "句子增长时取更完整的那份(补正替换,不是追加)",
            listOf("气温大概", "气温大概十八到二十三度。"),
            r.bodies(),
        )
        r.reply.onTtsState("stop", "")
        assertEquals("气温大概十八到二十三度。", r.lastBody())
        assertFalse(
            "同一句不能被拼成两遍",
            r.lastBody().contains("气温大概气温大概"),
        )
    }

    /** 反过来(end 比 start 短 / 少一个标点)也不能把同一句拼两遍。 */
    @Test
    fun shorter_sentence_end_text_does_not_duplicate() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "气温大概十八到二十三度。")
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("气温大概十八到二十三度。"), r.bodies())
    }

    /**
     * 验收点 A1:句级文本**按到达顺序累积**;空句、纯空白句与**相邻重复**句丢掉,
     * 但**不能连带丢掉正常句子**(去重只针对相邻的同句子/前后缀关系)。
     */
    @Test
    fun empty_and_duplicate_sentences_are_dropped_without_losing_normal_ones() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "")
        r.reply.onTtsState("sentence_start", "   ")
        r.reply.onTtsState("sentence_start", "第一句。")
        r.reply.onTtsState("sentence_end", "第一句。")        // 同一句:只算一次
        r.reply.onTtsState("start", "第一句。")               // 又一次重复(不是句子内容)
        r.reply.onTtsState("sentence_start", "第二句，")      // 正常句子:必须留下
        r.reply.onTtsState("sentence_end", "第二句，带更多内容。") // 句子在增长:取更完整的
        r.reply.onTtsState("sentence_end", "第三句。")
        r.reply.onTtsState("stop", "")
        assertEquals(
            "渐进交付每次交出的都是当前累积的完整拼接(空句/重复句不占位)",
            listOf(
                "第一句。",
                "第一句。第二句，",
                "第一句。第二句，带更多内容。",
                "第一句。第二句，带更多内容。第三句。",
            ),
            r.bodies(),
        )
        assertEquals("第一句。第二句，带更多内容。第三句。", r.lastBody())
    }

    /** 没有 `stop` 的服务端:收到过 tts 报文时只挂**强制兜底**窗口,到点才结算(而不是半路 2s)。 */
    @Test
    fun sentences_are_flushed_only_by_the_forced_window_when_stop_never_arrives() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "只有这一句。")
        assertEquals(
            XiaozhiReplyText.FORCED_IDLE_GRACE_MS,
            r.reply.pendingIdleGraceMs(),
        )
        r.reply.onIdle()
        assertEquals(listOf("只有这一句。"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.FORCED, r.lastTrigger())
    }

    /** 还没有任何文本时没有可结算候选(不挂定时器、不交出任何东西)。 */
    @Test
    fun nothing_pending_before_any_text() {
        val r = Recorder()
        r.reply.onTurnStart()
        assertNull(r.reply.pendingIdleGraceMs())
        r.reply.onIdle()
        r.reply.onLlm("")
        assertTrue(r.settles.isEmpty())
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
        assertEquals(
            "新一轮从零开始(上一轮那条不会再被拼进来)",
            listOf("上一句。", "这一句。"),
            r.bodies(),
        )
        assertEquals("这一句。", r.lastBody())
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
        assertEquals(listOf("北京今天晴，二十度。"), r.bodies())
    }

    // ---- 真机回归(2026-10-05):有可上屏的 tts 句级文本就必须上屏,绝不能退化成「没有可上屏正文」 ----

    /**
     * 真机那两个轮次的**实际字形串**:llm.text = 工具模板 + emoji,tts 句级文本才是真答案。
     *
     * 验收:上屏的是「明天上海是小雨喔，白天23度」(用户听到的那句),不含 `% get_weather` 也不含 emoji,
     * 更**不能**是空串(真机 bug:同轮里服务端明明给了 tts 句级文本,却提示「没有返回可上屏的正文」)。
     */
    @Test
    fun real_device_tool_call_turn_keeps_the_tts_prose() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("% get_weather(location=\"上海\", date=\"明天\")😊")
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\", date=\"明天\")")
        r.reply.onTtsState("sentence_end", "% get_weather(location=\"上海\", date=\"明天\")")
        r.reply.onTtsState("sentence_start", "明天上海是小雨喔，白天23度😊")
        r.reply.onTtsState("sentence_end", "明天上海是小雨喔，白天23度😊")
        r.reply.onTtsState("stop", "")

        assertEquals(
            "tts 句级文本就是用户听到的那句:必须原样(模板行已剔掉)上屏,而且不能退化成空串",
            listOf("明天上海是小雨喔，白天23度😊"),
            r.bodies(),
        )
        assertFalse("工具模板不能上屏", r.bodies().any { it.contains("get_weather") })
        assertFalse("也绝不是「没有可上屏正文」那条空串", r.bodies().any { it.isEmpty() })
    }

    /**
     * **本轮主问题的真机回归(2026-10-06)**：「从小智获取的文本不是完整的」。
     *
     * 真机交错流(与作者给的证据同形):
     *  1. `llm.text` 先到,里面是**中间态**的一句话 + `% get_weather…` 模板残留(清洗后仍可上屏);
     *  2. 工具调用轮次先推一条**只含模板**的 tts 句级文本,然后 `stop`(第一段结束);
     *  3. 工具结果回来后,真答案作为**第二段**的句级文本到达,再来一个 `stop`。
     *
     * 旧实现在第 2 步的 `stop` 上就把 `llm.text` 兜底交了出去(于是设备屏是那截 102 字节的中间态文本),
     * 第 3 步的完整句级文本因为「本轮已结算」被丢掉。现在:第 2 步**挂起不结算**,
     * 第 3 步交出**完整句级拼接**——一个字不少。
     */
    @Test
    fun real_device_multisegment_tool_call_turn_ends_with_the_complete_sentence() {
        val r = Recorder()
        r.reply.onTurnStart()
        // 1) 中间态 llm.text(真机取证形状:模板 + 截短的句子;清洗后仍含文字 → 是个「可上屏候选」)
        r.reply.onLlm("% get_weather(location=\"上海\", date=\"明天\")明天上海是小雨喔，白天23度")
        // 2) 第一段:只有模板的句级文本 → stop
        r.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\", date=\"明天\"):")
        r.reply.onTtsState("sentence_end", "% get_weather(location=\"上海\", date=\"明天\"):")
        r.reply.onTtsState("stop", "")
        assertTrue(
            "工具调用静默期(tts 有文本但清洗后不可上屏)不得在 stop 上结算",
            r.bodies().isEmpty(),
        )
        assertFalse("诊断行必须标明这次 stop 没有产生新正文", r.settles.last().changed)
        assertEquals(XiaozhiReplyTrigger.STOP, r.settles.last().trigger)

        // 3) 第二段:真答案(完整长句)→ stop
        r.reply.onTtsState("sentence_start", "明天上海是小雨喔，白天都湿湿的，晚上才转阴，算不上好天气啦。")
        r.reply.onTtsState("stop", "")

        val full = "明天上海是小雨喔，白天都湿湿的，晚上才转阴，算不上好天气啦。"
        assertEquals("最终上屏 = 完整句级拼接(清洗后)", listOf(full), r.bodies())
        assertEquals("结算由 stop 触发", XiaozhiReplyTrigger.STOP, r.lastTrigger())
        assertFalse("模板绝不能残留", r.lastBody().contains("get_weather"))
        assertFalse("不能是那条更短的中间态兜底文本", r.lastBody().contains("白天23度"))
        assertTrue("整段一句都不能少", r.lastBody().contains("晚上才转阴") && r.lastBody().contains("好天气"))
    }

    /**
     * **本轮主验收(渐进交付「第一句就好」)**:真机那种逐句交错流 —— 噪声句**绝不**提前交付;
     * 首句一到就交付(直通侧据此在首句上屏时即可开播);第二/三句各补正一次;`stop` 与最后一次
     * 相同则**不再**交付。
     *
     * 硬约束(上一轮「正文不完整」的根因):只拿到模板/emoji/空文本时**一个字节都不提前交付**,
     * 而且中途交付**从不回退 `llm.text`** —— 所以每一次交付的正文都是清洗后非空可读、不含模板的。
     */
    @Test
    fun progressive_delivery_first_sentence_then_corrections_and_stop_final() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("start", "")
        // 噪声句(真机形状的工具模板):清洗后不可读 → **绝不允许**产生一次交付
        r.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\", date=\"明天\")")
        r.reply.onTtsState("sentence_end", "% get_weather(location=\"上海\", date=\"明天\")")
        assertTrue("只拿到模板时不许提前交付", r.bodies().isEmpty())
        assertTrue("噪声句连「交付」都不算(changed 全为 false)", r.settles.none { it.changed })

        // 首句真文本:必须交付,且是**第一次**交付(触发者=首句)
        r.reply.onTtsState("sentence_start", "明天上海是小雨喔，")
        assertEquals("首句一清洗出来就交付(不等 stop)", listOf("明天上海是小雨喔，"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.PROGRESS_FIRST, r.settles.last().trigger)
        assertEquals("首句", r.settles.last().triggerLabel)
        assertEquals("首句就是本轮第 1 次交付", 1, r.settles.last().deliveryIndex)

        // 第二/三句:各交付一次**补正**(整段拼接,不是碎气泡)
        r.reply.onTtsState("sentence_end", "白天都湿湿的，")
        r.reply.onTtsState("sentence_start", "晚上才转阴。")
        assertEquals(
            listOf(
                "明天上海是小雨喔，",
                "明天上海是小雨喔，白天都湿湿的，",
                "明天上海是小雨喔，白天都湿湿的，晚上才转阴。",
            ),
            r.bodies(),
        )
        assertEquals("补正(第2句)", r.settles[r.settles.indexOfLast { it.changed } - 1].triggerLabel)
        assertEquals("补正(第3句)", r.settles.last().triggerLabel)
        assertEquals("补正是第 2、3 次交付", listOf(2, 3), r.settles.filter { it.changed }.drop(1).map { it.deliveryIndex })

        // stop 是最终结算点:与最后一次相同 → 不再交付
        val beforeStop = r.bodies().size
        r.reply.onTtsState("stop", "")
        assertEquals("stop 上与上次相同:不重复交付", beforeStop, r.bodies().size)
        assertEquals("stop 最终结算", r.settles.last().triggerLabel)
        assertFalse("stop 的诊断行不得声称产生了新正文", r.settles.last().changed)

        // 每一次交付的正文都是清洗后非空可读、不含模板的
        r.bodies().forEach { body ->
            assertTrue("交付的正文必须有可读文字:«$body»", XiaozhiReplySanitizer.isDisplayable(body))
            assertFalse("模板绝不能上屏:«$body»", body.contains("get_weather"))
        }
    }

    /**
     * 验收点 A3:兜底正文**先**上屏(纯 llm 兜底窗口到点,当时还没有 tts 句级文本),
     * 随后 tts 句级文本到达 —— 必须把它**当作新正文再交一次**(调用方替换屏幕上那条),
     * 同一轮内不得出现「兜底先上屏、完整正文再也上不了」。
     */
    @Test
    fun late_tts_text_after_the_llm_fallback_is_delivered_as_a_new_body() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("中间态的兜底正文。")
        // 没有 tts 报文 → 纯 llm 兜底窗口到点,先把兜底交出去(设备不能空着)
        r.reply.onIdle()
        assertEquals(listOf("中间态的兜底正文。"), r.bodies())

        // 之后 tts 句级文本才到(真答案更完整)
        r.reply.onTtsState("sentence_start", "真正的完整回答，")
        r.reply.onTtsState("sentence_end", "真正的完整回答，包含更多细节。")
        r.reply.onTtsState("stop", "")
        assertEquals(
            "tts 句级文本到达后必须**替换**已上屏的兜底正文(后续交付都是补正)",
            listOf(
                "中间态的兜底正文。",
                "真正的完整回答，",
                "真正的完整回答，包含更多细节。",
            ),
            r.bodies(),
        )
        assertTrue("首条之后都走补正通道", r.settles.filter { it.changed }.drop(1).all { it.triggerLabel.startsWith("补正") })
        assertEquals(XiaozhiReplyTrigger.STOP, r.lastTrigger())
    }

    /**
     * 验收点 A2:收到过 tts 报文后,句与句之间的**长间隔不会提前结算**(旧实现在最后一句后 2s 就交,
     * 正好把工具调用/长生成的停顿当成一轮结束 —— 真机「文本不完整」的另一条成因)。
     */
    @Test
    fun long_gap_between_sentences_never_settles_before_stop() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "第一句，")
        val grace = r.reply.pendingIdleGraceMs()
        assertEquals("有 tts 报文时只有强制兜底窗口", XiaozhiReplyText.FORCED_IDLE_GRACE_MS, grace)
        assertTrue(
            "强制兜底必须明显长于正常停顿(旧实现的 2s 正是抢跑的原因)",
            grace!! >= 15_000L,
        )
        // 间隔期间即便窗口判定被触发也只会重算(真答案还没来)
        r.reply.onTtsState("sentence_start", "第二句，")
        r.reply.onTtsState("sentence_end", "第三句。")
        r.reply.onTtsState("stop", "")
        assertEquals("第一句，第二句，第三句。", r.lastBody())
        assertTrue(
            "stop 上没有新正文:最终交付次数 = 逐句交付次数(不是又交一次)",
            r.settles.last().changed.not(),
        )
    }

    /**
     * 验收点 A4:同一轮**多段** `tts[start…stop]` —— 第二段的文本也要处理(不能只认第一段),
     * 第二段到达后交出的正文是**整段拼接**(第一段 + 第二段)。
     */
    @Test
    fun second_segment_text_is_appended_and_delivered_again() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "第一段的话。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("第一段的话。"), r.bodies())

        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "第二段的话。")
        r.reply.onTtsState("stop", "")
        assertEquals(
            "第二段文本不能丢:第二段 stop 上交出整段拼接(调用方替换屏幕上的那条)",
            listOf("第一段的话。", "第一段的话。第二段的话。"),
            r.bodies(),
        )
    }

    /** 同一段正文被第二个 `stop` / 强制窗口再结算一次时:只记日志,不重复上屏。 */
    @Test
    fun identical_body_is_not_emitted_twice() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "只有这一句。")
        r.reply.onTtsState("stop", "")
        r.reply.onTtsState("stop", "")   // 服务端重复发 stop(或第二段没有新文本)
        r.reply.onIdle()
        assertEquals(listOf("只有这一句。"), r.bodies())
        assertEquals(
            "重复结算仍然要留诊断行(真机日志靠它确认「结算了几次、为什么没变」)",
            XiaozhiReplyTrigger.FORCED,
            r.lastTrigger(),
        )
        assertFalse("重复结算不得产生新正文", r.settles.last().changed)
    }

    /**
     * 验收点 A5/A6:**工具调用静默期**在 `stop` 上挂起(不结算、不上屏),等到**强制兜底窗口**才交出
     * 兜底/可读原因 —— 这样「真答案在下一个分段」时屏幕不会被中间态文本占住。
     */
    @Test
    fun tool_call_silence_holds_at_stop_until_the_forced_window() {
        val r = Recorder()
        r.reply.onTurnStart()
        // 真机形状:llm.text 是中间态的一句话(带模板残留,清洗后仍有文字 → 是个可上屏候选)
        r.reply.onLlm("% get_weather(location=\"上海\", date=\"明天\")明天上海是小雨喔，白天23度")
        r.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\", date=\"明天\")")
        r.reply.onTtsState("stop", "")
        assertTrue("平静期:一条正文都不交", r.bodies().isEmpty())
        assertFalse("且只记诊断行(changed=false)", r.settles.last().changed)
        assertTrue("诊断行要看得出是「工具调用静默期挂起」", r.lastDetail().contains("静默期"))

        // 一直等不到后续分段 → 强制兜底到点:才交兜底(llm.text 清洗后的那句)
        r.reply.onIdle()
        assertEquals(listOf("明天上海是小雨喔，白天23度"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.FORCED, r.lastTrigger())
    }

    /** tts 那一路不可上屏(只 emoji/模板)时,`llm.text` 兜底是**强制兜底窗口**才交 —— 不是 stop 上抢跑。 */
    @Test
    fun llm_text_fallback_waits_for_the_forced_window_when_tts_is_noise_only() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("真正的回复在 llm 里。")
        r.reply.onTtsState("sentence_end", "% get_weather(city=\"上海\")")
        r.reply.onTtsState("sentence_end", "😊")
        r.reply.onTtsState("stop", "")
        assertTrue("tts 侧只有模板/表情:stop 上挂起,不抢注中间态文本", r.bodies().isEmpty())

        r.reply.onIdle()
        assertEquals(
            "强制兜底到点才用 llm.text 兜底(比「没有可上屏正文」正确得多)",
            listOf("真正的回复在 llm 里。"),
            r.bodies(),
        )
    }

    /** 两路都只有 emoji/模板时:强制兜底窗口到点交空串(带说明),**绝不**上屏 emoji。 */
    @Test
    fun emoji_only_on_both_layers_emits_empty_with_detail() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("sentence_end", "😊😊")
        r.reply.onTtsState("stop", "")
        assertTrue("工具调用静默期:stop 上不交空串(那是要等后续分段的)", r.bodies().isEmpty())

        r.reply.onIdle()
        assertEquals(listOf(""), r.bodies())
        assertTrue(
            "说明里两层都要写清楚原始/清洗后字数(真机排查要看这句)",
            r.lastDetail().contains("tts 句级文本 1 条") &&
                r.lastDetail().contains("llm.text(原始 ${EMOJI.length} 字"),
        )
    }

    /** 清洗前后字数必须进结算说明(真机排查「正文变短」的第一个依据)。 */
    @Test
    fun settle_detail_carries_before_and_after_cleaning_lengths() {
        val r = Recorder()
        r.reply.onTurnStart()
        val raw = "% get_weather(city=\"上海\")明天小雨。"
        r.reply.onTtsState("sentence_start", raw)
        r.reply.onTtsState("stop", "")
        val detail = r.lastDetail()
        assertTrue("要看到 tts 的原始字数", detail.contains("原始 ${raw.length} 字"))
        assertTrue("也要看到清洗后的字数", detail.contains("→清洗后 5 字"))
        assertEquals("明天小雨。", r.lastBody())
    }
}
