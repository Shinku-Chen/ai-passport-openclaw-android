package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.gateway.GatewaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设备朗读 / 播放小智语音」两处开关的**同源显示状态**单测(JVM,无 Android / 无界面)。
 *
 * 钉住的契约(作者明确要求「同一件事两处不能不一致」+「本机合不成语音不许连累小智」):
 *  1. 两处开关的勾选状态**只有一个来源** `tts_enabled` —— 任一处入口拨动后两处同值,不存在第二份状态;
 *  2. 「网关设置」里那行开关**只在「小智 AI」下出现**,其余四种网关(以及无类型)一律隐藏;
 *  3. **值与可用性解耦**:本机合成不可用时**只置灰**,勾选状态仍取 `tts_enabled`(灰但开),不写值;
 *  4. **小智 AI 下一定可点**:小智的音频由云端下发,不经本机合成,本机能力限制不到它;
 *  5. 文案写明「与『对话设置 → 设备朗读』是同一个开关」,并在置灰/小智分组里说明
 *     「本机不支持合成语音：<原因>;小智 AI 下仍可开启」。
 */
class DeviceTtsSwitchesTest {

    @Test
    fun xiaozhi_row_is_visible_only_for_xiaozhi_gateway() {
        assertTrue(DeviceTtsSwitches.xiaozhiRowVisible(GatewaySettings.TYPE_XIAOZHI))
        // 判定复用 XiaozhiIdentity(带大小写/空白归一化),不在这里另写一份比较
        assertTrue(DeviceTtsSwitches.xiaozhiRowVisible(" Xiaozhi "))

        // 其余四种网关:这一行隐藏(它们的「设备朗读」念的是手机本地合成的音频,不是小智的声音)
        assertFalse(DeviceTtsSwitches.xiaozhiRowVisible(GatewaySettings.TYPE_OPENCLAW))
        assertFalse(DeviceTtsSwitches.xiaozhiRowVisible(GatewaySettings.TYPE_HERMES))
        assertFalse(DeviceTtsSwitches.xiaozhiRowVisible(GatewaySettings.TYPE_OPENAI))
        assertFalse(DeviceTtsSwitches.xiaozhiRowVisible(GatewaySettings.TYPE_ECHO))
        assertFalse("未知/空类型按非小智处理", DeviceTtsSwitches.xiaozhiRowVisible(null))
    }

    @Test
    fun both_switches_read_the_same_state_whichever_entry_changed() {
        // 同一个设置项 → 同一个 checked:两处不可能一开一关
        listOf(true, false).forEach { on ->
            listOf(GatewaySettings.TYPE_XIAOZHI, GatewaySettings.TYPE_OPENCLAW).forEach { type ->
                assertEquals(on, DeviceTtsSwitches.view(type, ttsEnabled = on).checked)
            }
        }

        // 关掉后切到别的类型、再切回小智 AI:读到的仍然是同一个值(没有按类型各存一份)
        val closedInChat = DeviceTtsSwitches.view(GatewaySettings.TYPE_OPENCLAW, ttsEnabled = false)
        val backInXiaozhi = DeviceTtsSwitches.view(GatewaySettings.TYPE_XIAOZHI, ttsEnabled = false)
        assertFalse(closedInChat.checked)
        assertEquals(closedInChat.checked, backInXiaozhi.checked)
    }

