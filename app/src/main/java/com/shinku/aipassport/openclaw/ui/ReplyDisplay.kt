package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.gateway.RawEntry
import com.shinku.aipassport.openclaw.gateway.SUSPECTED_STATUS_TALK_FLAG
import com.shinku.aipassport.openclaw.gateway.looksLikeStatusTalk

/**
 * 正文(body)气泡的**来源标记**:正文是直接来自流式 body,还是经 `chat.history` 补正过。
 *
 * 只在 App 展示层用;**设备屏与 TTS 永远不带标记**(它们只吃 body 原文)。
 */
enum class BodySource(val flag: String) {
    /** 未补正:正文直接来自流式 `chat delta/final`。 */
    STREAM("[流式]"),

    /** 经 `chat.history` 补正后的正文(流式帧只推了状态话术时的真答案)。 */
    HISTORY("[来自历史]"),
}

/**
 * App 对话列表里的一条展示项(纯数据,不依赖 Android,可 JVM 单测)。
 *
 * @param text 条目正文
 * @param label 非 null = **弱化小字**样式(raw 调试条目的标签,如 `步骤 · Exec Fetch …`);
 *   null = **正常气泡**样式(body 正文)
 * @param flag 条目上的标记;**只加标记,不改 [text]**。body 条目 = 来源标记([BodySource.flag])
 *   在前,「疑似状态话术」在后(两者可同时存在);raw 条目只有「疑似状态话术」。
 */
data class ReplyDisplayEntry(
    val text: String,
    val label: String? = null,
    val flag: String? = null,
)

/**
 * 把一轮回复映射成 App 对话列表要展示的条目(body / raw 双路)。
 *
 * - **body 条目排在最前**,用**正常气泡**样式(`label = null`),并在 [ReplyDisplayEntry.flag]
 *   上带来源标记([bodySource]:`[流式]` / `[来自历史]`)。
 * - `showRaw = true`(设置页「App 显示完整回传流(调试)」开启,默认开)时,body 之后按到达顺序
 *   展示 **raw 全部条目**,一条都不丢;**raw 条目一律弱化小字 + 标签**(`label = entry.label`),
 *   包括 `kind == assistant` 的 `正文 · 全文` 与 `kind == terminal` 的终局条目 ——
 *   正常气泡**只留给 body**(含补正后的正文),否则「同一答案」会出现两条正常气泡。
 * - `showRaw = false`:只展示 body(全部正常气泡)。
 *
 * 「疑似状态话术」只在对应条目上加 [SUSPECTED_STATUS_TALK_FLAG] 标记,**不删、不拦、不改写**;
 * 设备屏与 TTS 始终只用 body,不带标记。
 *
 * @param bodySource 本轮 body 的来源标记([BodySource.STREAM] / [BodySource.HISTORY])
 */
fun replyDisplayEntries(
    body: List<String>,
    raw: List<RawEntry>,
    showRaw: Boolean,
    bodySource: BodySource = BodySource.STREAM,
): List<ReplyDisplayEntry> {
    val bodyEntries = body.map { text ->
        ReplyDisplayEntry(text = text, label = null, flag = bodyFlag(text, bodySource))
    }
    // 关闭开关、或该通道本来就没有 raw(Hermes / Echo / 自定义 OpenAI 兼容)→ 只展示 body
    if (!showRaw || raw.isEmpty()) return bodyEntries
    // raw 一律弱化小字 + 标签:正常气泡只留给 body
    return bodyEntries + raw.map { entry ->
        ReplyDisplayEntry(text = entry.text, label = entry.label, flag = statusTalkFlag(entry.text))
    }
}

/** 正文气泡的标记:来源标记在前,「疑似状态话术」在后(两者可同时存在)。 */
fun bodyFlag(text: String, source: BodySource): String =
    statusTalkFlag(text)?.let { source.flag + it } ?: source.flag

/**
 * 本轮正文的来源标记(展示在 App 的正文气泡上)。
 * @param corrected 本轮正文是否来自 `chat.history` 补正
 */
fun bodySourceOf(corrected: Boolean): BodySource =
    if (corrected) BodySource.HISTORY else BodySource.STREAM

/** 「疑似状态话术」标记(命中才返回非 null);只在展示层加,不改文本。 */
fun statusTalkFlag(text: String): String? =
    if (looksLikeStatusTalk(text)) SUSPECTED_STATUS_TALK_FLAG else null
