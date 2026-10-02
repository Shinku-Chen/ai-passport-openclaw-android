package com.shinku.aipassport.openclaw.service

import com.shinku.aipassport.openclaw.protocol.DeviceFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GatewayStatusText] 的单测。
 *
 * 两条断言方向：
 *  1. **设备屏能显示**（真机坑：长破折号 `—` 设备字库没有 → 提示行上一个方块）；
 *  2. **关键词还在** —— [VoiceBridgeService] 靠「含『重连』」把状态映射成 connecting 再下发设备，
 *     文案一改就把设备屏的网关状态弄丢了（设备会停在旧状态）。
 */
class GatewayStatusTextTest {

    @Test
    fun reloaded_text_is_renderable_on_device() {
        val withReason = GatewayStatusText.reloadedReconnecting("unknown method: usage")
        val withoutReason = GatewayStatusText.reloadedReconnecting(null)
        val blankReason = GatewayStatusText.reloadedReconnecting("   ")

        assertTrue("设备屏显示不了: ${DeviceFont.firstUnrenderable(withReason)}", DeviceFont.canRender(withReason))
        assertTrue(DeviceFont.canRender(withoutReason))
        assertTrue(DeviceFont.canRender(blankReason))
        assertEquals("空白原因不加后缀", GatewayStatusText.RELOADED_PREFIX, withoutReason)
        assertEquals(GatewayStatusText.RELOADED_PREFIX, blankReason)
        assertTrue("原因要拼进去: $withReason", withReason.contains("unknown method: usage"))
        assertNull("不能再用 —(U+2014)：设备上是方块", DeviceFont.firstUnrenderable(withReason))
    }

    @Test
    fun reconnecting_text_is_renderable_on_device() {
        val text = GatewayStatusText.reconnecting("网关连接中断")
        assertTrue(DeviceFont.canRender(text))
        assertTrue("原因要保留: $text", text.contains("网关连接中断"))
        assertTrue("必须含「正在重连」", text.contains(GatewayStatusText.RECONNECTING))
    }

    @Test
    fun texts_keep_the_keyword_that_drives_device_gateway_state() {
        // VoiceBridgeService.gatewayStateOf 靠子串判定：含「重连」→ connecting 并下发设备。
        // 这里把它读一遍，避免文案改动悄悄丢掉关键词（丢了设备屏就不更新网关状态）。
        assertTrue(GatewayStatusText.reloadedReconnecting("HTTP 500").contains("重连"))
        assertTrue(GatewayStatusText.reconnecting("HTTP 500").contains("重连"))
    }
}
