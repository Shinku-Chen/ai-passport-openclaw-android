package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log

/**
 * STT 引擎工厂。
 *
 * 固定使用小智云端识别(消费固件 BLE 送来的 Opus,识别率高、中文流式)。
 * 不再使用 Vosk 本地模型/系统识别(识别率低,已放弃)。
 */
object SttFactory {

    private const val TAG = "SttFactory"

    /** 创建引擎:固定返回小智云端识别(不再用 Vosk/模型识别)。 */
    fun create(context: Context, onPartial: ((String) -> Unit)? = null): SttEngine {
        // 小智云端识别为唯一路径(固件编码 Opus 原样转发,识别率高、中文流式)。
        val xz = XiaozhiSettings(context)
        val serverUrl = if (xz.activated && xz.wsUrl.isNotBlank()) xz.wsUrl else xz.serverUrl
        if (serverUrl.isBlank()) {
            // 极端兜底:小智地址为空也不回 Vosk,直接报错级日志(避免静默用错引擎)。
            Log.e(TAG, "小智识别地址为空且未启用小智,STT 不可用")
        }
        // 服务器只放行"全零 MAC"匿名通道(实测真实 MAC 被 op=8 拒)。用全零 MAC 跑通识别链路。
        val deviceId = "00:00:00:00:00:00"
        val wsToken = if (xz.activated) xz.wsToken else xz.token
        Log.i(TAG, "使用小智云端识别 wsUrl=$serverUrl activated=${xz.activated} deviceId=$deviceId")
        return XiaozhiStt(serverUrl, wsToken, deviceId, onPartial)
    }
}
