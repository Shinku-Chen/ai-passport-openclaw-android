package com.shinku.aipassport.openclaw.stt

import com.shinku.aipassport.openclaw.tts.XiaozhiTtsDownlink
import com.shinku.aipassport.openclaw.tts.XiaozhiTtsGate
import com.shinku.aipassport.openclaw.tts.XiaozhiTtsRelay
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话层 → TTS 观察者的**下行音频投递**真链路验证（MockWebServer 起一个假的小智服务端）。
 *
 * 复现的真机现象:小智 AI 模式下识别与回复都已通,App 侧日志显示**收到了几十帧小智下行音频**,
 * 而设备侧计数 `TTS=0` —— 帧在中间某一跳被丢了。本测试钉住第一跳(会话层)不能是断点:
 *  - 注册了观察者时,会话收到的**二进制音频帧必须到达观察者**(本次 bug 的回归测试);
 *  - 构造参数那条出口(`onTtsAudio`)与观察者**两条都通知**、互不覆盖(旧代码只做了一条就是丢帧的根因形态);
 *  - **只有音频帧、没有 `tts` 状态报文**时,整条链(会话 → 直通 relay → 下发)仍然把帧送到设备侧入口。
 *
 * 断言的都是**交付结果**(观察者/下发假实现收到了什么),不是内部状态。
 */
class XiaozhiTtsObserverDeliveryTest {

    private lateinit var server: MockWebServer

    private val helloJson =
        """{"type":"hello","version":1,"transport":"websocket","audio_params":""" +
            """{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60},"session_id":"mock-1"}"""

    private val ttsStateJson = """{"type":"tts","state":"sentence_start","text":"你好"}"""

    /** 假 opus 包(内容是任意字节:直通路径只做透传,不解码)。 */
    private fun opusFrame(i: Int): ByteArray = ByteArray(20) { ((i * 7 + it) and 0xFF).toByte() }

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

    private fun fastBudget() =
        TurnRecovery.Budget(firstWaitMs = 300L, reconnectWaitMs = 3_000L, replayWaitMs = 1_500L)

    /**
     * 假的小智服务端:握手后回 hello;收到客户端 `listen.start` 后按 [withTtsState] 决定是否先发一条
     * `tts` 状态报文,然后推 [frames] 个二进制 opus 帧。
     */
    private class FakeXiaozhi(
        private val hello: String,
        private val state: String?,
        private val frames: Int,
        private val frameFactory: (Int) -> ByteArray,
    ) : WebSocketListener() {

        val pushed = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!text.contains("\"type\":\"listen\"") || !text.contains("\"state\":\"start\"")) return
            state?.let { webSocket.send(it) }
            repeat(frames) { webSocket.send(ByteString.of(*frameFactory(it))) }
            pushed.countDown()
        }
    }

    /** 记录观察者收到的三组回调。 */
    private class RecordingObserver(
        private val audioLatch: CountDownLatch,
    ) : XiaozhiTtsObserver {

        val turnStarts = AtomicInteger()
        val states = CopyOnWriteArrayList<Pair<String, String>>()
        val audios = CopyOnWriteArrayList<Triple<ByteArray, Int, Int>>()

        override fun onTurnStart() {
            turnStarts.incrementAndGet()
        }

        override fun onTtsState(state: String, text: String) {
            states.add(state to text)
        }

        override fun onTtsAudio(opus: ByteArray, rateKhz: Int, frameMs: Int) {
            audios.add(Triple(opus, rateKhz, frameMs))
            audioLatch.countDown()
        }
    }

    /** 记录 relay 下发调用的假通路(设备侧入口)。 */
    private class FakeXiaozhiDownlink(private val frameLatch: CountDownLatch) : XiaozhiTtsDownlink {
        val events = CopyOnWriteArrayList<String>()

        override fun start() {
            events.add("start")
        }

        override fun pushFrame(rateKhz: Int, frameMs: Int, payload: ByteArray) {
            events.add("frame")
            frameLatch.countDown()
        }

        override fun stop() {
            events.add("stop")
        }
    }

    private fun newSession(
        frames: Int,
        withTtsState: Boolean,
        observer: XiaozhiTtsObserver? = null,
        onTtsAudio: ((ByteArray, Int, Int) -> Unit)? = null,
    ): Pair<XiaozhiSession, FakeXiaozhi> {
        val fake = FakeXiaozhi(helloJson, if (withTtsState) ttsStateJson else null, frames) { opusFrame(it) }
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val session = XiaozhiSession(
            serverUrl = wsUrl(),
            token = "test-token",
            deviceIdProvider = { "aa:bb:cc:dd:ee:ff" },
            recovery = fastBudget(),
            onTtsAudio = onTtsAudio,
        )
        // 观察者必须在 startTurn **之前**挂上:音频帧在服务端看到 listen.start 后就推,晚挂就漏了
        observer?.let { session.setTtsObserver(it) }
        val ready = CountDownLatch(1)
        session.startTurn { ready.countDown() }
        assertTrue("冷路径握手应在超时内完成", ready.await(5, TimeUnit.SECONDS))
        return session to fake
    }

    @Test
    fun binary_audio_frames_reach_the_registered_observer_and_the_constructor_callback() {
        val callbackFrames = AtomicInteger()
        val observerLatch = CountDownLatch(2)
        val observer = RecordingObserver(observerLatch)
        val (session, fake) = newSession(frames = 2, withTtsState = true, observer = observer) { _, _, _ ->
            callbackFrames.incrementAndGet()
        }

        assertTrue("push 到了", fake.pushed.await(5, TimeUnit.SECONDS))
        assertTrue("观察者必须收到会话收到的二进制音频帧", observerLatch.await(5, TimeUnit.SECONDS))

        assertEquals("startTurn 要通知观察者作废上一段", 1, observer.turnStarts.get())
        assertEquals(listOf("sentence_start" to "你好"), observer.states.toList())
        assertEquals(2, observer.audios.size)
        assertEquals(24, observer.audios[0].second)
        assertEquals(60, observer.audios[0].third)
        assertArrayEquals("opus 包要原样到达观察者", opusFrame(0), observer.audios[0].first)
        assertArrayEquals(opusFrame(1), observer.audios[1].first)
        assertEquals("构造参数那条出口也必须同时通知", 2, callbackFrames.get())
        session.release()
    }

    /**
     * 本次 bug 的**端到端回归测试**:服务端只推二进制音频、一条 `tts` 状态报文都没有时,
     * 帧仍然要一路走到设备侧入口(观察者 = 真 `XiaozhiTtsRelay`,假的下发实现计帧)。
     */
    @Test
    fun audio_only_stream_without_tts_state_still_reaches_the_device_downlink() {
        val frameLatch = CountDownLatch(3)
        val downlink = FakeXiaozhiDownlink(frameLatch)
        val relay = XiaozhiTtsRelay(
            gate = { XiaozhiTtsGate(gatewayType = "xiaozhi", ttsEnabled = true, deviceTtsCapable = true) },
            downlink = downlink,
        )
        val (session, fake) = newSession(frames = 3, withTtsState = false, observer = relay)

        assertTrue(fake.pushed.await(5, TimeUnit.SECONDS))
        assertTrue("没有 tts 状态报文时,音频帧也必须下发到设备侧", frameLatch.await(5, TimeUnit.SECONDS))

        assertEquals(
            "首帧前必须有 tts_start,其后每帧一个 TTS_OPUS",
            listOf("start", "frame", "frame", "frame"),
            downlink.events.toList(),
        )
        session.release()
    }
}
