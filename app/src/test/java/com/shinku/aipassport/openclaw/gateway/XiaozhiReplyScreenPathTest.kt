package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小智正文/字幕通道的网关闸门 —— 钉住「非小智网关下小智的回复不上屏」这条作者定的规则
 * (真机 bug:设备屏多出 App 列表里没有的小智回复)。
 */
class XiaozhiReplyScreenPathTest {

    @Test
    fun `only the xiaozhi gateway takes over the reply screen`() {
        assertTrue(XiaozhiReplyScreenPath.active(GatewaySettings.TYPE_XIAOZHI))
    }

    @Test
    fun `every other gateway leaves the reply path alone`() {
        // 逐个列出,避免将来新增网关类型时漏改 —— 这条断言就是「新类型默认不接管」的护栏。
        for (type in GatewaySettings.ALL_TYPES - GatewaySettings.TYPE_XIAOZHI) {
            assertFalse("网关 $type 不应接管小智的正文/字幕上屏", XiaozhiReplyScreenPath.active(type))
        }
    }

    @Test
    fun `unknown or not-yet-loaded type never takes over`() {
        assertFalse(XiaozhiReplyScreenPath.active(null))
        assertFalse(XiaozhiReplyScreenPath.active(""))
        assertFalse(XiaozhiReplyScreenPath.active("something-else"))
    }
}
