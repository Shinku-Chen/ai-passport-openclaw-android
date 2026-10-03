package com.shinku.aipassport.openclaw.stt

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「识别凭据过期 → 自动换新 token 并重连」的真链路验证(假小智服务端 = MockWebServer):
 *
 *  1. 首次 WS 升级被 **401** 拒 → **恰好一次** OTA 重查 → 用新 token 重连**成功**(用户侧无感);
 *  2. 刷新后**仍被拒** → 不再循环,给出可读原因(走 `unavailableReason` 那条状态文案);
 *  3. 兜底阈值:凭据落盘时间偏旧 → **建链前**主动刷新一次(第一次握手就用新 token);
 *  4. 匿名(非小智网关)模式:0 次 OTA 请求、仍用占位 token(行为与改动前一致);
 *  5. 正常情况下**不刷新**:凭据新鲜 + 握手成功 → 一次 OTA 都不查。
 *
 * 假服务端同时扮演 OTA(`POST /xiaozhi/ota/`)与识别 WS(`/xiaozhi/v1/`),WS 的升级响应按脚本出:
 * 401(被拒)或真实升级(回 hello,收 `listen.stop` 后回 stt)。
 */
class XiaozhiTokenRefreshTest {

    private lateinit var server: MockWebServer

    private val mac = "4c:11:ae:30:b9:7a"
    private val oldToken = "old.token.aaa"
    private val newToken = "new.token.bbb"
    private val nowMs = 1_700_000_000_000L

    private val helloJson =
        """{"type":"hello","version":1,"transport":"websocket","audio_params":""" +
            """{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60},"session_id":"mock-1"}"""

    private val sttJson = """{"type":"stt","text":"你好","session_id":"mock-1"}"""

    /** 识别 WS 的升级响应脚本(按顺序取);取完返回 500(出现就说明多连了)。 */
    private val wsScript = LinkedBlockingQueue<MockResponse>()

    /** OTA 下发的 token(默认 [newToken],可换)。 */
    @Volatile
    private var otaToken = newToken

    private val otaRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val wsRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private val http = OkHttpClient()

