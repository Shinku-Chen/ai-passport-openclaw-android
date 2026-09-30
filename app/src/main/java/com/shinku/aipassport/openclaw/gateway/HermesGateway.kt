package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * Hermes 网关配置(OpenAI 兼容 HTTP API server)。
 *
 * 与 OpenClaw 不同,Hermes 没有设备签名握手,只要一个 Bearer key 就能对话:
 *  - 基址 `http(s)://<host>:<port><basePath>`,端口默认 8642(见 docs/gateway-adapters.md)
 *  - 鉴权 `Authorization: Bearer <API_SERVER_KEY>`
 *  - 探活 `GET /health`;对话 `POST /v1/chat/completions`
 *
 * 字段语义:
 *  - [basePath] 反向代理挂在子路径时使用(如 `/hermes`),空串表示根路径
 *  - [model] 仅展示用途:Hermes 接受该字段,真正使用的模型由服务端配置决定
 *  - [conversation] 会话名;服务端按这个名字串历史(最多 100 条 LRU)
 *  - [useServerSideConversation] true 时只发当前一条消息 + conversation,false 时每次带完整 messages
 *  - [stream] true 时走 SSE 增量拼接,false 时取 choices[0].message.content
 *  - [allowInsecureTls] 仅调试用:允许自签证书(默认关闭,生产必须关闭)
 */
data class HermesConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean = false,
    val allowInsecureTls: Boolean = false,
    val basePath: String = "",
    val token: String = "",
    val model: String = "hermes-agent",
    val conversation: String = "",
    val useServerSideConversation: Boolean = false,
    val stream: Boolean = false,
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 45_000,
    val streamReadTimeoutMs: Long = 120_000,
) {
    /** Host/端口是否已填。 */
    fun isConfigured(): Boolean = host.isNotBlank() && port > 0

    /** 根基址,形如 `https://host:8642/hermes`(basePath 已归一化,无结尾斜杠)。 */
    fun baseUrl(): String {
        val scheme = if (useTls) "https" else "http"
        val path = basePath.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        return "$scheme://$host:$port$path"
    }
}

/**
 * Hermes(OpenAI 兼容 HTTP)网关实现。
 *
 * 职责:把一段文本发给 Hermes 的 OpenAI 兼容端点并取回完整回复;带 Bearer key;
 * 支持非流式与 SSE 流式两种取回方式;支持服务端会话名或客户端完整 messages 两种会话模式。
 *
 * 边界与约定:
 *  - 任何失败都不抛异常,统一写进 [lastError] 并让 [chat] 返回 null。
 *  - [interrupt] 会真正 cancel 在途 OkHttp Call(协程取消同样会 cancel),被打断的 chat 返回 null。
 *  - 不含任何 Android 依赖,便于 JVM 单测(MockWebServer)。
 */
