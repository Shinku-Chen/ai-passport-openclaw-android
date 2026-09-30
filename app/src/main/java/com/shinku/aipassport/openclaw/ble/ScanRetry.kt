package com.shinku.aipassport.openclaw.ble

/**
 * BLE 扫描失败后的重试策略(纯逻辑,便于 JVM 单测)。
 *
 * 背景:Android `ScanCallback.onScanFailed` 的常见错误码——
 *  - **1 = SCAN_FAILED_ALREADY_STARTED**:同 filters、同 callback 的扫描仍在进行。
 *    真机成因多是"上一轮扫描没被停掉就再次 startScan"(例如 `rescan()` 直接 startScan),
 *    此时**不是致命错误**:应为 `[needsStopScan] = true` 先 `stopScan()` 清掉残留,再短退避重试。
 *    旧实现不看错误码,一律退避 8s 再 startScan → 每次都是 code=1 → **自锁死循环**,
 *    真机表现为"设备断开后永远扫不到,必须重启 App"。
 *  - 2/3/4 等(注册失败/内部错误/不支持):长退避,并把原因透出给用户。
 */
object ScanRetry {

    /** Android ScanCallback 的错误码:`SCAN_FAILED_ALREADY_STARTED`。 */
    const val ERROR_ALREADY_STARTED = 1

    /** code=1 时的短退避:停扫 → 稍等 → 重扫。 */
    const val RETRY_ALREADY_STARTED_MS = 1_500L

    /** code=1 连续超过该次数后改用长退避(避免极端情况下打转)。 */
    const val MAX_ALREADY_STARTED_RETRIES = 5

    /** 其它错误码与超限后的长退避。 */
    const val RETRY_OTHER_MS = 8_000L

    /** 一次扫描失败该怎么处理。 */
    data class Decision(
        /** 重试前是否需要先 stopScan():code=1 必须停,否则下一次还是 ALREADY_STARTED。 */
        val needsStopScan: Boolean,
        /** 重试延迟;null = 不再自动重试(等用户手动点"扫描")。 */
        val retryDelayMs: Long?,
    )

    /**
     * @param errorCode [ScanCallback.onScanFailed] 的错误码
     * @param alreadyStartedAttempts 本轮里 code=1 已经连续发生的次数(本次之前)
     */
    fun decide(errorCode: Int, alreadyStartedAttempts: Int): Decision =
        if (errorCode == ERROR_ALREADY_STARTED) {
            // 先停扫再短退避重试;连续超限则拉长间隔(仍重试,不放弃:用户可能就是按了扫描)
            Decision(
                needsStopScan = true,
                retryDelayMs = if (alreadyStartedAttempts < MAX_ALREADY_STARTED_RETRIES) {
                    RETRY_ALREADY_STARTED_MS
                } else {
                    RETRY_OTHER_MS
                },
            )
        } else {
            Decision(needsStopScan = true, retryDelayMs = RETRY_OTHER_MS)
        }

    /** 该错误码是否需要向用户透出(ALREADY_STARTED 是自愈问题,不打扰用户)。 */
    fun shouldSurfaceToUser(errorCode: Int, alreadyStartedAttempts: Int): Boolean =
        errorCode != ERROR_ALREADY_STARTED || alreadyStartedAttempts >= MAX_ALREADY_STARTED_RETRIES
}
