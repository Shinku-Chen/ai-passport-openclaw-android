package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一帧「网关回复正文」的解析结果(纯数据,便于 JVM 单测)。
 *
 * @param text 该帧携带的正文(全量累计文本;可能为空字符串 —— 例如只有 final 没有正文的结束帧)
 * @param isFinal 是否为 `state=final`(这一条回复结束)
 * @param runId 帧里带的 runId(可能为 null;调用方据此学习/校验本轮 run)
 * @param seq 帧里的 `payload.seq`(可能为 null;body 的范围判定与 raw 条目展示用)
 */
data class ChatMessageEvent(
    val text: String,
    val isFinal: Boolean,
    val runId: String? = null,
    val seq: Int? = null,
)

/**
 * 从一帧原始 JSON 里解析出「正文消息事件」。
 *
 * 真机抓帧结论(见 `app/src/test/resources/gateway/chat-frames.jsonl`,来自一次真实 run):
 *  - **只有** `type=event` + `event=chat` + `state in (delta, final)` 才是回复正文;
 *    `event=chat state=status` 只是进度,`event=agent` 的各 stream(run_status/lifecycle/
 *    item/tool/command_output/usage/assistant)都是执行细节(客户端里的「Fetch fresh
 *    Beijing weather」这类步骤标题就来自 `agent stream=assistant` 的 `data.text`),
 *    **一律不进正文**。
 *  - 文本优先取 `payload.message.content[*].text`(网关的 content 是全量累计文本),
 *    退回 `payload.deltaText`,再退回 `payload.message.text` / 顶层 `text`。
 *  - 归属过滤:帧里带了 `sessionKey` / `runId` 时必须与期望一致,否则丢弃,
 *    避免把别的会话/别的 run 的帧算进本轮。期望值为空串表示「暂不知道」,此时不按该字段过滤
 *    (本轮的首帧就是用来学习 runId 的)。
 *
 * @return 0 或 1 条事件(一帧最多一次正文更新);非正文帧返回空列表。
 */
fun replyMessagesOf(frameJson: String, sessionKey: String, runId: String): List<ChatMessageEvent> {
    val obj = try {
        JsonParser.parseString(frameJson)?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    } ?: return emptyList()

    if (obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "event") return emptyList()
    if (obj.get("event")?.takeIf { it.isJsonPrimitive }?.asString != "chat") return emptyList()

    val payload = obj.getAsJsonObject("payload") ?: return emptyList()

    // 归属过滤:sessionKey 必须是本会话;runId 若本轮已知必须一致。
    val frameSession = payload.str("sessionKey")
    if (sessionKey.isNotBlank() && !frameSession.isNullOrBlank() && frameSession != sessionKey) {
        return emptyList()
    }
    val frameRun = payload.str("runId")
    if (runId.isNotBlank() && !frameRun.isNullOrBlank() && frameRun != runId) {
        return emptyList()
    }

    val state = payload.str("state")
    if (state != "delta" && state != "final") return emptyList()

    val text = chatTextOf(payload) ?: ""
    val isFinal = state == "final"
    // 非 final 帧没有文本 → 没有内容;final 即使没文本也要上报(用于结束这一条消息)
    if (text.isBlank() && !isFinal) return emptyList()
    val seq = payload.get("seq")?.takeIf { it.isJsonPrimitive }?.takeIf { it.asJsonPrimitive.isNumber }?.asInt
    return listOf(ChatMessageEvent(text = text, isFinal = isFinal, runId = frameRun, seq = seq))
}

/**
 * 分段规则(流式累计):把新的正文 [next] 并入已收集的消息列表 [acc]。
 *
 *  - [next] 为空/全空白:忽略(不产生空消息);
 *  - 与最后一条**完全相同**:去重,不新增(delta 与 final 常带同一份全量文本);
 *  - 以最后一条**开头**(是它的累计增长):同一条消息的更新,**就地替换**为更全的 [next];
 *  - 其它:视为**新的一条**回复,追加到末尾。
 *
 * 真机现象(本函数要修的核心问题):一轮里网关可能推多条 assistant 消息,
 * 旧实现每次 `set(full)` 覆盖,后到的状态消息会把前面的天气答案冲掉。
 *
 * @return 列表内容是否发生变化
 */
fun mergeMessages(acc: MutableList<String>, next: String): Boolean {
    if (next.isBlank()) return false
    if (acc.isEmpty()) {
        acc.add(next)
        return true
    }
    val last = acc.last()
    if (next == last) return false
    if (next.startsWith(last)) {
        acc[acc.size - 1] = next
        return true
    }
    acc.add(next)
    return true
}

/** 从 chat payload 里抠正文:`message.content[*].text` → `deltaText` → `message.text` → 顶层 `text`。 */
internal fun chatTextOf(payload: JsonObject): String? {
    payload.getAsJsonObject("message")?.let { m ->
        val content = m.get("content")
        if (content != null) {
            if (content.isJsonArray) {
                val joined = content.asJsonArray.mapNotNull { el ->
                    if (el.isJsonObject) {
                        el.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotBlank() }
                    } else null
                }.joinToString("")
                if (joined.isNotBlank()) return joined
            } else if (content.isJsonPrimitive) {
                content.asString.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        m.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }?.let { return it }
    }
    payload.get("deltaText")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.isNotBlank() }?.let { return it }
    payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
