package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * raw 回传流的条目类型(纯数据,便于 JVM 单测)。
 *
 * 「body / raw 双路」里的 **raw** 路:给 App 调试视图用,一轮里网关下行的**所有**有信息条目
 * 都按到达顺序收下来,一条都不丢(状态/生命周期/步骤/工具/工具输出/正文/终局/用量)。
 * 设备屏与 TTS 只吃 **body**(见 [ReplyCollector]),raw 绝不下发设备、不喂 TTS。
 */
object RawKind {
    const val STATUS = "status"
    const val LIFECYCLE = "lifecycle"
    const val STEP = "step"
    const val TOOL = "tool"
    const val TOOL_OUTPUT = "tool_output"
    const val ASSISTANT = "assistant"
    const val TERMINAL = "terminal"
    const val USAGE = "usage"

    /**
     * `chat.history` 补正里本轮 assistant 的**文本**消息(见 `ChatHistory.kt`)。
     *
     * 为什么不复用 [ASSISTANT]:流式 [ASSISTANT] 与历史条目在 App 里**都是弱化小字**
     * (正常气泡只留给 body),但两者标签不同(`正文 · 全文` vs `正文(历史) · 全文`),
     * 展示语义不同,必须分开。
     */
    const val HISTORY_ASSISTANT = "history_assistant"

    /** `chat.history` 里本轮的 toolCall(工具调用,不进正文)。 */
    const val HISTORY_TOOL = "history_tool"

    /** `chat.history` 里本轮的 toolResult(工具输出,不进正文,按 200 字截断)。 */
    const val HISTORY_TOOL_OUTPUT = "history_tool_output"
}

/** 标签前缀(用户可见文案;App 调试视图用弱化小字展示)。 */
object RawLabel {
    const val STATUS = "状态 · "
    const val LIFECYCLE = "生命周期 · "
    const val STEP = "步骤 · "
    const val TOOL = "工具 · "
    const val TOOL_OUTPUT = "工具输出 · "
    const val ASSISTANT = "正文 · 全文"
    const val TERMINAL = "终局 · "
    const val USAGE = "用量 · "

    /** `chat.history` 里本轮的 assistant 文本条目(同一轮可能有多条:答案 + 状态话术)。 */
    const val HISTORY_ASSISTANT = "正文(历史) · 全文"

    /** `chat.history` 里本轮的 toolCall 条目(后面拼工具名,如 `工具(历史) · exec`)。 */
    const val HISTORY_TOOL = "工具(历史) · "

    /** `chat.history` 里本轮的 toolResult 条目。 */
    const val HISTORY_TOOL_OUTPUT = "工具输出(历史) · 全文"
}

/** 工具输出 raw 条目保留的最大字符数;超出的部分丢弃并在末尾标注「(截断 N 字)」。 */
const val RAW_TOOL_OUTPUT_MAX_CHARS = 200

/** 「疑似状态话术」标记:只在 App 展示层加,不改写 body 文本,也不拦设备/TTS。 */
const val SUSPECTED_STATUS_TALK_FLAG = "[疑似状态话术]"

/**
 * raw 回传流里的一条(给 App 展示)。
 *
 * @param kind [RawKind] 之一,调用方据此决定展示样式;**raw 条目在 App 里一律弱化小字**
 *   (正常气泡只留给 body,见 `ui/ReplyDisplay.kt`)
 * @param label 可直接展示的弱化标签,例:`步骤 · Exec Fetch Beijing tomorrow weather`
 * @param text 条目正文(工具输出已按 [RAW_TOOL_OUTPUT_MAX_CHARS] 截断并标注)
 * @param seq 帧里的 `payload.seq`(可能为 null)
 * @param ts 帧里的 `payload.ts`(可能为 null)
 */
data class RawEntry(
    val kind: String,
    val label: String,
    val text: String,
    val seq: Int? = null,
    val ts: Long? = null,
)

