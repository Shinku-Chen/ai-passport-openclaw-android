package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Vosk 离线语音识别,消费设备 BLE 送来的 PCM。
 *
 * 输入:固件(ESP32-C3)采集的设备麦克风 PCM,经 BLE 送达,为
 * int16 单声道 16k 原始音频(见固件 voice_bridge_frame.h:"原始 PCM int16
 * 单声道 16k")。Vosk 以 acceptWaveForm 直接喂入,endTurn 时 finalResult
 * 取整段识别文本,不依赖手机麦克风。
 *
 * 模型:需用户把 Vosk 中文模型(推荐 vosk-model-small-cn-0.22,约 40MB)
 * 解压后放进 App 内部存储 filesDir,目录名包含 "vosk-model" 即被自动探测
 * (见 SttFactory.findModelDir)。模型缺失时 isAvailable=false,由
 * SttFactory 降级到系统 SpeechRecognizer,不打扰用户。
 *
 * 线程:识别器与 JNI 的交互(acceptWaveForm / finalResult / reset / close)
 * 统一用 [lock] 串行保护,与 VoicePipeline 在主线程投递帧的模型一致。
 */
class VoskStt(
    private val context: Context,
    private val modelDir: File,
    onPartialOverride: ((String) -> Unit)? = null,
) : SttEngine {

    private val tag = "VoskStt"

    /** 固件采样率 16k;Vosk 构造需要 float 采样率。 */
    private val sampleRate = 16000.0f

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /** 识别过程与 JNI 交互用同一把锁。 */
    private val lock = Any()

    private val gson = Gson()
    private var lastPartial = ""   // 节流:partial 文本变化才回调

    override val onPartial: ((String) -> Unit)? = onPartialOverride

    override val isAvailable: Boolean
        get() = modelDir.isDirectory

    /** 预加载:加载模型并创建识别器,后续 turn_start 可立即开始。 */
    override fun prewarm() {
        synchronized(lock) {
            if (model == null) {
                model = try {
                    Model(modelDir.absolutePath)
                } catch (e: Exception) {
                    Log.e(tag, "Vosk 模型加载失败: ${modelDir.absolutePath}", e)
                    null
                }
            }
            recognizer = model?.let { Recognizer(it, sampleRate) }
        }
    }

    override fun startTurn() {
        synchronized(lock) {
            // prewarm 未跑完或失败时,兜底加载模型。
            if (model == null) {
                model = try {
                    Model(modelDir.absolutePath)
                } catch (e: Exception) {
                    Log.e(tag, "Vosk 模型加载失败: ${modelDir.absolutePath}", e)
                    null
                }
                recognizer = model?.let { Recognizer(it, sampleRate) }
            }
            // 重置识别器,开始一段全新的语音。
            try {
                recognizer?.reset()
            } catch (e: Exception) {
                Log.w(tag, "startTurn reset 异常", e)
            }
        }
    }

    override fun feedPcm(pcm: ByteArray) {
        synchronized(lock) {
            try {
                // acceptWaveForm 返回 true 表示有 partial 可取;取 getPartialResult 实时上屏。
                val hasPartial = recognizer?.acceptWaveForm(pcm, pcm.size) ?: false
                if (hasPartial && onPartial != null) {
                    val partial = parsePartial(recognizer?.partialResult)
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        lastPartial = partial
                        onPartial(partial)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "feedPcm 异常", e)
            }
        }
    }

    // 从 partialResult JSON {"partial":"..."} 取 partial 文本。
    private fun parsePartial(json: String?): String {
        if (json.isNullOrEmpty()) return ""
        return try {
            gson.fromJson(json, JsonObject::class.java)
                ?.get("partial")?.asString?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun endTurn(): String? {
        val finalJson = synchronized(lock) {
            try {
                recognizer?.finalResult
            } catch (e: Exception) {
                Log.w(tag, "finalResult 异常", e)
                null
            }
        }
        if (finalJson == null) return null
        val text = try {
            gson.fromJson(finalJson, JsonObject::class.java)
                ?.get("text")?.asString?.trim()
        } catch (e: Exception) {
            Log.w(tag, "finalResult 解析失败", e)
            null
        }
        // 空文本(null / "")视为未识别到语音,返回 null。
        return text?.takeIf { it.isNotEmpty() }
    }

    override fun barge() {
        synchronized(lock) {
            // 打断当前识别:丢弃已积累的音频,等待下一次 startTurn。
            try {
                recognizer?.reset()
            } catch (e: Exception) {
                Log.w(tag, "barge reset 异常", e)
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            try {
                recognizer?.close()
            } catch (_: Exception) {}
            recognizer = null
            try {
                model?.close()
            } catch (_: Exception) {}
            model = null
        }
    }
}
