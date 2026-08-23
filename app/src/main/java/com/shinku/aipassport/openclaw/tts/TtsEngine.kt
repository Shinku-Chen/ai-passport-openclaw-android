package com.shinku.aipassport.openclaw.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 系统 TextToSpeech 封装。
 *
 * - 优先中文,失败退回默认语言。
 * - [speak] 用 QUEUE_FLUSH:播放前会打断上一段(配合 barge)。
 * - 一段合成自然结束时回调 [Listener.onDone](带文本,供上层回传 TEXT 帧上屏);
 *   被 stop()/打断时回调 [Listener.onInterrupted](不触发回传)。
 */
class TtsEngine(context: Context) {

    interface Listener {
        /** 正常合成结束。 */
        fun onDone(text: String)

        /** 被 stop()/新语音打断,未正常完成。 */
        fun onInterrupted(text: String)
    }

    private val tag = "TtsEngine"
    private var ready = false

    /** 当前正在播放的 utteranceId → 监听器;按 id 匹配避免旧 utterance 回调串台。 */
    private var pending: Pair<String, Listener>? = null
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.e(tag, "TTS 初始化失败 status=$status")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val chinese = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (chinese == TextToSpeech.LANG_MISSING_DATA ||
                chinese == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(tag, "中文语音不可用,使用默认语言")
                engine.setLanguage(Locale.getDefault())
            }
            Log.i(tag, "TTS 就绪")
        }.also { t ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    val p = pending
                    if (utteranceId != null && p?.first == utteranceId) {
                        pending = null
                        p.second.onDone(utteranceId)
                    }
                }

                override fun onError(utteranceId: String?) {
                    val p = pending
                    if (utteranceId != null && p?.first == utteranceId) pending = null
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    val p = pending
                    if (utteranceId != null && p?.first == utteranceId) {
                        pending = null
                        p.second.onInterrupted(utteranceId)
                    }
                }
            })
        }
    }

    /**
     * 合成并播放一段文本。utteranceId 即文本本身,回调按 id 匹配。
     */
    fun speak(text: String, listener: Listener) {
        val t = tts ?: run {
            listener.onInterrupted(text)
            return
        }
        if (!ready) {
            Log.w(tag, "TTS 未就绪,跳过")
            listener.onInterrupted(text)
            return
        }
        pending = text to listener
        val result = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
        if (result == TextToSpeech.ERROR) {
            pending = null
            listener.onInterrupted(text)
        }
    }

    /** 立即停止播放(打断)。 */
    fun stop() {
        pending = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        pending = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
