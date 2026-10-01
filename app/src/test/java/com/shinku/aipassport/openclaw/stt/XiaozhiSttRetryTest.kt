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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小智识别通道「本轮无结果 → 重连重放」的真链路验证:用 MockWebServer 起一个**假的小智服务端**,
 * 在 JVM 上复现真机那个 bug 并验证补救。
 *
 * 复现的真机现象:服务端闲置约 60s 后静默废弃连接上的识别会话 —— WebSocket 传输层还在
 * (OkHttp ping/pong 正常、服务端不报 error),但 `listen.start` 与音频**没有响应**。
 * 此时 App 认为「热连接可用」,用户的整轮语音被发进死会话 → 只能回「无语音」,话就丢了。
 *
 * 假服务端 `stt = null` 就是这个死会话(收下音频、什么都不回);`stt != null` 是正常会话。
 * 断言的都是**用户可见的结果**,不是内部状态:
 *  - 死会话后必须自动重连并把本轮重放,最终**仍然拿到识别文本**;
 *  - 链路正常时**绝不重连、绝不重复上送**(不能靠"每轮都重连"来掩盖问题);
 *  - 每轮最多重连一次(不会变成重连死循环);
 *  - 本轮没有音频可重放时不做无谓重连。
 */
class XiaozhiSttRetryTest {

    private lateinit var server: MockWebServer

    private val framesPerTurn = 5

    private val helloJson =
        """{"type":"hello","version":1,"transport":"websocket","audio_params":""" +
            """{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60},"session_id":"mock-1"}"""

    private val sttJson = """{"type":"stt","text":"现在什么天气啊？","session_id":"mock-1"}"""

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

    /** 缩短的等待预算:生产默认是 1.2s / 5s / 5s,单测照用会慢十几秒。 */
    private fun fastBudget() =
        TurnRecovery.Budget(firstWaitMs = 300L, reconnectWaitMs = 3_000L, replayWaitMs = 1_500L)

    /**
     * 假的小智服务端:握手后回 hello;收音频帧只计数。
     * [stt] 非空时在收到 `listen.stop` 后回一条 stt(正常会话),[stt] 为空则**什么都不回**(死会话)。
     */
    private class FakeXiaozhi(
        private val hello: String,
        private val stt: String?,
        frames: Int,
    ) : WebSocketListener() {

        val receivedFrames = AtomicInteger()
        val allFramesSeen = CountDownLatch(frames)
        val stopSeen = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            receivedFrames.incrementAndGet()
            allFramesSeen.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"state\":\"stop\"")) {
                stopSeen.countDown()
                stt?.let { webSocket.send(it) }
            }
        }
    }

    /** 跑一轮:冷路径握手 → 上送 [frames] 帧 → endTurn()。返回识别文本。 */
    private fun runTurn(
        stt: XiaozhiStt,
        frames: Int,
    ): String? {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("冷路径握手应在超时内完成", ready.await(5, TimeUnit.SECONDS))
        repeat(frames) { stt.feedOpus(byteArrayOf(0x58, it.toByte(), 0x2a, 0x11)) }
        return runBlocking { stt.endTurn() }
    }

    @Test
    fun dead_session_is_recovered_by_reconnect_and_replay() {
        val dead = FakeXiaozhi(helloJson, stt = null, frames = framesPerTurn)
        val alive = FakeXiaozhi(helloJson, stt = sttJson, frames = framesPerTurn)
        server.enqueue(MockResponse().withWebSocketUpgrade(dead))
        server.enqueue(MockResponse().withWebSocketUpgrade(alive))

        val stt = XiaozhiStt(wsUrl(), "test-token", "aa:bb:cc:dd:ee:ff", recovery = fastBudget())
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("冷路径握手应在超时内完成", ready.await(5, TimeUnit.SECONDS))
        repeat(framesPerTurn) { stt.feedOpus(byteArrayOf(0x58, it.toByte(), 0x2a, 0x11)) }
        assertTrue("第一段音频必须真的上送到了(死)会话", dead.allFramesSeen.await(2, TimeUnit.SECONDS))

        val text = runBlocking { stt.endTurn() }

        assertEquals("重连重放后必须仍然拿到识别结果(用户说的话不能丢)", "现在什么天气啊？", text)
        assertEquals("第一次连接应收到本轮全部帧", framesPerTurn, dead.receivedFrames.get())
        assertEquals("重连后必须把本轮原样重放一遍", framesPerTurn, alive.receivedFrames.get())
        assertEquals("只应重连一次(共两条 WS 连接)", 2, server.requestCount)
        stt.release()
    }

    @Test
    fun healthy_session_is_not_retried() {
        val alive = FakeXiaozhi(helloJson, stt = sttJson, frames = framesPerTurn)
        server.enqueue(MockResponse().withWebSocketUpgrade(alive))

        val stt = XiaozhiStt(wsUrl(), "test-token", "aa:bb:cc:dd:ee:ff", recovery = fastBudget())
        val text = runTurn(stt, framesPerTurn)

        assertEquals("现在什么天气啊？", text)
        assertEquals("链路正常时绝不重连", 1, server.requestCount)
        assertEquals("payload 不能重复上送", framesPerTurn, alive.receivedFrames.get())
        stt.release()
    }

    @Test
    fun retry_happens_at_most_once_per_turn() {
        val dead1 = FakeXiaozhi(helloJson, stt = null, frames = framesPerTurn)
        val dead2 = FakeXiaozhi(helloJson, stt = null, frames = framesPerTurn)
        server.enqueue(MockResponse().withWebSocketUpgrade(dead1))
        server.enqueue(MockResponse().withWebSocketUpgrade(dead2))

        val stt = XiaozhiStt(wsUrl(), "test-token", "aa:bb:cc:dd:ee:ff", recovery = fastBudget())
        val text = runTurn(stt, framesPerTurn)

        assertNull("两条连接都不回结果 → 本轮确实没有识别文本", text)
        assertEquals("补救只做一次,不得第三次重连", 2, server.requestCount)
        assertEquals("第二次连接应收到重放的帧", framesPerTurn, dead2.receivedFrames.get())
        stt.release()
    }

    @Test
    fun empty_turn_does_not_reconnect() {
        val dead = FakeXiaozhi(helloJson, stt = null, frames = 0)
        server.enqueue(MockResponse().withWebSocketUpgrade(dead))

        val stt = XiaozhiStt(wsUrl(), "test-token", "aa:bb:cc:dd:ee:ff", recovery = fastBudget())
        val text = runTurn(stt, frames = 0)

        assertNull(text)
        assertEquals("没有音频可重放 → 不做无谓重连", 1, server.requestCount)
        stt.release()
    }
}
