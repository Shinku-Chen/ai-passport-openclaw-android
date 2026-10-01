package com.shinku.aipassport.openclaw.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BridgeStatusText] 的 JVM 单测（纯逻辑，不依赖 Android）。
 *
 * 关键点：通知折叠时只能放一行 → 摘要必须短；展开时三行要各自带标签，一眼能分清
 * 设备 / 网关 / 语音三个状态。
 */
class BridgeStatusTextTest {

    @Test
    fun summary_contains_all_three_labels_in_one_line() {
        val s = BridgeStatusText.summary("已就绪", "就绪", "已就绪")
        assertTrue(s.startsWith("设备："))
        assertTrue(s.contains("网关："))
        assertTrue(s.contains("语音："))
        assertTrue("摘要不能换行（通知折叠只显示一行）", !s.contains("\n"))
    }

    @Test
    fun detail_has_three_lines_with_labels() {
        val d = BridgeStatusText.detail("已就绪", "就绪", "预热中…")
        val lines = d.split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("设备："))
        assertTrue(lines[1].startsWith("网关："))
        assertTrue(lines[2].startsWith("语音："))
    }

    @Test
    fun voice_line_follows_link_and_warm_state() {
        assertEquals(BridgeStatusText.VOICE_NO_DEVICE, BridgeStatusText.voiceLine(linkReady = false, warmReady = true))
        assertEquals(BridgeStatusText.VOICE_NO_DEVICE, BridgeStatusText.voiceLine(linkReady = false, warmReady = false))
        assertEquals(BridgeStatusText.VOICE_WARM, BridgeStatusText.voiceLine(linkReady = true, warmReady = true))
        assertEquals("预热中…", BridgeStatusText.voiceLine(linkReady = true, warmReady = false))
    }

    @Test
    fun gateway_name_maps_known_types() {
        assertEquals("OpenClaw", BridgeStatusText.gatewayName("openclaw"))
        assertEquals("Hermes", BridgeStatusText.gatewayName("hermes"))
        assertEquals("OpenAI 兼容", BridgeStatusText.gatewayName("openai"))
        assertEquals("Echo", BridgeStatusText.gatewayName("echo"))
        assertEquals("未配置", BridgeStatusText.gatewayName(null))
        assertEquals("未配置", BridgeStatusText.gatewayName("something-else"))
    }

    @Test
    fun summary_and_detail_stay_unchanged_without_warning() {
        // 默认（前台服务正常）时不能多出任何东西：警告只在真的被系统拒绝时出现
        assertEquals(
            "设备：已就绪 ｜ 网关：就绪 ｜ 语音：已就绪",
            BridgeStatusText.summary("已就绪", "就绪", "已就绪"),
        )
        assertEquals(
            "设备：已就绪\n网关：就绪\n语音：已就绪",
            BridgeStatusText.detail("已就绪", "就绪", "已就绪"),
        )
    }

    @Test
    fun warning_is_visible_in_both_collapsed_and_expanded_text() {
        val warning = ServiceGuard.warningText(ServiceGuard.ForegroundState.DENIED)
        val s = BridgeStatusText.summary("已就绪", "就绪", "已就绪", warning)
        val d = BridgeStatusText.detail("已就绪", "就绪", "已就绪", warning)
        // 折叠时也要能看见（否则三行全是“正常”，而实际上锁屏一分钟就会被系统停掉）
        assertTrue("折叠摘要必须带警告标记", s.contains(ServiceGuard.WARNING_SUMMARY))
        assertTrue("摘要仍不能换行", !s.contains("\n"))
        assertTrue("展开正文要保留原来的三行", d.contains("设备：已就绪\n网关：就绪\n语音：已就绪"))
        assertTrue("展开正文要带完整警告", d.contains(warning!!))
        assertEquals("三行 + 一行警告", 4, d.split("\n").size)
    }
}
