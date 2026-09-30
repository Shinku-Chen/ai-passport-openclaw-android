package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.delay

/**
 * 本地回显网关:不依赖任何外部服务,用于无网关环境下联调 BLE 链路、STT、TTS 与设备上屏。
 *
 * 行为:chat 返回「固定前缀 + 输入文本」,connect 恒成功,永不报错。
 * 设置页把网关类型选为 `echo` 即启用(见 docs/gateway-adapters.md)。
 */
class EchoGateway(
    /** 回复前缀,便于肉眼区分这是本地回显而非真实模型输出。 */
    private val prefix: String = "(本地回显) ",
    /** 模拟「思考」耗时,验证 barge 打断与状态流转;默认 300ms 足够短,不影响体验。 */
    private val fakeLatencyMs: Long = 300,
) : GatewayAdapter {

    @Volatile
    private var interrupted = false

    override val lastError: String? = null

    override suspend fun connect(): Boolean = true

    override fun isReady(): Boolean = true

    override suspend fun chat(text: String): String? {
        interrupted = false
        // 可被打断的等待:interrupt() 不取消协程,但这里主动检测打断标志
        val step = 50L
        var waited = 0L
        while (waited < fakeLatencyMs) {
            if (interrupted) return null
            delay(step)
            waited += step
        }
        if (interrupted) return null
        return prefix + text
    }

    override fun interrupt() {
        interrupted = true
    }

    override fun close() {
        interrupted = true
    }
}
