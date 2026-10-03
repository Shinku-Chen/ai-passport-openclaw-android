package com.shinku.aipassport.openclaw.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上屏正文清洗([XiaozhiReplySanitizer])的 JVM 单测:纯函数,无 Android/网络。
 *
 * 覆盖的规则(见该类注释与 `docs/design/xiaozhi-ai-gateway.md` §6):
 *  1. `% 工具模板`(整行 / 行内带参数列表)删除;
 *  2. `<tool_call>…</tool_call>` 等成对工具块整块删除;孤立标签只删标签、**保留**块内文本;
 *  3. `{{…}}` / `${…}` 占位删除(删完不留空格);
 *  4. 多余空白归一(连续空格、行尾空格、删行后的空行);
 *  5. **不误删正常正文的标点**:百分号(`50%`)、中文标点、数字与英文一律原样;
 *  6. 只剩模板/占位时返回空串(调用方据此按「本轮没有可上屏正文」处理)。
 */
class XiaozhiReplySanitizerTest {

    /** 真机日志里的形态:服务端在正文里夹了一行 `% get_weather…` 工具模板。 */
    @Test
    fun percent_weather_template_line_is_removed() {
        val raw = "今天天气不错。\n% get_weather(location=\"北京\", date=\"今天\")\n出门记得带伞。"
        assertEquals("今天天气不错。\n出门记得带伞。", XiaozhiReplySanitizer.clean(raw))
    }

    /** 行内嵌的工具调用(带参数列表)删干净,含参数;周围的正文与标点不受影响。 */
    @Test
    fun inline_percent_call_is_removed_with_its_arguments() {
        assertEquals(
            "北京今天晴，二十度。",
            XiaozhiReplySanitizer.clean("北京今天晴，% get_weather{city:\"北京\"}二十度。"),
        )
        assertEquals(
            "查一下天气吧。",
            XiaozhiReplySanitizer.clean("% get_weather(city=\"北京\")查一下天气吧。"),
        )
    }

    /** 成对工具块(含跨行)整块删除,块内内容不会漏到屏上。 */
    @Test
    fun tool_call_block_is_removed_including_its_body() {
        assertEquals(
            "刮风了，记得带伞。",
            XiaozhiReplySanitizer.clean(
                "刮风了<tool_call>{\"name\":\"get_weather\"}</tool_call>，记得带伞。",
            ),
        )
        assertEquals(
            "真正的回复。",
            XiaozhiReplySanitizer.clean(
                "<tool_call>\n{\"name\":\"get_weather\",\"args\":{\"city\":\"北京\"}}\n</tool_call>\n\n真正的回复。",
            ),
        )
        // 两个并列块:只各删各的,中间夹着的正文必须保留(反向引用按名字配对)
        assertEquals(
            "中间这句是正文。",
            XiaozhiReplySanitizer.clean(
                "<tool_result>甲</tool_result>中间这句是正文。<function_call>乙</function_call>",
            ),
        )
    }

    /** 孤立(没有闭合)的标签只删标签本身:宁可留下可读内容,也不冒「把整段正文删光」的风险。 */
    @Test
    fun unclosed_tool_tag_only_loses_the_tag() {
        val cleaned = XiaozhiReplySanitizer.clean("<tool_call>{\"name\":\"get_weather\"} 正文保留。")
        assertFalse("标签本身不能上屏", cleaned.contains("<tool_call>"))
        assertTrue("标签内的内容保留", cleaned.contains("正文保留。"))
    }

    /** 占位符删除,且删完不留下多余空格。 */
    @Test
    fun placeholders_are_removed_without_leaving_extra_spaces() {
        assertEquals(
            "你好，今天是晴天。",
            XiaozhiReplySanitizer.clean("你好 \${name}，今天是 {{date}} 晴天。"),
        )
        assertEquals(
            "答案在这里。",
            XiaozhiReplySanitizer.clean("答案在这里。\${answer}"),
        )
    }

    /** 多余空白归一:连续空格/行尾空格/连续换行。 */
    @Test
    fun redundant_whitespace_is_tidied() {
        assertEquals(
            "第一句。\n第二句。",
            XiaozhiReplySanitizer.clean("第一句。\n\n\n\n第二句。   "),
        )
        assertEquals(
            "一句 空格压成一个",
            XiaozhiReplySanitizer.clean("一句   空格压成一个"),
        )
    }

    /** 正常正文(含百分号、数字、中文标点、英文)原样保留 —— 不误删标点。 */
    @Test
    fun normal_prose_and_punctuation_are_untouched() {
        val cases = listOf(
            "成功率 50%，比昨天高了 5 个百分点。真的吗？",
            "气温大概十八到二十三度，出门记得带把伞喔。",
            "OK，收到！(第 1 条)：《说明书》— 完。",
            "100% 的用户都同意；只有 1 个反对。",
        )
        cases.forEach { body ->
            assertEquals("正常正文必须原样保留: $body", body, XiaozhiReplySanitizer.clean(body))
        }
    }

    /** 整条回复只有模板/占位 → 空串(调用方按「本轮没有可上屏正文」给可读原因,不上屏噪声)。 */
    @Test
    fun noise_only_body_becomes_empty() {
        assertEquals(
            "",
            XiaozhiReplySanitizer.clean(
                "<tool_call>{\"name\":\"get_weather\"}</tool_call>\n% get_weather(city=\"北京\")",
            ),
        )
        assertEquals("", XiaozhiReplySanitizer.clean(""))
        assertEquals("", XiaozhiReplySanitizer.clean("   \n\t "))
    }
}
