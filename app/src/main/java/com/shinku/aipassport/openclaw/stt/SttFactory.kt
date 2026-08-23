package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import java.io.File

/**
 * STT 引擎工厂。
 *
 * 优先使用 Vosk 离线识别(消费设备 BLE PCM,不依赖手机麦克风):
 * 若 App 内部存储 filesDir 存在名字含 "vosk-model" 的目录,则返回 VoskStt;
 * 否则降级到系统 SpeechRecognizerStt(手机麦克风,离线优先)。
 * 模型缺失时静默降级,不打扰用户。
 */
object SttFactory {

    private const val TAG = "SttFactory"

    /**
     * 在 filesDir 下寻找名字含 "vosk-model" 的模型目录。
     * 找不到返回 null。多个时按名称排序取第一个,保证确定性。
     */
    fun findModelDir(context: Context): File? {
        val candidates = context.filesDir?.listFiles { f -> f.isDirectory && f.name.contains("vosk-model") }
        if (candidates.isNullOrEmpty()) return null
        return candidates.sortedBy { it.name }.first()
    }

    /** 创建引擎:Vosk 模型存在 → VoskStt;否则 → SpeechRecognizerStt。 */
    fun create(context: Context): SttEngine {
        val modelDir = findModelDir(context)
        return if (modelDir != null) {
            Log.i(TAG, "使用 Vosk 离线识别,模型目录=${modelDir.absolutePath}")
            VoskStt(context, modelDir)
        } else {
            Log.i(TAG, "未找到 Vosk 模型(filesDir 下无 vosk-model 目录),降级为系统识别")
            SpeechRecognizerStt(context)
        }
    }
}
