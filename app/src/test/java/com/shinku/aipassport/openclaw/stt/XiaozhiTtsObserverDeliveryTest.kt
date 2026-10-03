package com.shinku.aipassport.openclaw.stt

import com.shinku.aipassport.openclaw.tts.XiaozhiScreenSignal
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
 *  - **只有音频帧、没有 `tts` 状态报文**时,整条链(会话 → 直通 relay → 下发)仍然把帧送到设备侧入口;
 *  - **本轮正文**必须走 [XiaozhiTtsObserver.onReplyBody] 先到达直通侧(开播闸门的依据;否则“正文已上屏”
 *    信号到了也对不上号、音频永远发不出去)。
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
     * 假的小智服务端:握手后回 hello;收到客户端 `listen.start` 后依次发 [extraMessages] 的文本报文、
     * 按 [withTtsState] 决定是否再发一条 `tts` 状态报文,然后推 [frames] 个二进制 opus 帧。
     */
    private class FakeXiaozhi(
        private val hello: String,
        private val state: String?,
        private val frames: Int,
        private val frameFactory: (Int) -> ByteArray,
        private val extraMessages: List<String> = emptyList(),
    ) : WebSocketListener() {

        val pushed = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!text.contains("\"type\":\"listen\"") || !text.contains("\"state\":\"start\"")) return
            extraMessages.forEach { webSocket.send(it) }
            state?.let { webSocket.send(it) }
            repeat(frames) { webSocket.send(ByteString.of(*frameFactory(it))) }
            pushed.countDown()
        }
    }

    /** 记录观察者收到的四组回调。 */
    private class RecordingObserver(
        private val audioLatch: CountDownLatch,
        private val replyBodyLatch: CountDownLatch = CountDownLatch(Int.MAX_VALUE),
    ) : XiaozhiTtsObserver {

        val turnStarts = AtomicInteger()
        val states = CopyOnWriteArrayList<Pair<String, String>>()
        val audios = CopyOnWriteArrayList<Triple<ByteArray, Int, Int>>()
        val replyBodies = CopyOnWriteArrayList<String>()

        override fun onTurnStart() {
            turnStarts.incrementAndGet()
        }

        override fun onReplyBody(body: String) {
            replyBodies.add(body)
            replyBodyLatch.countDown()
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
        extraMessages: List<String> = emptyList(),
    ): Pair<XiaozhiSession, FakeXiaozhi> {
        val fake = FakeXiaozhi(
            helloJson,
            if (withTtsState) ttsStateJson else null,
            frames,
            { opusFrame(it) },
            extraMessages = extraMessages,
        )
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
        val (session, fake) = newSession(
            frames = 2,
            withTtsState = true,
            observer = observer,
            onTtsAudio = { _, _, _ -> callbackFrames.incrementAndGet() },
        )

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
     * 回归测试:服务端只推二进制音频、一条 `tts` 状态报文都没有时,帧仍然要一路走到设备侧入口
     * (观察者 = 真 `XiaozhiTtsRelay`,假的下发实现计帧)—— 但**必须等「本段字幕已写进 BLE 写队列」**
     * (顺序优先:文字先于声音,降级也不抢跑)。
     */
    @Test
    fun audio_only_stream_without_tts_state_reaches_the_device_downlink_after_the_screen_signal() {
        val frameLatch = CountDownLatch(3)
        val downlink = FakeXiaozhiDownlink(frameLatch)
        val relay = XiaozhiTtsRelay(
            gate = { XiaozhiTtsGate(gatewayType = "xiaozhi", ttsEnabled = true, deviceTtsCapable = true) },
            downlink = downlink,
        )
        // 会话层 → relay 的转发器(顺便计一下真进了 relay 几帧,便于断言「信号前零下发」这个时点)
        val relayed = CountDownLatch(3)
        val forwarder = object : XiaozhiTtsObserver {
            override fun onTurnStart() = relay.onTurnStart()

            override fun onReplyBody(body: String) = relay.onReplyBody(body)

            override fun onTtsState(state: String, text: String) = relay.onTtsState(state, text)

            override fun onTtsAudio(opus: ByteArray, rateKhz: Int, frameMs: Int) {
                relay.onTtsAudio(opus, rateKhz, frameMs)
                relayed.countDown()
            }
        }
        val (session, fake) = newSession(frames = 3, withTtsState = false, observer = forwarder)

        assertTrue(fake.pushed.await(5, TimeUnit.SECONDS))
        assertTrue("会话层的 3 帧必须都交给 relay", relayed.await(5, TimeUnit.SECONDS))
        assertEquals("正文还没上屏:一个音频帧都不许下发", emptyList<String>(), downlink.events.toList())

        // 服务侧要把本段字幕写出去(流水线交来的 TEXT('A') 就是本轮正文):
        // 「字幕随段推进」里这个入口由 relay 接管 —— 首段**立即**写(段界还没轮到其它段)。
        relay.onReplyBody("测试正文")
        assertTrue(relay.onReplySubtitleReady(XiaozhiScreenSignal.REPLY_ROLE, "测试正文"))

        assertTrue("上屏信号之后,音频帧必须下发到设备侧", frameLatch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "首帧前必须有 tts_start,其后每帧一个 TTS_OPUS",
            listOf("start", "frame", "frame", "frame"),
            downlink.events.toList(),
        )
        session.release()
    }

    /**
     * 会话层→直通侧的**本轮正文**投递:服务端只推 `llm`(表情)+ `tts` 句级文本时,观察者收到的
     * 正文是 `tts` 拼接的那句(不是表情、也不含工具模板)。
     *
     * 为什么必须钉住这一跳:直通侧的开播闸门要求「上屏文本 == 本轮正文」,而本轮正文就是这里交付的;
     * 这一跳断了的话,「正文已上屏」信号永远对不上号,设备就永远没有声音。
     */
    @Test
    fun session_delivers_the_assembled_reply_body_to_the_observer() {
        val replyBodyLatch = CountDownLatch(1)
        val observer = RecordingObserver(CountDownLatch(1), replyBodyLatch)
        val (session, fake) = newSession(
            frames = 0,
            withTtsState = false,
            observer = observer,
            extraMessages = listOf(
                """{"type":"llm","emotion":"😊","text":"😊"}""",
                """{"type":"tts","state":"sentence_start","text":"% get_weather(city=\"上海\")"}""",
                """{"type":"tts","state":"sentence_start","text":"明天上海是小雨喔，白天23度"}""",
                """{"type":"tts","state":"stop"}""",
            ),
        )

        assertTrue("push 到了", fake.pushed.await(5, TimeUnit.SECONDS))
        assertTrue("观察者必须拿到本轮正文", replyBodyLatch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "正文 = tts 句级拼接(剔掉工具模板),不是表情",
            listOf("明天上海是小雨喔，白天23度"),
            observer.replyBodies.toList(),
        )
        session.release()
    }

    /**
     * 真机问题「从小智获取的文本不是完整的」的**端到端回归**(会话层接线 → 正文观察者)。
     *
     * 真机交错流:① `llm.text` 先到,是「中间态 + 工具模板」那截更短的文本;② 工具调用轮次的第一段
     * 只有模板句级文本、随后 `stop`;③ 工具结果回来后真答案作为**第二段**到达、再来一个 `stop`。
     *
     * 断言观察者（→ 直通 relay / 网关 / 流水线）**只能**收到完整的那句长文本:
     * 第一段的 `stop` 不得把中间态兜底交出去(否则设备屏就永远停在那截 102 字节的文本上)。
     */
    @Test
    fun session_delivers_the_complete_multisegment_body_not_the_intermediate_fallback() {
        val replyBodyLatch = CountDownLatch(1)
        val observer = RecordingObserver(CountDownLatch(1), replyBodyLatch)
        val full = "明天上海是小雨喔，白天都湿湿的，晚上才转阴，算不上好天气啦。"
        val (session, fake) = newSession(
            frames = 0,
            withTtsState = false,
            observer = observer,
            extraMessages = listOf(
                """{"type":"llm","emotion":"😊","text":"% get_weather(location=\"上海\", date=\"明天\")明天上海是小雨喔，白天23度"}""",
                """{"type":"tts","state":"sentence_start","text":"% get_weather(location=\"上海\", date=\"明天\"):"}""",
                """{"type":"tts","state":"stop"}""",
                """{"type":"tts","state":"sentence_start","text":"$full"}""",
                """{"type":"tts","state":"stop"}""",
            ),
        )

        assertTrue("push 到了", fake.pushed.await(5, TimeUnit.SECONDS))
        assertTrue("观察者必须拿到本轮正文", replyBodyLatch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "只能收到完整的句级拼接:第一段 stop 上的中间态兜底正文绝不能上屏",
            listOf(full),
            observer.replyBodies.toList(),
        )
        session.release()
    }
}
