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
     * 找到用于识别的模型目录(优先大模型,其次目录体积最大)。
     * 找不到返回 null(降级系统识别)。
     */
    fun findModelDir(context: Context): File? {
        return ModelManager.currentModelDir(context)
    }

    /** 创建引擎:小智已配置 → XiaozhiStt;否则 Vosk 模型存在 → VoskStt;否则系统识别。 */
    fun create(context: Context, onPartial: ((String) -> Unit)? = null): SttEngine {
        // 小智云端识别优先(固件编码 Opus 原样转发,识别率高、中文流式)。未配置才回退 Vosk。
        val xz = XiaozhiSettings(context)
        if (xz.enabled()) {
            // 服务器只放行"全零 MAC"匿名通道(实测真实 MAC 被 op=8 拒)。用全零 MAC 先跑通识别链路。
            val deviceId = "00:00:00:00:00:00"
            // 若已激活成功(用户在 xiaozhi.me 绑定过),用 OTA 下发的 ws url/token;否则用设置的地址。
            val wsUrl = if (xz.activated && xz.wsUrl.isNotBlank()) xz.wsUrl else xz.serverUrl
            val wsToken = if (xz.activated) xz.wsToken else xz.token
            Log.i(TAG, "使用小智云端识别 wsUrl=$wsUrl activated=${xz.activated} deviceId=$deviceId")
            return XiaozhiStt(wsUrl, wsToken, deviceId, onPartial)
        }
        Log.i(TAG, "未配置小智,回退 Vosk 本地识别")
        // 首装:把打包进 assets 的 small 模型复制到 filesDir(无模型时才复制)。
        ModelManager.ensureBundled(context)
        val modelDir = findModelDir(context)
        return if (modelDir != null) {
            Log.i(TAG, "使用 Vosk 离线识别,模型目录=${modelDir.absolutePath}")
            VoskStt(context, modelDir, onPartial)
        } else {
            Log.i(TAG, "未找到 Vosk 模型(filesDir 下无 vosk-model 目录),降级为系统识别")
            SpeechRecognizerStt(context, onPartial)
        }
    }
}
