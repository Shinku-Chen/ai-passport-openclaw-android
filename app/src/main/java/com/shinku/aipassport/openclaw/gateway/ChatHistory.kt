package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `chat.history` 的 RPC 方法名(实测网关可用;参数 `{sessionKey, limit}`)。
 *
 * 为什么需要它(真机抓帧结论):一轮 run 里可能有**多条** assistant 文本消息
 * (答案 + 后续状态话术),而流式 `chat delta/final` 只推**最后一条** ——
 * 于是设备屏与 TTS 拿到的是「已回复完毕,当前无进行中的 exec 会话…」这类状态话术,
 * 真正的答案只存在于网关的历史里。流式帧不够用,所以要补一次历史查询。
 */
const val RPC_CHAT_HISTORY = "chat.history"

/**
 * 补正时取多少条历史。
 *
 * 只为定位「本轮」的起点(最后一条 user 消息),不是要全部历史:一轮里网关可能产生
 * 十几条消息(答案 + 工具调用 + 工具输出 + 状态话术),20 条足以覆盖一轮。
 */
const val CHAT_HISTORY_LIMIT = 20

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val ROLE_TOOL_RESULT = "toolResult"
private const val PART_TEXT = "text"
private const val PART_TOOL_CALL = "toolCall"

/**
 * 一次「用 chat.history 补正正文」的完整判定结果(纯数据,便于 JVM 单测)。
 *
 * @param assistantTexts 本轮 assistant 的**全部**文本消息(历史顺序,工具消息不进这里)
 * @param correctBody 从 [assistantTexts] 里挑出的「正确正文」;null = 整轮只有状态话术,
 *   调用方保留流式正文(不凭空造)
 * @param sendText 需要**再发一帧 `'A'` 给设备**的正文;null = 不用补发
 *   (整轮只有状态话术 / 挑出的正文与流式正文相同 → 不重复上屏、不重复朗读)
 * @param rawEntries 追加到 App raw 全量视图的历史条目(已与流式条目按文本去重,
 *   内部保持历史顺序;kind/label 见 [RawKind.HISTORY_ASSISTANT] / [RawKind.HISTORY_TOOL]
 *   / [RawKind.HISTORY_TOOL_OUTPUT])
 */
data class HistoryCorrection(
    val assistantTexts: List<String>,
    val correctBody: String?,
    val sendText: String?,
    val rawEntries: List<RawEntry>,
) {
    /** 是否需要用历史正文补发一帧给设备。 */
    val hasCorrection: Boolean get() = !sendText.isNullOrBlank()
}

/**
 * 从 `chat.history` 的 payload 里取出「**本轮**」的 assistant 文本消息(按数组顺序 = 时间序)。
 *
 * 一轮的边界:以**我们发送的用户文本**为准 —— 取历史里最后一条内容与 [userText]
 * 相同(忽略首尾空白)的 user 消息;找不到时退回**历史里最后一条 user 消息**。
 * 只取其后的消息,因此多轮历史里不会把上一轮的答案当成这一轮的正文。
 *
 * - 只收 `role == "assistant"` 且 `content[*].type == "text"` 的文本(同一条消息里的多个
 *   text 片段拼成一条);`toolCall` / `toolResult` 等工具消息**不进正文**;
 * - 空白文本丢弃;每条文本做首尾空白裁剪;
 * - 非 JSON、缺 `messages`、没有 user 消息 → 空列表(调用方回到流式正文,行为不变)。
 *
 * @param historyJson `chat.history` 返回的 payload JSON(`{sessionKey, sessionId, messages:[…]}`)
 * @param userText 本轮实际发给网关的用户文本(含语音附加提示词;空串时只用「最后一条 user 消息」定位)
 */
fun assistantTextsSince(historyJson: String, userText: String): List<String> {
    val messages = historyMessages(historyJson)
    if (messages.isEmpty()) return emptyList()
    val boundary = turnBoundaryIndex(messages, userText)
    if (boundary < 0) return emptyList()
    return messages.drop(boundary + 1)
        .filter { roleOf(it) == ROLE_ASSISTANT }
        .mapNotNull { messageText(it)?.trim()?.takeIf { text -> text.isNotEmpty() } }
}

