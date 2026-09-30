package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GatewaySaveGuard 的 JVM 单测:验证「保存网关设置」先校验后落盘的闸门语义。
 *
 * 覆盖:401 / 404 / 连接拒绝 / 超时 → 不执行落盘且 lastError 可读;成功 → 恰好落盘一次。
 * 用 MockWebServer 模拟 Hermes 的 GET /health 探活,不依赖真设备与真网关。
 */
class GatewaySaveGuardTest {

    private lateinit var server: MockWebServer

    /** 记录落盘回调是否被调用;用它等价证明「校验失败不写 SharedPreferences」。 */
    private var persisted = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        persisted = false
    }

    @After
    fun tearDown() {
        // 连接拒绝用例里已主动 shutdown,重复 shutdown 会抛异常,这里兜住
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun config(
        port: Int = server.port,
        readTimeoutMs: Long = 3_000,
    ) = HermesConfig(
        host = server.hostName,
        port = port,
        useTls = false,
        token = "test-key",
        conversation = "ai-passport-test",
        connectTimeoutMs = 1_500,
        readTimeoutMs = readTimeoutMs,
        streamReadTimeoutMs = readTimeoutMs,
    )

    /** 校验:失败 → 返回可读原因且未落盘。 */
    private fun assertRejected(reason: String?, keyword: String) {
        assertNotNull("校验失败必须返回可读原因", reason)
        assertTrue("原因应包含「$keyword」,实际: $reason", reason!!.contains(keyword))
        assertFalse("校验失败时绝不能落盘", persisted)
    }

    /** 401:token 无效 → 不保存,原因可读(带状态码)。 */
    @Test
    fun auth_failure_401_does_not_persist() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid api key"}"""))
        val gw = HermesGateway(config())
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertRejected(reason, "401")
        assertTrue("应说明鉴权失败,实际: $reason", reason!!.contains("鉴权失败"))
        gw.close()
    }

    /** 404:服务未启用或路径不对 → 不保存,原因可读。 */
    @Test
    fun not_found_404_does_not_persist() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val gw = HermesGateway(config())
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertRejected(reason, "404")
        assertTrue("应说明服务未启用,实际: $reason", reason!!.contains("服务未启用"))
        gw.close()
    }

    /** 连接被拒绝(Host/端口不可达)→ 不保存,原因可读。 */
    @Test
    fun connection_refused_does_not_persist() = runBlocking {
        val deadPort = server.port
        server.shutdown()   // 关掉服务,制造连接拒绝
        val gw = HermesGateway(config(port = deadPort))
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertNotNull("连接拒绝必须返回可读原因", reason)
        assertTrue(
            "原因应为可读的网络错误,实际: $reason",
            reason!!.contains("无法连接") || reason.contains("网络") || reason.contains("超时"),
        )
        assertFalse("连接失败时绝不能落盘", persisted)
        gw.close()
    }

    /** 探活卡住(无响应)→ 守门超时兜底,不落盘,原因可读。 */
    @Test
    fun probe_timeout_does_not_persist() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // 适配器读超时给得较大,让 GatewaySaveGuard 自己的超时先生效
        val gw = HermesGateway(config(readTimeoutMs = 30_000))
        val reason = GatewaySaveGuard.validateAndPersist(gw, timeoutMs = 600) { persisted = true }
        assertRejected(reason, "超时")
        gw.close()
    }

    /** 探活成功(200 /health)→ 落盘恰好一次,无错误原因。 */
    @Test
    fun healthy_gateway_persists_once() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val gw = HermesGateway(config())
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertNull("校验通过不应有失败原因", reason)
        assertTrue("校验通过必须落盘", persisted)
        assertEquals("/health", server.takeRequest().path)
        assertNull(gw.lastError)
        gw.close()
    }

    /** 403:key 无权 → 不保存,原因可读(带状态码与鉴权说明)。 */
    @Test
    fun auth_failure_403_does_not_persist() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        val gw = HermesGateway(config())
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertRejected(reason, "403")
        assertTrue("应说明鉴权失败,实际: $reason", reason!!.contains("鉴权失败"))
        gw.close()
    }

    /**
     * 等待网关授权(OpenClaw 设备未批准):必须识别为「可重试」而不是配置错误,
     * 且同样【绝不】落盘 —— 校验不通过绝不落盘这条硬规则不因为“等一会儿就好”而放宽。
     */
    @Test
    fun awaiting_pairing_is_reported_as_retryable_and_never_persists() = runBlocking {
        val detail = "等待网关授权：请在 OpenClaw 控制台批准本设备 (deviceId 1a2b3c4d…)"
        val gw = AwaitingPairingAdapter(detail)

        val result = GatewaySaveGuard.validate(gw, timeoutMs = 1_000)
        assertTrue("等待授权必须单独分类,实际: $result", result is GatewaySaveGuard.SaveValidation.AwaitingPairing)
        assertEquals(detail, (result as GatewaySaveGuard.SaveValidation.AwaitingPairing).reason)
        assertFalse("等待授权绝不能落盘", persisted)

        // 兼容入口(一次性校验+落盘)行为一致:不落盘,并把授权原文透出给调用方
        val reason = GatewaySaveGuard.validateAndPersist(gw, timeoutMs = 1_000) { persisted = true }
        assertNotNull(reason)
        assertTrue("应保留「等待网关授权」原文,实际: $reason", reason!!.contains(AWAITING_PAIRING_PREFIX))
        assertFalse("等待授权绝不能落盘", persisted)
        gw.close()
    }

    /** 等待授权但适配器没给原因:兜底文案也必须说明是“等授权”而不是“配置错”。 */
    @Test
    fun awaiting_pairing_without_reason_uses_fallback_text() = runBlocking {
        val gw = AwaitingPairingAdapter(null)
        val result = GatewaySaveGuard.validate(gw, timeoutMs = 1_000)
        assertTrue(result is GatewaySaveGuard.SaveValidation.AwaitingPairing)
        assertEquals(
            GatewaySaveGuard.AWAITING_PAIRING_FALLBACK_REASON,
            (result as GatewaySaveGuard.SaveValidation.AwaitingPairing).reason,
        )
        assertFalse(persisted)
        gw.close()
    }

    /** 普通失败不会被误判成「等待授权」(否则设置页会白等 180s)。 */
    @Test
    fun plain_failure_is_not_awaiting_pairing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid api key"}"""))
        val gw = HermesGateway(config())
        val result = GatewaySaveGuard.validate(gw, timeoutMs = 3_000)
        assertTrue("401 是配置错,不该进入等待授权,实际: $result", result is GatewaySaveGuard.SaveValidation.Failed)
        assertFalse(persisted)
        gw.close()
    }

    /**
     * 假适配器:模拟 OpenClaw 的「设备未在网关被批准」——connect 恒失败,但声称自己在等授权。
     */
    private class AwaitingPairingAdapter(private val reason: String?) : GatewayAdapter {
        override suspend fun connect(): Boolean = false
        override fun isReady(): Boolean = false
        override suspend fun chat(text: String): String? = null
        override fun interrupt() = Unit
        override val lastError: String? get() = reason
        override val isAwaitingPairing: Boolean get() = true
        override fun close() = Unit
    }

    /** Echo 恒通过:无网关环境也能保存配置。 */
    @Test
    fun echo_draft_always_persists() = runBlocking {
        val gw = EchoGateway()
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertNull(reason)
        assertTrue(persisted)
        gw.close()
    }
}
