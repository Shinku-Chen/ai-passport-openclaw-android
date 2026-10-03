package com.shinku.aipassport.openclaw.stt

import android.content.Context
import android.util.Log
import com.shinku.aipassport.openclaw.ble.BleCentral
import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import com.shinku.aipassport.openclaw.service.VoiceBridgeService

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
        // 绑定得到的识别凭据(OTA 的 websocket url/token)只从本机读;没读到时不给凭据 ——
        // [XiaozhiCredentialGate] 对小智 AI 会返回可读原因,绝不拿占位 token 硬撞真 MAC 的链路。
        val credentials = XiaozhiCredentialStore(context)
        // 凭据刷新(策略①保守兜底 + 策略②被拒就刷新):重查 OTA 复用 [XiaozhiActivator.queryCloud],
        // 落盘复用同一个 [XiaozhiCredentialStore];网关类型实时读,非小智网关闭一次 OTA 都不发。
        // 会话层只在「绑定凭据链路」([XiaozhiLinkAuth.Ok.refreshable])上使用它。
        val credentialRefresh: XiaozhiCredentialRefreshSource = XiaozhiCredentialRefresher(
            context = context,
            otaUrl = xz.otaUrl,
            store = credentials,
            gatewayType = { gateway.type },
            // 与绑定/保存走**同一个**版本号来源(见 [XiaozhiOtaRequest.version]):固件版本优先。
            deviceFirmwareVersionProvider = { VoiceBridgeService.lastKnownFirmwareVersion() },
        )
        // 「非小智网关模式」= 小智通道只做识别:云端拿到识别文本后会接着跑走它自己的 LLM + TTS,
        // 那段回复没人用(回复来自 App 自己的网关) → 识别结束后要补发一次中止。
        // 判定来源只有一处:复用 [XiaozhiIdentity.isXiaozhi] 的既有判定,不在这里再写一份字符串比较;
        // 实时读设置(provider 每次调用取值),设置页切成「小智 AI」后立即不再发。
        val abortAfterEndTurn = { !XiaozhiIdentity.isXiaozhi(gateway.type) }
        Log.i(
            TAG,
            "使用小智云端识别 wsUrl=$serverUrl deviceId=按网关类型解析(${gateway.type})" +
                " 识别结束后补发中止=${abortAfterEndTurn()}",
        )
        return XiaozhiStt(
            serverUrl = serverUrl,
            token = wsToken,
            deviceIdProvider = {
                XiaozhiIdentity.resolve(gateway.type, BleCentral.lastConnectedAddr(context)).deviceId
            },
            onPartial = onPartial,
            sttAbortAfterEndTurn = abortAfterEndTurn,
            // 建链鉴权只有一处决策([XiaozhiCredentialGate]):小智 AI 必须用这台设备**绑定得到的凭据**
            // (OTA 的 websocket url/token,没凭据就不建链并给可读原因),其余网关仍走匿名地址 + 占位 token。
            linkAuthProvider = { deviceId ->
                XiaozhiCredentialGate.linkAuth(
                    gatewayType = gateway.type,
                    credential = credentials.get(deviceId),
                    defaultUrl = serverUrl,
                    placeholderToken = wsToken,
                )
            },
            credentialRefresh = credentialRefresh,
        )
    }
}
