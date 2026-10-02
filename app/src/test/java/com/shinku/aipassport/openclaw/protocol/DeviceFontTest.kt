package com.shinku.aipassport.openclaw.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceFont] 的单测：把「设备屏能显示哪些字符」钉死在**固件字库的真实字符集**上。
 *
 * 设备字库（`lv_font_intercom_cjk_16` = GB2312 全量 + ASCII，7540 个码位）没有字体回退，
 * 字库外字符在屏幕上就是方块。这里的期望值来自固件仓库
 * `assets/fonts/intercom_cjk_symbols.txt`（由 `tools/intercom_font.py` 生成）：
 * 实测 JDK 的 `GB2312` 可编码码位与那份清单逐位一致（差一个不可见的控制符 DEL）。
 */
class DeviceFontTest {

    @Test
    fun ascii_and_gb2312_text_is_renderable() {
        assertTrue(DeviceFont.canRender("固件 1.12 与 App 1.11 大版本不一致"))
        assertTrue(DeviceFont.canRender("（1.12 ≠ 1.11）"))
        assertTrue(DeviceFont.canRender("网关配置已重载,正在重连… ｜ unknown method: usage"))
        assertTrue(DeviceFont.canRender("：")); assertTrue(DeviceFont.canRender("，"))
        assertTrue(DeviceFont.canRender("…")); assertTrue(DeviceFont.canRender("｜"))
        assertTrue(DeviceFont.canRender("★")); assertTrue(DeviceFont.canRender("×"))
        assertTrue(DeviceFont.canRender("←")); assertTrue(DeviceFont.canRender("→"))
    }

    @Test
    fun arrow_left_right_pair_is_not_renderable() {
        // 真机 bug：提示里的 ↔(U+2194) 不在 GB2312（只有 ← ↑ → ↓）→ 设备屏两个方块
        assertFalse(DeviceFont.canRender("↔"))
        assertEquals('↔', DeviceFont.firstUnrenderable("1.12 ↔ 1.11"))
    }

    @Test
    fun common_punctuation_that_is_not_in_gb2312_is_rejected() {
        assertFalse("长破折号 — 设备字库没有", DeviceFont.canRender("—"))
        assertFalse("间隔点 · 设备字库没有", DeviceFont.canRender("·"))
        assertFalse("对勾 ✓ 设备字库没有", DeviceFont.canRender("✓"))
        assertTrue("弯引号是 GB2312 里的，不能误杀", DeviceFont.canRender("“你好”"))
    }

    @Test
    fun emoji_and_non_gb2312_hanzi_are_rejected() {
        assertFalse(DeviceFont.canRender("👍"))
        assertFalse(DeviceFont.canRender("⭐"))
        assertFalse("囧(U+56E7)在 GBK 里但 GB2312 没有", DeviceFont.canRender("囧"))
        assertFalse("ゔ(U+3094)不在 GB2312（平假名只收了基础那几十个）", DeviceFont.canRender("ゔ"))
        assertTrue("基础假名 GB2312 里有，不能误杀", DeviceFont.canRender("あアん"))
    }

    @Test
    fun first_unrenderable_points_at_the_offending_char() {
        assertNull(DeviceFont.firstUnrenderable("全是 GB2312 里的字,还有 ASCII 123"))
        assertEquals('↔', DeviceFont.firstUnrenderable("1.12 ↔ 1.11"))
        assertEquals('—', DeviceFont.firstUnrenderable("网关不可达 — 正在重连"))
        // emoji 是代理对：返回的是高位代理项（它本身也编不进 GB2312）
        val emojiFirst = DeviceFont.firstUnrenderable("你好👍再见")
        assertNotNull(emojiFirst)
        assertTrue("应指向 emoji 的代理项", emojiFirst!!.code in 0xD800..0xDBFF)
    }
}
