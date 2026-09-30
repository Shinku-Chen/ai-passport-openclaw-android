package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「终局后宽限窗 + 迟到帧增量」的 JVM 单测:纯逻辑,不依赖真设备/真网关/Android 运行时。
 *
 * 真机现象(本特性的由来):`agent lifecycle phase=end` 与 `chat final` 几乎同一时刻到达
 * (实测两者 seq 相同),旧实现 `awaitOutcome()` 一返回就把 `activeCollector` 清成 null,
 * 于是紧随 `end` 的 `final`、以及更晚的 `item/tool/command_output` 结果帧全部被丢弃
 * (真机 App 侧 `chat/final` 收到数恒为 0,而 PC 探针每个 run 都有)。
 *
 * 宽限窗本身在 `OpenClawGateway.chatMulti()`(Android 侧);这里覆盖它依赖的收集器语义:
 *  - 终局后到达的帧继续进 raw,且**不改写**已定局的 body;
 *  - 增量回调只报「游标之后」的新条目(不重复首批、按 seq 升序);
 *  - body 的返回时机与宽限窗无耦合(定局就返回,不等窗)。
 *
 * fixture `gateway/chat-frames.jsonl` 就是这一轮的关键帧:
 * … lifecycle start(3) … tool(5) assistant(6) chat delta(7) finishing(8) end(9) chat final(10)。
 */
class ReplyCollectorGraceTest {

    private companion object {
        const val SESSION_KEY = "agent:main:passport"
        const val RUN_ID = "e8287308-5314-4d7c-96cd-5fbb33bbc0f0"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val frames: List<String> by lazy {
            val stream = ReplyCollectorGraceTest::class.java.classLoader!!
                .getResourceAsStream("gateway/chat-frames.jsonl")
                ?: error("缺少测试 fixture gateway/chat-frames.jsonl")
            stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        }
    }

    /** 默认 settleMs=0:finishing / final 立即定局,便于把「终局后」的帧单独喂进去。 */
    private fun collector(deadlineAtMs: Long = System.currentTimeMillis() + 60_000) = ReplyCollector(
        runId = RUN_ID,
        sessionKey = SESSION_KEY,
        userText = "北京明天天气",
        idempotencyKey = "key-1",
        deadlineAtMs = deadlineAtMs,
        scope = scope,
        settleMs = 0L,
    )

    /** fixture 里 chat delta/delta 的正文(delta 与区间内的 final 文本相同)。 */
    private val answer: String by lazy {
        replyMessagesOf(frames[7], SESSION_KEY, RUN_ID).single().text
    }

    /** fixture 里终局后那条 `chat final` 的正文。 */
    private val lateFinalText: String by lazy {
        replyMessagesOf(frames[10], SESSION_KEY, RUN_ID).single().text
    }

    private fun itemFrame(title: String, seq: Int): String =
        """{"type":"event","event":"agent","payload":{"runId":"$RUN_ID","sessionKey":"$SESSION_KEY","stream":"item","seq":$seq,"data":{"title":"$title","status":"end"}}}"""

    private fun toolResultFrame(name: String, seq: Int): String =
        """{"type":"event","event":"agent","payload":{"runId":"$RUN_ID","sessionKey":"$SESSION_KEY","stream":"tool","seq":$seq,"data":{"name":"$name","phase":"result"}}}"""

    /**
     * 终局后到达的 `chat final` 与 `item/tool` 必须继续进 raw(旧实现全被丢弃),
     * 但**绝不能**再改写已经定局的 body —— 设备屏与 TTS 只吃 body。
     */
    @Test
    fun late_frames_after_terminal_land_in_raw_and_never_change_body() {
        val c = collector()
        (0..8).forEach { c.acceptFrame(frames[it]) }   // 含 finishing:settleMs=0 → 立即定局
        val outcome = runBlocking { c.awaitOutcome() }
        assertTrue("定局后应为正常结束,实际 $outcome", outcome is ReplyOutcome.Reply)

        val bodyAtTerminal = c.collectedMessages
        assertEquals("定局时 body = 区间内 delta 的正文", listOf(answer), bodyAtTerminal)

        val batches = mutableListOf<List<RawEntry>>()
        val first = c.snapshotRawAndArmUpdate { batches += it }

        // 终局后到达:lifecycle end(9) → chat final(10) → 更晚的 item / tool 结果帧
        c.acceptFrame(frames[9])
        c.acceptFrame(frames[10])
        c.acceptFrame(itemFrame("Exec Fetch Beijing tomorrow weather", 11))
        c.acceptFrame(toolResultFrame("exec", 12))

        assertEquals("终局后到达的帧也必须进 raw", first.size + 4, c.rawEntryCount)
        assertEquals("终局后的正文不得改写已定局的 body", bodyAtTerminal, c.collectedMessages)
        assertTrue(
            "迟到的 chat final 要进 raw",
            c.rawEntries.any { it.kind == RawKind.ASSISTANT && it.text == lateFinalText },
        )
        assertTrue("迟到的 item 要进 raw", c.rawEntries.any { it.kind == RawKind.STEP })
        assertTrue("迟到的 tool result 要进 raw", c.rawEntries.any { it.kind == RawKind.TOOL })
        assertEquals("增量回调收到的条目数 = 终局后新增条目数", 4, batches.flatten().size)
    }