/**
 * agent lifecycle 帧的解析结果(结束信号与终局回复都在这里)。
 *
 * @param phase `start` / `model` / `finishing` / `end` …
 * @param stopReason 结束原因(仅 finishing/end 有)
 * @param aborted 是否被中断(仅 finishing/end 有)
 * @param terminalReplyText 网关自己标注的终局回复文本(仅 `phase=end` 的 `data.terminalReply.text`)
 * @param terminalDisposition 终局处置:`visible` = 该给用户看,其余(如 hidden)= 不给用户看
 * @param seq 帧里的 `payload.seq`
 */
data class LifecycleEvent(
    val phase: String,
    val stopReason: String? = null,
    val aborted: Boolean? = null,
    val terminalReplyText: String? = null,
    val terminalDisposition: String? = null,
    val seq: Int? = null,
) {
    /** 网关标了「这条终局回复该给用户看」。 */
    val terminalReplyVisible: Boolean get() = terminalDisposition == "visible"
}

/**
 * 从一帧原始 JSON 里解析出 raw 条目(0 或 1 条;lifecycle end 带 terminalReply 时是 2 条)。
 *
 * 覆盖范围(只有 `type=event` 且 `event in (agent, chat)` 的下行帧算「有信息条目」):
 *
 * | kind | 来源 | label |
 * | --- | --- | --- |
 * | `status` | `chat state=status` 的 `phase` / `agent stream=run_status` 的 `data.phase` | `状态 · <phase>` |
 * | `lifecycle` | `agent stream=lifecycle` | `生命周期 · <phase>[(stopReason)]` |
 * | `step` | `agent stream=item` | `步骤 · <title 或 name>` |
 * | `tool` | `agent stream=tool` | `工具 · <name> · <phase>(error)` |
 * | `tool_output` | `agent stream=command_output` | `工具输出 · <title 或 name>` |
 * | `assistant` | `chat state=delta/final`、`agent stream=assistant` | `正文 · 全文` |
 * | `terminal` | lifecycle `phase=end` 的 `data.terminalReply` | `终局 · <disposition>` |
 * | `usage` | `agent stream=usage` | `用量 · <一行摘要>` |
 *
 * 归属过滤与 [replyMessagesOf] 一致:`sessionKey` 必须属于本会话,`runId` 若本轮已知必须一致;
 * 帧里没带该字段时不据此过滤(空串 = 暂不知道)。
 *
 * @return 该帧产生的 raw 条目(按应在 App 中出现的顺序);非信息帧返回空列表
 */
fun rawEntriesOf(frameJson: String, sessionKey: String, runId: String): List<RawEntry> {
    val obj = parseFrame(frameJson) ?: return emptyList()
    if (obj.str("type") != "event") return emptyList()
    val event = obj.str("event") ?: return emptyList()
    val payload = obj.getAsJsonObject("payload") ?: return emptyList()
    if (!matchesOwner(payload, sessionKey, runId)) return emptyList()

    val seq = payload.int("seq")
    val data = payload.getAsJsonObject("data")
    val ts = payload.long("ts") ?: data?.long("ts")

    return when (event) {
        "chat" -> chatRawEntries(payload, seq, ts)
        "agent" -> agentRawEntries(payload, data, seq, ts)
        else -> emptyList()
    }
}

/**
 * 解析 agent lifecycle 帧(归属过滤同 [rawEntriesOf])。
 * @return 非 lifecycle 帧 / 缺 phase / 归属不符时返回 null
 */
