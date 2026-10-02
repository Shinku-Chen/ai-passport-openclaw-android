package com.shinku.aipassport.openclaw.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统音色挑选的纯逻辑单测。
 *
 * 背景：系统 TTS 的音色由引擎决定，`setLanguage(zh)` 选到啥不可控 ✗ —— 可能选到需联网的在线音色，
 * 也可能选到质量最差的兜底音色。设备朗读是"替用户说话"，必须**离线 + 已下载 + 尽量好听**，
 * 且同条件下结果要**稳定**（不能每次启动换一个）。
 */
class TtsVoiceChoiceTest {

    private fun voice(
        name: String,
        language: String = "zh",
        country: String = "CN",
        quality: Int = 2,
        latency: Int = 2,
        requiresNetwork: Boolean = false,
        notInstalled: Boolean = false,
    ) = TtsVoiceChoice.VoiceInfo(name, language, country, quality, latency, requiresNetwork, notInstalled)

    @Test
    fun offline_downloaded_chinese_voice_is_preferred_over_network_one() {
        val network = voice("zh-cn-x-ccc-network", quality = 4, requiresNetwork = true)
        val offline = voice("zh-cn-x-ccc-local", quality = 2)
        assertEquals("离线音色优先于需联网(哪怕质量更低)", offline, TtsVoiceChoice.pick(listOf(network, offline)))
    }

    @Test
    fun not_installed_voice_pack_is_never_picked() {
        val missing = voice("zh-cn-x-missing", quality = 4, notInstalled = true)
        val ok = voice("zh-cn-x-ok")
        assertEquals(ok, TtsVoiceChoice.pick(listOf(missing, ok)))
        assertNull("只剩未下载的语音包 → 不选(交给引擎默认)", TtsVoiceChoice.pick(listOf(missing)))
    }

    @Test
    fun higher_quality_wins_then_lower_latency() {
        val low = voice("zh-cn-a", quality = 1, latency = 0)
        val high = voice("zh-cn-b", quality = 3, latency = 3)
        val highFast = voice("zh-cn-c", quality = 3, latency = 0)
        assertEquals(highFast, TtsVoiceChoice.pick(listOf(low, high, highFast)))
    }

    @Test
    fun result_is_stable_when_everything_is_equal() {
        val a = voice("zh-cn-aaa")
        val b = voice("zh-cn-bbb")
        assertEquals("同名条件必须稳定(名字升序)", a, TtsVoiceChoice.pick(listOf(b, a)))
        assertEquals(a, TtsVoiceChoice.pick(listOf(a, b)))
    }

    @Test
    fun google_style_cmn_locale_counts_as_chinese() {
        // Google 的中文音色标成 cmn-Hans-CN，不是 zh —— 只比 "zh" 会漏掉它
        assertTrue(TtsVoiceChoice.isChinese("cmn"))
        assertTrue(TtsVoiceChoice.isChinese("cmn-Hans-CN"))
        assertTrue(TtsVoiceChoice.isChinese("zh"))
        assertTrue(TtsVoiceChoice.isChinese("zh-Hans-CN"))
        assertTrue(TtsVoiceChoice.isChinese("yue"))
        assertFalse(TtsVoiceChoice.isChinese("en"))
        assertFalse(TtsVoiceChoice.isChinese("ja"))
    }

    @Test
    fun english_only_engine_falls_back_to_default_instead_of_picking_english() {
        val en = voice("en-us-x-sfg-local", language = "en", country = "US", quality = 4)
        assertNull("宁可不选,也不拿英文音色念中文", TtsVoiceChoice.pick(listOf(en)))
        // 明确允许退到任意离线音色时才选它(引擎不支持中文的场景)
        assertEquals(en, TtsVoiceChoice.pick(listOf(en), chineseOnly = false))
    }

    @Test
    fun network_only_engine_is_not_used_for_device_speech() {
        val only = voice("zh-cn-x-network", requiresNetwork = true)
        assertNull("设备朗读不能依赖网络", TtsVoiceChoice.pick(listOf(only)))
    }

    @Test
    fun empty_voice_list_returns_null() {
        assertNull(TtsVoiceChoice.pick(emptyList()))
    }

    @Test
    fun describe_contains_the_facts_needed_to_choose() {
        val text = TtsVoiceChoice.describe(
            voice("zh-cn-x-ccc-local", country = "CN", quality = 4, latency = 1),
        )
        assertTrue(text.contains("zh-CN"))
        assertTrue(text.contains("zh-cn-x-ccc-local"))
        assertTrue(text.contains("离线"))
        assertTrue(text.contains("已下载"))
        assertTrue(text.contains("质量=4"))
        assertTrue(text.contains("延迟=1"))
        assertTrue("需联网的音色要标出来", TtsVoiceChoice.describe(voice("x", requiresNetwork = true)).contains("需联网"))
    }
}
