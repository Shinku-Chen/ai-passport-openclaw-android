package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OpenClaw 网关 WebSocket 客户端(文本输入 → 文本回复)。
 *
 * 实时协议(已对线上网关逐帧验证):
 *  1. 连接 wss://<host>:<port><wsPath>?sessionKey=main(默认 /message/messages/ws,可配置)
 *     (带 Origin 头 = 网关自身 origin,否则 CONTROL_UI_ORIGIN_NOT_ALLOWED)
 *  2. 服务端发 connect.challenge{nonce};客户端回 connect:
 *     {type:"req", id:<uuid>, method:"connect", params:{minProtocol:4, maxProtocol:4,
 *       client:{id:"openclaw-android",...}, role:"operator", scopes:["operator.write"],
 *       device:{id, publicKey, signature, signedAt, nonce}, caps:["tool-events"],
 *       auth:{token, password}, userAgent, locale}}
 *     - client.id 必须为网关白名单值(openclaw-android 合法)
 *     - device 必须带 publicKey + ed25519 签名;未知设备触发配对(需主机 openclaw devices approve)
 *  3. connect 成功 → res 携带 auth.deviceToken(持久化,之后可免签复用) → 可发 chat.send
 *  4. chat.send{sessionKey, message, idempotencyKey, deliver:false, agentId:"main"} → res 带 runId
 *  5. 服务端以 chat 事件流式返回 assistant 文本(delta/final),收齐 final 即完整回复
 *
 * 帧格式:客户端请求 {type:"req", id:<uuid-string>, method, params};服务端回复 {type:"res", id, ok, payload|error}
 *
 * token 来自 App 内设置(GatewaySettings)。鉴权/配对/超时失败返回 null,流水线静默降级。
 */
