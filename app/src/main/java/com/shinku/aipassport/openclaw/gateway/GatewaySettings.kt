package com.shinku.aipassport.openclaw.gateway

import android.content.Context

/**
 * 网关设置在 App 内配置(SharedPreferences),不写死。
 *
 * MainActivity 提供域名/端口/token 输入框 + 保存按钮,存到这里;
 * VoiceBridgeService 从这里读取。token 是运行时 secret,只存本机,
 * 绝不写进任何提交的代码/构建文件。
 */
class GatewaySettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("gateway_settings", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: String
        get() = prefs.getString(KEY_PORT, "8035") ?: "8035"
        set(value) = prefs.edit().putString(KEY_PORT, value.trim()).apply()

    var useTls: Boolean
        get() = prefs.getBoolean(KEY_USE_TLS, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_TLS, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** 域名+端口是否已配置。 */
    fun isConfigured(): Boolean = host.isNotBlank() && port.isNotBlank()

    fun save(host: String, port: String, useTls: Boolean, token: String) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putString(KEY_PORT, port.trim())
            .putBoolean(KEY_USE_TLS, useTls)
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    companion object {
        private const val KEY_HOST = "gateway_host"
        private const val KEY_PORT = "gateway_port"
        private const val KEY_USE_TLS = "gateway_use_tls"
        private const val KEY_TOKEN = "gateway_token"
    }
}
