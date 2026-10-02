package com.shinku.aipassport.openclaw.service

import com.shinku.aipassport.openclaw.protocol.DeviceFont

/**
 * 网关状态文案（纯逻辑，便于单测）。
 *
 * 这两条会经 [VoiceBridgeService.publishGatewayStatus] → `forwardGatewayState` → 设备侧
 * `gateway.detail` **下发到设备屏底部提示行**，而设备字库只有 GB2312 + ASCII、没有字体回退
 * （见 [DeviceFont]）：
 *  - 分隔符用全角竖线 `｜`(U+FF5C，GB2312 里有)，**不能**用长破折号 `—`(U+2014) ——
 *    后者是中文里最顺手的分隔符，但设备屏上会画成方块（真机同款问题：版本提示里的 `↔`）；
 *  - 同理不能用 `·`(U+00B7)、`✓`、emoji。
 *
 * 单测 [GatewayStatusTextTest] 会把「设备屏能显示」这件事锁住。
 */
object GatewayStatusText {

    /** 重载后重连文案的固定前缀（监控/设备端都按「含『重连』」识别为 connecting）。 */
    const val RELOADED_PREFIX = "网关配置已重载,正在重连…"

    /** 常规重连文案的主体。 */
    const val RECONNECTING = "正在重连…"

    /**
     * 网关配置重载后正在重连。
     *
     * @param reason 本次尝试失败的可读原因（空/空白则不加后缀）—— 只允许拼【本次】原因，
     *   旧原因会让文案看起来像「网关不可达」（见 `GatewayAdapter.clearLastError`）
     */
    fun reloadedReconnecting(reason: String?): String {
        val trimmed = reason?.trim()
        return if (trimmed.isNullOrEmpty()) RELOADED_PREFIX else "$RELOADED_PREFIX ｜ $trimmed"
    }

    /** 网关因 [reason] 正在重连（连接监控的常规播报）。 */
    fun reconnecting(reason: String): String = "${reason.trim()} ｜ $RECONNECTING"
}
