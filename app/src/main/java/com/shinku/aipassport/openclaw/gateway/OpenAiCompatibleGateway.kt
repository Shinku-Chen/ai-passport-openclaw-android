package com.shinku.aipassport.openclaw.gateway

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
 * 「自定义 OpenAI 兼容」网关配置。
 *
 * 面向任意实现了 OpenAI 兼容 HTTP API 的服务(vLLM / llama.cpp server / LM Studio /
 * Ollama 的 /v1 / 各家中转):只要 `POST {basePath}/chat/completions` 能返回
 * `choices[0].message.content` 就能用。与 Hermes 的区别:
 *  - 没有 Hermes 的 `conversation` 服务端会话,历史一律由客户端每次带上
 *  - `model` 是**必填**:服务端按它选模型,不再只是展示用途
 *  - 多了可选的 `systemPrompt` 与可配的历史条数上限 [maxHistory]
 *
 * 字段语义:
 *  - [basePath] 基址路径前缀,默认 `/v1`(OpenAI 兼容端点),空串表示根路径
 *  - [apiKey] Bearer token,明文输入框、只存本机(部分本地服务不校验,留空即不带 Authorization)
 *  - [allowInsecureTls] 仅调试用:允许自签证书(默认关闭,生产必须关闭)
 *  - [stream] true 时走 SSE 增量拼接,false 时取 choices[0].message.content
 */
data class OpenAiConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean = false,
    val allowInsecureTls: Boolean = false,
    val basePath: String = "/v1",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val maxHistory: Int = 20,
    val stream: Boolean = false,
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 45_000,
    val streamReadTimeoutMs: Long = 120_000,
) {
    /** Host/端口/模型名是否都填了(model 必填,缺了无法发出合法请求)。 */
    fun isConfigured(): Boolean = host.isNotBlank() && port > 0 && model.isNotBlank()

    /** 根基址,形如 `http://host:8080/v1`(basePath 已归一化,无结尾斜杠)。 */
    fun baseUrl(): String {
        val scheme = if (useTls) "https" else "http"
        val path = basePath.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        return "$scheme://$host:$port$path"
    }
}

/**
 * 「自定义 OpenAI 兼容」网关实现。
 *
 * 请求:`POST {scheme}://{host}:{port}{basePath}/chat/completions`,
 * `Authorization: Bearer <apiKey>`,body `{model, messages, stream}`。
 * messages = `[可选 system] + ConversationStore 历史(user/assistant) + 本轮用户文本`,
 * 历史按 [OpenAiConfig.maxHistory] 截断;拼装复用 [OpenAiCompat.buildClientMessages]
 * (与 Hermes 的客户端历史模式同一份实现)。
 *
 * 保存前校验(见 [GatewaySaveGuard])在本类的 [connect] 里:优先 `GET {basePath}/models`,
 * 服务端没有该端点(404/405)时退化为一次最小 `chat/completions`,成功即视为可用。
 *
 * 边界与约定与 Hermes 一致:任何失败都不抛异常,统一写进 [lastError] 并让 [chat] 返回 null;
 * [interrupt] 真正 cancel 在途 OkHttp Call;不含 Android 依赖,便于 JVM 单测(MockWebServer)。
 */
