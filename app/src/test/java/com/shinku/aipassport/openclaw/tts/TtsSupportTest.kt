package com.shinku.aipassport.openclaw.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本机是否支持设备朗读」的判定边界。
 * 只测纯逻辑([TtsSupport.isLanguageUsable]);真正构造引擎的探测无法在 JVM 单测里跑。
 */
class TtsSupportTest {

    @Test
    fun `能念的语言状态都算可用`() {
        assertTrue(TtsSupport.isLanguageUsable(TextToSpeech.LANG_AVAILABLE))
        assertTrue(TtsSupport.isLanguageUsable(TextToSpeech.LANG_COUNTRY_AVAILABLE))
        assertTrue(TtsSupport.isLanguageUsable(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
    }

    @Test
    fun `缺数据或不被支持都不算可用`() {
        assertFalse(TtsSupport.isLanguageUsable(TextToSpeech.LANG_MISSING_DATA))
        assertFalse(TtsSupport.isLanguageUsable(TextToSpeech.LANG_NOT_SUPPORTED))
    }

    @Test
    fun `引擎没起来(null)也算不可用`() {
        assertFalse(TtsSupport.isLanguageUsable(null))
    }
}
