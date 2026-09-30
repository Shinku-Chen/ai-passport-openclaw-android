package com.shinku.aipassport.openclaw.gateway

/**
 * 重连指数退避:2s → 4s → 8s → 16s → 30s(封顶)。
 *
 * 为什么需要:连接监控原来固定每 6s 就调一次 `connect()`,
 * 网关不可达时会无限次「新建 socket → 立刻失败 → 6s 后再来一次」,
 * 既刷屏日志又把正在收集的回复打断。退避后失败次数越多间隔越长,恢复成功后 [reset] 回到 2s。
 *
 * 纯逻辑,无 Android / 网络依赖,便于 JVM 单测验证退避序列。
 */
class ReconnectBackoff(
    private val initialMs: Long = DEFAULT_INITIAL_MS,
    private val maxMs: Long = DEFAULT_MAX_MS,
    private val multiplier: Int = 2,
) {

    /** 已经排队过的重试次数(日志里「第 N 次重试」用它)。 */
    var attempts: Int = 0
        private set

    /** 取下一次重试前的等待时长并推进计数。 */
    fun nextDelayMs(): Long {
        var delay = initialMs
        var i = 0
        // 逐级翻倍直到封顶;attempts 只在末尾自增,循环条件用 i 而不是 attempts
        while (i < attempts && delay < maxMs) {
            delay *= multiplier
            i++
        }
        attempts++
        return delay.coerceAtMost(maxMs)
    }

    /** 连接恢复后清零,下次断开重新从 2s 起退避。 */
    fun reset() {
        attempts = 0
    }

    companion object {
        const val DEFAULT_INITIAL_MS = 2_000L
        const val DEFAULT_MAX_MS = 30_000L
    }
}
