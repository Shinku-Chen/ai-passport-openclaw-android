package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用 `chat.history` 补正正文」的 JVM 单测(纯函数,不依赖 Android / 真网关)。
 *
 * 真机现象(本特性的由来):一轮 run 里有**多条** assistant 文本消息
 * (查天气 → 工具结果 → **答案** → MemOS 工具 → **状态话术**),而流式 `chat delta/final`
 * 只推**最后一条**(= 状态话术)。于是设备屏与 TTS 拿到的是「已回复完毕…」,真正的答案
 * 只存在于 `chat.history` 里。
 *
 * fixture `gateway/chat-history-xinjiang.json` 就是这一轮的真实历史结构(已去掉 token/session
 * 等敏感字段、sessionId 置零):上一轮(北京)作为干扰项 + 本轮(新疆)的工具调用/工具输出/答案/状态话术。
 *
 * 覆盖:
 *  - [assistantTextsSince] 只取「本轮」的 assistant 文本(多轮历史里不串轮、工具消息不进正文);
 *  - [pickCorrectBody] 排除状态话术、取最长(并列取最早)、全命中时返回 null;
 *  - [historyRawEntries] 历史条目按文本与流式条目去重、保持历史顺序、标签可读、工具输出截断;
 *  - [planHistoryCorrection] 「补发只在不同的时候发生」(相同 → 不重复上屏/朗读)。
 */
class ChatHistoryTest {

    private companion object {
        /** 本轮实际发给网关的用户文本(含语音附加提示词)。 */
        const val USER_TEXT =
            "新疆天气是什么？\n请用不超过 200 字回复，不要使用 emoji 表情，也不要使用 Markdown 表格。"

        /** 本轮真正的答案(历史里最长的那条 assistant 文本)。 */
        const val ANSWER =
            "新疆以首府乌鲁木齐为例（09-30 00:05 实况）：多云，14.6℃，微风，AQI 54（良）。" +
                "全疆范围很大，阿勒泰、喀什等地差异明显，需要具体城市可以再说。"

        /** 本轮最后那条 assistant 文本 = 状态话术(流式帧推的就是它)。 */
        const val STATUS_TALK =
            "没有活跃的 exec 会话或子代理，也没有待办事项。新疆天气已回复完毕，有需要继续叫我。"

        /** 上一轮的答案(干扰项:绝不能被当成本轮正文)。 */
        const val PREV_ANSWER = "北京明天晴，20℃/10℃。"
    }

