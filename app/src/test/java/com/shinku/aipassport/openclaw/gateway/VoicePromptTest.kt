package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音附加提示词:只在语音输入时追加,文字输入绝不追加。
 * 覆盖追加/不追加、空 suffix、trim、input 为空这几条语义。
 */
class VoicePromptTest {

    private val suffix = "请用不超过 200 字回复，并且不要使用 emoji 表情。"

    @Test
    fun default_suffix_is_the_shipped_prompt() {
        assertEquals(
            "请用不超过 200 字回复，不要使用 emoji 表情，也不要使用 Markdown 表格。",
            DEFAULT_VOICE_PROMPT_SUFFIX,
        )
    }

    @Test
    fun voice_input_appends_suffix_after_a_newline() {
        assertEquals(
            "今天天气怎么样\n$suffix",
            VoicePrompt.compose("今天天气怎么样", suffix, isVoice = true),
        )
    }

    @Test
    fun text_input_is_never_appended() {
        assertEquals(
            "今天天气怎么样",
            VoicePrompt.compose("今天天气怎么样", suffix, isVoice = false),
        )
    }

    @Test
    fun blank_suffix_does_not_append() {
        val input = "打开客厅的灯"
        assertEquals(input, VoicePrompt.compose(input, "", isVoice = true))
        assertEquals(input, VoicePrompt.compose(input, "   \n\t ", isVoice = true))
    }

    @Test
    fun suffix_is_trimmed_before_appending() {
        assertEquals("hi\n提示", VoicePrompt.compose("hi", "  提示  ", isVoice = true))
    }

    @Test
    fun empty_input_sends_only_the_suffix() {
        assertEquals(suffix, VoicePrompt.compose("", suffix, isVoice = true))
        assertEquals(suffix, VoicePrompt.compose("   ", suffix, isVoice = true))
    }

    @Test
    fun empty_text_input_stays_empty() {
        assertEquals("", VoicePrompt.compose("", suffix, isVoice = false))
    }
}
