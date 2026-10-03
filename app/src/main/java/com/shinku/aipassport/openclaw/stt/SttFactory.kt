package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import com.shinku.aipassport.openclaw.ble.BleCentral
import com.shinku.aipassport.openclaw.gateway.GatewaySettings

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
     * Device-Id 交给 [XiaozhiIdentity] 在**每次建链时**按当时的网关类型解析(这里传「按需解析」而不把
     * 此刻的值定死:服务先起、设备后连,而且用户可能中途改网关类型):
     *  - 网关类型 == 小智 AI → 已连接设备的真实蓝牙 MAC(与 OTA/绑定同一个来源与格式);
     *  - 其余网关(OpenClaw/Hermes/自定义 OpenAI 兼容/回显)→ 全零匿名标识 —— 这些模式只把小智通道
     *    当识别引擎,必须继续可用(与设备是否在线无关);
     *  - 小智模式取不到设备地址 → 空串,会话层据此跳过建链,绝不退回匿名标识。
     */
    fun create(context: Context, onPartial: ((String) -> Unit)? = null): XiaozhiStt {
        // 小智云端识别为唯一路径(固件编码 Opus 原样转发,识别率高、中文流式)。
        val xz = XiaozhiSettings()
        val serverUrl = xz.serverUrl
        val wsToken = xz.token
        val gateway = GatewaySettings(context)
        Log.i(TAG, "使用小智云端识别 wsUrl=$serverUrl deviceId=按网关类型解析(${gateway.type})")
        return XiaozhiStt(
            serverUrl = serverUrl,
            token = wsToken,
            deviceIdProvider = {
                XiaozhiIdentity.resolve(gateway.type, BleCentral.lastConnectedAddr(context)).deviceId
            },
            onPartial = onPartial,
        )
    }
}
