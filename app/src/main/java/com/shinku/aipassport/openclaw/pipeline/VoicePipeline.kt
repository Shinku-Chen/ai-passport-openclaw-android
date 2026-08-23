package com.shinku.aipassport.openclaw.pipeline

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shinku.aipassport.openclaw.gateway.GatewayClient
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbFrameData
import com.shinku.aipassport.openclaw.stt.SttEngine
import com.shinku.aipassport.openclaw.tts.TtsEngine
import com.shinku.aipassport.openclaw.ui.ConversationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 语音流水线编排:
 *
 *  AUDIO 帧 --STT--> 文本 --网关--> 回复 --TTS--> 播放 --合成结束--> TEXT 帧回传固件上屏
 *
 * 由固件 EVENT(turn_start / turn_end)驱动一段对话的起止:
 *  - turn_start:打断任何进行中的 TTS/识别(即 barge,用户再按 PTT 立即打断),
 *    开始新一轮 STT。
 *  - AUDIO:喂 STT。
 *  - turn_end:STT 出文本 → 网关回复 → TTS 播放;TTS 正常结束才把文本回传设备。
 *
 * [sendText] 由 Service 注入,负责把 TEXT 帧经 BLE 发给固件。
 */
class VoicePipeline(
    private val scope: CoroutineScope,
    private val stt: SttEngine,
    private val gateway: GatewayClient,
    private val tts: TtsEngine,
    /** 回传 TEXT 帧给固件上屏。role: 'U'=用户语音识别文本,'A'=网关回复。 */
    private val sendText: (role: Char, text: String) -> Unit,
    private val onState: (String) -> Unit,
    private val clearPendingWrites: () -> Unit = {},
) {
    private val tag = "VoicePipeline"
    private val gson = Gson()

    /** 每次 turn_start 自增,用于判定结果是否已过期(barge)。TTS 回调在 binder 线程,需 volatile。 */
    @Volatile
    private var turnId = 0

    @Volatile
    private var turnActive = false

    fun prewarm() {
        scope.launch(Dispatchers.Default) { stt.prewarm() }
    }

    /** 从帧重组器来的完整帧。 */
    fun handleFrame(frame: VbFrameData) {
        // 调试:打印吐出的每帧 type + payload 前缀(定位帧重组错位)
        val dbg = frame.payload.take(24).joinToString(" ") { "%02x".format(it) }
        Log.i(tag, "帧 type=${frame.type} flags=${frame.flags} len=${frame.payload.size}: $dbg")
        when (frame.type) {
            VbFrame.TYPE_AUDIO -> handleAudio(frame.payload)
            VbFrame.TYPE_OPUS -> handleAudio(frame.payload)   // Opus 帧也交给识别端(小智会转发)
            VbFrame.TYPE_EVENT -> handleEvent(frame.payload)
            VbFrame.TYPE_TEXT -> Log.d(tag, "设备回传文本(忽略): ${String(frame.payload, Charsets.UTF_8)}")
            VbFrame.TYPE_CONTROL -> Unit
            else -> Unit
        }
    }

    /** BLE 断开:终止一切在途状态。 */
    fun onDisconnected() {
        turnId++
        turnActive = false
        tts.stop()
        stt.barge()
    }

    /** 服务销毁:停止 TTS 并释放识别引擎。 */
    fun shutdown() {
        turnId++
        turnActive = false
        tts.stop()
        stt.release()
    }

    // ---- 帧处理 ----

    private fun handleAudio(payload: ByteArray) {
        if (turnActive) stt.feedPcm(payload)
    }

    private fun handleEvent(payload: ByteArray) {
        val ev = try {
            val str = String(payload, Charsets.UTF_8)
            val obj = gson.fromJson(str, JsonObject::class.java)
            if (obj != null) {
                obj.get("ev")?.takeIf { it.isJsonPrimitive }?.asString
            } else {
                // payload 可能是纯字符串事件(如 "turn_start"),兼容非 JsonObject
                str.trim().trim('"').takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(tag, "EVENT 解析失败", e)
            return
        }
        when (ev) {
            "turn_start" -> onTurnStart()
            "turn_end" -> onTurnEnd()
            else -> Unit
        }
    }

    // ---- turn 起止 ----

    private fun onTurnStart() {
        // barge:打断正在进行的 TTS / 识别 / 网关等待。
        // 若上一轮网关回复还在收集中(activeCollector 被旧轮占用),先中断它,释放单例
        // 收集器,避免新一轮 chat.send 与其冲突导致发送失败/回复错配。
        turnId++
        turnActive = true
        tts.stop()
        stt.barge()
        gateway.interruptCurrent()
        clearPendingWrites()
        stt.startTurn()
        onState("录音中…")
    }

    private fun onTurnEnd() {
        if (!turnActive) return
        turnActive = false
        val myTurn = turnId
        scope.launch {
            val text = stt.endTurn()
            if (myTurn != turnId) return@launch   // 已被新一轮打断
            if (text.isNullOrBlank()) {
                Log.d(tag, "未识别到语音")
                onState("未识别到语音")
                // 反馈硬件:未识别到语音 → 设备屏显示"无语音"
                ConversationStore.add("agent", "无语音", ConversationStore.SOURCE_VOICE)
                sendText('A', "无语音")
                return@launch
            }
            Log.i(tag, "识别结果: $text")
            // 识别结果仅在对话列表 + 设备屏展示,不污染顶部状态栏(状态栏只显示流程状态)
            ConversationStore.add("user", text, ConversationStore.SOURCE_VOICE)
            // 识别文本先回传设备屏(role='U' 用户),让用户立即看到自己说的内容
            sendText('U', text)

            // 网关请求可能因网络/超时/异常失败;必须兜底回传 role='A',否则设备卡"接收中"。
            val reply = try {
                gateway.chat(text)
            } catch (e: Exception) {
                Log.e(tag, "gateway.chat 异常", e)
                null
            }
            if (myTurn != turnId) return@launch
            if (reply.isNullOrBlank()) {
                Log.w(tag, "网关无回复")
                ConversationStore.add("agent", "(网关无回复)", ConversationStore.SOURCE_VOICE)
                sendText('A', "(网关无回复)")
                return@launch
            }
            Log.i(tag, "网关回复: $reply")
            // 回复仅在对话列表 + 设备屏展示,不污染顶部状态栏
            ConversationStore.add("agent", reply, ConversationStore.SOURCE_VOICE)
            // 网关回复立即回传设备屏(role='A'),不阻塞 TTS
            sendText('A', reply)
            speak(reply)
        }
    }

    private fun speak(reply: String) {
        val myTurn = turnId
        tts.speak(reply, object : TtsEngine.Listener {
            // 回复已在 onTurnEnd sendText('A', reply) 立即回传设备;TTS 只负责播声音,
            // 不再重复回传,避免设备收到重复"助手"消息。onDone/onInterrupted 仅日志。
            override fun onDone(text: String) {
                Log.i(tag, "TTS 结束: $text")
            }
            override fun onInterrupted(text: String) {
                Log.d(tag, "TTS 未完成: $text")
            }
        })
    }
}
