package com.shinku.aipassport.openclaw.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okio.BufferedSource
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * OpenAI 兼容对话的共用实现(Hermes 与「自定义 OpenAI 兼容」两类网关逐字相同的那部分)。
 *
 * 两条通道都走 `POST <base>/chat/completions` + `Authorization: Bearer`:
 * 请求体结构、`choices[0].message.content` 提取、SSE 增量拼接完全一致,
 * 区别只在基址、鉴权字段与历史上限。因此这里只保留一份实现,避免出现两份会各自漂移的解析器:
 *
 *  - [buildClientMessages]:客户端历史模式的消息拼装(可选 system + 历史 + 本轮,按上限截断)
 *  - [parseNonStreaming]:非流式响应体 → 正文 / 可读错误
 *  - [readSse]:SSE 流式逐事件拼接(忽略工具进度与未知事件)
 *  - [applyInsecureTlsIfNeeded]:调试用「信任所有证书」开关,统一由这里构造(默认关闭)
 *
 * 约定:所有方法都不抛异常;失败原因以字符串返回,由调用方写进各自的 lastError。
 */
internal object OpenAiCompat {

    /**
     * 组装客户端模式的消息序列:可选 system + 历史(user/assistant 顺序) + 本轮用户文本。
     *
     * 要点:
     *  - [maxHistory] 只限制「历史」条数(取最近 N 条),system 与本轮永远保留;
     *  - 历史末尾可能已经包含本轮用户文本(VoicePipeline/ChatFragment 先把文本写进
     *    ConversationStore 再调 chat),此时不再重复追加;
     *  - 空历史时也能得到 [system?] + [user],不会发出空 messages。
     *
     * @param history role→text 列表(role 用 "user"/"assistant")
     * @param text 本轮用户文本
     * @param systemPrompt 可选系统提示;空白视为不发送
     * @param maxHistory 历史条数上限(<=0 表示不限)
     */
    fun buildClientMessages(
        history: List<Pair<String, String>>,
        text: String,
        systemPrompt: String? = null,
        maxHistory: Int = 0,
    ): List<Pair<String, String>> {
        val msgs = ArrayList<Pair<String, String>>(history.size + 2)
        if (!systemPrompt.isNullOrBlank()) msgs.add("system" to systemPrompt)
        msgs.addAll(if (maxHistory > 0) history.takeLast(maxHistory) else history)
        val last = msgs.lastOrNull()
        if (last == null || last.first != "user" || last.second != text) {
            msgs.add("user" to text)
        }
        return msgs
    }

    /** 非流式解析结果:成功给 [content],失败给可读 [error](两者必有其一)。 */
    data class NonStreamResult(val content: String?, val error: String?)

