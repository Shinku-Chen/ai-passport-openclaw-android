package com.shinku.aipassport.openclaw.service

/**
 * 前台常驻通知的三行状态文案（纯逻辑，便于单测）。
 *
 * 折叠时一行摘要，展开时三行看清：**设备**（BLE 链路）/ **网关** / **语音**（识别通道）。
 * 只做拼装与映射，不碰 Android API，所以能在 JVM 单测里断言。
 */
object BridgeStatusText {

    /** 语音通道状态词。 */
    const val VOICE_UNKNOWN = "未知"
    const val VOICE_NO_DEVICE = "无设备"
    const val VOICE_WARM = "已就绪"

    /**
     * 折叠时的一行摘要（通知默认只显示一行，尽量短）。
     */
    fun summary(device: String, gateway: String, voice: String): String =
        "设备：$device ｜ 网关：$gateway ｜ 语音：$voice"

    /**
     * 展开时的三行正文（供 `BigTextStyle` 使用）。
     */
    fun detail(device: String, gateway: String, voice: String): String =
        "设备：$device\n网关：$gateway\n语音：$voice"

    /**
     * 语音（识别通道）状态。
     *
     * - 设备链路没就绪 → 识别通道会被释放，没什么可说 → [VOICE_NO_DEVICE]；
     * - 链路在、热连接可用 → [VOICE_WARM]（按下即可说话）；
     * - 链路在、但还没预热好 → "预热中…"。
     */
    fun voiceLine(linkReady: Boolean, warmReady: Boolean): String = when {
        !linkReady -> VOICE_NO_DEVICE
        warmReady -> VOICE_WARM
        else -> "预热中…"
    }

    /**
     * 设备的默认网关显示名（设置页里选的那一类）。
     */
    fun gatewayName(type: String?): String = when (type) {
        "openclaw" -> "OpenClaw"
        "hermes" -> "Hermes"
        "openai" -> "OpenAI 兼容"
        "echo" -> "Echo"
        else -> "未配置"
    }
}
