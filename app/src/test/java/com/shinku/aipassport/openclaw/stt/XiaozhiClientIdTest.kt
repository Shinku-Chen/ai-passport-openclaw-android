package com.shinku.aipassport.openclaw.stt

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 小智 **Client-Id** 的持久化与复用(真机问题的核心):服务端按 `(client_id, device_id)` 签发/校验
 * 会话凭据(开源实现 `core/auth.py::generate_token(client_id, username)` +
 * `websocket_server.py::_handle_auth`),所以同一台已注册真设备**OTA 与识别 WS 必须是同一个 Client-Id**。
 *
 * 四条不变量:
 *  1. **一份身份一份值**:按 Device-Id 存,重复取到同值;跨「App 重启」(换一个 store 包装、同一份持久化数据)同值;
 *  2. **身份变了就换新值**(换设备 / 小智真 MAC ↔ 全零匿名切换),格式是 UUID v4(与官方固件
 *     `Board::GenerateUuid()` 同格式);
 *  3. **OTA 与 WS 用同一个值**:MockWebServer 上真发一次 OTA + 真跑一轮识别握手,两边 `Client-Id` 必须相等,
 *     且等于 `Client-Id` 头 = 请求体 `uuid`(官方固件两处都用 `Board::GetUuid()`);
 *  4. **匿名通道不变**:非小智网关仍是全零匿名 Device-Id + 占位 token,且 **0 次 OTA**。
 */
class XiaozhiClientIdTest {

    private val mac = "4C:11:AE:30:B9:7A"
    private val otherMac = "AA:BB:CC:DD:EE:FF"
    private val url = "wss://api.tenclass.net/xiaozhi/v1/"
    private val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"

    /** UUID v4:`8-4-4-4-12`、版本位 4、变体位 8/9/a/b(与官方 `Board::GenerateUuid()` 同格式)。 */
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /**
     * 「App 重启」用的 store:持久化数据不变([backing]),而进程内的对象全部重建。
     * SharedPreferences 重启后就是这个形态(数据在磁盘、对象新建),故用同一契约的包装模拟。
     */
    private class ReopenedXiaozhiStore(private val backing: XiaozhiKeyValueStore) : XiaozhiKeyValueStore {
        override fun getString(key: String): String? = backing.getString(key)

        override fun put(key: String, value: String?) = backing.put(key, value)
    }

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

    // ---- 1. 持久化与复用(纯逻辑,不碰网络) ----

    @Test
    fun `同一设备身份重复取到同值_跨重启仍同值`() {
        val kv = FakeXiaozhiStore()
        val first = XiaozhiClientId.forDevice(mac, kv)

        assertTrue("必须是 UUID v4(与官方固件同格式):$first", uuidV4.matches(first))
        assertEquals("同一身份两次取到同值", first, XiaozhiClientId.forDevice(mac, kv))
        assertEquals("大小写不同的同一台设备也算同一份", first, XiaozhiClientId.forDevice(mac.lowercase(), kv))

        // 「App 重启」= 进程内缓存清空、只剩持久化数据(同一个 kv,新的 store 包装)
        val afterRestart = XiaozhiClientId.forDevice(mac, ReopenedXiaozhiStore(kv))
        assertEquals("重启后必须还是同一个值(否则 OTA 一个、WS 一个)", first, afterRestart)

        // 键名清晰:存了「属于哪个身份」+「值」两件事
        assertEquals(setOf(XiaozhiClientId.KEY_DEVICE_ID, XiaozhiClientId.KEY_CLIENT_ID), kv.keys())
    }

    @Test
    fun `设备身份变了就换新值_真 MAC 与全零匿名各一份`() {
        val kv = FakeXiaozhiStore()
        val realMac = XiaozhiClientId.forDevice(mac, kv)
        val anonymous = XiaozhiClientId.forDevice(XiaozhiIdentity.ANONYMOUS_DEVICE_ID, kv)
        val other = XiaozhiClientId.forDevice(otherMac, kv)

        assertNotEquals("真 MAC 与全零匿名各一份", realMac, anonymous)
        assertNotEquals("换设备 = 换新值", realMac, other)
        assertTrue("新值也是 UUID v4", uuidV4.matches(other))
        assertTrue("匿名那份也是 UUID v4", uuidV4.matches(anonymous))
        assertEquals("换完身份后,当前身份那份就是刚生成的", other, XiaozhiClientId.forDevice(otherMac, kv))
        assertNotEquals("旧身份的值已被换掉(不会有两个身份共用一份)", realMac, XiaozhiClientId.forDevice(mac, kv))
    }

    @Test
    fun `空身份不落盘_也不覆盖真设备的记录`() {
        val kv = FakeXiaozhiStore()
        val realMac = XiaozhiClientId.forDevice(mac, kv)

        val blank = XiaozhiClientId.forDevice("  ", kv)
        assertTrue("空身份只生成不落盘", uuidV4.matches(blank))
        assertEquals("空身份不该污染 prefs", realMac, XiaozhiClientId.forDevice(mac, kv))
    }

    @Test
    fun `日志描述脱敏_长度加前八位`() {
        assertEquals("未生成", XiaozhiClientId.describe(null))
        assertEquals("未生成", XiaozhiClientId.describe("  "))
        val described = XiaozhiClientId.describe("11111111-2222-3333-4444-555555555555")
        assertTrue(described.contains("len=36"))
        assertTrue("只留前 8 位便于比对", described.contains("前8位=11111111"))
        assertFalse("完整值不进日志", described.contains("555555555555"))
    }

    // ---- 2. OTA 与 WS 用同一个值(真链路) ----

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