    /** 非流式:取 `choices[0].message.content`(content 为块数组时拼接各 text)。 */
    fun parseNonStreaming(body: String): NonStreamResult {
        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return NonStreamResult(null, "网关返回的不是合法 JSON")
        }
        json.getAsJsonObject("error")?.let { err ->
            // OpenAI 兼容错误体:{error:{message,type,code}}
            val message = err.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: "网关返回错误: ${err.toString().take(200)}"
            return NonStreamResult(null, message)
        }
        val choices = json.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            return NonStreamResult(null, "网关回复缺少 choices[0]")
        }
        val first = choices[0].takeIf { it.isJsonObject }?.asJsonObject
            ?: return NonStreamResult(null, "网关回复 choices[0] 结构异常")
        val content = textOf(first.getAsJsonObject("message")?.get("content"))
        if (content.isNullOrBlank()) return NonStreamResult(null, "网关回复内容为空")
        return NonStreamResult(content, null)
    }

    /**
     * SSE 流式:逐行读 `data:` 负载,拼接 `chat.completion.chunk` 的增量文本。
     *
     * 健壮性要点(与 Hermes 实测一致):
     *  - 用 BufferedSource.readUtf8Line(),天然处理跨 TCP 缓冲/跨行的分片
     *  - 事件以空行分隔,多行 data 按 SSE 规范用换行拼接(模型输出里的换行不丢)
     *  - `data: [DONE]` 结束
     *  - [ignoreEventSubstring] 非空时,事件名或负载里包含它的整条事件被忽略
     *    (Hermes 用它跳过 `hermes.tool.progress`);未知/畸形事件直接丢弃,不影响整段回复
     */
    fun readSse(source: BufferedSource, ignoreEventSubstring: String? = null): String? {
        val sb = StringBuilder()
        var eventName: String? = null
        val dataLines = mutableListOf<String>()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                // 空行 = 一个事件结束
                if (dataLines.isNotEmpty()) {
                    val payload = dataLines.joinToString("\n").trim()
                    dataLines.clear()
                    // [DONE] = 正常收尾;服务端可能不主动断连,必须主动跳出,否则会卡到读超时
                    if (payload == "[DONE]") break
                    appendSseEvent(sb, eventName, payload, ignoreEventSubstring)
                }
                eventName = null
                continue
            }
            when {
                line.startsWith("data:") -> dataLines.add(line.substring(5).trimStart())
                line.startsWith("event:") -> eventName = line.substring(6).trim()
                line.startsWith(":") -> Unit        // SSE 注释/心跳
                else -> Unit                        // id:/retry:/未知字段,忽略
            }
        }
        // 服务端未以空行收尾时,冲出最后一段
        if (dataLines.isNotEmpty()) {
            appendSseEvent(sb, eventName, dataLines.joinToString("\n"), ignoreEventSubstring)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 处理单个 SSE 事件:跳过被忽略的事件与 [DONE],其余按 chunk 取增量文本。 */
    private fun appendSseEvent(
        sb: StringBuilder,
        eventName: String?,
        rawPayload: String,
        ignoreEventSubstring: String?,
    ) {
        val payload = rawPayload.trim()
        if (payload.isEmpty() || payload == "[DONE]") return
        if (ignoreEventSubstring != null) {
            if (eventName != null && eventName.contains(ignoreEventSubstring)) return
            // 有些实现不带 event 行,只在 data 里标类型,这里再兜一层
            if (payload.contains(ignoreEventSubstring)) return
        }
        val json = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (e: Exception) {
            return   // 单条畸形/未知事件不影响整段回复
        }
        textOf(extractChunkText(json))?.let { sb.append(it) }
    }

    /** 从 chunk 里取增量文本:优先 delta.content,其次 message.content / text。 */
    private fun extractChunkText(json: JsonObject): JsonElement? {
        val choices = json.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) return null
        val first = choices[0].takeIf { it.isJsonObject }?.asJsonObject ?: return null
        first.getAsJsonObject("delta")?.get("content")?.let { return it }
        first.getAsJsonObject("message")?.get("content")?.let { return it }
        first.get("text")?.let { return it }
        return null
    }

    /** 把 content 字段转成文本:支持字符串,也支持 [{type:"text", text:"…"}] 块数组。 */
    private fun textOf(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            return element.asString.takeIf { it.isNotEmpty() }
        }
        if (element.isJsonArray) {
            val joined = element.asJsonArray.mapNotNull { el ->
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    o.get("text")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                        ?: o.get("content")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                } else if (el.isJsonPrimitive) {
                    el.asString
                } else {
                    null
                }
            }.joinToString("")
            return joined.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** 组装 OpenAI 兼容请求体 `{model, messages:[{role,content}], stream}`。 */
    fun requestBody(model: String, messages: List<Pair<String, String>>, stream: Boolean): String {
        val arr = JsonArray()
        messages.forEach { (role, content) ->
            arr.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }
        return JsonObject().apply {
            addProperty("model", model)
            add("messages", arr)
            addProperty("stream", stream)
        }.toString()
    }

    /**
     * 仅调试用:允许自签证书(默认关闭)。
     *
     * 注意:它信任所有证书并跳过主机名校验,只用于内网自签联调;
     * 生产与公网环境必须保持关闭,与 `network_security_config` 的域级信任互不替代。
     */
    fun applyInsecureTlsIfNeeded(builder: OkHttpClient.Builder, allow: Boolean) {
        if (!allow) return
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        val factory: SSLSocketFactory = ctx.socketFactory
        builder.sslSocketFactory(factory, trustAll[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }
}
