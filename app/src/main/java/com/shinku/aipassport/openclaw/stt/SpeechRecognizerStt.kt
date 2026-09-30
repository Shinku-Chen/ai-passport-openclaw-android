package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 系统 android.speech.SpeechRecognizer 封装(离线优先)。
 *
 * 说明:系统 SpeechRecognizer 无公开 API 接收外部 PCM,它采集本机麦克风,
 * 因此本引擎面向"用户直接对手机说话"的输入;识别优先走设备端(EXTRA_PREFER_OFFLINE)。
 */
class SpeechRecognizerStt(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") onPartialOverride: ((String) -> Unit)? = null,
) : SttEngine {

    private val tag = "SpeechRecognizerStt"
    private var recognizer: SpeechRecognizer? = null
    private var pending: CompletableDeferred<String?>? = null

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun startTurn(onReady: () -> Unit) {
        barge()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // 麦克风已打开、识别会话就绪 —— 这才是「现在说话一定能被识别」的那一刻
                // (startListening 只是请求,回调之前说话可能丢开头)。
                onReady()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.w(tag, "识别错误 code=$error")
                deferred.complete(null)
            }
            override fun onResults(results: Bundle?) {
                deferred.complete(
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                )
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.e(tag, "startListening 失败", e)
            deferred.complete(null)
        }
    }

    override fun feedPcm(pcm: ByteArray) {
        // 系统 SpeechRecognizer 无法接收外部 PCM;忽略。
        // feedOpus 同理不重写:系统引擎只采本机麦克风,拿不到设备送来的 Opus/PCM,
        // 所以这里不假装支持设备上行音频(需要设备音频时必须用 Vosk 或小智引擎)。
    }

    override suspend fun endTurn(): String? = withContext(Dispatchers.Default) {
        val d = pending
        if (d == null) return@withContext null
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        withTimeoutOrNull(10_000) { d.await() }
    }

    override fun barge() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        pending?.complete(null)
        pending = null
    }

    override fun release() = barge()
}
