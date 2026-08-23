package com.shinku.aipassport.openclaw.stt

import android.content.Context

/**
 * 小智(xiaozhi.me)识别设置(App 内配置,不写死)。
 *
 * SttFactory.create 据此决定是否用小智云端识别:
 *  - 配置了小智服务器地址 → XiaozhiStt(设备固件编码 Opus 原样转发,识别率高、中文流式)。
 *  - 未配置(默认) → 回退 Vosk 本地识别(需 filesDir 有 vosk-model 目录)。
 *
 * 服务器地址与 token 在设置页由用户填写;token 为共享测试 token(test-token)时也先用着。
 * deviceId 复用 Gateway 的 DeviceIdentity.deviceId 作小智 Device-Id 握手头。
 */
class XiaozhiSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("xiaozhi_settings", Context.MODE_PRIVATE)

    /** 小智服务器地址,如 wss://api.tenclass.net/xiaozhi/v1/。默认填官方地址;空串 = 不用小智回退 Vosk。 */
    var serverUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    /** 小智识别 token(默认官方共享 test-token)。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** 小智 OTA 激活地址(默认官方)。 */
    var otaUrl: String
        get() = prefs.getString(KEY_OTA, "https://api.tenclass.net/xiaozhi/ota/") ?: "https://api.tenclass.net/xiaozhi/ota/"
        set(value) = prefs.edit().putString(KEY_OTA, value.trim()).apply()

    /** 是否已完成小智激活(用户在 xiaozhi.me 网页绑定后,activate 返回 200)。 */
    var activated: Boolean
        get() = prefs.getBoolean(KEY_ACTIVATED, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVATED, value).apply()

    /** OTA 下发的 websocket 地址(激活成功后持久化;供 XiaozhiStt 连接)。 */
    var wsUrl: String
        get() = prefs.getString(KEY_WS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WS, value.trim()).apply()

    /** OTA 下发的 websocket token。 */
    var wsToken: String
        get() = prefs.getString(KEY_WS_TOKEN, "test-token") ?: "test-token"
        set(value) = prefs.edit().putString(KEY_WS_TOKEN, value.trim()).apply()

    /** 是否配置了小智(非空 serverUrl)。 */
    fun enabled(): Boolean = serverUrl.isNotBlank()

    fun save(url: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    companion object {
        /** 小智官方 websocket 地址(默认填入 App)。 */
        const val DEFAULT_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        /** 小智官方共享测试 token(默认填入 App)。 */
        const val DEFAULT_TOKEN = "test-token"
        private const val KEY_URL = "xiaozhi_server_url"
        private const val KEY_TOKEN = "xiaozhi_token"
        private const val KEY_OTA = "xiaozhi_ota_url"
        private const val KEY_ACTIVATED = "xiaozhi_activated"
        private const val KEY_WS = "xiaozhi_ws_url"
        private const val KEY_WS_TOKEN = "xiaozhi_ws_token"
    }
}
