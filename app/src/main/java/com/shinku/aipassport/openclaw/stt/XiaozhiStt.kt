package com.shinku.aipassport.openclaw.stt

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 小智(xiaozhi.me)云端流式中文识别 —— App 作小智 WebSocket 客户端,只取 ASR(stt)文本。
 *
 * 链路(manual 模式,对应设备 PTT 的 turn_start/turn_end):
 *  设备麦克风 → [固件 Opus 编码] → BLE → App feedPcm(已收到 Opus 帧) → 原样转发小智
 *  → 小智 server 跑 ASR → 回 {"type":"stt","text":"..."} → 只取 text 喂 OpenClaw
 *  → 小智的 llm/tts 消息丢弃(我们不用小智的 LLM/TTS)。
 *
 * 关键:固件已编码 Opus,App 收到的是【Opus 帧】,直接作为 WS 二进制帧上送,不做本地
 * 解码/重编码(因此 App 无需 Opus 库)。
 *
 * WS 协议(已实测):连接 wss://api.tenclass.net/xiaozhi/v1/,HTTP 头带
 *  Device-Id/Client-Id/Protocol-Version;必须先发客户端 hello,否则服务器立即 close。
 *  服务器回 hello{sample_rate:24000(下行TTS),session_id};发 listen.start(manual);
 *  上送 Opus 二进制帧;发 listen.stop;收 stt 文本。
 */
