package com.shinku.aipassport.openclaw.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 设备朗读(下行 TTS)的系统引擎实现:用 `TextToSpeech.synthesizeToFile` 把文本合成到临时 WAV,
 * 读回 WAV 取裸 PCM(见 [TtsFraming.pcmFromWav]),交给服务侧编 Opus 后逐帧下发。
 *
 * 与 [TtsEngine](手机本地 `speak` 播放)的区别:这里**不出声**,只产出 PCM 字节。
 *
 * 采样率:输出采样率由系统引擎决定,无法通过 API 指定(实测常见 16000 / 24000 / 22050)。
 * 因此 [synthesize] 一律以 WAV 头里的真实采样率回报([lastSampleRateHz]),并在与请求不一致时
 * 打日志说明 —— **M1 不做重采样**,不是 16k/24k 的采样率由调用方放弃本轮下发。
 *
 * 已知坑与对策:
 *  - `TextToSpeech` 初始化是**异步**的,[prepare] 会等回调(最多 [INIT_TIMEOUT_MS]),否则
 *    服务启动后第一次朗读必然失败;
 *  - `tts_default_synth`(系统默认合成器)可能为 null(未安装语音引擎):直接给出可读原因
 *    `未安装语音引擎`,而不是抛异常或静默;
 *  - `synthesizeToFile` 的结果只能靠 [UtteranceProgressListener] 回调拿到,故用
 *    `suspendCancellableCoroutine` 包成挂起函数,并带超时兜底(引擎卡死时不能把流水线挂住)。
 */
class AndroidTtsEngine(private val context: Context) : DeviceTtsEngine {

    override val id: String = TtsEngines.ANDROID

    @Volatile
    override var lastSampleRateHz: Int = 0
        private set

    @Volatile
    override var lastError: String? = null
        private set

