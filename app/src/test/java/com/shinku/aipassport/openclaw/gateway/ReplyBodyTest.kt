package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * body(正确正文)选择的 JVM 单测:优先 `terminalReply(visible)` → 区间内 `final` → 区间内最后一个 `delta`,
 * 非 visible 的 terminalReply 与区间外(seq > end)的正文不进 body。
 *
 * 真机 fixture `gateway/gw-probe-run2.jsonl` 是「问北京天气」那轮的关键帧
 * (lifecycle start … final … finishing … end(terminalReply=visible),final 与 end 同 seq)。
 */
class ReplyBodyTest {

    private companion object {
        const val SESSION_KEY = "agent:main:passport"
        const val RUN2 = "7cbfb4a2-649a-4134-b50c-c873b3ef913e"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun fixture(name: String): List<String> {
            val stream = ReplyBodyTest::class.java.classLoader!!
                .getResourceAsStream("gateway/$name")
                ?: error("缺少测试 fixture gateway/$name")
            return stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        }
    }

    /**
     * 默认给 500ms 沉降窗口(生产默认 800ms):
     * `finishing` / `final` 不是「立即完成」,`end` 帧与后续 final 会在窗口内到齐。
     */
    private fun collector(settleMs: Long = 500L, runId: String = RUN2) = ReplyCollector(
        runId = runId,
        sessionKey = SESSION_KEY,
        userText = "北京明天天气",
        idempotencyKey = "key-1",
        deadlineAtMs = System.currentTimeMillis() + 5_000,
        scope = scope,
        settleMs = settleMs,
    )

    private fun chatFrame(text: String, state: String, seq: Int, runId: String = RUN2): String =
        """{"type":"event","event":"chat","payload":{"runId":"$runId","sessionKey":"$SESSION_KEY","agentId":"main","state":"$state","seq":$seq,"message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}}"""

    private fun lifecycleFrame(
        phase: String,
        seq: Int,
        runId: String = RUN2,
        terminal: String? = null,
        disposition: String = "visible",
    ): String {
        val terminalJson =
            if (terminal == null) "" else ""","terminalReply":{"disposition":"$disposition","text":"$terminal"}"""
        return """{"type":"event","event":"agent","payload":{"runId":"$runId","sessionKey":"$SESSION_KEY","agentId":"main","stream":"lifecycle","seq":$seq,"data":{"phase":"$phase"$terminalJson}}}"""
    }

    // ---- 1) 优先用 terminalReply(visible) ----

    @Test
    fun body_prefers_visible_terminal_reply_from_real_run() {
        val frames = fixture("gw-probe-run2.jsonl")
        // finishing 只进沉降窗口,end 才立即定局:要给 end 帧机会把 terminalReply 带进来
        val c = collector(settleMs = 500)
        frames.forEach { c.acceptFrame(it) }

        val end = frames.mapNotNull { lifecycleEventOf(it, SESSION_KEY, RUN2) }
            .first { it.phase == "end" }
        val expected = end.terminalReplyText!!
        assertTrue("fixture 应带真正的天气正文", expected.contains("北京"))

        assertEquals(listOf(expected), c.collectedMessages)
        // final 与 end 同 seq:边界用 <=,该 final 也在区间内(与 terminalReply 同文本)
        assertTrue(c.rawEntries.any { it.kind == RawKind.TERMINAL })
    }

    // ---- 2) 没有 terminalReply 时回退到区间内的 final ----

    @Test
    fun body_falls_back_to_final_without_terminal_reply() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(chatFrame("答案 A", "delta", 2))
        c.acceptFrame(chatFrame("答案 A + 补充", "final", 2))
        c.acceptFrame(lifecycleFrame("end", 3))

        assertEquals(listOf("答案 A + 补充"), c.collectedMessages)
    }

    /** 多条 final 按 seq 顺序全保留(与到达顺序一致)。 */
    @Test
    fun body_keeps_all_finals_in_range_in_seq_order() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(chatFrame("第一条 final", "final", 2))
        c.acceptFrame(chatFrame("第二条 final", "final", 3))
        c.acceptFrame(lifecycleFrame("end", 4))

        assertEquals(listOf("第一条 final", "第二条 final"), c.collectedMessages)
    }

    // ---- 3) 非 visible 的 terminalReply 不进 body,改用区间内 final ----

    @Test
    fun non_visible_terminal_reply_is_ignored_and_final_is_used() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(chatFrame("真正的正文", "final", 2))
        c.acceptFrame(
            lifecycleFrame("end", 3, terminal = "已回复完毕，无进行中的任务。", disposition = "hidden"),
        )

        assertEquals(listOf("真正的正文"), c.collectedMessages)
        // 非 visible 的终局仍然进 raw(App 能看到)
        assertTrue(
            c.rawEntries.any { it.kind == RawKind.TERMINAL && it.label == "终局 · hidden" },
        )
    }

    // ---- 4) 区间外的迟到正文不进 body(raw 仍保留) ----

    @Test
    fun body_drops_final_beyond_end_seq_but_keeps_it_in_raw() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(chatFrame("区间内的 delta", "delta", 2))
        c.acceptFrame(chatFrame("区间外的迟到正文", "final", 9))
        c.acceptFrame(lifecycleFrame("end", 3))

        assertEquals(listOf("区间内的 delta"), c.collectedMessages)
        assertTrue(
            "区间外正文不得丢:raw 里仍要有",
            c.rawEntries.any { it.text == "区间外的迟到正文" },
        )
    }

    /** 迟到的区间外 delta 不会把区间内最后一个 delta 顶掉。 */
    @Test
    fun body_uses_last_in_range_delta_even_if_a_late_delta_arrives() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(chatFrame("区间内的 delta", "delta", 2))
        c.acceptFrame(chatFrame("区间外的迟到 delta", "delta", 9))
        c.acceptFrame(lifecycleFrame("end", 3))

        assertEquals(listOf("区间内的 delta"), c.collectedMessages)
    }

    @Test
    fun body_is_empty_when_the_only_body_frame_is_beyond_end_seq() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(chatFrame("区间外的迟到正文", "final", 9))
        c.acceptFrame(lifecycleFrame("end", 3))

        assertTrue("区间外没有可用的正确正文", c.collectedMessages.isEmpty())
    }

    /** 非可见 terminalReply、区间内又没有 final/delta → body 为空(不是把话术当答案)。 */
    @Test
    fun body_is_empty_for_non_visible_terminal_without_any_chat_body() {
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(
            lifecycleFrame("end", 2, terminal = "已回复完毕。", disposition = "hidden"),
        )

        assertTrue(c.collectedMessages.isEmpty())
    }

    /** 只有一条 visible 状态话术时,body 就是它:**App 侧不擅自改写**(网关行为问题,只加标记)。 */
    @Test
    fun visible_status_talk_terminal_still_becomes_body_verbatim() {
        val talk = "已回复完毕，当前无进行中的 exec 会话或子代理。"
        val c = collector()
        c.acceptFrame(lifecycleFrame("start", 1))
        c.acceptFrame(lifecycleFrame("end", 2, terminal = talk))

        assertEquals(listOf(talk), c.collectedMessages)
    }
}