    /**
     * 真机 bug 的回归(最重要的两条):
     * 本机合成不可用时,开关**仍然是开**(checked 只跟 `tts_enabled` 走),且**小智 AI 下可点** ——
     * 小智的音源是小智自己下发的音频,不需要本机合成。
     */
    @Test
    fun unsupported_local_tts_keeps_switch_on_and_lets_xiaozhi_toggle_it() {
        // ① 本机合成不可用 + 小智 AI:可点(本机能力限制不到小智直通)
        val xiaozhi = DeviceTtsSwitches.view(
            GatewaySettings.TYPE_XIAOZHI,
            ttsEnabled = true,
            ttsSupported = false,
        )
        assertTrue("小智 AI 下即使本机合不成语音也必须可点", xiaozhi.enabled)
        assertTrue("开关的值只由 tts_enabled 决定:默认开就是开", xiaozhi.checked)
        assertTrue(DeviceTtsSwitches.switchEnabled(GatewaySettings.TYPE_XIAOZHI, ttsSupported = false))

        // ② 本机合成不可用 + 非小智:置灰,但**仍然是开**(灰 ≠ 关,不再把用户的值写成 false)
        listOf(GatewaySettings.TYPE_OPENCLAW, GatewaySettings.TYPE_HERMES, null).forEach { type ->
            val grey = DeviceTtsSwitches.view(type, ttsEnabled = true, ttsSupported = false)
            assertFalse("非小智 + 本机合不成语音 → 置灰", grey.enabled)
            assertTrue("置灰不改值:checked 仍取自 tts_enabled", grey.checked)
        }

        // ③ 用户手动关掉后再探测一次:置灰也只是置灰,不能把它又拨回开
        val closed = DeviceTtsSwitches.view(
            GatewaySettings.TYPE_OPENCLAW,
            ttsEnabled = false,
            ttsSupported = false,
        )
        assertFalse(closed.enabled)
        assertFalse(closed.checked)
    }

    @Test
    fun supported_local_tts_keeps_both_switches_clickable_in_every_gateway() {
        // 本机合成可用:两种网关类型下都可点;checked 只跟 ttsEnabled 走
        listOf(GatewaySettings.TYPE_XIAOZHI, GatewaySettings.TYPE_OPENCLAW, null).forEach { type ->
            listOf(true, false).forEach { on ->
                val v = DeviceTtsSwitches.view(type, ttsEnabled = on, ttsSupported = true)
                assertTrue("本机合成可用时处处可点", v.enabled)
                assertEquals("可点与否不影响值", on, v.checked)
            }
        }
    }

    @Test
    fun hint_says_it_is_the_same_switch_as_device_tts() {
        assertEquals("播放小智语音", DeviceTtsSwitches.XIAOZHI_TITLE)
        assertTrue(DeviceTtsSwitches.XIAOZHI_HINT.contains("同一个开关"))
        assertTrue(DeviceTtsSwitches.XIAOZHI_HINT.contains("设备朗读"))
        assertTrue("说明里要讲清音源是小智自己的声音", DeviceTtsSwitches.XIAOZHI_HINT.contains("小智的声音"))
        assertTrue("小智这条通路不经过本机合成", DeviceTtsSwitches.XIAOZHI_HINT.contains("不需要本机合成"))

        // 两处入口名(只进日志):日志里要能看出用户拨的是哪一处
        assertEquals("设备朗读", DeviceTtsSwitches.ENTRY_CHAT)
        assertEquals("播放小智语音", DeviceTtsSwitches.ENTRY_XIAOZHI)
    }

    /** 置灰提示 / 小智分组提示都要把「原因」与「小智 AI 下仍可开启」一起说出来。 */
    @Test
    fun hints_explain_reason_and_that_xiaozhi_still_works() {
        val reason = "系统语音引擎不可用"

        val greyHint = DeviceTtsSwitches.unsupportedHint(reason)
        assertTrue("要带上可读原因", greyHint.contains(reason))
        assertTrue("要说清是小智 AI 下仍能开", greyHint.contains("小智 AI"))
        assertTrue(greyHint.contains("仍可开启"))
        assertTrue("要说清这条通路不需要本机合成", greyHint.contains("不需要本机合成"))

        // 小智分组里同样带原因(用户在小智分组看到「与设备朗读是同一个开关」时不会误以为这里也开不了)
        val xzHint = DeviceTtsSwitches.xiaozhiHint(reason)
        assertTrue(xzHint.startsWith(DeviceTtsSwitches.XIAOZHI_HINT))
        assertTrue(xzHint.contains(reason))
        assertTrue(xzHint.contains("仍可开启"))

        // 本机支持合成时:小智分组就是原说明,不糊一段无意义的告警
        assertEquals(DeviceTtsSwitches.XIAOZHI_HINT, DeviceTtsSwitches.xiaozhiHint(null))
    }
}
