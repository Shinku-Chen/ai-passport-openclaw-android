package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import com.shinku.aipassport.openclaw.ble.BleCentral

/**
 * STT 引擎工厂。
 *
 * 固定使用小智云端识别(消费固件 BLE 送来的 Opus,识别率高、中文流式)。
 * 不再使用 Vosk 本地模型/系统识别(识别率低,已放弃)。
 */
object SttFactory {

    private const val TAG = "SttFactory"

    /**
     * 创建引擎:固定返回小智云端识别(不再用 Vosk/模型识别)。
     *
     * 返回具体类型 [XiaozhiStt] 而不是 [SttEngine]:语音桥服务要把它的 [XiaozhiStt.session]
     * 交给「小智 AI 网关」共用(见 `docs/design/xiaozhi-ai-gateway.md` §4.2)。
     *
     * Device-Id 用**已连接设备的真实蓝牙 MAC**(与 OTA/绑定同一个来源与格式,见 [XiaozhiDeviceId]);
     * 这里传「按需解析」而不把此刻的值定死 —— 服务先起、设备后连,建链时才取值才能拿到真 MAC。
     * 取不到时不退回全零/手机侧标识,由会话层给出可读原因并跳过建链。
     */
    fun create(context: Context, onPartial: ((String) -> Unit)? = null): XiaozhiStt {
        // 小智云端识别为唯一路径(固件编码 Opus 原样转发,识别率高、中文流式)。
        val xz = XiaozhiSettings()
        val serverUrl = xz.serverUrl
        val wsToken = xz.token
        Log.i(TAG, "使用小智云端识别 wsUrl=$serverUrl deviceId=设备蓝牙 MAC(按需解析)")
        return XiaozhiStt(
            serverUrl = serverUrl,
            token = wsToken,
            deviceIdProvider = {
                XiaozhiDeviceId.formatAddress(BleCentral.lastConnectedAddr(context)).orEmpty()
            },
            onPartial = onPartial,
        )
    }
}
