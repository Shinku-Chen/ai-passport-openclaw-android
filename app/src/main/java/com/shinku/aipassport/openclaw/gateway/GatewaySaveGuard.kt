package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 保存网关设置前的连接校验闸门。
 *
 * 语义:用【草稿配置】建的适配器先探活(OpenClaw=WS connect 鉴权,Hermes=GET /health,Echo=恒通过),
 * 只有探活成功才执行 [validateAndPersist] 的 persist 回调落盘;失败一个字段都不写,
 * 原配置继续生效,并把可读失败原因返回给调用方展示。
 *
 * 「等待网关授权」(设备没在网关被批准,见 [GatewayAdapter.isAwaitingPairing])【不】算配置错误:
 * 校验结果单独区分成 [SaveValidation.AwaitingPairing],调用方据此自动重试(设置页 5s/次、最长 180s),
 * 而【不是】弹“保存失败”让用户去改本来正确的配置。但它同样绝不落盘 —— 校验不通过绝不落盘。
 *
 * 之所以单独成类:不依赖 Android 运行时,可以在 JVM 单测里用 MockWebServer
 * 直接验证「401/403/404/连接拒绝/超时 → 不落盘且 lastError 可读」与「成功 → 落盘」。
 */
object GatewaySaveGuard {

    /**
     * 校验超时(毫秒)。
     * 比适配器自身的超时略长,让适配器先给出更具体的原因(如「HTTP 401」「连接被拒绝」),
     * 本超时只作为兜底,防止界面上的「校验中…」无限期卡住。
     */
    const val VALIDATE_TIMEOUT_MS = 20_000L

    /** 等待授权但适配器没给原因时的兜底文案。 */
    const val AWAITING_PAIRING_FALLBACK_REASON = "$AWAITING_PAIRING_PREFIX$AWAITING_PAIRING_HINT"

    /**
     * 一次连接校验的结果。除 [Ok] 外都代表「未落盘」。
     *
     * 分三态(而不是一个可空字符串)的目的:调用方要区分「继续等授权」与「配置错了」——
     * 前者自动重试,后者立刻把原因给用户。靠匹配文案不可靠。
     */
    sealed interface SaveValidation {
        /** 校验通过(可以落盘)。 */
        data object Ok : SaveValidation

        /** 连接本身没问题,只是设备还没在网关被批准:可自动重试,不是配置错误。 */
        data class AwaitingPairing(val reason: String) : SaveValidation

        /** 真正的失败(配置错/网络不通/鉴权失败):重试无意义,交给用户改。 */
        data class Failed(val reason: String) : SaveValidation
    }

    /**
     * 只校验不落盘。调用方拿结果自行决定落盘时机(设置页要在多次重试后落盘)。
     *
     * @return [SaveValidation.Ok] / [SaveValidation.AwaitingPairing] / [SaveValidation.Failed]
     */
    suspend fun validate(
        adapter: GatewayAdapter,
        timeoutMs: Long = VALIDATE_TIMEOUT_MS,
    ): SaveValidation {
        // 标记探活是否在超时前自然返回;false 说明是本方法的兜底超时生效
        var finished = false
        val ok = try {
            withTimeoutOrNull(timeoutMs) {
                adapter.connect().also { finished = true }
            } == true
        } catch (e: CancellationException) {
            // 协程被取消(页面销毁):交给上层处理,不能落盘
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) return SaveValidation.Ok
        // 等待授权优先于超时判定:授权提示比「超时」更能说明用户该做什么
        if (adapter.isAwaitingPairing) {
            return SaveValidation.AwaitingPairing(
                adapter.lastError?.takeIf { it.isNotBlank() } ?: AWAITING_PAIRING_FALLBACK_REASON,
            )
        }
        if (!finished) return SaveValidation.Failed("连接校验超时:${describeTimeout(timeoutMs)}内未完成连接或鉴权")
        // 优先展示适配器给出的具体原因;它为空时再给兜底文案
        return SaveValidation.Failed(
            adapter.lastError ?: "连接校验失败(未取得失败原因,请检查网关配置)",
        )
    }

    /**
     * 先校验再落盘。
     *
     * @param adapter 用草稿配置构造好的适配器(调用方负责在结束后 close)
     * @param timeoutMs 校验超时
     * @param persist 校验通过后执行的落盘动作
     * @return null = 校验通过且已落盘;非 null = 可读失败原因(未落盘,等待授权也【不】落盘)
     */
    suspend fun validateAndPersist(
        adapter: GatewayAdapter,
        timeoutMs: Long = VALIDATE_TIMEOUT_MS,
        persist: () -> Unit,
    ): String? = when (val result = validate(adapter, timeoutMs)) {
        is SaveValidation.Ok -> {
            persist()
            null
        }

        is SaveValidation.AwaitingPairing -> result.reason
        is SaveValidation.Failed -> result.reason
    }

    /** 超时文案:>=1 秒用秒,否则用毫秒(便于单测用短超时)。 */
    private fun describeTimeout(timeoutMs: Long): String =
        if (timeoutMs >= 1000) "${timeoutMs / 1000} 秒" else "${timeoutMs} 毫秒"
}