    /** 跑一轮识别(冷路径握手 → 上送一帧 → endTurn)。 */
    private fun runTurn(stt: XiaozhiStt) {
        val ready = CountDownLatch(1)
        stt.startTurn { ready.countDown() }
        assertTrue("本轮应建链就绪", ready.await(5, TimeUnit.SECONDS))
        stt.feedOpus(byteArrayOf(0x58, 0x01, 0x2a, 0x11))
        assertEquals("你好", runBlocking { stt.endTurn() })
    }

    /** 与 [SttFactory] 组装方式一致的引擎(Device-Id 解析 + 鉴权闸门 + 同一份 Client-Id 来源)。 */
    private fun engine(
        gatewayType: String,
        kv: XiaozhiKeyValueStore,
        credential: XiaozhiCredential? = null,
    ) = XiaozhiStt(
        serverUrl = wsUrl(),
        token = XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN,
        deviceIdProvider = { XiaozhiIdentity.resolve(gatewayType, mac).deviceId },
        recovery = fastBudget(),
        linkAuthProvider = { id ->
            XiaozhiCredentialGate.linkAuth(
                gatewayType = gatewayType,
                credential = credential,
                defaultUrl = wsUrl(),
                placeholderToken = XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN,
            )
        },
        clientIdProvider = { id -> XiaozhiClientId.forDevice(id, kv) },
    )

    private fun wsUrl(): String = server.url("/xiaozhi/v1/").toString().replaceFirst("http://", "ws://")

    @Test
    fun `OTA 与识别 WS 用同一个 Client-Id_且就是持久化的那一份`() {
        val kv = FakeXiaozhiStore()
        val expected = XiaozhiClientId.forDevice(mac, kv)

        // ① OTA:真发一次,看 Client-Id 头与请求体 uuid
        server.enqueue(MockResponse().setBody("{}"))
        OkHttpClient().newCall(
            XiaozhiOtaRequest.request(
                server.url("/xiaozhi/ota/").toString(),
                mac,
                XiaozhiClientId.forDevice(mac, kv),
                "1.13",
            )
        ).execute().close()
        val ota = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("OTA 用的是持久化那份", expected, ota.getHeader("Client-Id"))
        assertEquals(
            "OTA 请求体 uuid 与 Client-Id 头同值",
            expected,
            JsonParser.parseString(ota.body.readUtf8()).asJsonObject.get("uuid").asString,
        )

        // ② 识别 WS:小智 AI 网关 + 有凭据 → 真握手,看同名字段
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        val stt = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, kv, XiaozhiCredential(wsUrl(), token))
        runTurn(stt)
        val ws = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("WS 必须与 OTA 用同一个 Client-Id(真机 1005 的修复点)", expected, ws.getHeader("Client-Id"))
        assertEquals("Device-Id 仍是设备真 MAC", mac, ws.getHeader("Device-Id"))
        assertEquals("Authorization 仍是绑定凭据(Bearer)", "Bearer $token", ws.getHeader("Authorization"))
        stt.release()
    }

    @Test
    fun `重启后用同一个 store 建链_Client-Id 不变`() {
        val kv = FakeXiaozhiStore()
        val first = XiaozhiClientId.forDevice(mac, kv)

        // 第一次进程:跑一轮
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        val before = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, kv, XiaozhiCredential(wsUrl(), token))
        runTurn(before)
        assertEquals(first, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Client-Id"))
        before.release()

        // 「App 重启」后再建链:同一个持久化数据 → 同一个 Client-Id
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        val after = engine(XiaozhiIdentity.GATEWAY_XIAOZHI, kv, XiaozhiCredential(wsUrl(), token))
        runTurn(after)
        assertEquals(
            "重连/重启后仍复用同一个值(否则云端按 (client_id, device_id) 校验会对不上)",
            first,
            server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Client-Id"),
        )
        after.release()
    }

    // ---- 3. 匿名通道:地址 + 占位 token 逐字不变,0 次 OTA ----

    @Test
    fun `非小智网关仍是匿名地址加占位 token_且 0 次 OTA`() {
        val kv = FakeXiaozhiStore()
        var otaCalls = 0
        val refresher = XiaozhiCredentialRefresher(
            store = XiaozhiCredentialStore(FakeXiaozhiStore()),
            gatewayType = { "openclaw" },
            queryCloud = { otaCalls++; XiaozhiActivator.CloudQuery(XiaozhiBindGate.CloudState.Activated) },
        )

        // 解析 + 闸门:匿名标识 + 占位 token 原样(且不是「凭据链路」→ 永远不会触发刷新)
        assertEquals(
            XiaozhiIdentity.ANONYMOUS_DEVICE_ID,
            XiaozhiIdentity.resolve("openclaw", mac).deviceId,
        )
        assertEquals(
            XiaozhiLinkAuth.Ok(url, "test-token"),
            XiaozhiCredentialGate.linkAuth("openclaw", XiaozhiCredential(url, token), url, "test-token"),
        )
        assertNull("非小智模式没有「该设备的凭据」可换", runBlocking { refresher.refresh(mac) })
        assertEquals("0 次 OTA", 0, otaCalls)

        // 真跑一轮:握手头必须是匿名 Device-Id + 占位 token
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeXiaozhi(helloJson, sttJson)))
        val stt = engine("openclaw", kv)
        runTurn(stt)
        val ws = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(XiaozhiIdentity.ANONYMOUS_DEVICE_ID, ws.getHeader("Device-Id"))
        assertEquals("占位 token 原样", "test-token", ws.getHeader("Authorization"))
        assertTrue("匿名身份也有自己那份(稳定的)Client-Id", uuidV4.matches(ws.getHeader("Client-Id").orEmpty()))
        stt.release()
    }
}