    /** 首批 + 增量拼起来刚好等于窗末的完整 raw:一条不丢、一条不重、seq 升序。 */
    @Test
    fun first_batch_plus_increments_equals_the_full_raw_stream() {
        val c = collector()
        (0..9).forEach { c.acceptFrame(frames[it]) }   // end 定局
        runBlocking { c.awaitOutcome() }

        val batches = mutableListOf<List<RawEntry>>()
        val first = c.snapshotRawAndArmUpdate { batches += it }
        assertEquals("装配前不得有增量回调", 0, batches.size)

        c.acceptFrame(frames[10])
        c.acceptFrame(itemFrame("Exec Fetch Beijing tomorrow weather", 11))
        c.acceptFrame(toolResultFrame("exec", 12))

        val delivered = batches.flatten()
        assertEquals("首批 + 增量 = 窗末完整 raw(不重不漏)", c.rawEntries, first + delivered)
        assertEquals("增量按 seq 升序", listOf(10, 11, 12), delivered.map { it.seq })
        assertTrue("增量不得与首批重复", delivered.none { it in first })
    }

    /** 增量回调分批触发,每批只含该次新到的条目;再次装配后仍只报新条目(游标语义)。 */
    @Test
    fun raw_update_callback_reports_only_new_entries_per_batch() {
        val c = collector()
        (0..9).forEach { c.acceptFrame(frames[it]) }
        runBlocking { c.awaitOutcome() }

        val batches = mutableListOf<List<RawEntry>>()
        val first = c.snapshotRawAndArmUpdate { batches += it }
        c.acceptFrame(frames[10])
        c.acceptFrame(itemFrame("Exec Fetch Beijing tomorrow weather", 11))

        assertEquals("每次追加回调一次", 2, batches.size)
        assertEquals("第一批增量只有 chat final", listOf(RawKind.ASSISTANT), batches[0].map { it.kind })
        assertEquals("第二批增量只有 item", listOf(RawKind.STEP), batches[1].map { it.kind })
        assertTrue("增量不含首批条目", batches.flatten().none { it in first })

        val second = c.snapshotRawAndArmUpdate { batches += it }
        c.acceptFrame(toolResultFrame("exec", 12))

        assertEquals("重新装配后的增量只有这一次的新条目", 1, batches.last().size)
        assertEquals(second.size + 1, c.rawEntryCount)
    }

    /** body 的返回与终局宽限窗无耦合:一拿到结束信号就返回,不等窗;窗内的帧只进 raw。 */
    @Test
    fun body_return_is_not_coupled_to_the_post_terminal_grace_window() = runBlocking {
        val c = collector()
        c.acceptFrame(frames[7])   // chat delta(正文)
        c.acceptFrame(frames[8])   // finishing(settleMs=0):等价于生产里 end 帧定局
        val start = System.currentTimeMillis()
        val outcome = withTimeout(2_000) { c.awaitOutcome() }
        val elapsed = System.currentTimeMillis() - start

        assertTrue("定局后应为正常结束,实际 $outcome", outcome is ReplyOutcome.Reply)
        assertTrue(
            "body 的返回不得等终局宽限窗(实测 ${elapsed}ms / 窗口 ${OpenClawGateway.POST_TERMINAL_GRACE_MS}ms)",
            elapsed < OpenClawGateway.POST_TERMINAL_GRACE_MS,
        )

        // 宽限窗内的帧继续进 raw,但 body 早在返回时就已固定
        val body = c.collectedMessages
        val rawBefore = c.rawEntryCount
        c.acceptFrame(frames[9])
        c.acceptFrame(frames[10])
        assertEquals("宽限窗内不得改写 body", body, c.collectedMessages)
        assertEquals("宽限窗内继续收 raw", rawBefore + 2, c.rawEntryCount)
    }
}
