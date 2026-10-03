package com.shinku.aipassport.openclaw.stt

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 识别通道**建链鉴权**的真链路验证(假小智服务端 = MockWebServer):
 *  - 小智 AI + 本机有绑定凭据 → 用凭据的 url 与 `Authorization: Bearer <token>` 握手(真 MAC);
 *  - 小智 AI + 本机**没有**凭据 → **不建链**,并给出可读原因(不再拿占位 token 硬撞云端 = 「无语音」的修法);
 *  - 非小智网关 → 仍用匿名地址 + **原有占位 token**(识别照旧可用,行为不变)。
 */
class XiaozhiCredentialLinkTest {

    private lateinit var server: MockWebServer

    private val mac = "4c:11:ae:30:b9:7a"
    private val anonymous = XiaozhiIdentity.ANONYMOUS_DEVICE_ID
    private val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"

    private val helloJson =
        """{"type":"hello","version":1,"transport":"websocket","audio_params":""" +
            """{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60},"session_id":"mock-1"}"""

    private val sttJson = """{"type":"stt","text":"你好","session_id":"mock-1"}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun wsUrl(path: String = "/xiaozhi/v1/"): String =
        server.url(path).toString().replaceFirst("http://", "ws://")

    private fun fastBudget() =
        TurnRecovery.Budget(firstWaitMs = 300L, reconnectWaitMs = 1_000L, replayWaitMs = 800L)

    private class FakeXiaozhi(private val hello: String, private val stt: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"state\":\"stop\"")) webSocket.send(stt)
        }
    }

    /** 跑一轮:冷路径握手 → 上送一帧 → endTurn()。 */
    private fun runTurn(stt: XiaozhiStt): String? {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("本轮应建链就绪", ready.await(5, TimeUnit.SECONDS))
        stt.feedOpus(byteArrayOf(0x58, 0x01, 0x2a, 0x11))
        return runBlocking { stt.endTurn() }
    }

    /** 与 [SttFactory] 组装方式一致的鉴权注入(测试里不碰 SharedPreferences,用内存存储)。 */
    private fun engine(
        gatewayType: String,
        deviceId: String,
        credentials: XiaozhiCredentialStore = XiaozhiCredentialStore(FakeXiaozhiStore()),
    ) = XiaozhiStt(
        serverUrl = wsUrl(),
        token = XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN,
        deviceId = deviceId,
        recovery = fastBudget(),
        linkAuthProvider = { id ->
            XiaozhiCredentialGate.linkAuth(
                gatewayType = gatewayType,
                credential = credentials.get(id),
                defaultUrl = wsUrl(),
                placeholderToken = XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN,
            )
        },
    )

    @Test
    fun `小智 AI 有凭据时用凭据 url 与 Bearer token 握手`() {
        val boundUrl = wsUrl("/xiaozhi-bound/v1/")
        val credentials = XiaozhiCredentialStore(FakeXiaozhiStore())
        credentials.save(mac, XiaozhiCredential(boundUrl, token))
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, mac, credentials)

        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        assertEquals("你好", runTurn(stt))

        val handshake = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("用 OTA 下发的凭据地址,而不是写死的匿名地址", "/xiaozhi-bound/v1/", handshake.path)
        assertEquals("Bearer $token", handshake.getHeader("Authorization"))
        assertEquals("小智 AI 用真 MAC", mac, handshake.getHeader("Device-Id"))
        assertEquals("协议版本头不变", "1", handshake.getHeader("Protocol-Version"))
        assertNull("不建链时不该有原因", stt.unavailableReason)
        stt.release()
    }

    @Test
    fun `小智 AI 没凭据时不建链_给可读原因而不是硬撞 test-token`() {
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, mac)

        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertFalse("没有凭据时本轮不应就绪", ready.await(500, TimeUnit.MILLISECONDS))
        assertEquals("绝不发起握手(不能用占位 token 硬撞真 MAC 的链路)", 0, server.requestCount)
        assertEquals(XiaozhiCredentialGate.MISSING_CREDENTIAL_REASON, stt.unavailableReason)

        stt.feedOpus(byteArrayOf(0x58, 0x01, 0x2a, 0x11))
        assertNull("本轮没有识别结果", runBlocking { stt.endTurn() })
        assertEquals("状态文案用的原因仍在(供流水线显示)", XiaozhiCredentialGate.MISSING_CREDENTIAL_REASON, stt.unavailableReason)
        assertEquals("整轮都不建链", 0, server.requestCount)
        stt.release()
    }

    @Test
    fun `非小智网关仍走匿名标识与原占位 token`() {
        val stt = engine("openclaw", anonymous)

        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        assertEquals("你好", runTurn(stt))

        val handshake = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("匿名通道:占位 token 原样(与改动前逐字一致)", "test-token", handshake.getHeader("Authorization"))
        assertEquals(anonymous, handshake.getHeader("Device-Id"))
        assertEquals("/xiaozhi/v1/", handshake.path)
        stt.release()
    }
}
