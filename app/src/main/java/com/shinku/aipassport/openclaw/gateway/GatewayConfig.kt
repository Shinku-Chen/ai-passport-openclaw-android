package com.shinku.aipassport.openclaw.gateway

/**
 * OpenClaw 网关配置入口。
 *
 * 域名/端口/token 全部来自 App 内设置(GatewaySettings/SharedPreferences),
 * 不写死;MainActivity 的网关设置区负责配置。token 只存本机,绝不进提交的代码。
 *
 * 网关对接的验证结果(域名 hs0033439-openclaw.my.hiksemi.net:8035):
 *  - GET  {base}/health                        -> 200 {"ok":true,"status":"live"}  (无需 token)
 *  - POST {base}/tools/invoke                  -> 401 Unauthorized (需 Bearer token;端点存在)
 *  - WS   {base}{wsPath}?sessionKey=main(/message/messages/ws 或 /ws)-> 101 + connect.challenge (需 token + 设备鉴权)
 *  - /v1/chat/completions、/agent/message       -> 404 (本网关未启用)
 * 因此采用 OpenClaw 网关实时 WebSocket 协议:connect(challenge+token+ed25519) -> chat.send -> 收 chat 事件。
 */
object GatewayConfig {

    /** 默认会话键(设备维度的 main 会话) */
    const val SESSION_KEY = "main"

    /** 请求超时(秒) */
    const val TIMEOUT_SECONDS = 45L

    fun baseUrl(s: GatewaySettings): String =
        "http${if (s.useTls) "s" else ""}://${s.host}:${s.port}"

    fun wsUrl(s: GatewaySettings): String =
        "ws${if (s.useTls) "s" else ""}://${s.host}:${s.port}${s.wsPath}?sessionKey=$SESSION_KEY"
}
