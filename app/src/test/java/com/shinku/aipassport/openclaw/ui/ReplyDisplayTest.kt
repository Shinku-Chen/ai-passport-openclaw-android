package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.gateway.RawEntry
import com.shinku.aipassport.openclaw.gateway.RawKind
import com.shinku.aipassport.openclaw.gateway.RawLabel
import com.shinku.aipassport.openclaw.gateway.SUSPECTED_STATUS_TALK_FLAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * body / raw 双路展示映射、「App 显示完整回传流(调试)」开关语义,以及正文气泡**来源标记**
 * 的 JVM 单测(纯函数,无 Android)。
 *
 * 核心回归:raw 里的 `正文 · 全文`(kind=assistant)以前是**正常气泡**,和 body 气泡一起
 * 让「同一答案」出现两条正常气泡;现在 **raw 一律弱化小字 + 标签,正常气泡只留给 body**。
 */
class ReplyDisplayTest {

    private val body = listOf("北京明天晴，20℃/10℃。")
    private val raw = listOf(
        RawEntry(RawKind.STATUS, "状态 · preparing_workspace", "preparing_workspace"),
        RawEntry(RawKind.STEP, "步骤 · Exec Fetch Beijing tomorrow weather", "running"),
        RawEntry(RawKind.ASSISTANT, RawLabel.ASSISTANT, "北京明天晴，20℃/10℃。"),
        RawEntry(RawKind.TERMINAL, "终局 · visible", "北京明天晴，20℃/10℃。"),
    )

    // ---- 开关开:body 正常气泡在前,raw 全部条目弱化小字跟在后 ----

    @Test
    fun body_bubble_count_equals_body_size_in_raw_mode() {
        // 核心回归:正常气泡只留给 body —— 不再出现「body 气泡 + raw 正文气泡」两条正常气泡
        val entries = replyDisplayEntries(body, raw, showRaw = true)

        assertEquals("body + raw 一条都不丢", body.size + raw.size, entries.size)
        assertEquals("正常气泡数量 == body 数量", body.size, entries.count { it.label == null })
        assertEquals(body, entries.filter { it.label == null }.map { it.text })
    }

    @Test
    fun raw_entries_are_weak_with_non_blank_labels() {
        val entries = replyDisplayEntries(body, raw, showRaw = true)

        val weak = entries.filter { it.label != null }
        assertEquals("raw 条目全部弱化展示", raw.size, weak.size)
        weak.forEach { entry ->
            assertNotNull(entry.label)
            assertTrue("raw 条目必须有非空标签: ${entry.text}", !entry.label.isNullOrBlank())
        }
        assertEquals(
            "raw 条目的标签原样保留(含「正文 · 全文」与终局)",
            listOf(
                "状态 · preparing_workspace",
                "步骤 · Exec Fetch Beijing tomorrow weather",
                RawLabel.ASSISTANT,
                "终局 · visible",
            ),
            weak.map { it.label },
        )
        assertEquals("raw 条目顺序与到达一致", raw.map { it.text }, weak.map { it.text })
    }

    // ---- 开关关:App 只展示 body,不展示 raw ----

    @Test
    fun raw_is_hidden_when_switch_off() {
        val entries = replyDisplayEntries(body, raw, showRaw = false)

        assertEquals("关掉后只展示 body", body.size, entries.size)
        assertEquals(body, entries.map { it.text })
        entries.forEach { assertNull("body 条目都是正常气泡", it.label) }
    }

    /** 该通道本来没有 raw(Hermes / Echo / 自定义 OpenAI 兼容)时,即使开关开也只展示 body。 */
    @Test
    fun body_is_shown_when_channel_has_no_raw() {
        val entries = replyDisplayEntries(body, emptyList(), showRaw = true)
        assertEquals(body, entries.map { it.text })
        entries.forEach { assertNull(it.label) }
    }

    // ---- 正文气泡的来源标记:[流式] / [来自历史] ----

    @Test
    fun body_bubble_carries_stream_source_flag() {
        val entries = replyDisplayEntries(body, emptyList(), showRaw = false)

        assertEquals("[流式]", entries[0].flag)
        assertEquals("[流式]", BodySource.STREAM.flag)
        assertEquals("[流式]", bodySourceOf(corrected = false).flag)
    }

    @Test
    fun body_bubble_carries_history_source_flag_when_corrected() {
        val entries = replyDisplayEntries(
            body,
            emptyList(),
            showRaw = false,
            bodySource = BodySource.HISTORY,
        )

        assertEquals("[来自历史]", entries[0].flag)
        assertEquals("[来自历史]", BodySource.HISTORY.flag)
        assertEquals("[来自历史]", bodySourceOf(corrected = true).flag)
    }

    @Test
    fun raw_entries_never_carry_source_flag() {
        val entries = replyDisplayEntries(body, raw, showRaw = true, bodySource = BodySource.HISTORY)

        val weak = entries.filter { it.label != null }
        assertEquals("正常气泡(第一条 body)带来源标记", "[来自历史]", entries[0].flag)
        weak.forEach { assertNull("raw 弱化条目不带来源标记", it.flag) }
    }

    // ---- 「疑似状态话术」只加标记、不改文本;与来源标记同时存在时「来源在前、疑似在后」 ----

    @Test
    fun suspected_status_talk_keeps_text_and_appends_flag_after_source_flag() {
        val talk = "黑龙江天气已回复完毕，当前无进行中的 exec 会话或子代理。"
        val entries = replyDisplayEntries(listOf(talk), emptyList(), showRaw = false)

        assertEquals(1, entries.size)
        assertEquals("文本必须原样保留", talk, entries[0].text)
        assertEquals("[流式]$SUSPECTED_STATUS_TALK_FLAG", entries[0].flag)
    }

    @Test
    fun suspected_status_talk_flag_is_appended_after_history_source_flag() {
        assertEquals(
            "[来自历史]$SUSPECTED_STATUS_TALK_FLAG",
            bodyFlag("已回复完毕，当前无进行中的任务。", BodySource.HISTORY),
        )
    }

    @Test
    fun normal_body_has_only_source_flag() {
        val entries = replyDisplayEntries(body, emptyList(), showRaw = false)
        assertEquals("[流式]", entries[0].flag)
    }

    @Test
    fun raw_suspected_status_talk_entry_keeps_flag() {
        val talk = "已回复完毕，当前无进行中的任务。"
        val entries = replyDisplayEntries(
            emptyList(),
            listOf(RawEntry(RawKind.ASSISTANT, RawLabel.ASSISTANT, talk)),
            showRaw = true,
        )

        assertEquals(SUSPECTED_STATUS_TALK_FLAG, entries[0].flag)
        assertEquals(talk, entries[0].text)
    }
}
