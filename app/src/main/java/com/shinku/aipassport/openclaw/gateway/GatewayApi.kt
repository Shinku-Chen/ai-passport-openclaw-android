package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenClaw 网关 REST 层:健康/Agent/模型/技能/概览/用量/通知等端点。
 *
 * 端点形状以实测为准(该网关 /v1/chat/completions、/agent/message 为 404,
 * /tools/invoke 需 Bearer token)。这里按 OpenClaw 常见 REST 端点尽力对接:
 *   GET {base}/health          -> 探活(已实测 200)
 *   GET {base}/agents          -> Agent 列表(尽力解析)
 *   GET {base}/models          -> 模型列表
 *   GET {base}/skills          -> 技能列表
 *   GET {base}/overview        -> 概览
 *   GET {base}/usage           -> 用量
 *   GET {base}/notifications   -> 通知列表(手机同步过来的通知展示)
 * token 来自 GatewaySettings,放在 Authorization: Bearer。401/404 视为端点不可用。
 */
class GatewayApi(
    private val settings: GatewaySettings,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    data class ApiResult(val ok: Boolean, val body: String, val code: Int)

    /** 通用 GET;token 存在则带 Bearer。URL 构造失败/网络异常统一捕获,不崩溃。 */
    private fun get(path: String): ApiResult {
        return try {
            val url = GatewayConfig.baseUrl(settings) + path
            val builder = Request.Builder().url(url).get()
            if (settings.token.isNotBlank()) {
                builder.header("Authorization", "Bearer ${settings.token}")
            }
            client.newCall(builder.build()).execute().use { resp ->
                ApiResult(
                    ok = resp.isSuccessful,
                    body = resp.body?.string().orEmpty(),
                    code = resp.code,
                )
            }
        } catch (e: Exception) {
            // 网关不可达/URL 非法/证书不信任:返回失败结果,不向 UI 抛异常。
            ApiResult(ok = false, body = "请求失败: ${e.message}", code = 0)
        }
    }

    /**
     * POST(带 Bearer token 鉴权)。用于调需鉴权端点(如 /tools/invoke 验证 token)。
     * 网络/URL 异常统一捕获,不崩溃。
     */
    private fun post(path: String, body: String, probeToken: String? = null): ApiResult {
        return try {
            val url = GatewayConfig.baseUrl(settings) + path
            val builder = Request.Builder().url(url)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            val token = probeToken ?: settings.token
            if (token.isNotBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            client.newCall(builder.build()).execute().use { resp ->
                ApiResult(
                    ok = resp.isSuccessful,
                    body = resp.body?.string().orEmpty(),
                    code = resp.code,
                )
            }
        } catch (e: Exception) {
            ApiResult(ok = false, body = "请求失败: ${e.message}", code = 0)
        }
    }

    /**
     * 校验网关 token 是否有效。调 /tools/invoke(需 Bearer 鉴权,实测):
     *  - 有效 token → 非 401(鉴权通过,返回 ok:false 是缺参数的业务错)
     *  - 无效/缺失 token → 401(鉴权失败)
     * @param probeToken 待校验的 token(不持久化);null 时用 settings.token。
     * 返回 true 表示 token 有效(鉴权通过),false 表示无效/网关不可达。
     */
    suspend fun verifyToken(probeToken: String? = null): Boolean = withContext(Dispatchers.IO) {
        val token = probeToken ?: settings.token
        if (token.isBlank()) return@withContext false
        val r = post("/tools/invoke", "{}", probeToken = probeToken)
        r.code != 401 && r.code != 0
    }

    suspend fun health(): ApiResult = withContext(Dispatchers.IO) { get("/health") }

    suspend fun agents(): ApiResult = withContext(Dispatchers.IO) { get("/agents") }

    suspend fun models(): ApiResult = withContext(Dispatchers.IO) { get("/models") }

    suspend fun skills(): ApiResult = withContext(Dispatchers.IO) { get("/skills") }

    suspend fun overview(): ApiResult = withContext(Dispatchers.IO) { get("/overview") }

    suspend fun usage(): ApiResult = withContext(Dispatchers.IO) { get("/usage") }

    suspend fun notifications(): ApiResult = withContext(Dispatchers.IO) { get("/notifications") }

    /** 尽力把 agents 响应解析成 id/name 列表;失败返回空表。 */
    fun parseAgents(body: String): List<AgentSummary> {
        if (body.isBlank()) return emptyList()
        return try {
            val el = JsonParser.parseString(body)
            val arr: JsonArray = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject && el.asJsonObject.has("agents") ->
                    el.asJsonObject.get("agents").asJsonArray
                el.isJsonObject && el.asJsonObject.has("result") &&
                    el.asJsonObject.get("result").isJsonArray ->
                    el.asJsonObject.get("result").asJsonArray
                else -> return emptyList()
            }
            arr.mapNotNull { o ->
                val obj = o.asJsonObject
                val id = obj.get("id")?.asString
                    ?: obj.get("key")?.asString
                    ?: obj.get("agentId")?.asString
                if (id == null) null else AgentSummary(
                    id = id,
                    name = obj.get("name")?.asString ?: id,
                    status = obj.get("status")?.asString ?: "unknown",
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 尽力把 notifications 响应解析成列表;失败返回空表。 */
    fun parseNotifications(body: String): List<NotificationItem> {
        if (body.isBlank()) return emptyList()
        return try {
            val el = JsonParser.parseString(body)
            val arr: JsonArray = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject && el.asJsonObject.has("notifications") ->
                    el.asJsonObject.get("notifications").asJsonArray
                else -> return emptyList()
            }
            arr.mapNotNull { o ->
                val obj = o.asJsonObject
                val text = obj.get("title")?.asString
                    ?: obj.get("text")?.asString
                    ?: obj.get("body")?.asString
                    ?: obj.get("message")?.asString
                // app:来源 App 名(优先 app/source/packageName 字段)
                val app = obj.get("app")?.asString
                    ?: obj.get("source")?.asString
                    ?: obj.get("packageName")?.asString
                    ?: ""
                if (text == null) null else NotificationItem(
                    title = text,
                    detail = obj.get("detail")?.asString ?: "",
                    time = obj.get("time")?.asString
                        ?: obj.get("timestamp")?.asString
                        ?: "",
                    app = app,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class AgentSummary(val id: String, val name: String, val status: String)

    data class NotificationItem(
        val title: String,
        val detail: String,
        val time: String,
        val app: String = "",
    )
}
