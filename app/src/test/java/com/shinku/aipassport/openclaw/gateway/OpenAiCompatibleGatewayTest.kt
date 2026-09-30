package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonParser
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
 * OpenAiCompatibleGateway 的 JVM 单测:用 MockWebServer 起一个假的 OpenAI 兼容服务,
 * 覆盖历史拼装(顺序/上限/system/去重)、非流式提取、SSE 增量、错误映射(401/404/超时)、
 * 保存前校验的 /models → 最小对话退化,以及「校验失败不落盘」。
 *
 * 不依赖真设备、真网关与 Android 运行时。
 */
class OpenAiCompatibleGatewayTest {

    private lateinit var server: MockWebServer

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

    /** 指向 MockWebServer 的配置;basePath 默认 /v1,与设置页默认值一致。 */
    private fun config(
        stream: Boolean = false,
        readTimeoutMs: Long = 5_000,
        model: String = "gpt-4o-mini",
        systemPrompt: String = "",
        maxHistory: Int = 20,
    ) = OpenAiConfig(
        host = server.hostName,
        port = server.port,
        useTls = false,
        basePath = "/v1",
        apiKey = "test-key",
        model = model,
        systemPrompt = systemPrompt,
        maxHistory = maxHistory,
        stream = stream,
        connectTimeoutMs = 3_000,
        readTimeoutMs = readTimeoutMs,
        streamReadTimeoutMs = readTimeoutMs,
    )

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun sseResponse(sse: String, chunkSize: Int = 0) =
        MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .apply { if (chunkSize > 0) setChunkedBody(sse, chunkSize) else setBody(sse) }

    /** 请求体里的 (role, content) 序列,按顺序返回。 */
    private fun messagesOf(body: String): List<Pair<String, String>> {
        val arr = JsonParser.parseString(body).asJsonObject.getAsJsonArray("messages")
        return arr.map { el ->
            val o = el.asJsonObject
            o.get("role").asString to o.get("content").asString
        }
    }

    // ---- 历史拼装(与 Hermes 客户端历史模式共用 OpenAiCompat.buildClientMessages) ----