fun lifecycleEventOf(frameJson: String, sessionKey: String, runId: String): LifecycleEvent? {
    val obj = parseFrame(frameJson) ?: return null
    if (obj.str("type") != "event" || obj.str("event") != "agent") return null
    val payload = obj.getAsJsonObject("payload") ?: return null
    if (payload.str("stream") != "lifecycle") return null
    if (!matchesOwner(payload, sessionKey, runId)) return null
    val data = payload.getAsJsonObject("data") ?: return null
    val phase = data.str("phase")?.takeIf { it.isNotBlank() } ?: return null

    val terminalReply = data.getAsJsonObject("terminalReply")
    val receipt = data.getAsJsonObject("terminalReceipt")
    return LifecycleEvent(
        phase = phase,
        stopReason = data.str("stopReason")?.takeIf { it.isNotBlank() },
        aborted = data.bool("aborted"),
        terminalReplyText = terminalReply?.str("text"),
        terminalDisposition = terminalReply?.str("disposition")
            ?: receipt?.str("terminalDisposition"),
        seq = payload.int("seq"),
    )
}

/**
 * 工具输出截断规则:不超过 [max] 字原样返回;超出则截到 [max] 字并在末尾标注「(截断 N 字)」,
 * N = 被丢弃的字符数。
 */
fun truncateToolOutput(text: String, max: Int = RAW_TOOL_OUTPUT_MAX_CHARS): String {
    if (max <= 0 || text.length <= max) return text
    return text.take(max) + "(截断 ${text.length - max} 字)"
}

/**
 * 正文是否「疑似状态话术」:网关有时把「已回复完毕/无进行中的任务…」这类状态话术当成终局回复推出来,
 * 这是网关的行为问题。App 侧**不删不拦也不改写**,只在对应条目上加 [SUSPECTED_STATUS_TALK_FLAG] 标记,
 * 方便一眼看出「这不是真正的答案,是网关的状态话术」。
 */
fun looksLikeStatusTalk(text: String): Boolean = STATUS_TALK.containsMatchIn(text)

/** 疑似状态话术(实测话术 + 用户给出的样例;`没有进行中的` 是 `无进行中的` 的自然变体,"无活跃 exec" 中间可能带空格)。 */
private val STATUS_TALK = Regex(
    "已回复完毕|已答复完毕|无待处理事项|" +
        "无进行中的\\s*(?:任务|exec 会话|子代理|子会话)|" +
        "没有进行中的\\s*(?:任务|exec 会话|子代理|子会话)|" +
        "无活跃\\s*(?:exec 会话|子代理)",
)

// ---- 各 event 的条目抽取 ----

private fun chatRawEntries(payload: JsonObject, seq: Int?, ts: Long?): List<RawEntry> =
    when (payload.str("state")) {
        "status" -> {
            val phase = payload.str("phase")?.takeIf { it.isNotBlank() } ?: return emptyList()
            listOf(RawEntry(RawKind.STATUS, RawLabel.STATUS + phase, phase, seq, ts))
        }

        "delta", "final" -> {
            val text = chatTextOf(payload)?.takeIf { it.isNotBlank() } ?: return emptyList()
            listOf(RawEntry(RawKind.ASSISTANT, RawLabel.ASSISTANT, text, seq, ts))
        }

        else -> emptyList()
    }

