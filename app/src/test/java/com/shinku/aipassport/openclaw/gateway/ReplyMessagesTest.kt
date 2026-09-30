package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [replyMessagesOf] / [mergeMessages] 的 JVM 单测。
 *
 * fixture `gateway/chat-frames.jsonl` 由真机抓帧
 * (`gw-probe-frames.jsonl`,一次真实 run 的关键帧)精简而来,只保留
 * `type/event/payload`(runId/sessionKey/state/phase/stream/data/deltaText/message)。
 * 该 run 的正文是一句话;文件里的 `agent stream=assistant` 帧带同文本的执行细节步骤,
 * 用来确认「步骤标题不是正文」。
 */
class ReplyMessagesTest {

    private val sessionKey = "agent:main:passport"
    private val runId = "e8287308-5314-4d7c-96cd-5fbb33bbc0f0"

    /** 按行读 fixture;顺序与生产一致:进度 → 工具 → 正文 delta → lifecycle → 正文 final。 */
    private val frames: List<String> by lazy {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("gateway/chat-frames.jsonl")
            ?: error("缺少测试 fixture gateway/chat-frames.jsonl")
        stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
    }

    private val answer: String by lazy {
        val event = replyMessagesOf(frames[7], sessionKey, runId).single()
        assertFalse("delta 帧不是 final", event.isFinal)
        event.text
    }

    private fun chatFrame(
        text: String,
        state: String = "delta",
        key: String = sessionKey,
        run: String = runId,
    ): String = """
        {"type":"event","event":"chat","payload":{"runId":"$run","sessionKey":"$key",
        "agentId":"main","state":"$state","deltaText":"$text",
        "message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}}
    """.trimIndent()

    // ---- a) 只有答案一轮 → 1 条,文本正确 ----

    @Test
    fun single_reply_run_yields_one_message_with_exact_text() {
        val acc = mutableListOf<String>()
        // delta 与 final 各来一次(真机就是这两帧)
        replyMessagesOf(frames[7], sessionKey, runId).forEach { mergeMessages(acc, it.text) }
        replyMessagesOf(frames[10], sessionKey, runId).forEach { mergeMessages(acc, it.text) }

        assertEquals(1, acc.size)
        assertEquals(answer, acc.single())
        assertTrue(answer.contains("exec"))
    }

    // ---- b) 答案 + 后续状态消息两条 → 2 条都要拿到(本次核心回归) ----

    @Test
    fun answer_plus_followup_status_message_both_collected() {
        val statusText = "（无进行中的后台任务或子会话，上一轮北京天气已答复完毕。）"
        val acc = mutableListOf<String>()

        // 答案(与真机一致的 delta 帧)
        replyMessagesOf(frames[7], sessionKey, runId).forEach { mergeMessages(acc, it.text) }
        // 后续状态消息:同一轮里网关又推的一条 assistant 消息(不再覆盖答案)
        replyMessagesOf(chatFrame(statusText, state = "final"), sessionKey, runId)
            .forEach { mergeMessages(acc, it.text) }

        assertEquals("答案 + 后续状态消息两条都必须保留", 2, acc.size)
        assertEquals(answer, acc[0])
        assertEquals(statusText, acc[1])
    }

    // ---- c) delta 与 final 同文本 → 只算 1 条 ----

    @Test
    fun identical_delta_and_final_are_deduplicated() {
        val acc = mutableListOf<String>()
        assertTrue(mergeMessages(acc, answer))
        assertFalse("完全相同的 final 不应新增消息", mergeMessages(acc, answer))
        assertEquals(1, acc.size)
    }

    // ---- d) delta 累计增长 → 1 条且为最新全文 ----

    @Test
    fun cumulative_delta_replaces_in_place_with_latest_full_text() {
        val acc = mutableListOf<String>()
        assertTrue(mergeMessages(acc, "你好"))
        assertTrue(mergeMessages(acc, "你好，世界"))
        assertEquals(1, acc.size)
        assertEquals("你好，世界", acc.single())
    }

    // ---- e) 不同 sessionKey / runId 的 chat 帧不被收集 ----

    @Test
    fun foreign_session_or_run_frames_are_ignored() {
        assertTrue(
            "别的会话的 chat 帧不得进正文",
            replyMessagesOf(chatFrame("串台消息", key = "agent:main:other"), sessionKey, runId).isEmpty(),
        )
        assertTrue(
            "别的 run 的 chat 帧不得进正文",
            replyMessagesOf(chatFrame("串 run 消息", run = "other-run"), sessionKey, runId).isEmpty(),
        )
        // 期望值未知(空串)时不做过滤,用于本轮首帧学习 runId
        assertEquals(1, replyMessagesOf(chatFrame("首帧"), sessionKey, "").size)
    }

    // ---- f) state=status 与 agent/tool 帧里的 phase/tool 文本不进正文 ----

    @Test
    fun progress_and_agent_frames_never_become_body_text() {
        // 0/2/4 = chat state=status(带 phase);1/3/5 = agent run_status/lifecycle/tool;
        // 6 = agent stream=assistant(带 data.text 的步骤细节)
        listOf(0, 1, 2, 3, 4, 5, 6).forEach { index ->
            assertTrue(
                "帧 $index 不应被当成正文: ${frames[index]}",
                replyMessagesOf(frames[index], sessionKey, runId).isEmpty(),
            )
        }
        // 确认 agent stream=assistant 帧确实带步骤文本(否则用例没有意义)
        assertTrue(frames[6].contains("exec"))
    }

    // ---- 补充:非 chat / 畸形帧一律丢弃 ----

    @Test
    fun non_chat_and_malformed_frames_are_dropped() {
        assertTrue(replyMessagesOf("not json", sessionKey, runId).isEmpty())
        assertTrue(replyMessagesOf("""{"type":"res","payload":{}}""", sessionKey, runId).isEmpty())
        assertTrue(
            replyMessagesOf(
                """{"type":"event","event":"presence","payload":{"seq":1}}""",
                sessionKey,
                runId,
            ).isEmpty(),
        )
        // chat state=status 即使带 message 文本也不进正文
        assertTrue(
            replyMessagesOf(
                chatFrame("进度文本", state = "status"),
                sessionKey,
                runId,
            ).isEmpty(),
        )
    }
}
