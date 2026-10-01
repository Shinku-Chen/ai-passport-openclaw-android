package com.shinku.aipassport.openclaw.ble

/**
 * 掉线后的重连策略（纯逻辑，便于单测）。
 *
 * 背景：设备关机重启后，旧实现固定「退避 8 秒 + 重新扫描」，用户体感就是"重新连接特别慢"；
 * 而设备重启只需要几秒，按记住的地址直连通常一两次就能接上。策略因此分两段：
 *
 *  - **快路**：前 [DIRECT_RETRY_LIMIT] 次直连上次记住的地址，退避 [DIRECT_RETRY_DELAY_MS]；
 *  - **慢路**：之后固定 [SCAN_RETRY_DELAY_MS] 并改走扫描 —— 设备换了广播地址、
 *    或本机绑定被清除需要重新配对时，只有扫描才能重新发现它。
 */
object LinkRetryPolicy {

    /** 先直连重试的次数上限。 */
    const val DIRECT_RETRY_LIMIT = 3

    /** 快路重连延迟：设备刚重启完，等它把广播/连接能力拉起来通常一两秒够。 */
    const val DIRECT_RETRY_DELAY_MS = 1_500L

    /** 慢路重连延迟：避免高频扫描被 Android 限流（与 ScanRetry 的退避口径一致）。 */
    const val SCAN_RETRY_DELAY_MS = 8_000L

    /** 连接超时：Android 的直连请求本身没有超时，设备不在时会一直挂起。 */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /** 一次重连决定：走直连还是扫描，以及等多久。 */
    data class Decision(val direct: Boolean, val delayMs: Long)

    /**
     * 根据"已经用掉的直连重试次数"决定下一次重连方式。
     *
     * @param directAttempts 本次掉线后已经直连重试过的次数（首次掉线传 0）。
     */
    fun decide(directAttempts: Int): Decision =
        if (directAttempts < DIRECT_RETRY_LIMIT) {
            Decision(direct = true, delayMs = DIRECT_RETRY_DELAY_MS)
        } else {
            Decision(direct = false, delayMs = SCAN_RETRY_DELAY_MS)
        }
}