class HermesGateway(
    private val config: HermesConfig,
    client: OkHttpClient? = null,
    /** 客户端会话模式的历史来源:role→text(role 用 "user"/"assistant")。 */
    private val historyProvider: () -> List<Pair<String, String>> = { emptyList() },
    /** 状态文案回调:探活结果与「不支持 conversation 已退回客户端模式」等过程提示。 */
    private val onStatus: (String) -> Unit = {},
) : GatewayAdapter {

    private val ownsClient = client == null
    private val http: OkHttpClient = client ?: buildClient(config)

    /** 在途请求;interrupt()/close() 靠它真正取消。 */
    @Volatile
    private var activeCall: Call? = null

    /** 本次请求是否被 [interrupt] 主动打断:打断不算错误,不写 lastError。 */
    @Volatile
    private var interrupting = false

    @Volatile
    private var ready = false

    @Volatile
    private var _lastError: String? = null

    override val lastError: String?
        get() = _lastError

    override fun isReady(): Boolean = ready

    /**
     * Hermes 没有握手,探活即 `GET /health`(与设置页「保存前校验」一致)。
     * 返回 2xx 视为可用;401/403 = key 错,404 = 路径或服务未启用。
     */
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            _lastError = "Hermes 未配置:请填写 Host 与端口"
            ready = false
            return@withContext false
        }
        val call = try {
            http.newCall(request("/health").get().build())
        } catch (e: Exception) {
            _lastError = "请求地址非法: ${e.message}"
            ready = false
            return@withContext false
        }
        activeCall = call
        val cancelHandle = registerCancelOnCoroutineCompletion(call, coroutineContext[Job])
        try {
            interrupting = false
            call.execute().use { resp ->
                if (resp.isSuccessful) {
                    _lastError = null
                    ready = true
                    onStatus("Hermes 网关可用")
                    true
                } else {
                    ready = false
                    _lastError = describeHttpError(resp.code, resp.body?.string().orEmpty())
                    onStatus(_lastError ?: "Hermes 连接失败")
                    false
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: Exception) {
            ready = false
            // 用户主动打断不算错误状态
            if (!interrupting) _lastError = describeException(e)
            false
        } finally {
            cancelHandle?.dispose()
            if (activeCall === call) activeCall = null
        }
    }

    /**
     * 发一段文本,返回完整回复;失败/超时/被打断返回 null。
     *
     * 服务端会话模式([HermesConfig.useServerSideConversation])下只发当前消息 + conversation;
     * 客户端模式下发完整 messages(历史来自 [historyProvider],由 ConversationStore 提供)。
     * 若服务端版本不支持 conversation 字段(400),自动退回客户端完整 messages 重试一次。
     */
    override suspend fun chat(text: String): String? = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            _lastError = "Hermes 未配置:请填写 Host 与端口"
            return@withContext null
        }
        if (text.isBlank()) return@withContext null

        var serverSide = config.useServerSideConversation
        var attempt = 0
        var result: String? = null
        while (true) {
            attempt++
            when (val r = attemptChat(text, serverSide)) {
                is Attempt.Ok -> {
                    _lastError = null
                    ready = true
                    result = r.text
                    break
                }

                is Attempt.HttpError -> {
                    // 服务端不支持 conversation:退回客户端完整 messages(只退一次,避免死循环)
                    if (serverSide && r.code == 400 && attempt == 1) {
                        serverSide = false
                        onStatus("Hermes 不支持 conversation,已退回客户端历史模式")
                        continue
                    }
                    if (!interrupting) _lastError = describeHttpError(r.code, r.body)
                    break
                }

                // 网络异常/空内容/被打断:lastError 已在 attemptChat 内写好
                Attempt.Failed -> break
            }
        }
        result
    }

    /** 单次对话尝试的结果。 */
    private sealed interface Attempt {
        /** 成功取到非空回复。 */
        data class Ok(val text: String) : Attempt
        /** HTTP 非 2xx,附带状态码与响应体。 */
        data class HttpError(val code: Int, val body: String) : Attempt
        /** 网络异常/空内容/被打断;lastError 已在内部写好(打断时不写)。 */
        data object Failed : Attempt
    }

    /** 执行一次 /v1/chat/completions 请求;所有异常/中断都在这里消化。 */
    private suspend fun attemptChat(text: String, serverSide: Boolean): Attempt {
        val call = try {
            http.newCall(
                request(CHAT_PATH)
                    .post(buildRequestBody(text, serverSide).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        } catch (e: Exception) {
            _lastError = "请求发送失败: ${e.message}"
            return Attempt.Failed
        }

        activeCall = call
        val cancelHandle = registerCancelOnCoroutineCompletion(call, coroutineContext[Job])
        try {
            interrupting = false
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Attempt.HttpError(resp.code, resp.body?.string().orEmpty())
                }
                val body = resp.body ?: run {
                    _lastError = "网关返回空响应体"
                    return Attempt.Failed
                }
                val result = if (config.stream) {
                    // SSE 解析与「自定义 OpenAI 兼容」网关共用一份实现(见 OpenAiCompat)
                    OpenAiCompat.readSse(body.source(), TOOL_PROGRESS_EVENT)
                } else {
                    val parsed = OpenAiCompat.parseNonStreaming(body.string())
                    if (parsed.content == null && parsed.error != null) _lastError = parsed.error
                    parsed.content
                }
                // 被打断:即使已经收到部分文本也丢弃,由调用方的 turnId 判定丢弃过期回复
                if (interrupting) return Attempt.Failed
                if (result.isNullOrBlank()) {
                    if (_lastError == null) _lastError = "网关回复内容为空"
                    return Attempt.Failed
                }
                return Attempt.Ok(result)
            }
        } catch (e: CancellationException) {
            // 协程被取消:同步取消 HTTP 请求,避免连接泄漏
            call.cancel()
            throw e
        } catch (e: Exception) {
            if (!interrupting) _lastError = describeException(e)
            return Attempt.Failed
        } finally {
            cancelHandle?.dispose()
            if (activeCall === call) activeCall = null
        }
    }

    /** 真正取消在途请求(barge);被打断的一轮 [chat] 会返回 null。 */
    override fun interrupt() {
        interrupting = true
        activeCall?.cancel()
    }

    override fun close() {
        activeCall?.cancel()
        activeCall = null
        if (ownsClient) {
            // 只回收本类自己建的客户端,外注客户端由调用方负责
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    // ---- 请求构造 ----

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(config.baseUrl() + normalizePath(path))
        if (config.token.isNotBlank()) {
            b.header("Authorization", "Bearer ${config.token}")
        }
        return b
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    /** 组装 OpenAI 兼容请求体;conversation 只在服务端会话模式且非空时下发。 */
    private fun buildRequestBody(text: String, serverSide: Boolean): String {
        val base = OpenAiCompat.requestBody(config.model, buildMessages(text, serverSide), config.stream)
        if (!(serverSide && config.conversation.isNotBlank())) return base
        // 服务端会话模式追加 conversation 字段(OpenAI 兼容体之外的 Hermes 扩展)
        val obj = JsonParser.parseString(base).asJsonObject
        obj.addProperty("conversation", config.conversation)
        return obj.toString()
    }

    /**
     * 生成 messages。服务端会话模式只带当前一条;客户端模式复用 [OpenAiCompat.buildClientMessages]
     * (与「自定义 OpenAI 兼容」网关同一份拼装:可选 system + 历史 + 本轮,按上限截断)。
     */
    private fun buildMessages(text: String, serverSide: Boolean): List<Pair<String, String>> {
        if (serverSide) return listOf("user" to text)
        return OpenAiCompat.buildClientMessages(
            history = historyProvider(),
            text = text,
            systemPrompt = null,   // Hermes 没有 systemPrompt 字段
            maxHistory = MAX_HISTORY_MESSAGES,
        )
    }

    // ---- 错误映射 ----

    /** HTTP 状态码 → 可读提示(见 docs/gateway-adapters.md 的错误映射表)。 */
    private fun describeHttpError(code: Int, body: String): String {
        val detail = body.take(200).replace('\n', ' ').ifBlank { "" }
        return when (code) {
            401, 403 ->
                "鉴权失败($code):API_SERVER_KEY 缺失或错误(绑定非回环地址时必须设置)"
            404 ->
                "服务未启用或路径错误(404):请确认 hermes gateway 的 OpenAI 兼容服务与路径前缀"
            429 -> "请求被限流(429):请稍后重试"
            in 500..599 -> "Hermes 服务端错误($code):$detail"
            else -> "网关返回 HTTP $code:$detail"
        }
    }

    /** 网络/IO 异常 → 可读提示。 */
    private fun describeException(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时或读取超时:请检查 Host/端口与网络(Tailscale 是否在线)"
        is UnknownHostException -> "无法解析主机名 ${config.host}:请检查域名或 Tailscale 名称"
        is ConnectException -> "无法连接 ${config.host}:${config.port}:服务未启动或端口不对"
        is java.net.UnknownServiceException ->
            "明文连接被网络安全策略拦截(http:// 未放行):请改用 https:// 或调整 network_security_config"
        is SSLHandshakeException -> "TLS 握手失败:证书不受信任(调试可开启「允许自签证书」)"
        is CertificateException -> "证书校验失败:调试可开启「允许自签证书」"
        is IOException -> "网络错误: ${e.message ?: e.javaClass.simpleName}"
        else -> "请求失败: ${e.message ?: e.javaClass.simpleName}"
    }

    /** 协程被取消时同步 cancel 在途请求(OkHttp 阻塞 IO 不响应协程取消)。 */
    private fun registerCancelOnCoroutineCompletion(call: Call, job: Job?): kotlinx.coroutines.DisposableHandle? {
        return job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
    }

    companion object {
        /** OpenAI 兼容对话端点(相对基址)。 */
        const val CHAT_PATH = "/v1/chat/completions"

        /** Hermes 工具进度事件名:不属于正文,解析时忽略。 */
        const val TOOL_PROGRESS_EVENT = "hermes.tool.progress"

        /** 客户端模式单次请求携带的历史上限条数(与 OpenAiCompat.buildClientMessages 配合)。 */
        const val MAX_HISTORY_MESSAGES = 20

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 按配置构造 OkHttpClient;流式要放宽读超时(增量之间可能间隔较久)。 */
        private fun buildClient(config: HermesConfig): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(
                    if (config.stream) config.streamReadTimeoutMs else config.readTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
                .writeTimeout(15, TimeUnit.SECONDS)
            // 调试用允许自签证书开关与「自定义 OpenAI 兼容」共用同一实现(默认关闭)
            OpenAiCompat.applyInsecureTlsIfNeeded(builder, config.allowInsecureTls)
            return builder.build()
        }
    }
}
