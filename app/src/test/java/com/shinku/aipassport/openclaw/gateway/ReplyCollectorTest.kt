package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReplyCollector] 的 JVM 单测:纯逻辑,不依赖真设备/真网关/Android 运行时。
 *
 * 覆盖线上实测的两个现象:
 *  1. WS 一失败就把在途回复判成空回复 → 「设备说完话 App 立刻显示(网关无回复)」;
 *  2. 超时/断连/被打断时必须返回**已收到的部分消息**,不能把答案丢光。
 *
 * `gateway/chat-frames.jsonl` 是真机抓帧精简来的 fixture(见 [ReplyMessagesTest] 注释)。
 */
class ReplyCollectorTest {

    private companion object {
        const val SESSION_KEY = "agent:main:passport"
        const val RUN_ID = "e8287308-5314-4d7c-96cd-5fbb33bbc0f0"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val frames: List<String> by lazy {
            val stream = javaClass.classLoader!!
                .getResourceAsStream("gateway/chat-frames.jsonl")
                ?: error("缺少测试 fixture gateway/chat-frames.jsonl")
            stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        }
    }

    /** 默认给 5s 等待窗口(用例都在窗口内完成);settleMs=0 表示 final 立即定局。 */
    private fun collector(
        deadlineAtMs: Long = System.currentTimeMillis() + 5_000,
        settleMs: Long = 0L,
    ) = ReplyCollector(
        runId = RUN_ID,
        sessionKey = SESSION_KEY,
        userText = "你好",
        idempotencyKey = "key-1",
        deadlineAtMs = deadlineAtMs,
        scope = scope,
        settleMs = settleMs,
    )

    private fun chatFrame(
        text: String,
        state: String = "delta",
        key: String = SESSION_KEY,
        run: String = RUN_ID,
    ): String = """{"type":"event","event":"chat","payload":{"runId":"$run","sessionKey":"$key","state":"$state","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}}"""

    private val answer: String by lazy {
        replyMessagesOf(frames[7], SESSION_KEY, RUN_ID).single().text
    }

    /** socket 中途断开不终结本轮:断开后到达的 final 仍然算这一轮的回复。 */
    @Test
    fun socket_drop_does_not_finish_pending_reply_with_empty_text() = runBlocking {
        val c = collector()
        c.appendMessage("部分回复")
        c.onConnectionInterrupted("连接超时:网络或 Tailscale 未就绪")

        // 关键:断开不是结局,不能变成空回复
        assertFalse("断开不得终结本轮收集", c.isTerminal)
        assertEquals("断开时不得清空已收文本", 4, c.textLength)
        assertEquals(1, c.interruptCount)

        // 重连后继续收,收到 final
        c.appendMessage("部分回复,完整内容", isFinal = true)

        assertEquals(ReplyOutcome.Reply(listOf("部分回复,完整内容")), c.awaitOutcome())
    }

    /** 迟到的 final 在等待窗口内仍然能被收到(不允许提前用空文本结束等待)。 */
    @Test
    fun late_final_still_completes_the_waiting_collector() = runBlocking {
        val c = collector(deadlineAtMs = System.currentTimeMillis() + 5_000)
        val waiting = async { c.awaitOutcome() }

        delay(80)
        c.onConnectionInterrupted("WS 失败")
        delay(80)
        c.appendMessage("迟到的最终回复", isFinal = true)

        assertEquals(ReplyOutcome.Reply(listOf("迟到的最终回复")), withTimeout(2_000) { waiting.await() })
    }

