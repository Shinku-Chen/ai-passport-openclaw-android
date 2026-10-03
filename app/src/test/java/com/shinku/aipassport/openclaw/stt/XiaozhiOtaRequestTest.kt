package com.shinku.aipassport.openclaw.stt

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 小智 OTA(绑定)请求的字段必须来自**一个来源**、且上报的是**当前版本号**，并与官方固件同形:
 *  - `name` = `ai-passport`(作者要求,之前写死 `Passport`);
 *  - `version` = 设备固件版本(`hello.fw`,如 `1.13`)→ App `versionName` → 显式回退(之前写死 `0.1.0`);
 *  - `User-Agent` = `<板型>/<版本>`(官方 `SystemInfo::GetUserAgent()` 的形状);
 *  - 顶层 `version: 2` / `language` / `mac_address` / `uuid`(官方 `Board::GetSystemInfoJson()`);
 *  - `uuid` 与 `Client-Id` 头**必须是同一个值**(官方两处都用 `Board::GetUuid()`);
 *  - 响应结构取证不把 token 明文写进日志;上行报文原文进日志时也只脱敏密钥字段。
 */
class XiaozhiOtaRequestTest {

    private lateinit var server: MockWebServer

    private val mac = "4C:11:AE:30:B9:7A"
    private val clientId = "11111111-2222-3333-4444-555555555555"

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

    @Test
    fun `版本号优先用设备固件版本_其次 App 版本_最后显式回退`() {
        assertEquals("设备固件版本优先", "1.13", XiaozhiOtaRequest.version("1.13", "1.12"))
        assertEquals("设备未上报 → App versionName", "1.12", XiaozhiOtaRequest.version(null, "1.12"))
        assertEquals("空串等同未上报", "1.12", XiaozhiOtaRequest.version("  ", "1.12"))
        assertEquals(
            "两者都没有 → 显式回退值(不是写死的 0.1.0)",
            XiaozhiOtaRequest.FALLBACK_VERSION,
            XiaozhiOtaRequest.version(null, null),
        )
        assertNotEquals("0.1.0", XiaozhiOtaRequest.version(null, null))
        assertNotEquals("", XiaozhiOtaRequest.version(null, null))
    }

    @Test
    fun `User-Agent 与版本号同源_且与官方固件同形`() {
        assertEquals("lancelot/1.13", XiaozhiOtaRequest.userAgent("1.13"))
        assertTrue(
            "官方 SystemInfo::GetUserAgent() = BOARD_NAME \"/\" 固件版本:不能把工程名塞进版本位",
            XiaozhiOtaRequest.userAgent("1.13") == "${XiaozhiOtaRequest.BOARD_TYPE}/1.13",
        )
    }

    @Test
    fun `请求体与请求头字段(name=ai-passport、version=当前版本、UA 同步、uuid 与 Client-Id 同值)`() {
        val version = XiaozhiOtaRequest.version("1.13", null)
        server.enqueue(MockResponse().setBody("{}"))
        OkHttpClient().newCall(
            XiaozhiOtaRequest.request(server.url("/xiaozhi/ota/").toString(), mac, clientId, version)
        ).execute().close()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        val body = JsonParser.parseString(recorded.body.readUtf8()).asJsonObject
        val application = body.getAsJsonObject("application")
        val board = body.getAsJsonObject("board")

        assertEquals("ai-passport", application.get("name").asString)
        assertEquals("1.13", application.get("version").asString)
        assertEquals("ai-passport", board.get("name").asString)
        assertEquals("lancelot", board.get("type").asString)
        assertEquals(mac, board.get("mac").asString)
        assertEquals("设备身份头与 body 里的 MAC 一致", mac, recorded.getHeader("Device-Id"))
        assertEquals(clientId, recorded.getHeader("Client-Id"))
        assertEquals("lancelot/1.13", recorded.getHeader("User-Agent"))
        assertTrue(
            "Content-Type 是 JSON",
            recorded.getHeader("Content-Type")!!.startsWith("application/json"),
        )
        assertEquals("无 SN 走 v1 激活", "1", recorded.getHeader("Activation-Version"))
        assertEquals("/xiaozhi/ota/", recorded.path)

        // 官方 GetSystemInfoJson():顶层系统信息格式版本 + 语言 + 设备 MAC + 客户端 uuid
        assertEquals("顶层系统信息格式版本(固件恒发 2)", 2, body.get("version").asInt)
        assertEquals("zh-CN", body.get("language").asString)
        assertEquals("与 Device-Id 头同值", mac, body.get("mac_address").asString)
        assertEquals("uuid 与 Client-Id 头必须是同一个值(官方两处都用 GetUuid())", clientId, body.get("uuid").asString)
    }