class XiaozhiStt(
    private val serverUrl: String,          // 如 wss://api.tenclass.net/xiaozhi/v1/
    private val token: String,              // 如 test-token
    private val deviceId: String,           // 设备 MAC(小智按 Device-Id 白名单登记)
    onPartial: ((String) -> Unit)? = null,
) : SttEngine {

    private val tag = "XiaozhiStt"
    private val gson = Gson()

    private companion object {
        /** 小智 Client-Id(写死,与网页端注册的设备一致,保持稳定不变)。 */
        const val CLIENT_ID = "1dd91545-082a-454e-a131-1c8251375c9c"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // 会话状态
    private var ws: WebSocket? = null

    /** 本 turn 最新一条 stt 文本(小智边识边发,取最后一次作结果)。 */
    @Volatile private var lastStt = ""

    // 缓存 session_id 与 downstream 参数(必要时回填)
    private var sessionId: String? = null

    @Volatile private var listening = false

    /** turn 是否在进行(startTurn 置 true,endTurn/barge 置 false)。feedPcm 据此刻允许上送,不依赖 hello 回包。 */
    @Volatile private var turnRunning = false

    /** Opus 编码器(libopus JNI):把设备 PCM 编成小智要的 16k 裸 Opus 帧。 */
    private var opusEncoder: Opus? = null

    /** 16k 60ms 帧 = 960 samples = 1920 字节 int16。 */
    private val opusFrameBytes = 960 * 2

    /** PCM 累积缓冲:喂不满一帧的余量,凑满 60ms 再编码。 */
    private val pcmBuffer = ByteArray(opusFrameBytes * 2)

    /** pcmBuffer 当前已填字节数。 */
    private var pcmLen = 0

    /** 收到服务器 hello 后回调,供 startTurn 决定是否 startListening。 */
    private var onHelloCallback: ((Boolean) -> Unit)? = null

    /** 识别文本(含中间 stt)每句回调。 */
    override val onPartial: ((String) -> Unit)? = onPartial

    override val isAvailable: Boolean
        get() = serverUrl.isNotBlank()

    /** 小智 websocket 是否当前活跃连接(供连接监控器定时检测)。 */
    fun isConnected(): Boolean = ws != null

    override fun startTurn() {
        // 确保连接 + 发 hello;成功后发 listen.start。若失败,置 listening=false,endTurn 返回 null。
        listening = false
        turnRunning = true      // 立即允许 feedPcm 累积编码上送(不等 hello 回包,避免开头 PCM 丢失)
        lastStt = ""
        pcmLen = 0
        initOpusEncoder()
        onHelloCallback = { ok ->
            if (ok) {
                startListening()
                listening = true
            }
            // 失败:listening 保持 false,endTurn 已 guard 返回 null
        }
        connectAndHello(onHelloCallback!!)
    }

    /** 初始化 Opus 编码器(16k 单声道,audio 应用模式,兼容小智上行)。 */
    private fun initOpusEncoder() {
        try {
            if (opusEncoder == null) {
                val enc = Opus()
                enc.encoderInit(
                    Constants.SampleRate._16000(),
                    Constants.Channels.mono(),
                    Constants.Application.audio(),
                )
                opusEncoder = enc
                Log.i(tag, "Opus 编码器已初始化 16k/mono")
            }
        } catch (e: Exception) {
            Log.e(tag, "Opus 编码器初始化失败", e)
            opusEncoder = null
        }
    }

    override fun feedPcm(pcm: ByteArray) {
        // 固件发来的是【原始 PCM int16 16k】;App 累积到 60ms(960 samples/1920B)编成 Opus 帧上送小智。
        val socket = ws ?: return
        val enc = opusEncoder ?: return
        if (!turnRunning) return   // 用 turnRunning(不等 hello 回包),避免开头 PCM 丢失
        try {
            // 累积输入缓冲(天然是整数个 1024B 块,但 1920B/帧 不整除 → 需跨块缓冲)
            var idx = 0
            while (idx < pcm.size) {
                // 计算缓冲剩余空间
                val space = pcmBuffer.size - pcmLen
                if (space <= 0) break   // 缓冲满,丢弃多余(丢帧)
                val take = minOf(space, pcm.size - idx)
                System.arraycopy(pcm, idx, pcmBuffer, pcmLen, take)
                pcmLen += take
                idx += take
                // 凑齐一帧(1920B=960 samples=60ms)就编码上送
                while (pcmLen >= opusFrameBytes) {
                    val frame = pcmBuffer.copyOfRange(0, opusFrameBytes)
                    val opusData = enc.encode(frame, Constants.FrameSize._960())
                    if (opusData != null && opusData.isNotEmpty()) {
                        socket.send(okio.ByteString.of(*opusData))   // 二进制帧(opcode 0x2)
                    }
                    // 挪走已编码的前 1920B
                    System.arraycopy(pcmBuffer, opusFrameBytes, pcmBuffer, 0, pcmLen - opusFrameBytes)
                    pcmLen -= opusFrameBytes
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "feedPcm 编码/上送失败", e)
        }
    }

    override suspend fun endTurn(): String? = withContext(Dispatchers.IO) {
        if (!listening) return@withContext null
        // 发 listen.stop 结束本段,小智会停止本次识别。
        stopListening()
        listening = false
        turnRunning = false
        // 循环等待 stt 文本到达(小智识别可能需要几百 ms~几秒),直到收到或超时(6s)。
        // 之前只等 500ms 太短,服务器识别慢时会错过 stt → 误判"未识别到语音"。
        val deadline = System.currentTimeMillis() + 6_000
        while (lastStt.isBlank() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(200)
        }
        val result = lastStt.takeIf { it.isNotBlank() }
        // 用完即清理,下轮 startTurn 重建
        releaseSocket()
        result
    }

    override fun barge() {
        // 打断:取消等待、关闭会话;新一轮 startTurn 会重连。
        listening = false
        turnRunning = false
        releaseSocket()
        releaseOpus()
    }

    private fun releaseOpus() {
        try { opusEncoder?.encoderRelease() } catch (_: Exception) {}
        opusEncoder = null
        pcmLen = 0
    }

    override fun release() = barge()

    // ---- 连接与协议 ----

    private fun connectAndHello(onReady: (Boolean) -> Unit) {
        val req = Request.Builder()
            .url(serverUrl)
            // 小智：必须先带 Device-Id/Client-Id/Protocol-Version 握手头 + 发 hello,否则立即 close
            // Client-Id 写死为固定值(与网页端注册的设备一致),保持稳定不变。
            .addHeader("Authorization", token.ifBlank { "test-token" })
            .addHeader("Protocol-Version", "1")
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", CLIENT_ID)
            .build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "小智 WS 已连接 code=${response.code} ${response.message},发送 hello")
                sendHello(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "收到小智文本: ${text.take(200)}")
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "小智 WS 失败 code=${response?.code} ${response?.message}: ${t.message}")
                onHelloCallback = null
                listening = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(tag, "小智 WS 关闭 $code $reason")
                listening = false
            }
        })
        ws = socket
        // onReady 由 handleServerMessage 收到 hello 后触发(存于 onReady);此函数只建连+发 hello
    }

    private fun sendHello(webSocket: WebSocket) {
        val hello = JsonObject().apply {
            addProperty("type", "hello")
            addProperty("version", 1)
            addProperty("transport", "websocket")
            add("audio_params", JsonObject().apply {
                addProperty("format", "opus")
                addProperty("sample_rate", 16000)   // 上行 ASR 用 16k;服务器下行 TTS 24k 不影响上行
                addProperty("channels", 1)
                addProperty("frame_duration", 60)
            })
        }
        webSocket.send(hello.toString())
    }

    private fun startListening() {
        val sid = sessionId ?: "probe"
        val start = JsonObject().apply {
            addProperty("session_id", sid)
            addProperty("type", "listen")
            addProperty("state", "start")
            addProperty("mode", "manual")
        }
        ws?.send(start.toString())
    }

    private fun stopListening() {
        val sid = sessionId ?: "probe"
        val stop = JsonObject().apply {
            addProperty("session_id", sid)
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        ws?.send(stop.toString())
    }

    private fun handleServerMessage(text: String) {
        try {
            val obj = gson.fromJson(text, JsonObject::class.java) ?: return
            when (obj.get("type")?.asString) {
                "hello" -> {
                    // 服务器回 hello:记录 session_id,然后通知 startTurn 可以 startListening
                    obj.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString?.let { sessionId = it }
                    Log.i(tag, "小智 hello 回包: ${obj.toString()}")
                    onHelloCallback?.invoke(true)
                    onHelloCallback = null
                }
                "stt" -> {
                    // 识别结果。小智边识边发 stt(每条可能是部分/最终),取最新一条作最终结果,
                    // 同时回调 onPartial 让设备屏实时上屏。
                    val textVal = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!textVal.isNullOrBlank()) {
                        lastStt = textVal
                        onPartial?.invoke(textVal)
                    }
                }
                // llm/tts 是小智的回复/TTS,我们不用,忽略
                "llm", "tts" -> Unit
                "error" -> Log.w(tag, "小智端错误: ${obj.toString()}")
                else -> Unit
            }
        } catch (e: Exception) {
            Log.e(tag, "解析小智消息失败", e)
        }
    }

    private fun releaseSocket() {
        try { ws?.close(1000, "done") } catch (_: Exception) {}
        ws = null
    }
}