    /** 历史顺序保持 user/assistant 原序,末尾接本轮用户文本。 */
    @Test
    fun client_history_is_sent_in_order_then_current_turn() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val gw = OpenAiCompatibleGateway(
            config(),
            historyProvider = {
                listOf("user" to "第一问", "assistant" to "第一答", "user" to "第二问")
            },
        )
        assertEquals("好", gw.chat("第三问"))

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        val messages = messagesOf(req.body.readUtf8())
        assertEquals(4, messages.size)
        assertEquals(listOf("user", "assistant", "user", "user"), messages.map { it.first })
        assertEquals("第一问", messages[0].second)
        assertEquals("第一答", messages[1].second)
        assertEquals("第二问", messages[2].second)
        assertEquals("第三问", messages[3].second)
        gw.close()
    }

    /** maxHistory 截断的是「历史」:只保留最近 N 条,system 与本轮永远保留。 */
    @Test
    fun history_is_truncated_to_max_history() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val gw = OpenAiCompatibleGateway(
            config(maxHistory = 2, systemPrompt = "系统提示"),
            historyProvider = {
                listOf(
                    "user" to "h1",
                    "assistant" to "h2",
                    "user" to "h3",
                    "assistant" to "h4",
                )
            },
        )
        gw.chat("新问题")

        val messages = messagesOf(server.takeRequest().body.readUtf8())
        // system + h3 + h4 + 本轮
        assertEquals(4, messages.size)
        assertEquals("system", messages[0].first)
        assertEquals("h3", messages[1].second)
        assertEquals("h4", messages[2].second)
        assertEquals("新问题", messages[3].second)
        gw.close()
    }

    /** 无历史:只发本轮一条,不带空 messages。 */
    @Test
    fun no_history_sends_only_current_turn() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val gw = OpenAiCompatibleGateway(config(), historyProvider = { emptyList() })
        gw.chat("你好")

        val body = server.takeRequest().body.readUtf8()
        val messages = messagesOf(body)
        assertEquals(1, messages.size)
        assertEquals("user" to "你好", messages[0])
        // 非流式应显式带 stream=false
        assertTrue("应带 stream=false: $body", body.contains("\"stream\":false"))
        assertTrue("应带 model: $body", body.contains("\"model\":\"gpt-4o-mini\""))
        gw.close()
    }

    /** 有 systemPrompt 时作为第一条 system 发送;没有时不出现 system 角色。 */
    @Test
    fun system_prompt_is_optional() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val withSystemGw = OpenAiCompatibleGateway(
            config(systemPrompt = "你是助手"),
            historyProvider = { emptyList() },
        )
        withSystemGw.chat("你好")
        val withSystem = messagesOf(server.takeRequest().body.readUtf8())
        assertEquals("system", withSystem[0].first)
        assertEquals("你是助手", withSystem[0].second)
        withSystemGw.close()

        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val plainGw = OpenAiCompatibleGateway(config(), historyProvider = { emptyList() })
        plainGw.chat("你好")
        val withoutSystem = messagesOf(server.takeRequest().body.readUtf8())
        assertTrue("不该出现 system", withoutSystem.none { it.first == "system" })
        assertEquals(1, withoutSystem.size)
        plainGw.close()
    }

    /** 历史末尾已经含本轮文本(ConversationStore 先写入)时不重复追加。 */
    @Test
    fun current_turn_is_not_duplicated() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val gw = OpenAiCompatibleGateway(
            config(),
            historyProvider = { listOf("user" to "旧问", "user" to "本轮") },
        )
        gw.chat("本轮")
        val messages = messagesOf(server.takeRequest().body.readUtf8())
        assertEquals(2, messages.size)
        assertEquals("本轮", messages.last().second)
        gw.close()
    }

    // ---- 回复提取 ----

    /** 非流式:取 choices[0].message.content。 */
    @Test
    fun non_streaming_extracts_content() = runBlocking {
        server.enqueue(
            jsonResponse("""{"choices":[{"message":{"role":"assistant","content":"兼容回复"}}]}""")
        )
        val gw = OpenAiCompatibleGateway(config())
        assertEquals("兼容回复", gw.chat("你好"))
        assertNull(gw.lastError)
        gw.close()
    }

    /** 流式:SSE 增量按序拼接,跨 TCP 分片也要能拼对。 */
    @Test
    fun streaming_concatenates_sse_deltas() = runBlocking {
        server.enqueue(sseResponse(SSE_TEXT, chunkSize = 7))
        val gw = OpenAiCompatibleGateway(config(stream = true))
        assertEquals("你好,兼容网关", gw.chat("你好"))
        assertNull(gw.lastError)
        gw.close()
    }

    /** 未知 SSE 事件(非 chunk 结构)不影响正文。 */
    @Test
    fun streaming_ignores_unknown_events() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"结果\"}}]}\n\n")
            append("event: some.unknown.event\n")
            append("data: {\"note\":\"not a chunk\"}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\":42\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(sseResponse(sse))
        val gw = OpenAiCompatibleGateway(config(stream = true))
        assertEquals("结果:42", gw.chat("天气"))
        gw.close()
    }

    // ---- 错误映射 ----

    /** 401:API Key 错 → 可读 lastError,chat 返回 null。 */
    @Test
    fun http_401_reports_auth_error() = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"invalid api key"}}""", code = 401))
        val gw = OpenAiCompatibleGateway(config())
        assertNull(gw.chat("你好"))
        assertNotNull(gw.lastError)
        assertTrue("应提示鉴权失败: ${gw.lastError}", gw.lastError!!.contains("鉴权失败"))
        assertTrue("应带状态码: ${gw.lastError}", gw.lastError!!.contains("401"))
        gw.close()
    }

    /** 404:路径/服务不对 → 可读 lastError(提示 Base Path)。 */
    @Test
    fun http_404_reports_path_error() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"not found"}""", code = 404))
        val gw = OpenAiCompatibleGateway(config())
        assertNull(gw.chat("你好"))
        assertTrue("应提示 404: ${gw.lastError}", gw.lastError!!.contains("404"))
        assertTrue("应提示 Base Path: ${gw.lastError}", gw.lastError!!.contains("Base Path"))
        gw.close()
    }

    /** 读取超时:不挂死、不抛异常,lastError 可读,chat 返回 null。 */
    @Test
    fun read_timeout_reports_readable_error() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val gw = OpenAiCompatibleGateway(config(readTimeoutMs = 800))
        assertNull(gw.chat("你好"))
        assertTrue("应提示超时: ${gw.lastError}", gw.lastError!!.contains("超时"))
        gw.close()
    }

    /** 模型名必填:空白模型不发请求,直接给可读原因。 */
    @Test
    fun blank_model_is_rejected_without_request() = runBlocking {
        val gw = OpenAiCompatibleGateway(config(model = ""))
        assertNull(gw.chat("你好"))
        assertTrue("应提示模型名必填: ${gw.lastError}", gw.lastError!!.contains("模型名"))
        assertFalse("不该发出任何请求", gw.connect())
        assertEquals(0, server.requestCount)
        gw.close()
    }

    // ---- 保存前校验 ----

    /** 校验优先 GET {basePath}/models;该端点不可用时退化到一次最小 chat/completions。 */
    @Test
    fun connect_prefers_models_then_falls_back_to_minimal_chat() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"id":"gpt-4o-mini"}]}"""))
        val gw = OpenAiCompatibleGateway(config())
        assertTrue(gw.connect())
        assertNull(gw.lastError)
        assertEquals("/v1/models", server.takeRequest().path)

        // 服务端没有 /models(404)→ 退化到最小对话请求
        server.enqueue(jsonResponse("""{"error":"not found"}""", code = 404))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好的"}}]}"""))
        assertTrue(gw.connect())
        assertEquals("/v1/models", server.takeRequest().path)
        val fallback = server.takeRequest()
        assertEquals("/v1/chat/completions", fallback.path)
        val messages = messagesOf(fallback.body.readUtf8())
        assertEquals(1, messages.size)
        assertEquals("ping", messages[0].second)
        gw.close()
    }

    /** 校验失败(401)→ GatewaySaveGuard 不落盘且原因可读。 */
    @Test
    fun save_guard_rejects_invalid_config_without_persisting() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"unauthorized"}""", code = 401))
        var persisted = false
        val gw = OpenAiCompatibleGateway(config())
        val reason = GatewaySaveGuard.validateAndPersist(gw) { persisted = true }
        assertNotNull("校验失败必须返回可读原因", reason)
        assertTrue("应说明鉴权失败: $reason", reason!!.contains("鉴权失败"))
        assertFalse("校验失败绝不能落盘", persisted)
        gw.close()
    }

    private companion object {
        /** 三段增量 + [DONE]。 */
        val SSE_TEXT = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\",兼容\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"网关\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
    }
}