    /** 断开后到时限仍没结果:返回可读的中断原因,同时保留已收到的部分消息。 */
    @Test
    fun interrupted_without_final_returns_readable_reason_and_keeps_partial() = runBlocking {
        val c = collector(deadlineAtMs = System.currentTimeMillis() - 1)
        c.appendMessage("已收到的半句话")
        c.onConnectionInterrupted("连接被拒绝:域名/端口不可达")

        val outcome = c.awaitOutcome()
        assertTrue("应为中断结局,实际 $outcome", outcome is ReplyOutcome.Interrupted)
        outcome as ReplyOutcome.Interrupted
        assertEquals("网关连接中断:连接被拒绝:域名/端口不可达", outcome.reason)
        assertEquals("中断时不得丢掉已收到的部分消息", listOf("已收到的半句话"), outcome.messages)
    }

    /** 等满窗口后返回的是中断原因,而不是「立刻」返回。 */
    @Test
    fun await_waits_full_window_before_reporting_interruption() = runBlocking {
        val c = collector(deadlineAtMs = System.currentTimeMillis() + 300)
        c.onConnectionInterrupted("网络不可达")

        val start = System.currentTimeMillis()
        val outcome = withTimeout(5_000) { c.awaitOutcome() }
        val elapsed = System.currentTimeMillis() - start

        assertTrue(outcome is ReplyOutcome.Interrupted)
        assertTrue("必须在剩余时限内继续等,而不是收到断开就返回(实际 ${elapsed}ms)", elapsed >= 200)
    }

    /** 只有「收到 final 且文本为空」才算空回复,且空 final 不携带消息。 */
    @Test
    fun empty_final_is_the_only_empty_reply() = runBlocking {
        val c = collector()
        c.finishFinal()
        assertEquals(ReplyOutcome.EmptyFinal(), c.awaitOutcome())
    }

    /** 没有连接中断、网关就是不回 final → 超时(不是中断,也不是空回复),部分消息保留。 */
    @Test
    fun no_final_without_interruption_is_timeout_with_partial() = runBlocking {
        val c = collector(deadlineAtMs = System.currentTimeMillis() - 1)
        c.appendMessage("半句答案")
        val outcome = c.awaitOutcome()
        assertTrue("应为等待超时,实际 $outcome", outcome is ReplyOutcome.Timeout)
        outcome as ReplyOutcome.Timeout
        assertTrue(outcome.reason.contains("超时"))
        assertEquals(listOf("半句答案"), outcome.messages)
    }

    /** barge 取消是独立结局,不会被当成空回复;已收到的部分消息保留。 */
    @Test
    fun cancel_is_barge_not_empty_reply() = runBlocking {
        val c = collector()
        c.appendMessage("写了一半")
        c.cancel()
        assertEquals(ReplyOutcome.Cancelled(listOf("写了一半")), c.awaitOutcome())
        // 终结后再来的帧不得改写文本
        c.appendMessage("更迟的文本")
        assertEquals(1, c.messageCount)
    }

    /** 终结只认第一次:重复 finishFinal/cancel 不改变已定结局。 */
    @Test
    fun terminal_outcome_is_settled_once() = runBlocking {
        val c = collector()
        c.appendMessage("最终文本", isFinal = true)
        c.cancel()
        assertEquals(ReplyOutcome.Reply(listOf("最终文本")), c.awaitOutcome())
    }

    // ---- 帧级语义(用真机 fixture) ----

    /** 只有 chat delta/final 被收集;status 与 agent 各 stream 一律不进正文。 */
    @Test
    fun only_chat_delta_final_frames_are_collected() = runBlocking {
        val c = collector()
        // 0..6 = chat status / agent run_status / lifecycle / tool / assistant 步骤
        (0..6).forEach { index ->
            assertFalse("帧 $index 不应被收集", c.acceptFrame(frames[index]))
        }
        assertTrue("delta 帧应被收集", c.acceptFrame(frames[7]))
        assertEquals(listOf(answer), c.collectedMessages)

        assertTrue("lifecycle finishing 是本轮结束信号", c.onLifecycle("finishing"))
        assertEquals(ReplyOutcome.Reply(listOf(answer)), c.awaitOutcome())
    }