/**
 * 从本轮 assistant 文本里挑「该给设备 / TTS 的正确正文」。
 *
 * 规则:先排除 [looksLikeStatusTalk] 命中的状态话术,在剩下的里取**最长**的一条
 * (并列取**最早**);一条都没剩(整轮只有状态话术)→ 返回 null,
 * 调用方保留 [streamedBody](不凭空造正文)。
 *
 * @param streamedBody 流式帧已经下发的正文(调用方在返回 null 时保留它;本函数不修改它)
 * @param texts 本轮 assistant 文本(见 [assistantTextsSince]),按历史顺序
 */
fun pickCorrectBody(streamedBody: String, texts: List<String>): String? {
    val candidates = texts.map { it.trim() }.filter { it.isNotEmpty() && !looksLikeStatusTalk(it) }
    if (candidates.isEmpty()) return null
    // 最长优先;并列取最早(严格大于才替换),因此不需要额外排序
    return candidates.reduce { best, next -> if (next.length > best.length) next else best }
}

/**
 * 本轮历史的可展示条目(assistant 正文 + 工具消息),保持历史顺序,并**与流式条目按文本去重**。
 *
 * 只做展示材料,绝不进正文:正文由 [assistantTextsSince] / [pickCorrectBody] 决定。
 * 工具消息的文本按 [RAW_TOOL_OUTPUT_MAX_CHARS] 截断(附 `(截断 N 字)`),
 * 因为工具输出常是几十 KB 的 JSON。
 *
 * 只与 [streamed] 里的条目按文本去重(固定集合):同一轮里的两次同名工具调用
 * (如两次 `exec`)是两条独立条目,不能互相去重。
 *
 * @param streamed 本轮流式帧已经收下的 raw 条目(文本命中即不再重复追加)
 */
fun historyRawEntries(
    historyJson: String,
    userText: String,
    streamed: List<RawEntry>,
): List<RawEntry> {
    val messages = historyMessages(historyJson)
    if (messages.isEmpty()) return emptyList()
    val boundary = turnBoundaryIndex(messages, userText)
    if (boundary < 0) return emptyList()
    // 只与【流式条目】去重(固定集合):同一轮里的两次同名工具调用是两条独立条目,不能互相去重
    val streamedTexts = streamed.map { it.text.trim() }.filter { it.isNotEmpty() }.toSet()
    val entries = mutableListOf<RawEntry>()
    messages.drop(boundary + 1).forEach { message ->
        historyPartsOf(message).forEach { part ->
            val text = part.text.trim()
            if (text.isEmpty() || text in streamedTexts) return@forEach
            entries += RawEntry(kind = part.kind, label = part.label, text = text)
        }
    }
    return entries
}

/**
 * 把上面的判定合成一次补正计划(网关侧只调用这一个入口,单测也可直接断言整条决策)。
 *
 * @param streamedBody 本轮流式已下发的正文(可能多条);补发判定用它与历史正文做 trim 比较
 * @param streamed 本轮流式已收下的 raw 条目(历史条目按文本去重)
 */
fun planHistoryCorrection(
    historyJson: String,
    userText: String,
    streamedBody: List<String>,
    streamed: List<RawEntry>,
): HistoryCorrection {
    val texts = assistantTextsSince(historyJson, userText)
    val correctBody = pickCorrectBody(streamedBody.joinToString("\n"), texts)
    // 「补发」只在不同的时候发生:历史正文与任何一条已下发正文相同 → 不重复上屏、不重复朗读
    val sendText = correctBody?.takeIf { body -> streamedBody.none { sameText(it, body) } }
    return HistoryCorrection(
        assistantTexts = texts,
        correctBody = correctBody,
        sendText = sendText,
        rawEntries = historyRawEntries(historyJson, userText, streamed),
    )
}

/** 两段文本是否是同一段正文(忽略首尾空白)。 */
fun sameText(a: String, b: String): Boolean = a.trim() == b.trim()

// ---- 解析细节 ----

/** 一条历史消息对应的可展示 raw 条目(kind + label + 文本)。 */
private data class HistoryPart(val kind: String, val label: String, val text: String)

/** payload 里的 messages 数组(兼容直接传数组的情形);解析失败返回空列表。 */
private fun historyMessages(historyJson: String): List<JsonObject> {
    val root = try {
        JsonParser.parseString(historyJson)
    } catch (_: Exception) {
        return emptyList()
    }
    val array = when {
        root == null || root.isJsonNull -> return emptyList()
        root.isJsonArray -> root.asJsonArray
        root.isJsonObject -> root.asJsonObject.getAsJsonArray("messages") ?: return emptyList()
        else -> return emptyList()
    }
    return array.mapNotNull { element ->
        element.takeIf { it.isJsonObject }?.asJsonObject
    }
}

