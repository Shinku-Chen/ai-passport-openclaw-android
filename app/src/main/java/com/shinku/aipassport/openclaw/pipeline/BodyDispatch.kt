package com.shinku.aipassport.openclaw.pipeline

import com.shinku.aipassport.openclaw.gateway.looksLikeStatusTalk

/**
 * 一段正文(body)下发给设备的动作(纯数据,便于 JVM 单测;见 [bodyDispatch])。
 */
sealed interface BodyAction {
    /** 本动作要下发的正文。 */
    val text: String

    /** 立即下发:正文不是状态话术(行为与改造前一致,时延不增加)。 */
    data class SendNow(override val text: String) : BodyAction

    /** 先缓发:正文命中「疑似状态话术」,不下发设备,等历史补正/宽限窗给出结论。 */
    data class Hold(override val text: String) : BodyAction

    /** 用历史补正后的正文替换流式正文下发(设备屏只留真答案)。 */
    data class ReplaceWithCorrected(override val text: String) : BodyAction

    /** 历史没有更好的正文:补发缓存的流式 body(设备不能空着)。 */
    data class SendHeld(override val text: String) : BodyAction
}

/**
 * 「一段正文该不该发给设备」的纯决策函数(不依赖 Android / 协程,可 JVM 单测)。
 *
 * 背景(真机现象):流式 `chat delta/final` 只推本轮**最后一条** assistant 消息,常常是
 * 「已回复完毕,当前无进行中的 exec 会话或子代理」这类**状态话术**;真正的答案只在
 * `chat.history` 里(见 `ChatHistory.kt`)。状态话术不该出现在设备屏上,但历史判定要等
 * 终局宽限窗,所以**先缓发、判定后再决定**;非状态话术的正文行为完全不变(立即下发)。
 *
 * 判定点与返回:
 *  - **刚拿到流式 body**(`correctedAvailable = false`):
 *    - 命中状态话术且 [historyCorrectionSupported] → [BodyAction.Hold](不下发,等判定);
 *    - 否则 → [BodyAction.SendNow](立即下发,时延不增加;不支持历史补正的通道也不会被拖慢)。
 *  - **历史判定完成**(`correctedAvailable = true`):
 *    - 有补正正文 → [BodyAction.ReplaceWithCorrected](只发历史答案);
 *    - 没有补正正文 + 流式是状态话术 → [BodyAction.SendHeld](补发缓存,设备不能空着);
 *    - 没有补正正文 + 流式本来就不是状态话术 → [BodyAction.SendNow](正文早已下发,幂等无害)。
 *
 * @param streamedBody 本轮流式 body(单条)
 * @param correctedBody `chat.history` 补正出的正文;null/空白 = 历史没有更好的正文
 * @param correctedAvailable 历史判定是否已经有结论(false = 还在等宽限窗)
 * @param historyCorrectionSupported 本通道是否支持「用 `chat.history` 补正正文」
 *   ([com.shinku.aipassport.openclaw.gateway.GatewayAdapter.supportsBodyCorrection]);
 *   不支持的通道**绝不缓发**(否则没人来给出结论,设备会空着)
 */
fun bodyDispatch(
    streamedBody: String,
    correctedBody: String?,
    correctedAvailable: Boolean,
    historyCorrectionSupported: Boolean = true,
): BodyAction = when {
    // 历史补正给出更好的正文 → 只发补正后的正文(设备屏只有真答案)
    correctedAvailable && !correctedBody.isNullOrBlank() ->
        BodyAction.ReplaceWithCorrected(correctedBody)

    // 非状态话术 / 通道不支持历史补正 → 立即下发(行为不变)
    !historyCorrectionSupported || !looksLikeStatusTalk(streamedBody) ->
        BodyAction.SendNow(streamedBody)

    // 历史已判定但没给出更好的正文 → 补发缓存的流式 body(设备不能空着)
    correctedAvailable -> BodyAction.SendHeld(streamedBody)

    // 状态话术 + 还没判定 → 先缓发
    else -> BodyAction.Hold(streamedBody)
}
