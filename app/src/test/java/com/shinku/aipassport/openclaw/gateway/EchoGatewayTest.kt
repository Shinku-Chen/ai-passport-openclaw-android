package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EchoGateway 单测:验证本地回显的固定前缀回复与 barge 打断语义。
 * 无网络、无 Android 依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EchoGatewayTest {

    /** 回复 = 固定前缀 + 输入文本。 */
    @Test
    fun chat_returns_prefixed_text() = runTest {
        val gw = EchoGateway()
        assertTrue(gw.connect())
        assertTrue(gw.isReady())
        assertEquals("(本地回显) 你好", gw.chat("你好"))
        assertNull(gw.lastError)
        gw.close()
    }

    /** 打断在途回显时返回 null(供流水线的 turnId 判定丢弃过期回复)。 */
    @Test
    fun interrupt_returns_null_for_inflight_turn() = runTest {
        val gw = EchoGateway(fakeLatencyMs = 1_000)
        var result: String? = "未执行"
        val job = launch { result = gw.chat("你好") }
        // 先让 chat 进入模拟延迟,再打断
        runCurrent()
        gw.interrupt()
        advanceUntilIdle()
        job.join()
        assertNull(result)
    }
}
