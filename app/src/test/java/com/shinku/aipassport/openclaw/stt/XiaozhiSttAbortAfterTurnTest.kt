package com.shinku.aipassport.openclaw.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「非小智网关模式:识别结束后补发一次中止」的真链路验证 —— 用 MockWebServer 起一个**假的小智服务端**,
 * 直接看服务端收到了什么。
 *
 * 背景:非小智网关下小智通道只做**识别**,回复来自 App 自己的网关;而小智云端拿到识别文本后会接着跑
 * 它自己的 LLM + TTS —— 那一段没人用,纯属白耗额度。所以识别真的结束(最终 stt 已到)后要补发一次
 * **中止**,且格式必须与既有打断路径([XiaozhiSession.barge] / [XiaozhiSession.endTurn] 的 `listen.stop`)
 * **逐字一致**(不新增第二种消息格式)。
 *
 * 断言的都是**用户/服务端可见的结果**:
 *  - 非小智模式:服务端在收到 stt **之后**还有第二条 `listen.stop`(内容与第一条逐字相同);
 *  - 小智 AI 模式:一条都不多发(小智那一轮的 `llm`/TTS 正是我们要的);
 *  - 幂等:重复收尾、迟到收尾都不重复发,更不会把已经开始的新一轮停掉;
 *  - 不变量:`socket` 不关(两轮复用同一条热连接,没有重连)。
 */
class XiaozhiSttAbortAfterTurnTest {

    private lateinit var server: MockWebServer

    private val framesPerTurn = 3

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

    /** 缩短的等待预算(生产默认 1.2s/5s/5s,单测照用会慢)。 */
    private fun fastBudget() =
        TurnRecovery.Budget(firstWaitMs = 300L, reconnectWaitMs = 3_000L, replayWaitMs = 1_500L)

    /**
     * 假的小智服务端:握手回 hello;收到 `listen.stop` 时——如果这是本轮的**第一条**——回一条 stt
     * (模拟真机:收尾后云端出识别结果,接着就要跑 LLM + TTS)。
     *
     * 把两种 `listen.stop` 分开记:
     *  - [stopMessages]:收到的全部收尾/中止消息原文(用来断言「中止与既有打断逐字同格式」);
     *  - [abortAfterStt]:在**已回过 stt 之后**收到的 `listen.stop` 条数 —— 这就是「补发的中止」。
     */
    private class FakeXiaozhi(
        private val hello: String,
        private val stt: String,
        /** true = 收到第一条 `listen.stop` 就回 stt;[sendSttNow] 手动触发的用例用 false。 */
        private val autoStt: Boolean = true,
        /** 预期几轮 `listen.start`(用于「消息有序」的兜底断言,见 [listenStarts])。 */
        private val expectedListenStarts: Int = 2,
    ) : WebSocketListener() {

        /** 全部收尾/中止消息原文(按到达顺序;第一条是正常收尾,之后的是补发的中止)。 */
        val stopMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 「已回过 stt 之后」收到的 `listen.stop` 条数 = 补发的中止条数。 */
        val abortAfterStt = AtomicInteger()

        /** 本轮是否已经回过 stt(服务端视角的「识别已结束」)。 */
        private val sttSent = AtomicBoolean(false)

        /** 第一条 `listen.stop` 到达(收尾那条)。 */
        val firstStopSeen = CountDownLatch(1)

        /** 补发的中止到达。 */
        val abortSeen = CountDownLatch(1)

        /** 第 [expectedListenStarts] 条 `listen.start` 到达(用来做「此前的发送都已完成」的兜底)。 */
        val listenStarts = CountDownLatch(expectedListenStarts)

        /** 服务端侧 socket(手动补发 stt 用)。 */
        @Volatile
        private var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            val isListen = text.contains("\"type\":\"listen\"")
            if (isListen && text.contains("\"state\":\"start\"")) {
                listenStarts.countDown()
            }
            if (isListen && text.contains("\"state\":\"stop\"")) {
                stopMessages.add(text)
                firstStopSeen.countDown()
                // 「已经回过 stt 之后」到达的收尾 = 补发的中止(正常收尾永远先于 stt)。
                if (sttSent.get()) {
                    abortAfterStt.incrementAndGet()
                    abortSeen.countDown()
                }
                if (autoStt && !sttSent.getAndSet(true)) webSocket.send(stt)
            }
        }