    /** 结束信号 final 与 lifecycle 都能让本轮定局;不同 run/会话的帧被丢弃。 */
    @Test
    fun lifecycle_and_final_both_end_the_run_foreign_frames_are_dropped() = runBlocking {
        // 别的 run 的正文帧不得进本轮
        val c1 = collector()
        assertFalse(c1.acceptFrame(chatFrame("串台", run = "other-run")))
        assertFalse(c1.acceptFrame(chatFrame("串台", key = "agent:main:other")))
        assertEquals(0, c1.messageCount)

        // final 结束(settleMs=0 立即定局)
        val c2 = collector()
        assertTrue(c2.acceptFrame(chatFrame("答案", state = "final")))
        assertEquals(ReplyOutcome.Reply(listOf("答案")), c2.awaitOutcome())

        // lifecycle end 结束
        val c3 = collector()
        c3.acceptFrame(chatFrame("答案"))
        assertTrue(c3.onLifecycle("end"))
        assertEquals(ReplyOutcome.Reply(listOf("答案")), c3.awaitOutcome())
    }

    /**
     * 一轮里两条分开的 final(答案 + 后续状态消息)都要收集到:
     * 第一条 final 只结束该条消息,沉降窗口内到达的第二条继续收集。
     */
    @Test
    fun two_final_messages_in_one_run_are_both_collected() = runBlocking {
        val c = collector(settleMs = 400)
        c.acceptFrame(chatFrame("北京今天晴", state = "final"))
        delay(60)
        c.acceptFrame(chatFrame("（上一轮已答复完毕。）", state = "final"))

        val outcome = withTimeout(3_000) { c.awaitOutcome() }
        assertTrue("应为正常结束,实际 $outcome", outcome is ReplyOutcome.Reply)
        assertEquals(
            listOf("北京今天晴", "（上一轮已答复完毕。）"),
            (outcome as ReplyOutcome.Reply).messages,
        )
    }

    /** 生命周期帧构造器(agent/stream=lifecycle/phase=…,seq 可控)。 */
    private fun lifecycleFrame(phase: String, seq: Int): String =
        """{"type":"event","event":"agent","payload":{"runId":"$RUN_ID","sessionKey":"$SESSION_KEY","stream":"lifecycle","seq":$seq,"data":{"phase":"$phase"}}}"""

    private fun runStatusFrame(phase: String, seq: Int): String =
        """{"type":"event","event":"agent","payload":{"runId":"$RUN_ID","sessionKey":"$SESSION_KEY","stream":"run_status","seq":$seq,"data":{"phase":"$phase"}}}"""

    /**
     * raw 条目必须按 seq 升序输出:真机上多路帧共享一份 seq,按到达顺序展示会出现
     * 「finishing 排在 model 前」的乱序(position: 真机实测 model(5) → finishing(18) → end(20))。
     */
    @Test
    fun raw_entries_are_sorted_by_seq_ascending() = runBlocking {
        // settleMs 给大值:finishing 只进沉降窗口,不会在本用例中途把本轮定局(否则后续帧会被拒收)
        val c = collector(deadlineAtMs = System.currentTimeMillis() + 60_000, settleMs = 60_000)
        // 故意乱序到达:finishing(18) → model(5) → run_status(2) → end(20)(end 必须最后,它是终局信号)
        c.acceptFrame(lifecycleFrame("finishing", 18))
        c.acceptFrame(lifecycleFrame("model", 5))
        c.acceptFrame(runStatusFrame("preparing_context", 2))
        c.acceptFrame(lifecycleFrame("end", 20))

        assertEquals(listOf(2, 5, 18, 20), c.rawEntries.map { it.seq })
        assertEquals(
            listOf("preparing_context", "phase=model", "phase=finishing", "phase=end"),
            c.rawEntries.map { it.text },
        )
        assertTrue("raw 条目不应丢失", c.rawEntryCount >= 4)
    }
}
