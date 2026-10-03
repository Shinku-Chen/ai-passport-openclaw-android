package com.shinku.aipassport.openclaw.stt

/**
 * 小智**上屏正文**的清洗器(纯函数:不碰 Android/网络/时间,可 JVM 单测;见
 * `docs/design/xiaozhi-ai-gateway.md` §6)。
 *
 * 为什么需要它:小智服务端的应答里可能夹着**工具调用模板的残留** —— 真机取证里出现过
 * `% get_weather…`(声明 MCP 能力后服务端走带工具调用的应答路径),这类文本不是给用户看的正文,
 * 直接上屏就是设备屏上多出一串看不懂的符号(整段缓冲上屏后更明显:一条气泡里混进模板)。
 *
 * 清洗规则(**白名单式**:只删能明确判定为模板/占位的东西,绝不碰正文里的标点):
 *  1. **XML 工具块**:`<tool_call>…</tool_call>` / `<tool_result>…</tool_result>` /
 *     `<function_call>…</function_call>` / `<invoke>…</invoke>`(含复数形式)整块删除(大小写不敏感、
 *     跨行)。判定依据:**闭合标签必须与开启标签同名**(正则反向引用)—— 只有成对出现才敢整块删;
 *  2. **残留的孤立标签**:同上那些标签名单独出现(开启/闭合/自闭合)时只删标签本身,
 *     **保留标签内的文本** —— 服务端忘写闭合标签时,宁可留下可读内容,也不冒「把整段正文删光」的风险;
 *  3. **`%` 工具模板**:整行只有 `% 工具名` 或 `% 工具名(…)` / `% 工具名{…}`(如
 *     `% get_weather(location="北京")`)→ 删掉整行;行内嵌的 `% 工具名(…)` / `% 工具名{…}`
 *     (带参数列表,**参数里允许一层嵌套**,如 `location="上海(浦东)"`)也删除(连同两侧空白);
 *     行首的**裸模板 token**(`% 工具名` 后面还跟着正文,如 `% get_weather 明天小雨`)只删 token 本身、
 *     **保留**同一行的正文。
 *     **判定依据是「`%` + ASCII 标识符 + 参数列表」或「整行只剩这个模板」** —— `50%`、
 *     `100% 的用户` 这类正文里的百分号不受影响(前面是数字、后面不是 ASCII 标识符);
 *     行内形态**必须带参数列表**(或位于行首)才删:一行里 `正文…% 模板(…)` 时只删模板本身。
 *  4. **占位符**:`{{…}}`、`${…}`(含跨行)删除(连同两侧空白,不留空格);
 *  5. **多余空白**:把连续空格/制表符压成一个空格、删掉**中文标点旁边**的 ASCII 空格
 *     (占位符被移除后常留下这种空格)、去掉行尾空白、连续换行压成一个换行、整体 trim。
 *     只动空白,**不动任何标点与文字**(中文标点一个都不会被删/被改)。
 *
 * 不做的事:不改写正文、不补标点、不做敏感词过滤、不截断长度 —— 上屏长度仍由既有规则负责
 * (`VbFrame.TEXT_PAYLOAD_MAX` = 2048B 分片,见 [com.shinku.aipassport.openclaw.protocol.splitTextPayload])。
 *
 * 清洗后可能变成空串(整条回复只有模板/占位):调用方([XiaozhiReplyText])把它当作
 * 「本轮没有可上屏正文」交出 —— 与「只有 emoji」同一条可读原因路径,不产生空气泡。
 * 注意**清洗后非空不等于可上屏**:emoji 不会被清洗删掉,所以要由 [isDisplayable] 判定
 * (至少有一个字母/数字),纯表情的回复同样按「没有可上屏正文」处理。
 */
object XiaozhiReplySanitizer {

    /** 文本入口:**任何**正文(含空串)都可以安全地过一遍;返回值可能为空串(全被清掉时)。 */
    fun clean(raw: String): String {
        if (raw.isEmpty()) return ""
        var text = raw
        text = TOOL_BLOCK.replace(text, "")
        text = TOOL_TAG.replace(text, "")
        text = PERCENT_LINE.replace(text, "")
        text = PERCENT_CALL.replace(text, "")
        text = PERCENT_BARE_LINE.replace(text, "")
        text = MUSTACHE.replace(text, "")
        text = DOLLAR_PLACEHOLDER.replace(text, "")
        return tidy(text)
    }

    /**
     * 清洗后的正文是否**真的有可读文字**(至少一个汉字/字母/数字)。
     *
     * 为什么需要它:调用方([XiaozhiReplyText])要用它区分「本轮有可上屏正文」与「本轮只有表情/
     * 模板/空白」。真机出现过「设备屏上只有一个 emoji(显示为方块)」,而把 emoji 当正文上屏正是要修的
     * bug;反过来 `clean` 之后**非空**并不等于**可上屏**(emoji 不会被删,所以必须单独判定)。
     *
     * 判定只认 Unicode 的字母与十进制数字(`Char.isLetterOrDigit`):emoji 属符号类
     * (`So`),标点/空白也不算 —— 纯符号/纯标点的回复按「没有可上屏正文」处理(交可读原因,
     * 不上屏方块)。
     */
    fun isDisplayable(body: String): Boolean = body.any { it.isLetterOrDigit() }