        /** 手动把本轮的 stt 回给客户端(模拟「迟到的识别结果」)。 */
        fun sendSttNow() {
            sttSent.set(true)
            socket?.send(stt)
        }
    }

    /** 跑一轮:冷路径握手 → 上送 [frames] 帧 → endTurn()。返回识别文本。 */
    private fun runTurn(stt: XiaozhiStt, frames: Int = framesPerTurn): String? {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("冷路径握手应在超时内完成", ready.await(5, TimeUnit.SECONDS))
        repeat(frames) { stt.feedOpus(byteArrayOf(0x58, it.toByte(), 0x2a, 0x11)) }
        return runBlocking { stt.endTurn() }
    }

    /**
     * 决定性兜底:再开一轮(复用热连接,必然发出下一条 `listen.start`)。
     *
     * 消息在**同一条 socket 上有序**:这条 `listen.start` 都到了,就说明它之前「该发的都发完了」——
     * 此时再断言 `listen.stop` 的条数,才不会被 OkHttp 发送队列的延迟骗过(否则「没多发」可能
     * 只是还没送到)。
     */
    private fun canaryTurn(stt: XiaozhiStt, fake: FakeXiaozhi) {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("兜底轮应复用热连接立即就绪", ready.await(2, TimeUnit.SECONDS))
        assertTrue("兜底轮的 listen.start 应到达服务端", fake.listenStarts.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `非小智模式_识别结束后补发一次中止_且不影响返回文本`() {
        val fake = FakeXiaozhi(helloJson, sttJson)
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val stt = XiaozhiStt(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceId = "00:00:00:00:00:00",   // 非小智模式:匿名标识
            recovery = fastBudget(),
            sttAbortAfterEndTurn = { true },
        )

        val text = runTurn(stt)

        assertEquals("补发中止不影响返回给调用方的识别文本", "现在什么天气啊？", text)
        assertTrue("服务端必须收到补发的中止消息", fake.abortSeen.await(2, TimeUnit.SECONDS))
        assertEquals("本轮两条 listen.stop:收尾那条 + 补发的中止", 2, fake.stopMessages.size)
        assertEquals(
            "中止消息必须与既有打断路径(barge)逐字同格式",
            fake.stopMessages[0],
            fake.stopMessages[1],
        )
        assertEquals("只补发一次", 1, fake.abortAfterStt.get())
        stt.release()
    }

    @Test
    fun `小智模式_一个字节都不多发`() {
        val fake = FakeXiaozhi(helloJson, sttJson, expectedListenStarts = 2)
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val stt = XiaozhiStt(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceId = "aa:bb:cc:dd:ee:ff",   // 小智 AI:设备真 MAC
            recovery = fastBudget(),
            sttAbortAfterEndTurn = { false },
        )

        val text = runTurn(stt)
        assertEquals("小智模式的识别结果照常返回", "现在什么天气啊？", text)

        // 兜底轮:这条 listen.start 都到了 ⇒ 上一轮结束时该发的都发完了,再断言条数才有意义。
        canaryTurn(stt, fake)

        assertEquals("小智模式:本轮只有收尾那一条 listen.stop,没有补发", 1, fake.stopMessages.size)
        assertEquals("小智模式:没有中止消息", 0, fake.abortAfterStt.get())
        assertEquals("热连接保留:两轮复用同一条 socket,不重连", 1, server.requestCount)
        stt.release()
    }

    @Test
    fun `重复收尾不重复补发中止`() {
        val fake = FakeXiaozhi(helloJson, sttJson)
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val stt = XiaozhiStt(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceId = "00:00:00:00:00:00",
            recovery = fastBudget(),
            sttAbortAfterEndTurn = { true },
        )

        runTurn(stt)
        assertTrue(fake.abortSeen.await(2, TimeUnit.SECONDS))

        // 重复收尾:同一轮再收一次(已无在听的会话)—— 必须安静返回,不得再发。
        val again = runBlocking { stt.endTurn() }
        assertNull("重复收尾没有新的识别结果", again)

        canaryTurn(stt, fake)
        assertEquals("补发只做一次:一条收尾 + 一条中止", 2, fake.stopMessages.size)
        assertEquals(1, fake.abortAfterStt.get())
        stt.release()
    }

    @Test
    fun `迟到的收尾不得停掉已经开始的新一轮`() = runBlocking {
        // 本轮永不自动回 stt:由测试在「新一轮已经开始」之后再让它迟到,复现慢云的时序。
        val fake = FakeXiaozhi(helloJson, sttJson, autoStt = false, expectedListenStarts = 2)
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val stt = XiaozhiStt(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceId = "00:00:00:00:00:00",
            recovery = TurnRecovery.Budget(firstWaitMs = 5_000, reconnectWaitMs = 3_000, replayWaitMs = 1_500),
            sttAbortAfterEndTurn = { true },
        )

        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        stt.feedOpus(byteArrayOf(0x58, 0x01, 0x2a, 0x11))

        // 旧轮的收尾:先发出那条 listen.stop,然后在等 stt(不等它结束)。
        val ended = async(Dispatchers.IO) { stt.endTurn() }
        assertTrue("第一段 listen.stop 应到达", fake.firstStopSeen.await(2, TimeUnit.SECONDS))

        // 用户又按了:新一轮真的开始(复用热连接,发第二条 listen.start)。
        val ready2 = CountDownLatch(1)
        stt.startTurn { ready2.countDown() }
        assertTrue("新一轮应就绪", ready2.await(2, TimeUnit.SECONDS))
        assertTrue("新一轮的 listen.start 应到达", fake.listenStarts.await(2, TimeUnit.SECONDS))

        // 旧轮的 stt 这时候才回来。
        fake.sendSttNow()
        val text = ended.await()

        assertEquals("迟到的识别结果照原样返回给调用方", "现在什么天气啊？", text)
        assertEquals(
            "迟到收尾不得补发中止(那会把新一轮的 listen 停掉)",
            1,
            fake.stopMessages.size,
        )
        assertEquals("热连接保留:两轮共用一条 socket", 1, server.requestCount)
        stt.release()
    }
}
