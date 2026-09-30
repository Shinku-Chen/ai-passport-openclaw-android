package com.shinku.aipassport.openclaw.gateway

/**
 * OpenClaw 网关配置入口。
 *
 * 域名/端口/token 全部来自 App 内设置(GatewaySettings/SharedPreferences),
 * 不写死;MainActivity 的网关设置区负责配置。token 只存本机,绝不进提交的代码。
 *
 * 网关对接的验证结论(只有协议事实,不记录任何具体主机/端口):
 *  - GET  {base}/health                        -> 200 {"ok":true,"status":"live"}  (无需 token,可用于探活)
 *  - POST {base}/tools/invoke                  -> 401 Unauthorized (需 Bearer token;端点存在,非 404)
 *  - WS   {base}{wsPath}?sessionKey=main(/message/messages/ws 或 /ws)-> 101 + connect.challenge (需 token + 设备鉴权)
 *  - /v1/chat/completions、/agent/message       -> 404 (本网关未启用 OpenAI 兼容与消息端点)
 * 因此采用 OpenClaw 网关实时 WebSocket 协议:connect(challenge+token+ed25519) -> chat.send -> 收 chat 事件。
 * 具体域名/端口由用户在设置页填写,不写进代码。
 */
object GatewayConfig {

    /** 默认会话键(设备维度的 main 会话) */
    const val SESSION_KEY = "main"

    /**
     * 对讲机自己的会话名:`sessionKey = agent:main:<这个>`。
     * 不跟 PC 控制台/微信/飞书共用 `main` 会话——共用的那次上下文到过 23 万 tokens、359 条消息,
     * 每回合几十秒,而且回复会串到别的渠道的话题上。
     */
    const val DEFAULT_SESSION_NAME = "passport"

    /** 单次 RPC(chat.send / health / sessions.list 等)请求超时(秒) */
    const val TIMEOUT_SECONDS = 45L

    /**
     * 等待网关【最终回复】的默认上限(秒)。
     *
     * Agent 跑工具常要几十秒甚至更久,原来的 45s 会把正常的长任务判成失败;
     * 这里默认 180s,可在设置页按网关负载调整([MIN_REPLY_TIMEOUT_SECONDS]..[MAX_REPLY_TIMEOUT_SECONDS])。
     */
    const val DEFAULT_REPLY_TIMEOUT_SECONDS = 180L

    /** 回复等待上限允许的最小值(秒):比 RPC 超时略短的值没有意义。 */
    const val MIN_REPLY_TIMEOUT_SECONDS = 15L

    /** 回复等待上限允许的最大值(秒):15 分钟,再长不如让用户重说一次。 */
    const val MAX_REPLY_TIMEOUT_SECONDS = 900L

    fun baseUrl(s: GatewaySettings): String = baseUrl(OpenClawConfig.of(s))

    fun wsUrl(s: GatewaySettings): String = wsUrl(OpenClawConfig.of(s))

    /** 显式配置版本:设置页保存前用输入框草稿校验时,不经过 SharedPreferences。 */
    fun baseUrl(c: OpenClawConfig): String =
        "http${if (c.useTls) "s" else ""}://${c.host}:${c.port}"

    fun wsUrl(c: OpenClawConfig): String =
        "ws${if (c.useTls) "s" else ""}://${c.host}:${c.port}${c.wsPath}?sessionKey=$SESSION_KEY"
}
