package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.gateway.RawLabel

/**
 * 一轮对话里**正文气泡**的记账与「历史补正」结算(纯逻辑,不依赖 Android,可 JVM 单测)。
 *
 * 背景(同事上次报告的遗留项):语音路径补正时用 [ConversationStore.replaceById] **就地替换**正文气泡,
 * 文字输入路径(`ChatFragment` 的 typed chat)却是**追加**一个 `[来自历史]` 气泡 —— 一轮里会出现
 * 「`[流式]` + `[来自历史]`」两个正常正文气泡。这里把两条路径共用的规则收在一处:
 * **同一轮只有一个正常正文气泡**,补正让文本就地替换;raw 弱化条目不受影响,照常追加。
 *
 * 两种到达顺序都必须成立(补正回调是异步的,见 `OpenClawGateway.holdCollectorForGrace`):
 *  - **先写流式正文、后到补正**(语音路径的常态):补正就地替换已有的正文气泡;
 *  - **先到补正、后写流式正文**(文字路径的竞态:`chatMulti` 的补正回调可能先于返回值到达):
 *    补正先落地成唯一正常气泡(替换「…」占位气泡),[isCorrected] 置位,后到的流式正文降级成
 *    弱化小字 `正文 · 全文`(不再占正常气泡)。
 */
class BodyBubbles {

    private val ids = ArrayList<Long>()

    @Volatile
    private var corrected = false

    /** 历史补正是否**已经落地**(落地后不能再出现新的正常正文气泡)。 */
    val isCorrected: Boolean get() = corrected

    /** 已写入的正文气泡 id(顺序 = 写入顺序;第一条是正常气泡,其余补正时降级成弱化小字)。 */
    fun bodyIds(): List<Long> = synchronized(this) { ids.toList() }

    /**
     * 登记一条已写入的正文气泡([ConversationStore.Message.label] == null 的才是正文气泡)。
     * 同一 id 重复登记只算一次(否则补正会把「第一条」和「多余气泡」指向同一条)。
     */
    fun onBodyWritten(id: Long) {
        synchronized(this) {
            if (!ids.contains(id)) ids += id
        }
    }

    /**
     * 历史补正到达:标记本轮已补正,并给出「就地替换」还是「新增」。
     *
     * 重复补正(同一轮回调两次)只会再次替换同一条,幂等无害。
     */
    fun onCorrection(): BodyCorrectionPlan = synchronized(this) {
        corrected = true
        if (ids.isEmpty()) {
            BodyCorrectionPlan.Append
        } else {
            BodyCorrectionPlan.ReplaceInPlace(ids.first(), ids.drop(1))
        }
    }
}

/** 「历史补正写进对话列表」的动作(见 [writeBodyCorrection])。 */
sealed interface BodyCorrectionPlan {
    /** 本轮已有正文气泡 → **就地替换**第一条(不新增气泡),其余降级成弱化小字。 */
    data class ReplaceInPlace(val firstId: Long, val extraIds: List<Long>) : BodyCorrectionPlan

    /** 本轮还没有正文气泡(例:流式 body 为空但历史有答案)→ **新增**一条正常气泡。 */
    data object Append : BodyCorrectionPlan
}

/**
 * 把本轮展示项写进 [ConversationStore](第一条复用 [placeholderId] 的「…」占位气泡,其余按顺序追加)。
 *
 * 与语音路径同一套规则:
 *  - `label == null` 的**正文气泡**登记进 [bodies](补正时就地替换它,不新增气泡);
 *  - **历史补正已经落地**([BodyBubbles.isCorrected],即补正回调先于本批展示项到达)时,
 *    后到的**流式正文**不能再占正常气泡 —— 降级成弱化小字 `正文 · 全文` 追加,
 *    占位气泡里保留的仍然是被补正后的正文。这就是「同一轮只有一个正常正文气泡」的保证;
 *  - raw 弱化条目照常追加,只加标签/标记,不改文本。
 *
 * 只依赖线程安全的 [ConversationStore],不碰 View/Context,因此可 JVM 单测。
 *
 * @param placeholderId 本轮开场写入的「…」占位气泡(本轮正文气泡的槽位)
 * @param bodies 本轮正文记账(见 [BodyBubbles])
 * @param source [ConversationStore.SOURCE_TEXT] / [ConversationStore.SOURCE_VOICE]
 */
fun writeReplyDisplay(
    display: List<ReplyDisplayEntry>,
    placeholderId: Long,
    bodies: BodyBubbles,
    source: String,
) {
    display.forEachIndexed { index, entry ->
        // 补正已落地:流式正文不再是「正确正文」,降级成弱化小字(不再产生第二个正常气泡)
        val demote = entry.label == null && bodies.isCorrected
        val label = if (demote) RawLabel.ASSISTANT else entry.label
        // 弱化小字条目允许空文本;正文(body)空文本仍显示「(网关空回复)」
        val text = if (entry.label == null && entry.text.isBlank()) "(网关空回复)" else entry.text
        val flag = if (demote) statusTalkFlag(text) else entry.flag
        if (index == 0 && !demote) {
            // 第一条复用占位气泡:不新增消息,列表里不会出现孤立的「…」
            ConversationStore.replaceById(placeholderId, text, label = label, flag = flag)
            if (label == null) bodies.onBodyWritten(placeholderId)
        } else {
            val id = ConversationStore.add("agent", text, source, label = label, flag = flag)
            if (label == null) bodies.onBodyWritten(id)
        }
    }
}

/**
 * 把[历史补正][plan]写进 [ConversationStore]:
 *
 *  - [BodyCorrectionPlan.ReplaceInPlace]:用 [ConversationStore.replaceById] 覆盖该轮的正文气泡 ——
 *    **就地替换、不新增气泡**,来源标记 [BodySource.HISTORY](`[来自历史]`);同一轮多余的正文气泡
 *    降级成弱化小字([RawLabel.ASSISTANT] = `正文 · 全文`),做到「同一轮只有一个正常正文气泡」。
 *  - [BodyCorrectionPlan.Append]:本轮还没有正文气泡(body 为空但历史有答案)→ 新增一条正常气泡。
 *
 * **raw 弱化条目不受影响**:它们由 [writeReplyDisplay](首批)与迟到增量回调照常追加。
 * 设备屏与 TTS 不经过这里(它们只吃 body 原文)。
 */
fun writeBodyCorrection(
    plan: BodyCorrectionPlan,
    corrected: String,
    source: String,
    bodySource: BodySource = BodySource.HISTORY,
) {
    val flag = bodyFlag(corrected, bodySource)
    when (plan) {
        is BodyCorrectionPlan.ReplaceInPlace -> {
            ConversationStore.replaceById(plan.firstId, corrected, label = null, flag = flag)
            plan.extraIds.forEach { ConversationStore.demoteById(it, RawLabel.ASSISTANT) }
        }

        BodyCorrectionPlan.Append ->
            ConversationStore.add("agent", corrected, source, flag = flag)
    }
}