    @Test
    fun `响应结构取证打出键名_凭据只留长度与前四位`() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
        val response = JsonParser.parseString(
            """
            {"activation":{"code":"123456","message":"xiaozhi.me","challenge":"$token","timeout_ms":60000},
             "websocket":{"url":"wss://api.tenclass.net/xiaozhi/v1/","token":"$token"},
             "mqtt":{"endpoint":"mqtt://x","client_id":"c","username":"u","password":"$token","keepalive":60},
             "server_time":{"timestamp":1730000000000,"timezone_offset":480},
             "firmware":{"version":"1.13","url":"http://fw"}}
            """.trimIndent()
        ).asJsonObject

        val dump = XiaozhiOtaRequest.summarize(response)

        // 结构:两层键名都要在,便于判断「这次到底下发了哪几段」
        for (key in listOf(
            "activation", "websocket", "mqtt", "server_time", "firmware",
            "code", "challenge", "url", "token", "password", "keepalive", "timestamp", "version",
        )) {
            assertTrue("结构摘要里应有键 $key:\n$dump", dump.contains(key))
        }
        assertFalse("token 明文绝不能进日志:\n$dump", dump.contains(token))
        assertTrue("凭据只留长度/前 4 位:\n$dump", dump.contains("len=${token.length}"))
        assertTrue("非敏感 url 打明文:\n$dump", dump.contains("wss://api.tenclass.net/xiaozhi/v1/"))
    }

    @Test
    fun `describeSecret 不泄露明文`() {
        assertEquals("未下发", XiaozhiOtaRequest.describeSecret(null))
        assertEquals("未下发", XiaozhiOtaRequest.describeSecret("  "))
        val described = XiaozhiOtaRequest.describeSecret("abcdefgh")
        assertTrue(described.contains("len=8"))
        assertTrue(described.contains("前4位=abcd"))
        assertFalse(described.contains("efgh"))
    }

    @Test
    fun `上行报文原文进日志_但密钥字段必颓脱敏`() {
        // 与 true sendHello 同形的客户端 hello:必须原样可读(真机取证靠它判断「发了什么」)
        val hello = JsonParser.parseString(
            """{"type":"hello","version":1,"transport":"websocket",
                "audio_params":{"format":"opus","sample_rate":16000,"channels":1,"frame_duration":60}}"""
        ).asJsonObject
        val dump = XiaozhiOtaRequest.describeOutgoing(hello)
        for (key in listOf("hello", "version", "transport", "websocket", "audio_params", "opus", "16000", "60")) {
            assertTrue("hello 原文应照打(含 $key):$dump", dump.contains(key))
        }
        assertEquals("不修改传入对象", 1, hello.get("version").asInt)

        // 将来若在报文里加凭据字段,值不能明文出去(逐层递归)
        val withSecret = JsonParser.parseString(
            """{"type":"x","token":"super-secret-token","nested":{"Authorization":"Bearer abcdef"}}"""
        ).asJsonObject
        val redacted = XiaozhiOtaRequest.describeOutgoing(withSecret)
        assertFalse("token 明文不得进日志:$redacted", redacted.contains("super-secret-token"))
        assertFalse("嵌套的 Authorization 也要脱敏:$redacted", redacted.contains("abcdef"))
        assertTrue("只留长度与前 4 位:$redacted", redacted.contains("len=18"))
    }
}