    private val history: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("gateway/chat-history-xinjiang.json")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("缺少测试 fixture gateway/chat-history-xinjiang.json")
    }

    // ---- 1) 只取「本轮」的 assistant 文本 ----

    @Test
    fun assistant_texts_since_keeps_only_this_turn() {
        val texts = assistantTextsSince(history, USER_TEXT)

        assertEquals("本轮只有两条 assistant 文本:答案 + 状态话术", listOf(ANSWER, STATUS_TALK), texts)
        assertFalse("上一轮的答案不得混进来", texts.contains(PREV_ANSWER))
        assertTrue("工具调用/工具输出的文本不进正文", texts.none { it.contains("\"city\"") })
    }

    @Test
    fun assistant_texts_since_falls_back_to_the_last_user_message() {
        // 文本对不上(例:网关对上传文本做了归一化)时退回「历史里最后一条 user 消息」
        assertEquals(
            listOf(ANSWER, STATUS_TALK),
            assistantTextsSince(history, "这段文本在历史里不存在"),
        )
        // 空用户文本同样只用「最后一条 user 消息」定位
        assertEquals(listOf(ANSWER, STATUS_TALK), assistantTextsSince(history, ""))
    }

    @Test
    fun assistant_texts_since_is_empty_when_history_is_unusable() {
        assertEquals(emptyList<String>(), assistantTextsSince("not json", USER_TEXT))
        assertEquals(emptyList<String>(), assistantTextsSince("{}", USER_TEXT))
        assertEquals(emptyList<String>(), assistantTextsSince("""{"messages":[]}""", USER_TEXT))
        // 没有 user 消息 → 无法判定「本轮」,一律不补正(退回流式正文)
        assertEquals(
            emptyList<String>(),
            assistantTextsSince(historyOf("assistant" to ANSWER), USER_TEXT),
        )
    }

    @Test
    fun assistant_texts_since_ignores_assistant_messages_without_text() {
        val json = historyJson(
            textMessage("user", USER_TEXT),
            toolCallMessage("exec"),
            textMessage("assistant", ANSWER),
        )
        assertEquals(
            "只有 toolCall 的 assistant 消息没有文本,不进正文",
            listOf(ANSWER),
            assistantTextsSince(json, USER_TEXT),
        )
    }

    // ---- 2) 挑「正确正文」:排除状态话术、取最长、全命中返回 null ----

    @Test
    fun pick_correct_body_skips_status_talk_and_takes_the_longest() {
        assertEquals(
            "状态话术被排除,剩下最长的答案胜出",
            ANSWER,
            pickCorrectBody(STATUS_TALK, listOf(STATUS_TALK, ANSWER)),
        )
        // 并列取最早
        assertEquals("abcd", pickCorrectBody("", listOf("abcd", "wxyz")))
        // 首尾空白裁剪后再比长度
        assertEquals("真正的答案", pickCorrectBody("", listOf("  真正的答案  ", "短")))
    }

    @Test
    fun pick_correct_body_returns_null_when_only_status_talk() {
        assertNull(
            "整轮只有状态话术 → 保留流式正文,不凭空造",
            pickCorrectBody(STATUS_TALK, listOf(STATUS_TALK, "已回复完毕。", "无活跃 exec 会话。")),
        )
        assertNull("空历史同样返回 null", pickCorrectBody(STATUS_TALK, emptyList()))
        assertNull("空白文本不算候选", pickCorrectBody(STATUS_TALK, listOf("   ", "")))
    }

    // ---- 3) 历史 raw 条目:与流式条目按文本去重、保持历史顺序、标签可读 ----

    /** 流式条目里的状态话术(真机上流式 final 推的就是它)。 */
    private val streamedStatusTalk = RawEntry(RawKind.ASSISTANT, RawLabel.ASSISTANT, STATUS_TALK)

    @Test
    fun history_entries_are_deduped_against_streamed_and_keep_history_order() {
        val entries = historyRawEntries(history, USER_TEXT, listOf(streamedStatusTalk))

        // 顺序 = 历史顺序(工具调用 → 工具输出 → 答案 → 工具调用 → 工具输出);
        // 结尾那条状态话术与流式条目文本相同 → 去重,不再追加
        assertEquals(
            listOf(
                RawKind.HISTORY_TOOL,
                RawKind.HISTORY_TOOL_OUTPUT,
                RawKind.HISTORY_ASSISTANT,
                RawKind.HISTORY_TOOL,
                RawKind.HISTORY_TOOL_OUTPUT,
            ),
            entries.map { it.kind },
        )
        assertFalse("与流式条目文本相同的条目不得重复追加", entries.any { it.text == STATUS_TALK })
        assertFalse("上一轮的历史条目不得混进来", entries.any { it.text == PREV_ANSWER })

        // 标签必须是可直接展示的文案
        assertEquals("工具(历史) · exec", entries[0].label)
        assertEquals(
            "工具条目正文用命令(同名工具也能区分)",
            "curl -sS http://d1.weather.com.cn/sk_2d/101130101.html",
            entries[0].text,
        )
        assertEquals("工具输出(历史) · 全文", entries[1].label)
        assertTrue("工具输出文本进 raw 条目", entries[1].text.contains("\"city\":\"乌鲁木齐\""))
        assertEquals("正文(历史) · 全文", entries[2].label)
        assertEquals(ANSWER, entries[2].text)
    }

    @Test
    fun history_without_streamed_entries_keeps_the_status_talk_too() {
        val entries = historyRawEntries(history, USER_TEXT, emptyList())
        assertEquals(
            "流式条目为空时历史里的状态话术也要收(App 侧再加「疑似状态话术」标记)",
            listOf(
                RawKind.HISTORY_TOOL,
                RawKind.HISTORY_TOOL_OUTPUT,
                RawKind.HISTORY_ASSISTANT,
                RawKind.HISTORY_TOOL,
                RawKind.HISTORY_TOOL_OUTPUT,
                RawKind.HISTORY_ASSISTANT,
            ),
            entries.map { it.kind },
        )
        assertEquals(STATUS_TALK, entries.last().text)
        assertTrue("状态话术的标记由展示层按文本识别", looksLikeStatusTalk(entries.last().text))
    }

    @Test
    fun long_history_tool_output_is_truncated() {
        val withTool = historyWithToolResult(USER_TEXT, "x".repeat(300))

        val entries = historyRawEntries(withTool, USER_TEXT, emptyList())
        assertEquals(
            listOf(RawKind.HISTORY_TOOL_OUTPUT),
            entries.map { it.kind },
        )
        assertEquals("x".repeat(200) + "(截断 100 字)", entries.single().text)
    }

    // ---- 4) 补发只在不同的时候发生 ----

    @Test
    fun plan_sends_history_answer_when_streamed_body_is_the_status_talk() {
        val plan = planHistoryCorrection(history, USER_TEXT, listOf(STATUS_TALK), listOf(streamedStatusTalk))

        assertEquals(listOf(ANSWER, STATUS_TALK), plan.assistantTexts)
        assertEquals("正确正文 = 历史里最长的非状态话术", ANSWER, plan.correctBody)
        assertEquals("与流式 body 不同 → 需要补发一帧给设备", ANSWER, plan.sendText)
        assertTrue(plan.hasCorrection)
        assertTrue("历史条目同时补进 App raw 视图", plan.rawEntries.isNotEmpty())
    }

    @Test
    fun plan_does_not_resend_when_history_answer_matches_the_streamed_body() {
        // 流式 body 就是答案(含首尾空白差异)→ 什么都不做:不重复上屏、不重复朗读
        val plan = planHistoryCorrection(history, USER_TEXT, listOf("  $ANSWER  "), emptyList())

        assertEquals(ANSWER, plan.correctBody)
        assertNull("相同 → 不补发", plan.sendText)
        assertFalse(plan.hasCorrection)
    }

    @Test
    fun plan_keeps_streamed_body_when_the_turn_only_has_status_talk() {
        val onlyTalk = historyOf(
            "user" to USER_TEXT,
            "assistant" to STATUS_TALK,
        )
        val plan = planHistoryCorrection(onlyTalk, USER_TEXT, listOf(STATUS_TALK), emptyList())

        assertEquals(listOf(STATUS_TALK), plan.assistantTexts)
        assertNull("整轮只有状态话术 → 保留现有流式 body", plan.correctBody)
        assertNull(plan.sendText)
    }

    @Test
    fun plan_is_a_no_op_when_history_is_unusable() {
        listOf("", "not json", "{}", """{"messages":[]}""").forEach { bad ->
            val plan = planHistoryCorrection(bad, USER_TEXT, listOf(STATUS_TALK), emptyList())
            assertNull("查不到历史 → 退回流式正文", plan.correctBody)
            assertNull(plan.sendText)
            assertTrue("查不到历史 → 不追加任何 raw 条目", plan.rawEntries.isEmpty())
        }
    }

    // ---- 测试用小工具 ----

    /** 直接构造 `chat.history` 形态的 payload(避免手写 JSON 的转义问题)。 */
    private fun historyOf(vararg messages: Pair<String, String>): String =
        historyJson(*messages.map { (role, text) -> textMessage(role, text) }.toTypedArray())

    private fun historyJson(vararg messages: JsonObject): String {
        val root = JsonObject()
        root.add("messages", JsonArray().apply { messages.forEach { add(it) } })
        return root.toString()
    }

    private fun textMessage(role: String, text: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        add(
            "content",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    },
                )
            },
        )
    }

    private fun toolCallMessage(name: String): JsonObject = JsonObject().apply {
        addProperty("role", "assistant")
        add(
            "content",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "toolCall")
                        addProperty("name", name)
                    },
                )
            },
        )
    }

    /** 一轮「用户文本 + 一条超长工具输出」的历史(用于截断规则)。 */
    private fun historyWithToolResult(userText: String, output: String): String =
        historyJson(textMessage("user", userText), textMessage("toolResult", output))
}
