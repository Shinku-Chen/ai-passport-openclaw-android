package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * raw 回传流(`rawEntriesOf` / `truncateToolOutput` / `looksLikeStatusTalk`)的 JVM 单测。
 *
 * 覆盖:
 *  - 八类条目各来一帧 → kind 序列与条数正确、label 是可直接展示的文案;
 *  - 归属过滤(别的会话 / 别的 run 一律丢弃);
 *  - 工具输出截断规则(含「(截断 N 字)」);
 *  - 「疑似状态话术」识别(用户给出的样例 + 真机话术)。
 *
 * 真机 fixture `gateway/gw-probe-run4.jsonl` / `gw-probe-run2.jsonl` / `gw-probe-note.jsonl`
 * 由抓帧文件 `gw-probe-run4.jsonl` 等精简而来(只留 type/event/payload,已去掉 session/token 等字段)。
 */
class RawEntriesTest {

    private val sessionKey = "agent:main:passport"
    private val runId = "run-1"

    private fun chatFrame(state: String, seq: Int, phase: String? = null, text: String? = null): String =
        buildString {
            append("""{"type":"event","event":"chat","payload":{""")
            append(""""runId":"$runId","sessionKey":"$sessionKey","agentId":"main","state":"$state","seq":$seq""")
            if (phase != null) append(""","phase":"$phase"""")
            if (text != null) {
                append(""","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}""")
            }
            append("}}")
        }

    private fun agentFrame(stream: String, seq: Int, data: String): String =
        """{"type":"event","event":"agent","payload":{"runId":"$runId","sessionKey":"$sessionKey","agentId":"main","stream":"$stream","seq":$seq,"data":$data}}"""

    private fun lifecycleFrame(phase: String, seq: Int, extra: String = ""): String =
        agentFrame("lifecycle", seq, """{"phase":"$phase"$extra}""")

    // ---- 1) 八类条目各来一帧:kind 序列与条数正确 ----

    @Test
    fun all_raw_kinds_are_collected_in_arrival_order() {
        val longOutput = "x".repeat(300)
        val frames = listOf(
            chatFrame("status", seq = 1, phase = "preparing_workspace"),
            lifecycleFrame("start", seq = 2),
            agentFrame(
                "item",
                seq = 3,
                """{"title":"Exec Fetch Beijing tomorrow weather","status":"running"}""",
            ),
            agentFrame("tool", seq = 4, """{"phase":"result","name":"exec","isError":true}"""),
            agentFrame(
                "command_output",
                seq = 5,
                """{"title":"command Fetch Beijing tomorrow weather","output":"$longOutput"}""",
            ),
            agentFrame("usage", seq = 6, """{"outputTokens":261}"""),
            agentFrame("assistant", seq = 7, """{"text":"北京明天晴。"}"""),
            lifecycleFrame(
                "end",
                seq = 8,
                extra = ""","stopReason":"stop","aborted":false,"terminalReply":{"disposition":"visible","text":"北京明天晴。"}""",
            ),
        )

        val entries = frames.flatMap { rawEntriesOf(it, sessionKey, runId) }

        assertEquals(
            "八类各一帧 + lifecycle end 额外带一条 terminal → 9 条",
            listOf(
                RawKind.STATUS,
                RawKind.LIFECYCLE,
                RawKind.STEP,
                RawKind.TOOL,
                RawKind.TOOL_OUTPUT,
                RawKind.USAGE,
                RawKind.ASSISTANT,
                RawKind.LIFECYCLE,
                RawKind.TERMINAL,
            ),
            entries.map { it.kind },
        )
        assertEquals(9, entries.size)

        assertEquals("状态 · preparing_workspace", entries[0].label)
        assertEquals("preparing_workspace", entries[0].text)
        assertEquals("生命周期 · start", entries[1].label)
        assertEquals("步骤 · Exec Fetch Beijing tomorrow weather", entries[2].label)
        assertEquals("running", entries[2].text)
        assertEquals("工具 · exec · result(error)", entries[3].label)
        assertEquals("工具输出 · command Fetch Beijing tomorrow weather", entries[4].label)
        assertEquals("用量 · outputTokens=261", entries[5].label)
        assertEquals("正文 · 全文", entries[6].label)
        assertEquals("北京明天晴。", entries[6].text)
        assertEquals("生命周期 · end(stop)", entries[7].label)
        assertEquals("phase=end stopReason=stop aborted=false", entries[7].text)
        assertEquals("终局 · visible", entries[8].label)
        assertEquals("北京明天晴。", entries[8].text)

        // seq 落在条目上(final 与 end 可能同 seq,调用方据此做区间判定)
        assertEquals(8, entries.last().seq)
    }

    // ---- 2) 归属过滤:别的会话 / 别的 run 一律丢弃 ----

    @Test
    fun foreign_session_or_run_frames_are_dropped() {
        val frame = agentFrame("item", 1, """{"title":"步骤","status":"running"}""")
        assertTrue(rawEntriesOf(frame, sessionKey, runId).isNotEmpty())
        assertTrue("别的会话的帧不得进 raw", rawEntriesOf(frame, "agent:main:other", runId).isEmpty())
        assertTrue("别的 run 的帧不得进 raw", rawEntriesOf(frame, sessionKey, "other-run").isEmpty())
        // 期望值未知(空串)时不按该字段过滤:本轮首帧用来学习 runId
        assertTrue(rawEntriesOf(frame, sessionKey, "").isNotEmpty())
    }

    @Test
    fun non_informational_frames_yield_no_raw_entries() {
        assertTrue(rawEntriesOf("not json", sessionKey, runId).isEmpty())
        assertTrue(
            rawEntriesOf("""{"type":"res","payload":{"runId":"$runId"}}""", sessionKey, runId).isEmpty(),
        )
        assertTrue(
            rawEntriesOf(
                """{"type":"event","event":"presence","payload":{"runId":"$runId"}}""",
                sessionKey,
                runId,
            ).isEmpty(),
        )
        // chat 帧没有正文文本时不算条目
        assertTrue(
            rawEntriesOf(
                """{"type":"event","event":"chat","payload":{"runId":"$runId","sessionKey":"$sessionKey","state":"delta","seq":1}}""",
                sessionKey,
                runId,
            ).isEmpty(),
        )
    }

    // ---- 3) 工具输出截断规则 ----

    @Test
    fun tool_output_over_limit_is_truncated_with_count() {
        assertEquals("x".repeat(200) + "(截断 3000 字)", truncateToolOutput("x".repeat(3200)))
        assertEquals("恰好 200 字不截断", "x".repeat(200), truncateToolOutput("x".repeat(200)))
        assertEquals("少于 200 字不截断", "abc", truncateToolOutput("abc"))
        // 自定义上限也按同一规则
        assertEquals("abcde(截断 5 字)", truncateToolOutput("abcdefghij", max = 5))
    }

    // ---- 4) 「疑似状态话术」识别(只识别,不改写) ----

    @Test
    fun status_talk_patterns_are_detected() {
        listOf(
            "已回复完毕。",
            "上一轮已答复完毕，无待处理事项。",
            "已回复完毕，当前无进行中的 exec 会话或子代理。",
            "黑龙江天气已回复完毕，当前无进行中的 exec 会话或子代理。",
            "当前无进行中的任务。",
            "无活跃 exec 会话。",
            "无活跃子代理。",
            "当前没有进行中的任务、exec 会话或子代理。",
        ).forEach { assertTrue("应判为疑似状态话术: $it", looksLikeStatusTalk(it)) }

        listOf(
            "北京明天 9 月 30 日（周三）：晴，20℃/10℃。",
            "任务已开始，预计 10 分钟完成。",
            "没有网络连接，请检查 Tailscale。",
        ).forEach { assertFalse("不应判为疑似状态话术: $it", looksLikeStatusTalk(it)) }
    }

    // ---- 5) 真机 fixture:一条都不丢 ----

    @Test
    fun real_run_fixture_keeps_every_information_frame() {
        val frames = fixture("gw-probe-run4.jsonl")
        val run = "3eb2e6d8-3466-404d-a870-97e6c1aed99f"
        // run4 用的是独立会话名(设置里会话名 = passport2)
        val key = "agent:main:passport2"
        val entries = frames.flatMap { rawEntriesOf(it, key, run) }

        assertTrue(
            "每帧至少贡献一条 raw 条目(frames=${frames.size}, entries=${entries.size})",
            entries.size >= frames.size,
        )
        assertEquals(
            "run4 只有一条 lifecycle end(带 terminalReply)",
            1,
            entries.count { it.kind == RawKind.TERMINAL },
        )
        assertEquals(
            "八类条目在真机帧里都出现过",
            RawKind.let {
                setOf(
                    it.STATUS, it.LIFECYCLE, it.STEP, it.TOOL,
                    it.TOOL_OUTPUT, it.USAGE, it.ASSISTANT, it.TERMINAL,
                )
            },
            entries.map { it.kind }.toSet(),
        )
        // 工具输出超限的条目确实被截断并标注
        val truncated = entries.filter { it.kind == RawKind.TOOL_OUTPUT && it.text.contains("(截断") }
        assertTrue("run4 的命令输出应触发截断标注", truncated.isNotEmpty())
    }

    private fun fixture(name: String): List<String> {
        val stream = javaClass.classLoader!!.getResourceAsStream("gateway/$name")
            ?: error("缺少测试 fixture gateway/$name")
        return stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
    }
}
