package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按段上屏」的 JVM 单测(纯逻辑,无网络/无 Android) —— 见
 * `docs/design/xiaozhi-ai-gateway.md` §4.8 按段播放、§5 时序、§6 上屏策略。
 *
 * 被测的 [XiaozhiReplyText] 就是 `XiaozhiSession` 里 `llm`/`tts` 两条下行的装配点:会话层只把
 * **`text` 字段**喂进来(`emotion` 一律不喂),`emit` 收到的正文字符串就是流水线 `sendText('A', …)`
 * 上屏到设备屏的正文。作者定的语义是**一段一段显示**:每次上屏 = **那一段(那一句)自己的文本**,
 * **不累计**(屏幕上只有 A、B、C 三条,绝不多出 AB、ABC 这些重复气泡)。
 *
 * 用例覆盖本轮验收点:
 *  1. **真机那种 3 段流** → 恰好三条上屏,内容分别是 A/B/C,不存在 AB/ABC,段号 1/2/3;
 *  2. `tts.state=stop` 不产生额外一条(各段都已上屏时不重复上屏);
 *  3. 某段文本缺失时的兜底**只补那一段**(不把已上屏的段累计进来);
 *  4. 首段**立即**上屏(不等 `stop`;直通侧据此在首段上屏时开播);
 *  5. 段与字幕/音频的顺序关系与既有不变量回归:噪声句不占段号、工具调用静默期挂起、
 *     整轮兜底(累计/`llm.text`)、清洗、同一句更新本段、`turn_start` 清零。
 */
class XiaozhiReplyTextTest {

    /** 真机报文里的表情(UTF-16 下是 2 个 char,断言长度时要用它而不是手写的数字)。 */
    private val EMOJI = "😊"

    /** 一次结算的记录:正文、说明、触发者、是否真的产生了新正文、段号、触发者描述、是否新段。 */
    private data class Settle(
        val body: String,
        val detail: String,
        val trigger: XiaozhiReplyTrigger,
        val changed: Boolean,
        val deliveryIndex: Int,
        val triggerLabel: String,
        val isNewSegment: Boolean,
    )

    /** 记录 [XiaozhiReplyText.emit] 交出的每一次结算(含「未变化」的诊断)与每一行段级日志。 */
    private class Recorder {
        val settles = ArrayList<Settle>()
        val logs = ArrayList<String>()
        val reply = XiaozhiReplyText(
            emit = { outcome ->
                settles += Settle(
                    outcome.body,
                    outcome.detail,
                    outcome.trigger,
                    outcome.changed,
                    outcome.deliveryIndex,
                    outcome.triggerLabel,
                    outcome.isNewSegment,
                )
            },
            log = { line -> logs += line },
        )

        /** 真正上屏的正文(按顺序;`changed = false` 的诊断不进这里)。 */
        fun bodies(): List<String> = settles.filter { it.changed }.map { it.body }

        /** 真正上屏那几次的段号。 */
        fun ordinals(): List<Int> = settles.filter { it.changed }.map { it.deliveryIndex }

        /** 最后一次结算的说明。 */
        fun lastDetail(): String = settles.last().detail

        /** 最后一次结算的触发者。 */
        fun lastTrigger(): XiaozhiReplyTrigger = settles.last().trigger

        /** 最后一次**真正上屏**的正文。 */
        fun lastBody(): String = bodies().last()
    }

    // ---- ① / ② / ④ 真机那种 3 段流:恰好 A、B、C 三条,stop 不重复 ----

