package com.shinku.aipassport.openclaw.gateway

import com.shinku.aipassport.openclaw.stt.XiaozhiLlmSource
import com.shinku.aipassport.openclaw.stt.XiaozhiReplyText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 小智 AI 网关「文本这条路」的单测:无网络、无 Android 依赖。
 *
 * 用假会话 [FakeSession] 直接触发会话层的 `llm` 回调(与真
 * [com.shinku.aipassport.openclaw.stt.XiaozhiSession] 的分流同形),断言的都是**用户可见的结果**:
 *  1. 正常拿到 `llm.text` → 作为**单条**回复返回(流水线据此一次整段上屏);
 *  2. 一直等不到 → 超时 + 可读原因(不是无限挂着);
 *  2b. 会话层明确说「本轮没有可上屏正文」(空串 = 只有表情/空文本)→ **立刻**给可读原因,不空等超时;
 *  3. barge/打断后旧轮不再产出结果:在途等待立刻以「本轮已打断」收尾(不悬挂)、
 *     旧轮暂存的正文也不交给下一轮,且**不误报网关故障**(lastError 保持空,
 *     否则状态卡/连接监控会把一次正常打断当成网关故障并触发重连)。
 * 另覆盖两处真机容易踩到的边界:`llm` 比 `chatMulti` 早到(暂存窗口)、`close()` 不关闭共用会话。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XiaozhiGatewayTest {

    /**
     * 假的小智会话:只保留网关真正用到的那一面 —— 挂观察者 / 可用 / 预热。
     * [pushLlm] 等价于真会话在 WS 回调线程里分流出一条 `{"type":"llm","text":…}`。
     */
    private class FakeSession(var available: Boolean = true) : XiaozhiLlmSource {

        /** 当前观察者;null = 没人接(llm 无处可去,与真会话里没有网关挂载时一致)。 */
        var observer: ((String) -> Unit)? = null

        var prewarmCount = 0
        var warm = true

        /** 会话层的结算说明(见 [XiaozhiLlmSource.lastReplyDetail]):网关拿它拼可读原因。 */
        override var lastReplyDetail: String? = null

        override val isAvailable: Boolean get() = available

        override fun prewarm() {
            prewarmCount++
        }

        override fun isWarmReady(): Boolean = warm

        override fun setLlmObserver(observer: ((String) -> Unit)?) {
            this.observer = observer
        }

        override fun clearLlmObserver(observer: (String) -> Unit) {
            if (this.observer === observer) this.observer = null
        }

        fun pushLlm(text: String) {
            observer?.invoke(text)
        }
    }

    /** 正常路径:`llm.text` 作为单条回复返回;[chat] 取同一条回复。 */
    @Test
    fun llm_text_becomes_single_reply() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session)

        var reply: ChatReply? = null
        // 入参只是本地识别原文;本通道不发任何请求,回复来自会话层的 llm 事件。
        val job = launch { reply = gw.chatMulti("今天天气怎么样") }
        runCurrent()                       // 让 chatMulti 挂上等待
        session.pushLlm("今天晴,20 度。")   // 会话层推正文
        advanceUntilIdle()
        job.join()

        assertEquals(listOf("今天晴,20 度。"), reply!!.messages)
        assertNull(reply!!.error)
        assertNull("成功一轮不留错误", gw.lastError)

        var one: String? = null
        val job2 = launch { one = gw.chat("再说一句") }
        runCurrent()
        session.pushLlm("  好的  ")         // 首尾空白应裁掉
        advanceUntilIdle()
        job2.join()
        assertEquals("好的", one)
    }

    /**
     * 两个**真实件**接起来(假传输):`XiaozhiReplyText` 装配 → 网关等待 → 流水线拿到的正文。
     *
     * 验收点 1 的「上屏的是 tts 拼接的正文(不是 😊)」在这一层可见:会话层(此处用真装配器模拟
     * 它的分流)喂进 `llm` 的表情 + 两句 `tts` 文本,网关交出的**首条**回复就是渐进交付出的首句;
     * 之后更完整的正文经 `onBodyCorrection` 补正交付(小智网关「首条 + 补正」的完整契约)。
     */
    @Test
    fun tts_concat_body_reaches_the_gateway_verbatim() = runTest {
        val session = FakeSession()
        // 与会话层同构:装配器的 emit 就是推给本会话正文观察者的那个回调(未变化的诊断不上屏)。
        val replyText = XiaozhiReplyText(emit = { outcome ->
            if (outcome.changed) session.pushLlm(outcome.body)
        })
        val gw = XiaozhiGateway(session)
        val corrections = CopyOnWriteArrayList<String>()
        var reply: ChatReply? = null
        val job = launch {
            reply = gw.chatMulti("明天天气怎么样", onBodyCorrection = { corrections += it })
        }
        runCurrent()
        replyText.onTurnStart()
        replyText.onLlm("😊")
        replyText.onTtsState("sentence_start", "气温大概十八到二十三度，")
        advanceUntilIdle()
        assertEquals(
            "首句先到:首条交付就是它(渐进交付,直接开播的那条)",
            listOf("气温大概十八到二十三度，"),
            reply!!.messages,
        )

        replyText.onTtsState("sentence_end", "出门记得带把伞喔。")
        replyText.onTtsState("stop", "")
        advanceUntilIdle()
        job.join()

        assertEquals(listOf("气温大概十八到二十三度，"), reply!!.messages)
        assertEquals(
            "更完整的正文(tts 两句拼接)经补正交付",
            listOf("气温大概十八到二十三度，出门记得带把伞喔。"),
            corrections.toList(),
        )
        assertNull(reply!!.error)
    }

    /** 超时路径:等不到 `llm` 时给出可读原因,并写进 lastError(与其它网关的失败语义一致)。 */
    @Test
    fun timeout_returns_readable_error() = runTest {
        val gw = XiaozhiGateway(FakeSession(), replyTimeoutMs = 5_000)
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("在吗") }
        runCurrent()
        advanceTimeBy(5_000)     // 虚拟时间:不真的等 5 秒
        advanceUntilIdle()
        job.join()

        assertTrue("超时不应产出气泡", reply!!.messages.isEmpty())
        assertTrue("原因必须可读", reply!!.error!!.contains("小智没有返回回复"))
        assertEquals("超时原因要能被状态卡读到", reply!!.error, gw.lastError)
    }

    /**
     * 会话层确认「本轮没有可上屏正文」(整轮只有表情/空文本) → **立刻**给可读原因。
     *
     * 真机问题:上屏的回复只一个 emoji(设备屏是方块)。修法是把正文来源改成「tts 句级文本拼接」,
     * 而两者都没有时必须给可读原因 —— 绝不把表情/空串当正文,也不白等一个 30s 超时
     * (虚拟时间下,若空串被当成「没到」,这里会推进到超时并给出另一条文案 → 断言失败)。
     */
    @Test
    fun no_body_from_session_fails_fast_with_readable_reason() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("明天天气怎么样") }
        runCurrent()
        session.pushLlm("")            // 会话层的「本轮没有可上屏正文」信号(空串)
        advanceUntilIdle()
        job.join()

        assertTrue("没有正文就不该产出气泡", reply!!.messages.isEmpty())
        assertTrue("原因必须可读", reply!!.error!!.contains("没有返回可上屏的正文"))
        assertEquals("原因要能被状态卡读到", reply!!.error, gw.lastError)
    }

    /**
     * 「没有可上屏正文」的可读原因必须带上会话层的结算说明([XiaozhiLlmSource.lastReplyDetail]):
     * 写清是**哪一层为空** —— 真机排查就靠这句分辨「压根没收到文本」与「收到的全是表情/工具模板」。
     */
    @Test
    fun no_body_reason_names_the_empty_layer() = runTest {
        val session = FakeSession()
        session.lastReplyDetail = "tts 句级文本 2 条(清洗后 0 字);llm.text 1 字(清洗后 0 字)"
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("明天天气怎么样") }
        runCurrent()
        session.pushLlm("")
        advanceUntilIdle()
        job.join()

        assertTrue("结论文案保持可读", reply!!.error!!.contains("没有返回可上屏的正文"))
        assertTrue(
            "必须写清哪一层为空",
            reply!!.error!!.contains("tts 句级文本 2 条(清洗后 0 字)") &&
                reply!!.error!!.contains("llm.text 1 字(清洗后 0 字)"),
        )
        assertEquals("原因要能被状态卡读到", reply!!.error, gw.lastError)
    }

    /** 打断在途等待(barge):新一轮 `turn_start` 先调 interrupt,旧轮立刻收尾、不悬挂。 */
    @Test
    fun barge_aborts_inflight_wait_without_result() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("第一轮的问题") }
        runCurrent()
        gw.interrupt()                       // = 新一轮 turn_start(流水线固定会调)
        advanceUntilIdle()                   // 若 interrupt 没作废等待,这里会一直等到 60s 超时
        job.join()

        assertTrue("打断的旧轮不能产出气泡", reply!!.messages.isEmpty())
        assertEquals(XiaozhiGateway.ABORTED, reply!!.error)
        assertNull("打断不是网关故障,不能写 lastError", gw.lastError)
    }

    /** 打断把旧轮暂存的正文一并作废:下一轮绝不能吃到上一轮的回复。 */
    @Test
    fun barge_discards_previous_turn_buffered_reply() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        // 旧轮的正文已经到、还没人来取(暂存),此时用户打断。
        session.pushLlm("第一轮的正文")
        gw.interrupt()

        var next: ChatReply? = null
        val job = launch { next = gw.chatMulti("第二轮的问题") }
        runCurrent()
        assertNull("打断后不能把旧轮的正文当成新一轮的回复", next)
        session.pushLlm("第二轮的正文")
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("第二轮的正文"), next!!.messages)
    }

    /** `llm` 比 chatMulti 早到几百毫秒(真机常见:llm 紧跟在最终 stt 之后):不白等一个超时。 */
    @Test
    fun llm_before_chatMulti_is_reused() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 5_000)
        session.pushLlm("提前到达的正文")
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("问题") }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("提前到达的正文"), reply!!.messages)
        assertNull(reply!!.error)
    }

    /** 没接线(App 内文本输入页没有语音会话)时立刻给可读原因,不空等。 */
    @Test
    fun missing_session_fails_fast_with_readable_reason() = runTest {
        val gw = XiaozhiGateway(source = null)
        val reply = gw.chatMulti("打字输入")
        assertTrue(reply.messages.isEmpty())
        assertTrue(reply.error!!.contains("只在设备语音链路里工作"))
        assertNull(gw.chat("打字输入"))
        assertFalse("connect 也要给出原因而不是假装就绪", gw.connect())
        assertTrue(gw.lastError!!.contains("只在设备语音链路里工作"))
    }

    /** `connect()` 只确认会话可用并复用同一条热连接(不另建 socket);就绪看共用会话的握手状态。 */
    @Test
    fun connect_reuses_shared_session_and_readiness_follows_it() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session)
        assertTrue(gw.connect())
        assertEquals(1, session.prewarmCount)
        assertTrue("会话已握手 = 网关可收发", gw.isReady())

        session.warm = false              // 热连接掉了(会话层自己会重连)
        assertFalse("会话还在,只是没握手:不能假装可收发", gw.isReady())
        assertTrue(gw.connect())
        assertEquals("预热幂等:只是催一次会话层", 2, session.prewarmCount)

        session.available = false
        assertFalse(gw.connect())
        assertFalse(gw.isReady())
    }

    /** `close()` 只摘观察者、作废等待:**绝不能关掉共用的会话**(那是识别通道的)。 */    @Test
    fun close_detaches_observer_without_touching_shared_session() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("问题") }
        runCurrent()
        gw.close()
        advanceUntilIdle()
        job.join()

        assertNull("关掉网关应摘掉观察者(不留回调给旧实例)", session.observer)
        assertEquals(XiaozhiGateway.ABORTED, reply!!.error)
        // 旧实例不再使用:再来一条 llm 不产出结果,且快速给出可读原因(不白等一个超时)。
        session.pushLlm("关闭后的正文")
        val after = gw.chatMulti("问题")
        assertTrue(after.messages.isEmpty())
        assertTrue(after.error!!.contains("已关闭"))
    }

    // ---- 真机问题「从小智获取的文本不是完整的」:同一轮后续到达的更完整正文必须能上屏 ----

    /**
     * 同一轮的**第二条**正文(更完整的那条)必须经 `onBodyCorrection` 交付给流水线。
     *
     * 真机形状:首条正文可能是「中间态 + 工具模板」那种更短的文本(或纯 llm 兜底),
     * 真答案随后的分段才到。旧实现在第一条之后就没人能再上屏了(回复已交、deferred 已完),
     * 设备屏就永远停在那条更短的文本上 —— 这就是本轮的根因。
     */
    @Test
    fun later_full_body_is_delivered_as_a_body_correction() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        val corrections = CopyOnWriteArrayList<String>()
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("明天天气怎么样", onBodyCorrection = { corrections += it }) }
        runCurrent()
        session.pushLlm("明天上海是小雨喔，白天23度")                       // 中间态(首条交付)
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("明天上海是小雨喔，白天23度"), reply!!.messages)
        assertTrue("首条交付时还没有补正", corrections.isEmpty())

        // 真答案随第二个分段到达:更完整的那条正文必须作为补正交出去
        val full = "明天上海是小雨喔，白天都湿湿的，晚上才转阴，算不上好天气啦。"
        session.pushLlm(full)
        advanceUntilIdle()
        assertEquals(listOf(full), corrections.toList())
        assertNull("补正不是失败,不写 lastError", gw.lastError)
    }

    /**
     * 同一轮的**多次**补正(多段 `tts[start…stop]`)都要交付 —— 不能只认第一条补正,
     * 否则第三段之后的文本又会丢掉。
     */
    @Test
    fun every_growing_body_is_delivered_to_the_pipeline() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        val corrections = CopyOnWriteArrayList<String>()
        val job = launch { gw.chatMulti("问题", onBodyCorrection = { corrections += it }) }
        runCurrent()
        session.pushLlm("第一段。")
        advanceUntilIdle()
        session.pushLlm("第一段。第二段。")
        advanceUntilIdle()
        session.pushLlm("第一段。第二段。第三段。")
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("第一段。第二段。", "第一段。第二段。第三段。"), corrections.toList())
    }

    /** 与已交付正文相同的那条不再重复交付(否则设备屏会多一个重复气泡)。 */
    @Test
    fun identical_body_is_not_re_delivered() = runTest {
        val session = FakeSession()
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        val corrections = CopyOnWriteArrayList<String>()
        val job = launch { gw.chatMulti("问题", onBodyCorrection = { corrections += it }) }
        runCurrent()
        session.pushLlm("同一段正文。")
        advanceUntilIdle()
        session.pushLlm("同一段正文。")
        session.pushLlm("  同一段正文。  ")
        advanceUntilIdle()
        job.join()
        assertTrue("文本没变就不重复上屏", corrections.isEmpty())
    }

    /**
     * 「本轮没有可上屏正文」(空串 = 只表情/工具模板)也是一条**结论**:
     * 真答案随后到达时同样要作为补正把它替掉(否则设备屏永远停在那句可读原因上)。
     */
    @Test
    fun real_body_after_a_no_body_outcome_is_delivered_as_a_correction() = runTest {
        val session = FakeSession()
        session.lastReplyDetail = "tts 句级文本 1 条(原始 30 字→清洗后 0 字);llm.text 无"
        val gw = XiaozhiGateway(session, replyTimeoutMs = 60_000)
        val corrections = CopyOnWriteArrayList<String>()
        var reply: ChatReply? = null
        val job = launch { reply = gw.chatMulti("问题", onBodyCorrection = { corrections += it }) }
        runCurrent()
        session.pushLlm("")
        advanceUntilIdle()
        job.join()
        assertTrue(reply!!.messages.isEmpty())
        assertTrue(reply!!.error!!.contains("没有返回可上屏的正文"))

        session.pushLlm("工具调用完成后才到的真答案。")
        advanceUntilIdle()
        assertEquals(listOf("工具调用完成后才到的真答案。"), corrections.toList())
    }
}
