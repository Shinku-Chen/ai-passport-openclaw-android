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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 识别通道握手上的 `Device-Id` 必须**每次建链时**按当时的网关类型解析 —— 不能在服务启动时定死。
 *
 * 用 MockWebServer 起一个假的小智服务端,直接看握手请求头:
 *  - 非小智网关(设备已连接)→ `00:00:00:00:00:00`;
 *  - 类型切到「小智 AI」并丢掉旧热连接后 → 已连接设备的真 MAC;
 *  - 小智网关但没连设备 → **不建链**(不回落匿名标识)。
 */
class XiaozhiIdentityPerLinkTest {

    private lateinit var server: MockWebServer

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

    private fun wsUrl(): String = server.url("/xiaozhi/v1/").toString().replaceFirst("http://", "ws://")

    /** 缩短的等待预算(生产默认 1.2s/5s/5s,单测照用会慢)。 */
    private fun fastBudget() =
        TurnRecovery.Budget(firstWaitMs = 300L, reconnectWaitMs = 3_000L, replayWaitMs = 1_500L)

    /** 假的正常会话:握手回 hello,收到 `listen.stop` 回一条 stt。 */
    private class FakeXiaozhi(private val hello: String, private val stt: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"state\":\"stop\"")) webSocket.send(stt)
        }
    }

    /** 跑一轮:冷路径握手 → 上送一帧 → endTurn()。返回识别文本。 */
    private fun runTurn(stt: XiaozhiStt): String? {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("本轮应建链就绪", ready.await(5, TimeUnit.SECONDS))
        stt.feedOpus(byteArrayOf(0x58, 0x01, 0x2a, 0x11))
        return runBlocking { stt.endTurn() }
    }

    @Test
    fun `每次建链按当时的网关类型解析 Device-Id`() {
        var gatewayType = "openclaw"
        var address: String? = "4C:11:AE:30:B9:7A"   // 设备已连接
        val stt = XiaozhiStt(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceIdProvider = { XiaozhiIdentity.resolve(gatewayType, address).deviceId },
            recovery = fastBudget(),
        )

        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        assertEquals("你好", runTurn(stt))
        assertEquals(
            "非小智网关:即使设备已连接也要用全零匿名标识(与返工前的可跑通形态一致)",
            "00:00:00:00:00:00",
            server.takeRequest().getHeader("Device-Id"),
        )

        // 网关类型切到「小智 AI」并且丢掉旧热连接 → 下一次建链必须带真 MAC
        gatewayType = "xiaozhi"
        stt.resetDeviceId()
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        assertEquals("你好", runTurn(stt))
        assertEquals(
            "小智 AI 网关:用已连接设备的真实 MAC(归一化后)",
            "4C:11:AE:30:B9:7A",
            server.takeRequest().getHeader("Device-Id"),
        )
        stt.release()
    }

    @Test
    fun `小智网关没连设备时不建链(不回落匿名标识)`() {
        val stt = XiaozhiStt(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceIdProvider = { XiaozhiIdentity.resolve("xiaozhi", null).deviceId },
            recovery = fastBudget(),
        )
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }

        assertFalse("取不到真实 MAC 时本轮不应就绪", ready.await(500, TimeUnit.MILLISECONDS))
        assertEquals("不应该发起任何 WS 连接", 0, server.requestCount)
        assertFalse(stt.isConnected())
        stt.release()
    }
}
