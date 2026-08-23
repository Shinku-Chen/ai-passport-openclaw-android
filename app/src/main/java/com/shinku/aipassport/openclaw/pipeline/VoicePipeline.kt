package com.shinku.aipassport.openclaw.pipeline

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shinku.aipassport.openclaw.gateway.GatewayClient
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbFrameData
import com.shinku.aipassport.openclaw.stt.SttEngine
import com.shinku.aipassport.openclaw.tts.TtsEngine
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
    private val sendText: (String) -> Unit,
    private val onState: (String) -> Unit,
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
        when (frame.type) {
            VbFrame.TYPE_AUDIO -> handleAudio(frame.payload)
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
        // barge:打断正在进行的 TTS / 识别 / 网关等待
        turnId++
        turnActive = true
        tts.stop()
        stt.barge()
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
                return@launch
            }
            Log.i(tag, "识别结果: $text")
            onState("识别: $text")

            val reply = gateway.chat(text)
            if (myTurn != turnId) return@launch
            if (reply.isNullOrBlank()) {
                Log.w(tag, "网关无回复")
                onState("网关无回复")
                return@launch
            }
            Log.i(tag, "网关回复: $reply")
            onState("回复: $reply")
            speak(reply)
        }
    }

    private fun speak(reply: String) {
        val myTurn = turnId
        tts.speak(reply, object : TtsEngine.Listener {
            override fun onDone(text: String) {
                // 合成自然结束 → 回传 TEXT 帧给固件上屏
                if (myTurn == turnId) {
                    Log.i(tag, "TTS 结束,回传文本: $text")
                    sendText(text)
                }
            }

            override fun onInterrupted(text: String) {
                Log.d(tag, "TTS 被打断(不回传)")
            }
        })
    }
}
