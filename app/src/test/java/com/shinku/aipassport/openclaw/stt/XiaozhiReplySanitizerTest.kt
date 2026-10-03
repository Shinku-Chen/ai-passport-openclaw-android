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

    // ---- 真机那几轮的实际字形串(2026-10-05 回归:清洗不能把正文吃光) ----

    /**
     * 真机样本 ①:tts 句级拼接里**混着**工具模板行与正文 —— 模板剔掉,正文**一个字都不能少**。
     *
     * 这是「只要有非空、清洗后含文字的 tts 句级文本就必须上屏」的直接回归:不能让清洗把整段吃成空串,
     * 否则设备屏就会变成「小智这一轮没有返回可上屏的正文」那一句可读原因。
     */
    @Test
    fun real_device_mixed_template_and_prose_keeps_the_prose() {
        // llm.text 里的形态(真机日志):工具模板 + emoji → 清洗后只剩 emoji,而 emoji **不可上屏**
        val llmCleaned = XiaozhiReplySanitizer.clean("% get_weather(location=\"上海\", date=\"明天\")😊")
        assertEquals("😊", llmCleaned)
        assertFalse("只剩表情 → 不可上屏", XiaozhiReplySanitizer.isDisplayable(llmCleaned))

        // tts 句级拼接的形态:模板行 + 真答案(模板、emoji 都不影响正文)
        val ttsConcat = "% get_weather(location=\"上海\", date=\"明天\")\n明天上海是小雨喔，白天23度😊"
        val cleaned = XiaozhiReplySanitizer.clean(ttsConcat)
        assertEquals("明天上海是小雨喔，白天23度😊", cleaned)
        assertTrue("正文必须还在", cleaned.contains("明天上海是小雨喔"))
        assertTrue("含文字就算可上屏", XiaozhiReplySanitizer.isDisplayable(cleaned))

        // 句级拼接:工具调用做成了一句、真答案一句 —— 剔掉模板后仍是完整的两句正文
        val sentences = "% get_weather{\"city\":\"上海\"}" +
            "明天上海是小雨喔，白天23度。" +
            "出门记得带伞哦。"
        assertEquals(
            "明天上海是小雨喔，白天23度。出门记得带伞哦。",
            XiaozhiReplySanitizer.clean(sentences),
        )
    }

    /** 工具的**嵌套参数** / 行首无参数的裸模板 / `<tool_call>` 同现时,正文同样保留。 */
    @Test
    fun nested_arguments_bare_template_and_tool_block_leave_the_prose_intact() {
        // 参数值里有括号(地名):必须整块删掉,不能把正文与括号一起留下
        assertEquals(
            "上海浦东明天小雨。",
            XiaozhiReplySanitizer.clean("上海浦东明天小雨。% get_weather(location=\"浦东(上海)\")"),
        )
        // 行首裸模板 + 同一行的正文:只删 token
        assertEquals(
            "明天小雨，白天23度。",
            XiaozhiReplySanitizer.clean("% get_weather 明天小雨，白天23度。"),
        )
        // 成对工具块 + 跨行的正文
        assertEquals(
            "明天上海小雨。",
            XiaozhiReplySanitizer.clean(
                "<tool_call>{\"name\":\"get_weather\",\"args\":{\"city\":\"上海\"}}</tool_call>\n明天上海小雨。",
            ),
        )
    }

    /**
     * 「可上屏」判定([XiaozhiReplySanitizer.isDisplayable]):至少一个字母/数字。
     *
     * 为什么不能只看「清洗后非空」:emoji 不会被清洗删掉,而把 emoji 当正文上屏正是真机那个
     * 「设备屏上一个方块」的 bug;反过来纯标点/空白也不算正文。
     */
    @Test
    fun displayable_requires_at_least_one_letter_or_digit() {
        assertTrue(XiaozhiReplySanitizer.isDisplayable("明天小雨"))
        assertTrue(XiaozhiReplySanitizer.isDisplayable("OK"))
        assertTrue(XiaozhiReplySanitizer.isDisplayable("23 度"))
        assertTrue(XiaozhiReplySanitizer.isDisplayable("😊 好"))
        assertFalse("纯 emoji 不是正文", XiaozhiReplySanitizer.isDisplayable("😊"))
        assertFalse(XiaozhiReplySanitizer.isDisplayable("😊🎉"))
        assertFalse(XiaozhiReplySanitizer.isDisplayable("!?。，"))
        assertFalse(XiaozhiReplySanitizer.isDisplayable("   "))
        assertFalse(XiaozhiReplySanitizer.isDisplayable(""))
    }

    /** 反面：清洗的每条规则都不得误伤正常中文/英文/数字/标点(真机正文的常见形态)。 */
    @Test
    fun realistic_prose_survives_every_rule() {
        val cases = listOf(
            "明天上海是小雨喔，白天23度，晚上18度左右。",
            "湿度 50%，降水概率 100%，出门带伞。",
            "今天 100% 的用户都能听懂。",
            "价格为 12 元(含税)，退换请在 7 天内办理。",
            "OK！就这么定了——明天见。",
            "打折全 50％ 哦，别错过了。",
            "通关率 90％ 以上。",
            "命中率 50 % off 的时间都不到。",
        )
        cases.forEach { body ->
            assertEquals("正常正文必须原样保留: $body", body, XiaozhiReplySanitizer.clean(body))
        }
    }

    // ---- 模板形态的真机兼容(2026-10-06:「上屏正文里还混着模板」的可能成因) ----

    /**
     * 全角百分号 `％`:中文生成里很常见,旧规则只认半角 —— 模板会整段上屏。
     */
    @Test
    fun fullwidth_percent_template_is_removed() {
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("％ get_weather(city=\"上海\"):明天小雨。"),
        )
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("％get_weather 明天小雨。"),
        )
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("明天小雨。\n％ get_weather(city=\"上海\")"),
        )
    }

    /** 带连字符的工具名(`get-weather` / `search-web`):旧规则不认 `-`,整个模板漏网。 */
    @Test
    fun hyphenated_tool_name_is_removed() {
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("% get-weather(city=\"上海\"):明天小雨。"),
        )
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("% search-web(q=\"天气\") 明天小雨。"),
        )
    }

    /**
     * 行中(不在行首)的**裸模板 token**、且**紧跟中文/emoji**:只删 token,正文一个字不少。
     *
     * 真机形状:模板与正文挤在一行且模板不带参数列表 —— 旧规则只认「行首」或「带参数」,
     * 于是 `% get_weather` 原样留在设备屏上。
     */
    @Test
    fun inline_bare_template_glued_to_prose_keeps_the_prose() {
        assertEquals(
            "好的，明天小雨，白天23度。",
            XiaozhiReplySanitizer.clean("好的，% get_weather明天小雨，白天23度。"),
        )
        assertEquals(
            "表情不会被删掉(它不是模板):token 剔干净、正文一个字不少",
            "好的，😊明天小雨，白天23度。",
            XiaozhiReplySanitizer.clean("好的，% get_weather😊明天小雨，白天23度。"),
        )
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("％ get_weather：明天小雨。"),
        )
    }

    /**
     * 句级文本是**不带分隔符拼接**的:模板句末尾的那个 `:` 也必须跟模板一起删掉,
     * 否则设备屏会以 `:明天小雨。` 这种形状开头。
     */
    @Test
    fun colon_after_an_inline_template_is_removed_too() {
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("% get_weather(city=\"上海\"):明天小雨。"),
        )
        assertEquals(
            "明天小雨。",
            XiaozhiReplySanitizer.clean("% get_weather(location=\"上海\", date=\"明天\"):明天小雨。"),
        )
    }
}