    private lateinit var wsUrl: String
    private lateinit var otaUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        wsUrl = server.url("/xiaozhi/v1/").toString().replaceFirst("http://", "ws://")
        otaUrl = server.url("/xiaozhi/ota/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/xiaozhi/ota")) {
                    otaRequests.add(request)
                    return MockResponse().setResponseCode(200).setBody(
                        """{"websocket":{"url":"$wsUrl","token":"$otaToken"}}""",
                    )
                }
                // 识别 WS:记下握手请求(拿 Authorization / Device-Id),升级响应按脚本出。
                wsRequests.add(request)
                return wsScript.poll() ?: MockResponse().setResponseCode(500)
            }
        }
    }

    @After
    fun tearDown() {
        http.connectionPool.evictAll()
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun fastBudget() =
        TurnRecovery.Budget(firstWaitMs = 1_000L, reconnectWaitMs = 2_000L, replayWaitMs = 1_500L)

    /** 假小智服务端:握手后回 hello;收到 `listen.stop` 回一条 stt。 */
    private class FakeXiaozhi(private val hello: String, private val stt: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"state\":\"stop\"")) webSocket.send(stt)
        }
    }

    private fun acceptOnce() {
        wsScript.add(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
    }

    /**
     * 测试用的「凭据刷新」实现:像生产一样**发一次真实 OTA 请求**(复用 [XiaozhiOtaRequest],
     * 于是 OTA 的字段/头也一并被验证),拿 `websocket` 段落盘并返回新凭据。
     */
    private inner class TestRefresher(
        private val store: XiaozhiCredentialStore,
    ) : XiaozhiCredentialRefreshSource {

        val calls = AtomicInteger()

        override suspend fun refresh(deviceAddress: String): XiaozhiCredential? {
            calls.incrementAndGet()
            val req = XiaozhiOtaRequest.request(otaUrl, deviceAddress, "test-client", "1.13")
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = JsonParser.parseString(resp.body!!.string()).asJsonObject
                val ws = body.getAsJsonObject("websocket") ?: return null
                val fresh = XiaozhiCredential.fromOta(
                    ws.get("url")?.asString,
                    ws.get("token")?.asString,
                ) ?: return null
                store.save(deviceAddress, fresh, nowMs)
                return fresh
            }
        }

        override fun savedAtMs(deviceAddress: String): Long? = store.savedAt(deviceAddress)

        override fun nowMs(): Long = nowMs
    }

    /** 与 [SttFactory] 组装方式一致的引擎(网关类型 → 鉴权闸门 + 凭据刷新)。 */
    private fun engine(
        gatewayType: String,
        store: XiaozhiCredentialStore,
        refresher: XiaozhiCredentialRefreshSource?,
    ): XiaozhiStt = XiaozhiStt(
        serverUrl = wsUrl,
        token = XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN,
        deviceId = if (XiaozhiIdentity.isXiaozhi(gatewayType)) mac else XiaozhiIdentity.ANONYMOUS_DEVICE_ID,
        recovery = fastBudget(),
        linkAuthProvider = { id ->
            XiaozhiCredentialGate.linkAuth(
                gatewayType = gatewayType,
                credential = store.get(id),
                defaultUrl = wsUrl,
                placeholderToken = XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN,
            )
        },
        credentialRefresh = refresher,
    )

    /** 跑一轮:按下(等就绪)→ 上送一帧 → endTurn()。 */
    private fun runTurn(stt: XiaozhiStt, readyTimeoutMs: Long = 8_000L): Pair<Boolean, String?> {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        val ok = ready.await(readyTimeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) return false to null
        stt.feedOpus(byteArrayOf(0x58, 0x01, 0x2a, 0x11))
        return true to runBlocking { stt.endTurn() }
    }

    /** 轮询等待某个条件成立(后台协程完成刷新/重连时用,避免碰运气断言)。 */
    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50L)
        }
        return cond()
    }

    // ---- 1. 被拒 → 刷新一次 → 用新 token 重连成功 ----

    @Test
    fun `首次升级被 401 拒_恰好查一次 OTA_用新 token 重连成功`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        store.save(mac, XiaozhiCredential(wsUrl, oldToken), nowMs = nowMs)
        val refresher = TestRefresher(store)
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, store, refresher)

        wsScript.add(MockResponse().setResponseCode(401))   // 第一次:凭据过期
        acceptOnce()                                        // 刷新后:接受

        val (ready, text) = runTurn(stt)

        assertTrue("重连成功后本轮必须就绪(用户侧无感,不用手动重绑)", ready)
        assertEquals("你好", text)
        assertEquals("恰好一次 OTA 重查(不另写请求、也不重复查)", 1, otaRequests.size)
        assertEquals("刷新回调只触发一次", 1, refresher.calls.get())
        assertEquals("共两条 WS 连接:被拒的老凭据 + 新凭据", 2, wsRequests.size)
        assertEquals("第一次仍用落盘的老凭据", "Bearer $oldToken", wsRequests[0].getHeader("Authorization"))
        assertEquals("第二次必须用新 token(而不是老 token 硬撞)", "Bearer $newToken", wsRequests[1].getHeader("Authorization"))
        assertEquals("新凭据已落盘(下一次建链/重启直接用)", newToken, store.get(mac)?.token)
        assertNull("成功后不该留原因", stt.unavailableReason)

        val ota = otaRequests[0]
        assertEquals("POST", ota.method)
        assertEquals("/xiaozhi/ota/", ota.path)
        assertEquals("OTA 用设备真 MAC", mac, ota.getHeader("Device-Id"))
        assertTrue("OTA 请求体里 name 仍是 ai-passport", ota.body.readUtf8().contains("ai-passport"))
        stt.release()
    }

    // ---- 2. 刷新后仍被拒 → 不再循环 + 可读原因 ----

    @Test
    fun `刷新后仍被拒_不再循环_给出可读原因`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        store.save(mac, XiaozhiCredential(wsUrl, oldToken), nowMs = nowMs)
        val refresher = TestRefresher(store)
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, store, refresher)

        wsScript.add(MockResponse().setResponseCode(401))
        wsScript.add(MockResponse().setResponseCode(401))

        val (ready, text) = runTurn(stt, readyTimeoutMs = 4_000L)

        assertFalse("凭据换过还是被拒 → 放弃本轮(不无限重连)", ready)
        assertNull(text)
        assertEquals("OTA 只查一次(同一次尝试最多刷一次)", 1, otaRequests.size)
        assertEquals("不再第三次握手", 2, wsRequests.size)
        assertEquals("可读原因走 unavailableReason(状态文案用它)", XiaozhiCredentialRefresh.STILL_REJECTED_REASON, stt.unavailableReason)
        assertTrue("原因要让用户知道下一步(重新绑定)", stt.unavailableReason!!.contains("重新绑定"))
        stt.release()
    }

    // ---- 3. 兜底阈值:凭据偏旧 → 建链前主动刷新 ----

    @Test
    fun `凭据落盘偏旧_建链前主动刷新一次`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        store.save(
            mac,
            XiaozhiCredential(wsUrl, "很旧的token"),
            nowMs = nowMs - XiaozhiCredentialRefresh.STALE_AFTER_MS - 1_000L,
        )
        val refresher = TestRefresher(store)
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, store, refresher)

        acceptOnce()   // 只要一次升级(刷新发生在建链之前)

        val (ready, text) = runTurn(stt)

        assertTrue(ready)
        assertEquals("你好", text)
        assertEquals("建链前先查了一次 OTA", 1, otaRequests.size)
        assertEquals("只连一次(没有任何一次用旧 token 白撞)", 1, wsRequests.size)
        assertEquals(
            "第一次握手就已经是新 token(证明刷新发生在建链之前)",
            "Bearer $newToken",
            wsRequests[0].getHeader("Authorization"),
        )
        assertEquals(newToken, store.get(mac)?.token)
        stt.release()
    }

    @Test
    fun `凭据新鲜时不刷新_一次 OTA 都不查`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        store.save(mac, XiaozhiCredential(wsUrl, oldToken), nowMs = nowMs)
        val refresher = TestRefresher(store)
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, store, refresher)

        acceptOnce()

        val (ready, text) = runTurn(stt)

        assertTrue(ready)
        assertEquals("你好", text)
        assertEquals("正常一轮不该查 OTA", 0, otaRequests.size)
        assertEquals("只连一次、且用落盘的凭据", 1, wsRequests.size)
        assertEquals("Bearer $oldToken", wsRequests[0].getHeader("Authorization"))
        stt.release()
    }

    // ---- 4. 匿名(非小智网关)完全不变 ----

    @Test
    fun `匿名模式零 OTA 请求_仍用占位 token`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        // 故意塞一份别的设备的凭据:匿名通道也不该用它、更不该刷新它。
        store.save(mac, XiaozhiCredential(wsUrl, oldToken), nowMs = nowMs)
        val refresher = TestRefresher(store)
        val stt = engine("openclaw", store, refresher)

        acceptOnce()

        val (ready, text) = runTurn(stt)

        assertTrue(ready)
        assertEquals("你好", text)
        assertEquals("匿名通道:0 次 OTA 请求", 0, otaRequests.size)
        assertEquals("匿名通道:0 次刷新调用", 0, refresher.calls.get())
        assertEquals("只连一次", 1, wsRequests.size)
        assertEquals("仍用占位 token(与改动前逐字一致)", "test-token", wsRequests[0].getHeader("Authorization"))
        assertEquals("仍用全零匿名 Device-Id", XiaozhiIdentity.ANONYMOUS_DEVICE_ID, wsRequests[0].getHeader("Device-Id"))
        assertEquals("凭据没被动过", oldToken, store.get(mac)?.token)
        stt.release()
    }

    // ---- 5. 服务端以鉴权码关闭连接(hello 之后的通道被回收)也要刷新 ----

    @Test
    fun `被鉴权关闭(1008)后按过期处理_刷新并重连一次`() {
        val store = XiaozhiCredentialStore(FakeXiaozhiStore())
        store.save(mac, XiaozhiCredential(wsUrl, oldToken), nowMs = nowMs)
        val refresher = TestRefresher(store)
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, store, refresher)

        // 第一条连接:升级成功但服务端立刻以 1008(策略违规=鉴权失败)关闭。
        wsScript.add(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // 先回 hello 让会话认为建链成功,再立刻鉴权关闭。
                        webSocket.send(helloJson)
                        webSocket.close(1008, "unauthorized")
                    }
                },
            ),
        )
        acceptOnce()

        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue(
            "被鉴权关闭后自动换新凭据重连(第二条 WS 连接出现)",
            awaitTrue(8_000L) { wsRequests.size >= 2 },
        )

        assertEquals("查一次 OTA 换新凭据", 1, otaRequests.size)
        assertEquals("两条连接:被关闭的 + 换新凭据后的", 2, wsRequests.size)
        assertEquals("第二次用新 token", "Bearer $newToken", wsRequests[1].getHeader("Authorization"))
        assertEquals("重连后本轮的等待者仍然成立(没有因为刷新掉链子)", true, ready.await(5, TimeUnit.SECONDS))
        stt.barge()
        stt.release()
    }
}