private fun agentRawEntries(
    payload: JsonObject,
    data: JsonObject?,
    seq: Int?,
    ts: Long?,
): List<RawEntry> {
    val d = data ?: return emptyList()
    return when (payload.str("stream")) {
        "run_status" -> {
            val phase = d.str("phase")?.takeIf { it.isNotBlank() } ?: return emptyList()
            listOf(RawEntry(RawKind.STATUS, RawLabel.STATUS + phase, phase, seq, ts))
        }

        "lifecycle" -> {
            val phase = d.str("phase")?.takeIf { it.isNotBlank() } ?: return emptyList()
            val stopReason = d.str("stopReason")?.takeIf { it.isNotBlank() }
            val aborted = d.bool("aborted")
            val entries = mutableListOf(
                RawEntry(
                    kind = RawKind.LIFECYCLE,
                    label = RawLabel.LIFECYCLE + phase + (stopReason?.let { "($it)" } ?: ""),
                    text = buildString {
                        append("phase=").append(phase)
                        if (stopReason != null) append(" stopReason=").append(stopReason)
                        if (aborted != null) append(" aborted=").append(aborted)
                    },
                    seq = seq,
                    ts = ts,
                ),
            )
            d.getAsJsonObject("terminalReply")?.let { terminal ->
                val disposition = terminal.str("disposition")
                    ?: d.getAsJsonObject("terminalReceipt")?.str("terminalDisposition")
                    ?: "unknown"
                entries += RawEntry(
                    kind = RawKind.TERMINAL,
                    label = RawLabel.TERMINAL + disposition,
                    text = terminal.str("text") ?: "",
                    seq = seq,
                    ts = ts,
                )
            }
            entries
        }

        "item" -> {
            val title = d.str("title")?.takeIf { it.isNotBlank() }
                ?: d.str("name")?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            val status = d.str("status") ?: d.str("phase") ?: ""
            listOf(RawEntry(RawKind.STEP, RawLabel.STEP + title, status, seq, ts))
        }

        "tool" -> {
            val name = d.str("name")?.takeIf { it.isNotBlank() } ?: return emptyList()
            val phase = d.str("phase") ?: ""
            val isError = d.bool("isError") ?: false
            val label = RawLabel.TOOL + name + " · " + phase + if (isError) "(error)" else ""
            val text = buildString {
                append("phase=").append(phase)
                if (isError) append(" isError=true")
            }
            listOf(RawEntry(RawKind.TOOL, label, text, seq, ts))
        }

        "command_output" -> {
            val title = d.str("title")?.takeIf { it.isNotBlank() }
                ?: d.str("name")?.takeIf { it.isNotBlank() }
                ?: "command"
            val output = d.str("output") ?: return emptyList()
            listOf(
                RawEntry(
                    RawKind.TOOL_OUTPUT,
                    RawLabel.TOOL_OUTPUT + title,
                    truncateToolOutput(output),
                    seq,
                    ts,
                ),
            )
        }

        "usage" -> {
            val summary = usageSummary(d)
            if (summary.isEmpty()) return emptyList()
            listOf(RawEntry(RawKind.USAGE, RawLabel.USAGE + summary, summary, seq, ts))
        }

        "assistant" -> {
            val text = d.str("text")?.takeIf { it.isNotBlank() }
                ?: d.str("delta")?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            listOf(RawEntry(RawKind.ASSISTANT, RawLabel.ASSISTANT, text, seq, ts))
        }

        else -> emptyList()
    }
}

/** usage 一行摘要:把 data 里的原始字段按插入顺序拼成 `k=v[, k=v…]`。 */
private fun usageSummary(data: JsonObject): String =
    data.entrySet()
        .mapNotNull { (k, v) ->
            if (v == null || !v.isJsonPrimitive) null else "$k=${v.asString}"
        }
        .joinToString(", ")

// ---- 通用小工具 ----

private fun parseFrame(frameJson: String): JsonObject? = try {
    JsonParser.parseString(frameJson)?.takeIf { it.isJsonObject }?.asJsonObject
} catch (_: Exception) {
    null
}

/** 归属过滤:sessionKey 必须一致;runId 若本轮已知必须一致;帧里没带该字段则不过滤。 */
private fun matchesOwner(payload: JsonObject, sessionKey: String, runId: String): Boolean {
    val frameSession = payload.str("sessionKey")
    if (sessionKey.isNotBlank() && !frameSession.isNullOrBlank() && frameSession != sessionKey) {
        return false
    }
    val frameRun = payload.str("runId")
    if (runId.isNotBlank() && !frameRun.isNullOrBlank() && frameRun != runId) return false
    return true
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.takeIf { it.asJsonPrimitive.isNumber }?.asInt

private fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.takeIf { it.asJsonPrimitive.isNumber }?.asLong

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive }?.takeIf { it.asJsonPrimitive.isBoolean }?.asBoolean