/**
 * 「本轮」的边界下标:最后一条内容等于 [userText] 的 user 消息;找不到时用最后一条 user 消息。
 * @return -1 = 历史里根本没有 user 消息(此时不做任何补正,退回流式正文)
 */
private fun turnBoundaryIndex(messages: List<JsonObject>, userText: String): Int {
    val users = messages.withIndex().filter { roleOf(it.value) == ROLE_USER }
    if (users.isEmpty()) return -1
    val wanted = userText.trim()
    if (wanted.isNotEmpty()) {
        users.lastOrNull { sameText(messageText(it.value) ?: "", wanted) }?.let { return it.index }
    }
    return users.last().index
}

private fun roleOf(message: JsonObject): String =
    message.get("role")?.takeIf { it.isJsonPrimitive }?.asString ?: ""

/** 一条消息里的全部文本片段拼成一段(没有文本片段时返回 null)。 */
private fun messageText(message: JsonObject): String? {
    val content = message.get("content") ?: return null
    if (content.isJsonPrimitive) return content.asString
    if (!content.isJsonArray) return null
    val joined = content.asJsonArray.mapNotNull { part ->
        if (!part.isJsonObject) return@mapNotNull null
        val obj = part.asJsonObject
        if (obj.str("type") != PART_TEXT) return@mapNotNull null
        obj.str("text")
    }.joinToString("")
    return joined.takeIf { it.isNotBlank() }
}

/** 一条历史消息产生的可展示条目(按 content 数组顺序;工具消息单独成条,不进正文)。 */
private fun historyPartsOf(message: JsonObject): List<HistoryPart> {
    val role = roleOf(message)
    val content = message.get("content")
    // content 是纯字符串:只能是文本(历史里 assistant 的纯文本正文)
    if (content != null && content.isJsonPrimitive) {
        return if (role == ROLE_ASSISTANT) {
            listOf(HistoryPart(RawKind.HISTORY_ASSISTANT, RawLabel.HISTORY_ASSISTANT, content.asString))
        } else {
            emptyList()
        }
    }
    if (content == null || !content.isJsonArray) return emptyList()

    // toolResult:文本就是工具输出,单独成条并按 200 字截断(常是几十 KB 的 JSON)
    if (role == ROLE_TOOL_RESULT) {
        val output = content.asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { it.str("type") == PART_TEXT }?.str("text")
        }.joinToString("")
        return if (output.isBlank()) emptyList() else {
            listOf(
                HistoryPart(
                    kind = RawKind.HISTORY_TOOL_OUTPUT,
                    label = RawLabel.HISTORY_TOOL_OUTPUT,
                    text = truncateToolOutput(output.trim()),
                ),
            )
        }
    }

    if (role != ROLE_ASSISTANT) return emptyList()
    val parts = mutableListOf<HistoryPart>()
    val textBuffer = StringBuilder()

    /** 把累积的文本片段落成一条正文条目(toolCall 之前的文本自成一条)。 */
    fun flushText() {
        if (textBuffer.isNotBlank()) {
            parts += HistoryPart(
                kind = RawKind.HISTORY_ASSISTANT,
                label = RawLabel.HISTORY_ASSISTANT,
                text = textBuffer.toString(),
            )
        }
        textBuffer.setLength(0)
    }

    content.asJsonArray.forEach { element ->
        if (!element.isJsonObject) return@forEach
        val obj = element.asJsonObject
        when (obj.str("type")) {
            PART_TEXT -> textBuffer.append(obj.str("text") ?: "")
            PART_TOOL_CALL -> {
                flushText()
                val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: "tool"
                // 同名工具一轮里可能调多次:条目正文用命令(带截断)而不是工具名,
                // 否则两条 `exec` 在 App 里完全无法区分
                val command = obj.getAsJsonObject("arguments")?.str("command")?.takeIf { it.isNotBlank() }
                parts += HistoryPart(
                    kind = RawKind.HISTORY_TOOL,
                    label = RawLabel.HISTORY_TOOL + name,
                    text = truncateToolOutput(command ?: name),
                )
            }
        }
    }
    flushText()
    return parts
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