    /**
     * 成对工具块:`<tag …>…</tag>`(同名闭合标签才算数)。
     *
     * 为什么用反向引用而不是「删到下一个 `</…>`」:服务端可能推多个并列块,按名字配对才不会
     * 把「第一个块的开标签 + 第二个块的闭标签」之间的一大段正文一起删掉。
     */
    private val TOOL_BLOCK = Regex(
        "[ \\t]*<\\s*(tool_call|tool_calls|tool_result|tool_results|function_call|function_calls|invoke)\\b[^>]*>" +
            ".*?<\\s*/\\s*\\1\\s*>[ \\t]*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 同名的孤立标签(含开/闭/自闭合):只删标签,保留块内文本。 */
    private val TOOL_TAG = Regex(
        "</?\\s*(tool_call|tool_calls|tool_result|tool_results|function_call|function_calls|invoke)\\b[^>]*>",
        setOf(RegexOption.IGNORE_CASE),
    )

    /**
     * 整行工具模板:整行**只有** `% 工具名`(或带参数列表的 `% 工具名(…)` / `% 工具名{…}`),
     * 可有前导/行尾空白。
     *
     * 判定依据:ASCII 正文里 `%` 开头且整行只有「标识符 + 可选参数」的一行不是自然语言,
     * 而是服务端的工具调用模板(真机日志里就是 `% get_weather…`)。
     * 为什么要求「整行只剩模板」:同一行 `% get_weather(city="北京")查一下天气吧。` 里
     * 后半句是**正常正文** —— 这种情况下由 [PERCENT_CALL] 只删模板本身,不删整行。
     * 参数部分用贪婪的 `.*`:整行形态本来就以行尾为界,不必再对括号做嵌套限制(模板里的
     * `location="上海(浦东)"` 这类嵌套参数也能整行删干净)。
     */
    private val PERCENT_LINE = Regex(
        "(?m)^[ \\t]*%[ \\t]*[A-Za-z_][A-Za-z0-9_.]*(?:[ \\t]*\\(.*\\)|[ \\t]*\\{.*\\})?[ \\t]*$",
    )

    /**
     * 工具模板的参数列表:圆括号或花括号各一种形态,**允许一层嵌套**
     * (`% get_weather(location="上海(浦东)")`)。
     *
     * 为什么允许一层嵌套:真机的参数值里可能出现括号(地名/括号补充),只认 `[^()]*` 会让整个模板
     * 匹配不上而把 `% get_weather(…)` 原样留在屏上 —— 这正是清洗要消灭的观感。
     */
    private const val ARGS =
        "\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)|\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}"

    /**
     * 行内工具调用:必须带参数列表(`(…)` 或 `{…}`)才删(连同两侧空白)。
     *
     * 为什么要求参数列表:这样 `50% 的用户` 这种正文里的百分号不会被误删(后面没有标识符+参数表);
     * 而 `% get_weather(location="北京")` 这种明确的调用形态一定被删干净(含参数)。
     */
    private val PERCENT_CALL = Regex(
        "[ \\t]*%[ \\t]*[A-Za-z_][A-Za-z0-9_.]*[ \\t]*(?:" + ARGS + ")[ \\t]*",
    )

    /**
     * 行首的**裸模板 token**:`% 工具名` 后面还接着正文时(如 `% get_weather 明天上海小雨`),
     * 只删 `% 工具名` 这一段,同一行的正文**保留**。
     *
     * 为什么只认行首:带参数的行内模板已由 [PERCENT_CALL] 删干净;而 `% 工具名` 这种**没有参数列表**
     * 的形态只有出现在行首才是模板的信号 —— 正文里 `100% 的用户`(前面是数字)、`成功率 50%`
     * (`%` 前面不是行首)都不受影响。
     */
    private val PERCENT_BARE_LINE = Regex("(?m)^[ \\t]*%[ \\t]*[A-Za-z_][A-Za-z0-9_.]*[ \\t]*")

    /** 双花括号占位:`{{…}}`(跨行;连同两侧空白一起删,不留多余空格)。 */
    private val MUSTACHE = Regex("[ \\t]*\\{\\{.*?\\}\\}[ \\t]*", setOf(RegexOption.DOT_MATCHES_ALL))

    /** 美元花括号占位:`${…}`(跨行;同样连同两侧空白一起删)。 */
    private val DOLLAR_PLACEHOLDER = Regex("[ \\t]*\\$\\{.*?\\}[ \\t]*", setOf(RegexOption.DOT_MATCHES_ALL))

    /** 连续空格/制表符 → 一个空格(只处理 ASCII 空白,全角空格/中文排版符号一律不动)。 */
    private val HORIZONTAL_RUN = Regex("[ \\t]+")

    /** 行首/行尾的空格与制表符(删块后常留一串)。 */
    private val SPACES_AROUND_NEWLINE = Regex("[ \\t]*\\n[ \\t]*")

    /** 2 个以上连续换行 → 一个换行(删掉整行模板后不留空行;设备气泡按行换行,空行只是噪声)。 */
    private val BLANK_LINE_RUN = Regex("\\n{2,}")

    /** 中文标点**前面**的 ASCII 空格(占位符被移除后留下的那种:「你好 ${name}，」→「你好 ，」)。 */
    private val SPACE_BEFORE_CJK_PUNCT = Regex("[ \\t]+(?=[，。！？；：、）》」』”’…—])")

    /** 中文标点**后面**的 ASCII 空格(同上,占位符在标点后时留下)。 */
    private val SPACE_AFTER_CJK_PUNCT = Regex("(?<=[（《「『“‘，。！？；：、])[ \\t]+")

    /** 空白归一(只动空白字符,不动标点与文字)。 */
    private fun tidy(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(HORIZONTAL_RUN, " ")
        .replace(SPACES_AROUND_NEWLINE, "\n")
        .replace(BLANK_LINE_RUN, "\n")
        .replace(SPACE_BEFORE_CJK_PUNCT, "")
        .replace(SPACE_AFTER_CJK_PUNCT, "")
        .trim()
}