class OpenAiCompatibleGateway(
    private val config: OpenAiConfig,
    client: OkHttpClient? = null,
    /** 客户端历史来源:role→text(role 用 "user"/"assistant")。 */
    private val historyProvider: () -> List<Pair<String, String>> = { emptyList() },
    /** 状态文案回调:校验/退化的过程提示。 */
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
     * 探活:优先 `GET {basePath}/models`;404/405(服务端未提供模型列表)时退化到
     * 一次最小 `chat/completions`。两者都失败时把可读原因写进 [lastError]。
     */
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            _lastError = missingFieldReason()
            ready = false
            return@withContext false
        }
        // 1) 先试 GET /models(大多数 OpenAI 兼容服务都有;不必消费 token)
        val models = execute(http.newCall(request(MODELS_PATH).get().build()), coroutineContext[Job])
        when {
            models == null -> return@withContext false                 // 网络/URL 异常,lastError 已写
            models.ok -> {
                _lastError = null
                ready = true
                onStatus("OpenAI 兼容网关可用")
                return@withContext true
            }
            else -> {
                if (models.code != 404 && models.code != 405) {
                    ready = false
                    _lastError = describeHttpError(models.code, models.body)
                    onStatus(_lastError ?: "OpenAI 兼容网关连接失败")
                    return@withContext false
                }
                // 404/405:该服务没有 /models,退化到最小对话请求
                onStatus("服务无 /models 端点,改用最小对话请求校验")
            }
        }
        // 2) 最小 chat/completions:只需证明端点存在且鉴权通过
        val minimalBody = OpenAiCompat.requestBody(
            model = config.model,
            messages = listOf("user" to "ping"),
            stream = false,
        )
        val chat = execute(
            http.newCall(request(CHAT_PATH).post(minimalBody.toRequestBody(JSON_MEDIA_TYPE)).build()),
            coroutineContext[Job],
        )
        when {
            chat == null -> false
            chat.ok -> {
                _lastError = null
                ready = true
                onStatus("OpenAI 兼容网关可用")
                true
            }
            else -> {
                ready = false
                _lastError = describeHttpError(chat.code, chat.body)
                onStatus(_lastError ?: "OpenAI 兼容网关连接失败")
                false
            }
        }
    }

    /**
     * 发一段文本,返回完整回复;失败/超时/被打断返回 null。
     * 每次请求都带历史(可选 system + ConversationStore 历史 + 本轮,按 maxHistory 截断)。
     */
    override suspend fun chat(text: String): String? = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            _lastError = missingFieldReason()
            return@withContext null
        }
        if (text.isBlank()) return@withContext null

        val messages = OpenAiCompat.buildClientMessages(
            history = historyProvider(),
            text = text,
            systemPrompt = config.systemPrompt,
            maxHistory = config.maxHistory,
        )
        val body = OpenAiCompat.requestBody(config.model, messages, config.stream)
        val call = try {
            http.newCall(
                request(CHAT_PATH)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        } catch (e: Exception) {
            _lastError = "请求发送失败: ${e.message}"
            return@withContext null
        }

        activeCall = call
        val cancelHandle = registerCancelOnCoroutineCompletion(call, coroutineContext[Job])
        try {
            interrupting = false
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    _lastError = describeHttpError(resp.code, resp.body?.string().orEmpty())
                    return@withContext null
                }
                val respBody = resp.body
                val result = if (config.stream) {
                    // SSE 解析与 Hermes 共用一份实现;未知事件按畸形事件丢弃
                    respBody?.let { OpenAiCompat.readSse(it.source()) }
                } else {
                    val parsed = OpenAiCompat.parseNonStreaming(respBody?.string().orEmpty())
                    if (parsed.content == null && parsed.error != null) _lastError = parsed.error
                    parsed.content
                }
                // 被打断:即使已经收到部分文本也丢弃,由调用方的 turnId 判定丢弃过期回复
                if (interrupting) return@withContext null
                if (result.isNullOrBlank()) {
                    if (_lastError == null) _lastError = "网关回复内容为空"
                    ready = false
                    return@withContext null
                }
                _lastError = null
                ready = true
                return@withContext result
            }
        } catch (e: CancellationException) {
            // 协程被取消:同步取消 HTTP 请求,避免连接泄漏
            call.cancel()
            throw e
        } catch (e: Exception) {
            ready = false
            if (!interrupting) _lastError = describeException(e)
            return@withContext null
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

    // ---- 底层请求 ----

    /** 一次探活请求的结果;body 仅在失败时用于错误提示。 */
    private data class Probe(val ok: Boolean, val code: Int, val body: String)

    /** 执行一次探活请求;返回 null 表示网络或 URL 异常(lastError 已写)。 */
    private fun execute(call: Call, job: Job?): Probe? {
        activeCall = call
        val cancelHandle = registerCancelOnCoroutineCompletion(call, job)
        return try {
            interrupting = false
            call.execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.close()
                    Probe(true, resp.code, "")
                } else {
                    Probe(false, resp.code, resp.body?.string().orEmpty())
                }
            }
        } catch (e: CancellationException) {
            // 协程被取消(保存校验超时):同步取消请求并向上传播
            call.cancel()
            throw e
        } catch (e: Exception) {
            if (!interrupting) _lastError = describeException(e)
            null
        } finally {
            cancelHandle?.dispose()
            if (activeCall === call) activeCall = null
        }
    }

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(config.baseUrl() + path)
        if (config.apiKey.isNotBlank()) {
            b.header("Authorization", "Bearer ${config.apiKey}")
        }
        return b
    }

    /** 必填项缺失时的可读原因(model 必填是这类网关与 Hermes 的关键差异)。 */
    private fun missingFieldReason(): String = when {
        config.host.isBlank() || config.port <= 0 -> "未配置:请填写 Host 与端口"
        config.model.isBlank() -> "未配置:请填写模型名(Model,必填)"
        else -> "未配置:请检查 Host/端口/模型名"
    }

    // ---- 错误映射 ----

    /** HTTP 状态码 → 可读提示(与 Hermes 同一套说法)。 */
    private fun describeHttpError(code: Int, body: String = ""): String {
        val detail = body.take(200).replace('\n', ' ').ifBlank { "" }
        return when (code) {
            401, 403 -> "鉴权失败($code):API Key 缺失或错误"
            404 -> "服务未启用或路径错误(404):请确认 Base Path(默认 /v1)与服务是否在运行"
            429 -> "请求被限流(429):请稍后重试"
            in 500..599 -> "服务端错误($code):$detail"
            else -> "网关返回 HTTP $code:$detail"
        }
    }

    /** 网络/IO 异常 → 可读提示。 */
    private fun describeException(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时或读取超时:请检查 Host/端口与网络"
        is UnknownHostException -> "无法解析主机名 ${config.host}:请检查域名或网络"
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
        /** 模型列表端点(探活优先项,相对基址)。 */
        const val MODELS_PATH = "/models"

        /** OpenAI 兼容对话端点(相对基址)。 */
        const val CHAT_PATH = "/chat/completions"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 按配置构造 OkHttpClient;流式要放宽读超时(增量之间可能间隔较久)。 */
        private fun buildClient(config: OpenAiConfig): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(
                    if (config.stream) config.streamReadTimeoutMs else config.readTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
                .writeTimeout(15, TimeUnit.SECONDS)
            // 调试用允许自签证书开关与 Hermes 共用同一实现(默认关闭)
            OpenAiCompat.applyInsecureTlsIfNeeded(builder, config.allowInsecureTls)
            return builder.build()
        }
    }
}
