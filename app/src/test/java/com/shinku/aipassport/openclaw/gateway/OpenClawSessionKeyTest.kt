package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话名到 sessionKey 的拼装:对讲机用独立会话(agent:main:<名字>,默认 passport),
 * 不再和 PC 控制台/微信/飞书共用 main 会话(共用时实测单次提示 23 万 tokens、359 条消息)。
 */
class OpenClawSessionKeyTest {

    private fun config(sessionName: String?) = OpenClawConfig(
        host = "192.168.31.5",
        port = "18789",
        useTls = false,
        token = "t",
        wsPath = "/message/messages/ws",
        allowInsecureTls = false,
        replyTimeoutSeconds = 180L,
        sessionName = sessionName ?: GatewayConfig.DEFAULT_SESSION_NAME,
    )

    @Test
    fun default_session_name_is_passport() {
        assertEquals("passport", GatewayConfig.DEFAULT_SESSION_NAME)
        assertEquals("agent:main:passport", config(null).sessionKey)
    }

    @Test
    fun custom_session_name_is_embedded_in_session_key() {
        assertEquals("agent:main:phone2", config("phone2").sessionKey)
        // agentId 这一段必须固定 main:网关只认 main agent,变的是 <rest>
        assertEquals("main", config("phone2").sessionKey.split(":")[1])
    }

    @Test
    fun blank_or_odd_session_name_is_normalized() {
        assertEquals("agent:main:passport", config("   ").sessionKey)
        assertEquals("agent:main:studyroom", config(" study room ").sessionKey)
        assertEquals("agent:main:bed-room_2", config("bed-room_2").sessionKey)
    }

    @Test
    fun draft_builder_carries_the_session_name() {
        val draft = OpenClawConfig.of(
            host = " 192.168.31.5 ",
            port = "18789",
            useTls = false,
            token = " t ",
            wsPath = "message/messages/ws",
            allowInsecureTls = false,
            replyTimeoutSeconds = 180L,
            sessionName = "desk",
        )
        assertEquals("agent:main:desk", draft.sessionKey)
        assertEquals("/message/messages/ws", draft.wsPath)
        assertEquals("192.168.31.5", draft.host)
    }
}
