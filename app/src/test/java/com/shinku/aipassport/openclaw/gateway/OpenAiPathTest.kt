package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OpenAiPath] 的 JVM 单测:逐条覆盖 KDoc 里写明的解析规则
 * (空/前缀补全/完整路径/结尾多余斜杠/查询串/连续斜杠)与 `modelsPath` 推导。
 */
class OpenAiPathTest {

    // ---- resolveChatPath ----

    /** 规则 1:空/全空白 → 默认完整路径。 */
    @Test
    fun blank_falls_back_to_default_chat_path() {
        assertEquals(OpenAiPath.DEFAULT_CHAT_PATH, OpenAiPath.resolveChatPath(""))
        assertEquals(OpenAiPath.DEFAULT_CHAT_PATH, OpenAiPath.resolveChatPath("   "))
        assertEquals("/v1/chat/completions", OpenAiPath.resolveChatPath(""))
    }

    /** 规则 3:只填前缀 `/v1`(旧默认)时自动补 `/chat/completions`,向后兼容。 */
    @Test
    fun prefix_v1_gets_chat_suffix_appended() {
        assertEquals("/v1/chat/completions", OpenAiPath.resolveChatPath("/v1"))
    }

    /** 归一化:结尾多余斜杠被去掉后仍按前缀处理。 */
    @Test
    fun trailing_slash_on_prefix_is_normalized() {
        assertEquals("/v1/chat/completions", OpenAiPath.resolveChatPath("/v1/"))
        assertEquals("/v1/chat/completions", OpenAiPath.resolveChatPath("  /v1/  "))
    }

    /** 归一化:缺前导斜杠的前缀被补成绝对路径。 */
    @Test
    fun missing_leading_slash_is_added() {
        assertEquals("/openai/v1/chat/completions", OpenAiPath.resolveChatPath("openai/v1"))
    }

    /** 规则 2:已是完整 chat 路径 → 原样使用。 */
    @Test
    fun full_chat_path_is_kept() {
        assertEquals(
            "/openai/v1/chat/completions",
            OpenAiPath.resolveChatPath("/openai/v1/chat/completions"),
        )
    }

    /** 规则 2 + 归一化:完整路径的结尾多余斜杠被去掉。 */
    @Test
    fun full_chat_path_with_trailing_slash_is_normalized() {
        assertEquals(
            "/openai/v1/chat/completions",
            OpenAiPath.resolveChatPath("/openai/v1/chat/completions/"),
        )
    }

    /** 规则 4:带查询串时只对 `?` 之前的部分判定与拼接,查询串原样保留在末尾。 */
    @Test
    fun query_string_is_preserved() {
        assertEquals("/v1/chat/completions?key=1", OpenAiPath.resolveChatPath("/v1?key=1"))
        assertEquals(
            "/openai/v1/chat/completions?key=1",
            OpenAiPath.resolveChatPath("/openai/v1/chat/completions?key=1"),
        )
    }

    /** 规则 5:连续斜杠合并为单个。 */
    @Test
    fun consecutive_slashes_are_collapsed() {
        assertEquals("/v1/chat/completions", OpenAiPath.resolveChatPath("//v1//chat//completions"))
        assertEquals("/openai/v1/chat/completions", OpenAiPath.resolveChatPath("//openai//v1"))
    }

    /** 根路径 `/` 归一化为空前缀后只补后缀。 */
    @Test
    fun root_path_becomes_plain_chat_path() {
        assertEquals("/chat/completions", OpenAiPath.resolveChatPath("/"))
    }

    // ---- modelsPath ----

    /** 规则 1:结尾是 `/chat/completions` → 换成 `/models`。 */
    @Test
    fun models_path_replaces_chat_suffix() {
        assertEquals("/v1/models", OpenAiPath.modelsPath("/v1/chat/completions"))
        assertEquals("/openai/v1/models", OpenAiPath.modelsPath("/openai/v1/chat/completions"))
        assertEquals("/models", OpenAiPath.modelsPath("/chat/completions"))
    }

    /** 规则 2:结尾不是 `/chat/completions` → 用其所在目录 + `/models`。 */
    @Test
    fun models_path_uses_parent_directory_for_custom_endpoint() {
        assertEquals("/openai/v1/models", OpenAiPath.modelsPath("/openai/v1/chat"))
        assertEquals("/v1/models", OpenAiPath.modelsPath("/v1/completions"))
    }

    /** 规则 3:查询串原样保留在末尾。 */
    @Test
    fun models_path_keeps_query_string() {
        assertEquals("/v1/models?key=1", OpenAiPath.modelsPath("/v1/chat/completions?key=1"))
    }
}