    /** `TextToSpeech` 初始化结果(SUCCESS / ERROR);回调只到一次。 */
    private val initResult = CompletableDeferred<Int>()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var prewarmed = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                initResult.complete(status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TextToSpeech 构造失败", e)
            lastError = "语音引擎初始化失败:${e.message}"
            initResult.complete(TextToSpeech.ERROR)
        }
    }

    override suspend fun prepare(): Boolean {
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { initResult.await() }
        if (status == null) {
            lastError = "语音引擎初始化超时(${INIT_TIMEOUT_MS}ms)"
            return false
        }
        if (status != TextToSpeech.SUCCESS) {
            lastError = "未安装语音引擎(TextToSpeech 初始化失败 status=$status)"
            return false
        }
        val engine = tts ?: run {
            lastError = "未安装语音引擎(TextToSpeech 实例为空)"
            return false
        }
        // 系统默认合成器可以是 null(设备上没有可用 TTS 引擎):先给可读原因,再谈语言
        val defaultSynth = runCatching { engine.defaultEngine }.getOrNull()
        val installed = runCatching { engine.engines }.getOrNull().orEmpty()
        if (defaultSynth.isNullOrBlank() && installed.isEmpty()) {
            lastError = "未安装语音引擎"
            return false
        }
        if (!prewarmed) {
            val chinese = runCatching { engine.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrNull()
            if (chinese == TextToSpeech.LANG_MISSING_DATA || chinese == TextToSpeech.LANG_NOT_SUPPORTED ||
                chinese == null
            ) {
                Log.w(TAG, "中文语音不可用(setLanguage=$chinese),使用系统默认语言")
                runCatching { engine.setLanguage(Locale.getDefault()) }
            }
            prewarmed = true
            Log.i(TAG, "系统 TTS 引擎就绪,default=$defaultSynth engines=$installed")
            applyPreferredVoice(engine)
        }
        lastError = null
        return true
    }

    /**
     * 从引擎枚举出的音色里挑一个用于**设备朗读**:优先中文 + **离线** + 语音包已下载 + 质量高、延迟低
     * (策略见 [TtsVoiceChoice])。挑不到就保持 `setLanguage(zh)` 的结果(引擎默认),绝不硬塞英文音色。
     *
     * 全部候选都会打进日志 —— “这台机器到底有几个可用音色、能不能离线”只能这样看清楚
     * (系统设置里那页只给试听,不给清单;而且 `setLanguage` 到底选到了哪个也不告诉你)。
     */
    private fun applyPreferredVoice(engine: TextToSpeech) {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) {
            Log.w(TAG, "引擎未枚举出任何音色(getVoices 为空),保持引擎默认")
            return
        }
        val infos = voices.map { voice ->
            TtsVoiceChoice.VoiceInfo(
                name = voice.name,
                language = voice.locale?.language.orEmpty(),
                country = voice.locale?.country.orEmpty(),
                quality = runCatching { voice.quality }.getOrDefault(0),
                latency = runCatching { voice.latency }.getOrDefault(0),
                requiresNetwork = runCatching { voice.isNetworkConnectionRequired }.getOrDefault(false),
                notInstalled = runCatching {
                    voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
                }.getOrDefault(false),
            )
        }
        Log.i(TAG, "可选音色 ${infos.size} 个:")
        infos.forEach { Log.i(TAG, "  · ${TtsVoiceChoice.describe(it)}") }

        val picked = TtsVoiceChoice.pick(infos) ?: run {
            Log.w(TAG, "没有可用的离线中文音色,保持引擎默认(不拿英文音色念中文)")
            return
        }
        val voice = voices.firstOrNull { it.name == picked.name } ?: return
        val result = runCatching { engine.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.SUCCESS) {
            Log.i(TAG, "已选用音色: ${TtsVoiceChoice.describe(picked)}")
        } else {
            Log.w(TAG, "选用音色失败(result=$result),保持引擎默认音色")
        }
    }

    override suspend fun synthesize(text: String, sampleRateHz: Int): ByteArray? {
        if (text.isBlank()) {
            lastError = "文本为空"
            return null
        }
        val engine = tts ?: run {
            lastError = "未安装语音引擎"
            return null
        }
        val utteranceId = "tts-${System.nanoTime()}"
        val file = File(context.cacheDir, "$utteranceId.wav")
        val finished = AtomicBoolean(false)
        val ok = suspendCancellableCoroutine { cont ->
            // 结果只能靠监听器回调拿:每个 utterance 一组 onDone/onError,按 id 匹配避免串台
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId && finished.compareAndSet(false, true)) cont.resume(true)
                }

                @Suppress("DEPRECATION")
                override fun onError(id: String?) {
                    if (id == utteranceId && finished.compareAndSet(false, true)) cont.resume(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && finished.compareAndSet(false, true)) cont.resume(false)
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && finished.compareAndSet(false, true)) cont.resume(false)
                }
            })
            val started = try {
                engine.synthesizeToFile(text, null, file, utteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "synthesizeToFile 异常", e)
                TextToSpeech.ERROR
            }
            if (started != TextToSpeech.SUCCESS && finished.compareAndSet(false, true)) {
                Log.w(TAG, "synthesizeToFile 未接受请求 result=$started")
                cont.resume(false)
            }
        }
        if (!ok) {
            file.delete()
            lastError = "语音合成失败(引擎未完成,text=${text.take(20)}…)"
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "读取合成结果失败", e)
            ByteArray(0)
        } finally {
            file.delete()
        }
        if (bytes.isEmpty()) {
            lastError = "语音合成结果为空"
            return null
        }
        val wav = TtsFraming.pcmFromWav(bytes)
        if (wav == null) {
            lastError = "语音合成结果不是可解析的 WAV(PCM 16bit 单声道,${bytes.size}B)"
            return null
        }
        lastSampleRateHz = wav.sampleRateHz
        if (wav.sampleRateHz != sampleRateHz) {
            // M1 不重采样:如实记下引擎给的采样率,由调用方决定能否编成设备认的帧
            Log.w(
                TAG,
                "系统 TTS 输出 ${wav.sampleRateHz}Hz ≠ 请求 ${sampleRateHz}Hz(M1 不重采样," +
                    "按引擎实际采样率分帧;仅 16k/24k 可下发)",
            )
        }
        Log.i(
            TAG,
            "TTS 合成完成: text=${text.length}字 wav=${bytes.size}B pcm=${wav.pcm.size}B " +
                "rate=${wav.sampleRateHz}Hz ch=${wav.channels} bits=${wav.bitsPerSample}",
        )
        lastError = null
        return wav.pcm
    }

    /** 释放引擎(服务停止时调用)。 */
    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    private companion object {
        const val TAG = "AndroidTtsEngine"

        /** 等 `TextToSpeech` 初始化回调的上限。 */
        const val INIT_TIMEOUT_MS = 3_000L
    }
}
