package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * HermesGateway 的 JVM 单测:用 MockWebServer 起一个假的 OpenAI 兼容服务,
 * 覆盖非流式/流式提取、工具进度事件、错误映射、超时与 interrupt 取消。
 *
 * 不依赖真设备、真网关与 Android 运行时。
 */
class HermesGatewayTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 指向 MockWebServer 的配置;读超时默认 5s,便于超时用例单独调小。 */
    private fun config(
        stream: Boolean = false,
        readTimeoutMs: Long = 5_000,
        useServerSideConversation: Boolean = false,
    ) = HermesConfig(
        host = server.hostName,
        port = server.port,
        useTls = false,
        token = "test-key",
        model = "hermes-agent",
        conversation = "ai-passport-test",
        useServerSideConversation = useServerSideConversation,
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

    // ---- 回复提取 ----

    /** 非流式:取 choices[0].message.content,并带上 Bearer 头与 stream=false。 */
    @Test
    fun nonStreaming_extracts_choices_message_content() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":"你好,我是 Hermes"}}]}"""
            )
        )
        val gw = HermesGateway(config())
        val reply = gw.chat("你好")

        assertEquals("你好,我是 Hermes", reply)
        assertNull(gw.lastError)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue("请求体应带 model", body.contains("\"model\":\"hermes-agent\""))
        assertTrue("非流式应带 stream=false", body.contains("\"stream\":false"))
        gw.close()
    }

    /** 流式:多个 chat.completion.chunk 的 delta.content 按序拼接。 */
    @Test
    fun streaming_concatenates_delta_chunks() = runBlocking {
        server.enqueue(sseResponse(SSE_TEXT))
        val gw = HermesGateway(config(stream = true))
        val reply = gw.chat("你好")
        assertEquals("你好,我是Hermes", reply)
        assertNull(gw.lastError)
        gw.close()
    }

    /** 跨 TCP 缓冲/跨行分片:用小 chunk 强制把 SSE 切开,拼接结果必须一致。 */
    @Test
    fun streaming_survives_fragmented_transport() = runBlocking {
        server.enqueue(sseResponse(SSE_TEXT, chunkSize = 7))
        val gw = HermesGateway(config(stream = true))
        assertEquals("你好,我是Hermes", gw.chat("你好"))
        gw.close()
    }

    /** 混入 hermes.tool.progress 事件时,正文不受影响。 */
    @Test
    fun streaming_ignores_tool_progress_events() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"结果\"}}]}\n\n")
            append("event: hermes.tool.progress\n")
            append("data: {\"tool\":\"search\",\"status\":\"running\"}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\":40\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(sseResponse(sse))
        val gw = HermesGateway(config(stream = true))
        assertEquals("结果:40", gw.chat("天气"))
        gw.close()
    }

    /** 服务端会话模式:只带当前一条 user 消息 + conversation 字段,不重复带历史。 */
    @Test
    fun server_side_conversation_sends_only_current_message() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val gw = HermesGateway(
            config(useServerSideConversation = true),
            historyProvider = { listOf("user" to "旧问题", "assistant" to "旧回答") },
        )
        gw.chat("新问题")

        val chat = parseChatBody(server.takeRequest().body.readUtf8())
        assertEquals("ai-passport-test", chat.conversation)
        assertEquals(1, chat.messages)
        assertEquals("新问题", chat.lastMessage)
        gw.close()
    }

    /** 客户端会话模式:带完整 messages(历史来自 ConversationStore),且不重复本轮用户文本。 */
    @Test
    fun client_side_conversation_sends_history_without_duplicating_current() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))
        val gw = HermesGateway(
            config(),
            historyProvider = {
                // VoicePipeline 会先把本轮用户文本写进 ConversationStore,再调 chat
                listOf("user" to "旧问题", "assistant" to "旧回答", "user" to "新问题")
            },
        )
        gw.chat("新问题")

        val chat = parseChatBody(server.takeRequest().body.readUtf8())
        assertNull(chat.conversation)
        assertEquals(3, chat.messages)
        assertEquals("新问题", chat.lastMessage)
        gw.close()
    }

    /** 服务端不支持 conversation 字段(400)时,自动退回客户端完整 messages 重试一次。 */
    @Test
    fun server_side_conversation_falls_back_on_400() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"Unknown field conversation"}""", code = 400))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"回退后回复"}}]}"""))
        val gw = HermesGateway(
            config(useServerSideConversation = true),
            historyProvider = { listOf("user" to "旧问题") },
        )

        assertEquals("回退后回复", gw.chat("新问题"))

        val first = parseChatBody(server.takeRequest().body.readUtf8())
        assertEquals("ai-passport-test", first.conversation)
        assertEquals(1, first.messages)

        val second = parseChatBody(server.takeRequest().body.readUtf8())
        assertNull(second.conversation)
        assertEquals("回退后应带完整历史", 2, second.messages)
        assertEquals("新问题", second.lastMessage)
        gw.close()
    }

    // ---- 错误映射 ----

    /** 401:key 缺失或错误 → 可读 lastError,chat 返回 null。 */
    @Test
    fun http_401_reports_auth_error() = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"invalid api key"}}""", code = 401))
        val gw = HermesGateway(config())
        assertNull(gw.chat("你好"))
        assertNotNull(gw.lastError)
        assertTrue("应提示鉴权失败: ${gw.lastError}", gw.lastError!!.contains("鉴权失败"))
        assertTrue("应带状态码: ${gw.lastError}", gw.lastError!!.contains("401"))
        gw.close()
    }

    /** 404:路径或服务未启用 → 可读 lastError,chat 返回 null。 */
    @Test
    fun http_404_reports_service_not_enabled() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"not found"}""", code = 404))
        val gw = HermesGateway(config())
        assertNull(gw.chat("你好"))
        assertTrue("应提示 404: ${gw.lastError}", gw.lastError!!.contains("404"))
        assertTrue("应说明服务未启用: ${gw.lastError}", gw.lastError!!.contains("服务未启用"))
        gw.close()
    }

    /** 读取超时:不挂死、不抛异常,lastError 可读,chat 返回 null。 */
    @Test
    fun read_timeout_reports_readable_error() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val gw = HermesGateway(config(readTimeoutMs = 800))
        assertNull(gw.chat("你好"))
        assertTrue("应提示超时: ${gw.lastError}", gw.lastError!!.contains("超时"))
        gw.close()
    }

    /** 连接失败(端口没有服务):lastError 可读,chat 返回 null。 */
    @Test
    fun connection_refused_reports_readable_error() = runBlocking {
        val deadPort = server.port
        server.shutdown()   // 关掉服务,制造连接拒绝
        val gw = HermesGateway(config().copy(port = deadPort, connectTimeoutMs = 1_500))
        assertNull(gw.chat("你好"))
        assertNotNull(gw.lastError)
        gw.close()
    }

    /** 探活:GET /health 是「保存前校验」的实现;失败时 lastError 可读。 */
    @Test
    fun health_probe_reports_ok_and_failure() = runBlocking {
        server.enqueue(jsonResponse("""{"status":"ok"}"""))
        val gw = HermesGateway(config())
        assertTrue(gw.connect())
        assertNull(gw.lastError)
        assertEquals("/health", server.takeRequest().path)

        server.enqueue(jsonResponse("""{"error":"unauthorized"}""", code = 403))
        assertTrue(!gw.connect())
        assertTrue("应提示鉴权失败: ${gw.lastError}", gw.lastError!!.contains("鉴权失败"))
        gw.close()
    }

    // ---- interrupt ----

    /** interrupt 必须真正取消在途请求:被取消的一轮返回 null,且不算错误。 */
    @Test
    fun interrupt_cancels_inflight_request_and_next_round_works() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"第二轮"}}]}"""))
        val gw = HermesGateway(config(readTimeoutMs = 30_000))

        val inflight = async(Dispatchers.IO) { gw.chat("第一轮") }
        // 等请求确实到达服务端(否则 cancel 可能发生在 execute 之前,测不到在途取消)
        assertNotNull("请求应已发出", server.takeRequest(5, TimeUnit.SECONDS))

        gw.interrupt()
        val canceled = withTimeout(5_000) { inflight.await() }
        assertNull("被打断的一轮必须返回 null", canceled)
        assertNull("用户主动打断不算错误", gw.lastError)

        assertEquals("第二轮", gw.chat("第二轮"))
        gw.close()
    }

    private class ChatBody(
        val messages: Int,
        val lastMessage: String?,
        val conversation: String?,
    )

    /** 解析请求体,取出 messages 条数 / 最后一条内容 / conversation 字段。 */
    private fun parseChatBody(body: String): ChatBody {
        val root = JsonParser.parseString(body).asJsonObject
        val arr = root.getAsJsonArray("messages")
        val last = arr.lastOrNull()?.asJsonObject?.get("content")?.asString
        val conv = root.get("conversation")?.asString
        return ChatBody(arr.size(), last, conv)
    }

    private companion object {
        /** 三段增量 + [DONE],用于拼接断言。 */
        val SSE_TEXT = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\",我是\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hermes\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
    }
}