class GatewayClient(
    context: Context,
    private val settings: GatewaySettings,
    private val onStatus: (String) -> Unit = {},
) {

    private val tag = "GatewayClient"
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = DeviceIdentity(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)          // WS 长连,不因空闲断开
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val pendingReqs = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()

    @Volatile
    private var ws: WebSocket? = null

    /** 鉴权完成(connect.res 返回 ok) */
    @Volatile
    private var connected = false

    /** 当前进行中的一次对话(串行一次一条) */
    @Volatile
    private var activeCollector: ReplyCollector? = null

    /** 服务端 connect.challenge 下发的 nonce,用于签名 */
    @Volatile
    private var pendingNonce: String? = null

    private val lock = Any()

    /**
     * 发一段文本,返回网关完整回复文本;失败/超时/未配对返回 null。
     */
    suspend fun chat(text: String): String? = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext null
        val runId = sendChat(text)
        collectReply(runId)
    }

    /** 网关是否已鉴权可对话。 */
    fun isConnected(): Boolean = connected

    /** 设备是否已配对(有 deviceToken)。 */
    fun isPaired(): Boolean = identity.deviceToken.isNotBlank()

    fun close() {
        scope.cancel()
        try { ws?.close(1000, "shutdown") } catch (_: Exception) {}
        pendingReqs.values.forEach { it.complete(null) }
        pendingReqs.clear()
        activeCollector?.finish()
        activeCollector = null
    }

    // ---- 连接与鉴权 ----

    private suspend fun ensureConnected(): Boolean {
        if (connected) return true
        synchronized(lock) {
            if (ws == null) openSocket()
        }
        // 等待鉴权完成(connect.res ok)或失败,最多约 15s
        repeat(75) {
            if (connected) return true
            if (ws == null) return false
            kotlinx.coroutines.delay(200)
        }
        return false
    }

    private fun openSocket() {
        if (!settings.isConfigured()) {
            Log.w(tag, "网关未配置(请在 App 设置里填域名/端口)")
            onStatus("网关未配置,请在 App 设置中填写")
            return
        }
        val request = Request.Builder()
            .url(GatewayConfig.wsUrl(settings))
            // 网关要求 Origin 为网关自身 origin,否则 CONTROL_UI_ORIGIN_NOT_ALLOWED
            .addHeader("Origin", GatewayConfig.baseUrl(settings))
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WS 已连接,等待 challenge")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WS 失败", t)
                onSocketDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WS 关闭 $code $reason")
                onSocketDown()
            }
        })
        ws = socket
    }

    private fun onSocketDown() {
        connected = false
        ws = null
        pendingReqs.values.forEach { it.complete(null) }
        pendingReqs.clear()
        activeCollector?.finish()
        activeCollector = null
    }

    private fun handleFrame(text: String) {
        try {
            val obj = JsonParser.parseString(text).asJsonObject
            when (obj.get("type")?.asString) {
                "event" -> {
                    when (obj.get("event")?.asString) {
                        "connect.challenge" -> {
                            pendingNonce = obj.getAsJsonObject("payload")?.get("nonce")?.asString
                            sendConnect()
                        }
                        else -> handleReplyEvent(obj)
                    }
                }
                "response", "result", "message", "assistant.message" -> {
                    handleReplyFrame(obj)
                }
                "res" -> {
                    val id = obj.get("id")?.asString ?: return
                    val deferred = pendingReqs.remove(id) ?: return
                    if (obj.get("ok")?.asBoolean == true) {
                        val payload = obj.getAsJsonObject("payload")
                        // connect 成功后持久化 deviceToken
                        if (payload != null && payload.has("auth")) {
                            payload.getAsJsonObject("auth")?.get("deviceToken")?.asString
                                ?.takeIf { it.isNotBlank() }
                                ?.let { identity.deviceToken = it }
                        }
                        deferred.complete(payload)
                    } else {
                        val err = obj.getAsJsonObject("error")
                        val code = err?.get("code")?.asString
                        val message = err?.get("message")?.asString
                        Log.e(tag, "RPC 错误 [$code]: $message")
                        if (code == "INVALID_REQUEST" && message?.contains("device") == true) {
                            Log.w(tag, "设备未配对,需在网关主机执行 openclaw devices approve")
                            onStatus("网关设备待配对:请在网关主机执行 openclaw devices approve")
                        }
                        deferred.complete(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "帧解析失败", e)
        }
    }

    private fun sendConnect() {
        val nonce = pendingNonce ?: UUID.randomUUID().toString()
        val scopes = "operator.read"
        // 实测确认:client.id=openclaw-control-ui + mode=webchat + protocol=4 走 token 优先鉴权
        // (错误 token 返回 AUTH_TOKEN_MISMATCH;正确 token 即通过,无需设备配对签名)。
        // 不携带 device 块(control-ui 路径 token 是唯一门禁)。
        val params = JsonObject().apply {
            addProperty("minProtocol", 4)
            addProperty("maxProtocol", 4)
            add("client", JsonObject().apply {
                addProperty("id", "openclaw-control-ui")
                addProperty("version", "0.1.0")
                addProperty("platform", "android")
                addProperty("mode", "webchat")
            })
            addProperty("role", "operator")
            add("scopes", gson.toJsonTree(listOf("operator.read", "operator.write")))
            add("caps", gson.toJsonTree(emptyList<String>()))
            add("commands", gson.toJsonTree(emptyList<String>()))
            add("permissions", JsonObject())
            add("auth", JsonObject().apply {
                // 运行时注入的网关 token(设置页 SharedPreferences);默认空 → AUTH_TOKEN_MISSING
                addProperty("token", settings.token)
            })
            addProperty("locale", "zh-CN")
            addProperty("userAgent", "passport-android/0.1.0")
        }
        request("connect", params)?.let { deferred ->
            scope.launch {
                val ok = withTimeoutOrNull(10_000) { deferred.await() } != null
                connected = ok
                if (!ok) {
                    Log.w(tag, "网关 connect 鉴权失败(检查 token 是否正确填写)")
                    onStatus("网关鉴权失败:请在设置页检查 token")
                }
            }
        }
    }

    // ---- chat.send 与回复收集 ----

    private suspend fun sendChat(text: String): String? {
        val idempotency = UUID.randomUUID().toString()
        val params = JsonObject().apply {
            addProperty("sessionKey", GatewayConfig.SESSION_KEY)
            addProperty("message", text)
            addProperty("deliver", false)
            addProperty("idempotencyKey", idempotency)
            addProperty("agentId", "main")
        }
        val reply = requestSync("chat.send", params)
        return reply?.get("runId")?.asString
    }

    private suspend fun collectReply(runId: String?): String? {
        val collector = ReplyCollector()
        collector.runId = runId
        activeCollector = collector
        return try {
            withTimeoutOrNull(GatewayConfig.TIMEOUT_SECONDS * 1000) {
                collector.deferred.await()
            }
        } finally {
            if (activeCollector === collector) activeCollector = null
        }
    }

    /** 处理下行事件(event 包),按 OpenClaw 通用结构解析文本。 */
    private fun handleReplyEvent(obj: JsonObject) {
        val payload = obj.getAsJsonObject("payload") ?: return
        handleReplyPayload(payload)
    }

    /** 处理非 event 类型下行帧(response/result/message/assistant.message)。 */
    private fun handleReplyFrame(obj: JsonObject) {
        handleReplyPayload(obj)
    }

    /**
     * 从下行 payload 提取文本,兼容多种 OpenClaw 消息结构:
     *  - {state:delta|final, message:{content}, runId}
     *  - {message:{text}} / {text}
     *  - {reply} / {response} / {answer} / {content}
     *  - {choices:[{message:{content}}]}
     */
    private fun handleReplyPayload(payload: JsonObject) {
        val collector = activeCollector ?: return
        val evRunId = payload.get("runId")?.asString
        if (evRunId != null && collector.runId == null) collector.runId = evRunId
        if (collector.runId != null && evRunId != null && evRunId != collector.runId) return

        val text = extractText(payload) ?: return
        if (text.isNotBlank()) collector.append(text)

        // 终结判定:state==final 或 isFinal/final==true 或 delta 不再出现
        val state = payload.get("state")?.asString
        val isFinal = payload.get("isFinal")?.asBoolean
            ?: payload.get("final")?.asBoolean
            ?: false
        if (state == "final" || isFinal) collector.finish()
    }

    /** 尽量从各种结构的 JSON 里抠出文本。 */
    private fun extractText(payload: JsonObject): String? {
        // 1. {message:{content|text}}
        payload.getAsJsonObject("message")?.let { m ->
            m.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
            m.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // 2. {choices:[{message:{content}}]}
        payload.getAsJsonArray("choices")?.let { choices ->
            if (choices.size() > 0) {
                val first = choices[0]
                if (first.isJsonObject) {
                    first.asJsonObject.getAsJsonObject("message")
                        ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        // 3. 顶层文本字段
        listOf("text", "content", "reply", "response", "answer").forEach { k ->
            payload.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // ---- 底层请求 ----

    private fun request(method: String, params: JsonObject): CompletableDeferred<JsonObject?>? {
        val socket = ws ?: return null
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject?>()
        pendingReqs[id] = deferred
        val frame = JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        return try {
            socket.send(frame.toString())
            deferred
        } catch (e: Exception) {
            pendingReqs.remove(id)
            Log.e(tag, "发送 $method 失败", e)
            null
        }
    }

    private suspend fun requestSync(method: String, params: JsonObject): JsonObject? {
        val deferred = request(method, params) ?: return null
        return withTimeoutOrNull(GatewayConfig.TIMEOUT_SECONDS * 1000) { deferred.await() }
    }

    private class ReplyCollector {
        val deferred = CompletableDeferred<String?>()
        val sb = StringBuilder()
        @Volatile var finished = false
        @Volatile var runId: String? = null
        private val lock = Any()
        fun append(text: String) {
            synchronized(lock) { if (!finished) sb.append(text) }
        }
        fun finish() {
            synchronized(lock) {
                if (!finished) { finished = true; deferred.complete(sb.toString().takeIf { it.isNotBlank() }) }
            }
        }
    }
}
