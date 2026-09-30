package com.shinku.aipassport.openclaw.gateway

/**
 * 运行中语音桥服务实际依赖的网关配置快照。
 *
 * 背景(修 bug):适配器在服务启动时按当时的设置构造一次,原来只有【网关类型】变化才重启服务,
 * 于是只改 host/端口/token 时,保存成功、SharedPreferences 也写了,但运行中的服务仍用旧配置
 * (真机表现:顶部横幅一直「网关未配置,请在 App 设置中填写」,而 prefs 里 host/port/token 齐全)。
 *
 * 修复:保存成功后发 [com.shinku.aipassport.openclaw.service.VoiceBridgeService.ACTION_RELOAD_SETTINGS],
 * 服务据此重建适配器并重连(不动 BLE 链路)。「要不要重建」用本快照前后比较决定 ——
 * 任一影响建链/对话行为的字段变化都必须重建。
 *
 * 直接复用各通道已有的配置 data class(它们本身就是「构造适配器所需的全部参数」),
 * 因此宿主类型不同、或同类型内任一字段变化,`!=` 都为 true。
 * 纯数据类,无 Android 依赖,JVM 单测可直接构造前后快照验证判定逻辑(见 `GatewayReloadTest`)。
 *
 * 注意:不含 `voicePromptSuffix` —— 它只影响发给网关的文本,由流水线单独更新,不需要重建连接。
 */
sealed interface GatewayConfigSnapshot {
    /** OpenClaw(WebSocket + 设备鉴权)。 */
    data class OpenClaw(val config: OpenClawConfig) : GatewayConfigSnapshot

    /** Hermes(OpenAI 兼容 HTTP)。 */
    data class Hermes(val config: HermesConfig) : GatewayConfigSnapshot

    /** 自定义 OpenAI 兼容 HTTP。 */
    data class OpenAi(val config: OpenAiConfig) : GatewayConfigSnapshot

    /** 本地回显(无可配置字段)。 */
    data object Echo : GatewayConfigSnapshot
}

/**
 * 保存成功后是否需要重载运行中的适配器(纯函数)。
 *
 * @param prev 服务当前实际使用的配置快照;null = 服务还没建立过适配器(总是需要重载)
 * @param next 落盘后的最新配置快照
 */
fun needsGatewayReload(prev: GatewayConfigSnapshot?, next: GatewayConfigSnapshot): Boolean =
    prev != next
