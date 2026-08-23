package com.shinku.aipassport.openclaw.stt

/**
 * 小智(xiaozhi.me)识别设置 —— 【纯硬编码,不在 UI 展示/不存 SharedPreferences】。
 *
 * 小智是唯一识别引擎:URL/token/OTA 地址全部写死,用户无需也不能在设置页填。
 * 只读 getter 供 SttFactory / XiaozhiActivator / 设置页状态显示读取。
 */
class XiaozhiSettings {

    /** 小智 websocket 地址(写死官方)。 */
    val serverUrl: String = "wss://api.tenclass.net/xiaozhi/v1/"

    /** 小智识别 token(写死官方共享 test-token)。 */
    val token: String = "test-token"

    /** 小智 OTA 激活地址(写死官方)。 */
    val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/"

    /** 已完成激活(写死:设备已在 xiaozhi.me 绑定,OTA 不再要求激活)。 */
    val activated: Boolean = true

    /** 始终启用小智识别。 */
    fun enabled(): Boolean = true
}