    /**
     * **本轮主验收**:真机形状的 3 段流(`sentence_start` 已带该句完整文本,`sentence_end` 只把同一句
     * 再推一次)必须产出**恰好三条**上屏,内容分别是 A/B/C —— **不存在** AB、ABC 这种累计文本,
     * 段号 1/2/3(与直通侧「第 N 段已声明」同一套编号),`stop` 不产生额外一条。
     */
    @Test
    fun three_segment_stream_delivers_a_then_b_then_c_without_cumulative_bodies() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "A 段的话。")
        r.reply.onTtsState("sentence_end", "A 段的话。")      // 同一句再推一次:不新增一条
        r.reply.onTtsState("sentence_start", "B 段的话。")
        r.reply.onTtsState("sentence_end", "B 段的话。")
        r.reply.onTtsState("sentence_start", "C 段的话。")
        r.reply.onTtsState("sentence_end", "C 段的话。")
        r.reply.onTtsState("stop", "")

        assertEquals(
            "每次上屏 = 该段自己的文本(A → B → C),不累计",
            listOf("A 段的话。", "B 段的话。", "C 段的话。"),
            r.bodies(),
        )
        assertEquals("段号 1/2/3(与「第 N 段已声明」对得上)", listOf(1, 2, 3), r.ordinals())
        assertEquals(
            "每一条都是「新段」(App 侧据此各追加一条气泡,不是覆盖同一条)",
            listOf(true, true, true),
            r.settles.filter { it.changed }.map { it.isNewSegment },
        )
        assertEquals(
            "首段 = 首段;之后两条都是「下一段」",
            listOf(
                XiaozhiReplyTrigger.PROGRESS_FIRST,
                XiaozhiReplyTrigger.PROGRESS_NEXT,
                XiaozhiReplyTrigger.PROGRESS_NEXT,
            ),
            r.settles.filter { it.changed }.map { it.trigger },
        )
        // 真机验收:屏幕上不能出现 AB、ABC 这类累计气泡
        val joined = r.bodies().joinToString("|")
        assertFalse(
            "绝不能出现累计文本 AB",
            r.bodies().contains("A 段的话。B 段的话。") || joined.contains("A 段的话。B 段的话。"),
        )
        assertFalse(
            "绝不能出现累计文本 ABC",
            joined.contains("A 段的话。B 段的话。C 段的话。"),
        )
        // ② stop 不产生额外一条
        assertFalse("各段都已上屏:stop 不产生新的一条", r.settles.last().changed)
        assertEquals(XiaozhiReplyTrigger.STOP, r.lastTrigger())
        assertTrue(
            "stop 的说明要写明「各段已上屏、不再发累计全文」",
            r.lastDetail().contains("各段字幕均已上屏"),
        )
        // 段级日志:每段一行「第 N 段字幕上屏(本段 L 字)」
        assertEquals(
            listOf(
                "第 1 段字幕上屏(本段 6 字): A 段的话。",
                "第 2 段字幕上屏(本段 6 字): B 段的话。",
                "第 3 段字幕上屏(本段 6 字): C 段的话。",
            ),
            r.logs.filter { it.contains("段字幕上屏") },
        )
        assertTrue("要有一行「不重复上屏」的段级日志", r.logs.any { it.contains("不重复上屏") })
    }

    /** ④ 首段**立即**上屏:第一条 `sentence_start` 一到就交付,不等 `stop`(延迟优先,音频据此开播)。 */
    @Test
    fun first_segment_is_delivered_immediately_on_sentence_start() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "第一段的话。")
        assertEquals("首段不许等 stop", listOf("第一段的话。"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.PROGRESS_FIRST, r.settles.last().trigger)
        assertEquals("首段", r.settles.last().triggerLabel)
        assertEquals(1, r.settles.last().deliveryIndex)
        assertTrue(
            "首段的日志要能对上直通侧的「第 1 段已声明」",
            r.logs.any { it.startsWith("第 1 段字幕上屏(本段 6 字): 第一段的话。") },
        )
    }

    /** ② 重复 `stop` / 强制窗口都不得重复上屏(只记诊断)。 */
    @Test
    fun repeated_stop_and_forced_window_never_emit_the_body_again() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "只有这一段。")
        r.reply.onTtsState("stop", "")
        r.reply.onTtsState("stop", "")
        r.reply.onIdle()
        assertEquals(listOf("只有这一段。"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.FORCED, r.lastTrigger())
        assertFalse("重复结算不得产生新正文", r.settles.last().changed)
    }

    // ---- ③ 某段文本缺失时的兜底:只补那一段 ----

    /**
     * 某段文本**从未上屏**(那句的句界先到、带的却是清洗后不可上屏的模板),它的真文本在 `stop` 才到:
     * 兜底**只补这一段自己的文本**,**绝不**把前面已经上屏的段累计进来。
     */
    @Test
    fun missing_segment_is_supplemented_with_its_own_text_only() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "第一段的话。")          // 段 1:立即上屏
        r.reply.onTtsState("sentence_end", "第一段的话。")
        r.reply.onTtsState("sentence_start", "% get_weather(city=\"上海\")")  // 段 2:不可上屏
        r.reply.onTtsState("stop", "% get_weather(city=\"上海\")第二段的话。")

        assertEquals(
            "只补缺失的第 2 段(它自己的文本),不累计第 1 段",
            listOf("第一段的话。", "第二段的话。"),
            r.bodies(),
        )
        assertEquals("补的是第 2 段", listOf(1, 2), r.ordinals())
        assertEquals(XiaozhiReplyTrigger.STOP, r.settles.last().trigger)
        assertTrue(
            "日志要写明是「补上」并给原因",
            r.logs.any { it.contains("第 2 段字幕(补上:") },
        )
        assertFalse("模板绝不能上屏", r.bodies().any { it.contains("get_weather") })
    }

    /**
     * 「无法归段的文本」的判据:`sentence_end` / `stop` 带来的文本与当前段无关时(段界只认
     * `start`/`sentence_start`,直通侧也是这么声明段的)只把**这条文本**当本段的新文本交出去,
     * **不**累计已上屏的前一段 —— 段号保持当前段,不会多出一个不存在的段。
     */
    @Test
    fun stop_text_unrelated_to_the_current_segment_is_delivered_alone() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "第一段的话。")
        r.reply.onTtsState("stop", "后到的一段话。")
        assertEquals(
            "只上屏后到的那条文本(不累计成「第一段的话。后到的一段话。」)",
            listOf("第一段的话。", "后到的一段话。"),
            r.bodies(),
        )
        assertEquals("归到当前段(第 1 段)", listOf(1, 1), r.ordinals())
        assertTrue(r.logs.any { it.contains("第 1 段字幕(补上:") })
    }

    // ---- 噪声句:不占段号 ----

    /** 清洗后不可上屏的噪声句(工具模板)**不上屏也不占段号**,后续真句子仍是第 1 段。 */
    @Test
    fun noise_segment_is_skipped_without_consuming_a_segment_number() {
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
            "只上屏真答案那一段(用户听到的那句),模板行已剔掉",
            listOf("明天上海是小雨喔，白天23度😊"),
            r.bodies(),
        )
        assertEquals("噪声句不占段号:真句子仍是第 1 段", listOf(1), r.ordinals())
        assertTrue(
            "跳过要留一行带原因的段级日志",
            r.logs.any { it.contains("段字幕(跳过:") && it.contains("不占段号") },
        )
        assertFalse("模板绝不能上屏", r.bodies().any { it.contains("get_weather") })
    }

    // ---- 同一句变完整:只更新本段,不新增一条 ----

    /** `sentence_end` 带来同一句更完整的文本:**更新本段**(段号不变),不新增一条、不拼两遍。 */
    @Test
    fun growing_sentence_updates_the_same_segment_without_a_new_one() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "气温大概")
        assertEquals(listOf("气温大概"), r.bodies())
        r.reply.onTtsState("sentence_end", "气温大概十八到二十三度。")
        assertEquals(
            "同一句变完整 = 更新本段(段号不变)",
            listOf("气温大概", "气温大概十八到二十三度。"),
            r.bodies(),
        )
        assertEquals(listOf(1, 1), r.ordinals())
        assertEquals(XiaozhiReplyTrigger.PROGRESS_UPDATE, r.settles.last().trigger)
        assertEquals(
            "本段更新不是新段(App 侧要**就地替换该段那一条**,而不是再追加一条)",
            listOf(true, false),
            r.settles.filter { it.changed }.map { it.isNewSegment },
        )
        assertTrue(
            "日志要说清这是「本段更新」",
            r.logs.any { it.contains("第 1 段字幕更新(本段") },
        )
        r.reply.onTtsState("stop", "")
        assertEquals("stop 上没有新正文", 2, r.bodies().size)
        assertFalse("同一句不能被拼成两遍", r.lastBody().contains("气温大概气温大概"))
    }

    // ---- 既有不变量回归 ----

    /**
     * **工具调用静默期(§6.4)**:tts 已给过文本但清洗后不可上屏(只有模板/表情)、且一段都还没上屏时,
     * `stop` **挂起不结算**(不把中间态 `llm.text` 抢上屏);真答案随第二段到达后**只上屏它自己**。
     */
    @Test
    fun tool_call_silence_holds_at_stop_then_only_the_real_segment_is_displayed() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("% get_weather(location=\"上海\", date=\"明天\")明天上海是小雨喔，白天23度")
        r.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\", date=\"明天\"):")
        r.reply.onTtsState("sentence_end", "% get_weather(location=\"上海\", date=\"明天\"):")
        r.reply.onTtsState("stop", "")
        assertTrue("静默期:一条正文都不交", r.bodies().isEmpty())
        assertFalse("只记诊断行", r.settles.last().changed)
        assertTrue("诊断行要看出是「静默期挂起」", r.lastDetail().contains("静默期"))

        val full = "明天上海是小雨喔，白天都湿湿的，晚上才转阴，算不上好天气啦。"
        r.reply.onTtsState("sentence_start", full)
        r.reply.onTtsState("stop", "")
        assertEquals("第二段的真答案只上屏它自己(不累计噪声段)", listOf(full), r.bodies())
        assertEquals(listOf(1), r.ordinals())
        assertFalse("模板绝不能残留", r.lastBody().contains("get_weather"))
        assertFalse("不能是那条更短的中间态兜底文本", r.lastBody().contains("白天23度"))
        assertTrue("整段一句都不能少", r.lastBody().contains("晚上才转阴") && r.lastBody().contains("好天气"))
    }

    /** 静默期一直等不到后续分段 → **强制兜底窗口**才用 `llm.text` 兜底(整轮当作一段)。 */
    @Test
    fun tool_call_silence_falls_back_only_at_the_forced_window() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("% get_weather(location=\"上海\", date=\"明天\")明天上海是小雨喔，白天23度")
        r.reply.onTtsState("sentence_start", "% get_weather(location=\"上海\", date=\"明天\")")
        r.reply.onTtsState("stop", "")
        assertTrue("平静期:一条正文都不交", r.bodies().isEmpty())
        r.reply.onIdle()
        assertEquals(
            "强制兜底到点才交兜底(而且只有这一条,不累计)",
            listOf("明天上海是小雨喔，白天23度"),
            r.bodies(),
        )
        assertEquals(XiaozhiReplyTrigger.FORCED, r.lastTrigger())
        assertEquals("整轮当作一段", listOf(1), r.ordinals())
        assertTrue("日志要说清为什么是「补上」", r.logs.any { it.contains("第 1 段字幕(补上:") })
    }

    /** 整轮没有任何 tts 文本 → `stop` 上直接用 `llm.text` 兜底(不拖,不空等)。 */
    @Test
    fun llm_text_is_the_fallback_when_tts_has_no_text() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("今天晴，20 度。")
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("sentence_start", "")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("今天晴，20 度。"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.STOP, r.lastTrigger())
        assertTrue("结算说明里要看出取自 llm 兜底", r.lastDetail().contains("llm.text"))
    }

    /** 纯 llm 服务端(没有任何 tts 报文)时也在长窗口后才兜底,不抢跑。 */
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

    /** 收到过 tts 报文(哪怕不带文本)就把「纯 llm 兜底」的短窗口作废,改走**等 `stop`**。 */
    @Test
    fun any_tts_message_switches_to_the_forced_window() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("😊")
        r.reply.onTtsState("start", "")
        assertEquals(
            "收到过 tts 报文:空闲窗口退化为强制兜底",
            XiaozhiReplyText.FORCED_IDLE_GRACE_MS,
            r.reply.pendingIdleGraceMs(),
        )
        r.reply.onIdle()   // 强制兜底到点:只能交「没有可上屏正文」,**绝不能**把表情当正文交出
        assertEquals(listOf(""), r.bodies())
        assertFalse("表情绝不能进正文", r.bodies().any { it.contains("😊") })
        assertTrue("原因里要写清哪一层为空", r.lastDetail().contains("llm.text"))

        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "真正的回复。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("", "真正的回复。"), r.bodies())
    }

    /** 只有 emotion、没有任何文本 → 交空串(调用方可读原因),不空等、不上屏表情。 */
    @Test
    fun emotion_only_turn_emits_empty_body_not_emoji() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("")
        r.reply.onTtsState("start", "")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf(""), r.bodies())
        assertFalse(r.bodies().any { it.contains("😊") })
        assertTrue(
            "原因必须写清哪一层为空(真机排查要看这句)",
            r.lastDetail().contains("tts 句级文本 无") && r.lastDetail().contains("llm.text 无"),
        )
    }

    /** 纯表情(tts 只给 emoji)时:静默期挂起,强制窗口到点才交空串(带说明),绝不把 emoji 当正文。 */
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

    /** 空句、纯空白句与**重复的句界**都被丢掉,但正常句一个都不能少(每句各占一段)。 */
    @Test
    fun empty_and_duplicate_sentences_are_dropped_without_losing_normal_ones() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_start", "")
        r.reply.onTtsState("sentence_start", "   ")
        r.reply.onTtsState("sentence_start", "第一句。")
        r.reply.onTtsState("sentence_end", "第一句。")        // 同一句:只算一次
        r.reply.onTtsState("start", "第一句。")               // 重复的句界(同一句):不新增一段
        r.reply.onTtsState("sentence_start", "第二句，")      // 正常句
        r.reply.onTtsState("sentence_end", "第二句，带更多内容。") // 同一句变完整:更新本段
        r.reply.onTtsState("sentence_start", "第三句。")
        r.reply.onTtsState("stop", "")
        assertEquals(
            "每句各一段;空句/重复句界不占位,同一句变完整只更新本段(段号不变)",
            listOf("第一句。", "第二句，", "第二句，带更多内容。", "第三句。"),
            r.bodies(),
        )
        assertEquals(listOf(1, 2, 2, 3), r.ordinals())
        assertEquals("第三句。", r.lastBody())
    }

    /** 没有 `stop` 的服务端:收到过 tts 报文时只挂**强制兜底**窗口,到点只补没上屏的那段。 */
    @Test
    fun sentences_are_flushed_only_by_the_forced_window_when_stop_never_arrives() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "只有这一段。")
        assertEquals(
            XiaozhiReplyText.FORCED_IDLE_GRACE_MS,
            r.reply.pendingIdleGraceMs(),
        )
        r.reply.onIdle()
        assertEquals(listOf("只有这一段。"), r.bodies())
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

    /** 新一轮从零开始:上一轮的段与记账不会带进去(段号也重新从 1 开始)。 */
    @Test
    fun turn_start_resets_previous_turn() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "上一句。")
        r.reply.onTurnStart()
        r.reply.onTtsState("sentence_end", "这一句。")
        r.reply.onTtsState("stop", "")
        assertEquals(listOf("上一句。", "这一句。"), r.bodies())
        assertEquals("新一轮段号重新从 1 开始", listOf(1, 1), r.ordinals())
        assertEquals("这一句。", r.lastBody())
    }

    /** 服务端把工具模板**跨句界报文**推来(`start` 给开标签、`end` 给闭标签)时,整块要被剔掉。 */
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
        assertTrue("跨报文拼成的模板段要记一行「跳过」", r.logs.any { it.contains("段字幕(跳过:") })
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
        assertEquals("每段一次上屏", listOf(1), r.ordinals())
    }

    /** 整段兜底正文先上屏(纯 llm 窗口到点),随后 tts 句级文本到达 —— 它作为**下一段**再交一次。 */
    @Test
    fun late_tts_text_after_the_llm_fallback_is_delivered_as_its_own_segment() {
        val r = Recorder()
        r.reply.onTurnStart()
        r.reply.onLlm("中间态的兜底正文。")
        r.reply.onIdle()
        assertEquals(listOf("中间态的兜底正文。"), r.bodies())
        assertEquals(XiaozhiReplyTrigger.IDLE_LLM_FALLBACK, r.settles.last().trigger)

        r.reply.onTtsState("sentence_start", "真正的完整回答，")
        r.reply.onTtsState("sentence_end", "真正的完整回答，包含更多细节。")
        r.reply.onTtsState("stop", "")
        assertEquals(
            "后到的 tts 文本只上屏它自己(不把兜底正文累计进来)",
            listOf("中间态的兜底正文。", "真正的完整回答，", "真正的完整回答，包含更多细节。"),
            r.bodies(),
        )
        assertEquals("兜底那条占第 1 段,真答案从第 2 段起", listOf(1, 2, 2), r.ordinals())
        assertEquals(XiaozhiReplyTrigger.STOP, r.lastTrigger())
    }

    /** 多段 `tts[start…stop]`(工具调用轮次的两段)里,第二段的文本只上屏它自己。 */
    @Test
    fun second_segment_text_is_delivered_alone() {
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
            "第二段文本不能丢、也不能与第一段累计",
            listOf("第一段的话。", "第二段的话。"),
            r.bodies(),
        )
        assertEquals(listOf(1, 2), r.ordinals())
    }
}
