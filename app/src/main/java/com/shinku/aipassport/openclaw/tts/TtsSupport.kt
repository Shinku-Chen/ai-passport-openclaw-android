package com.shinku.aipassport.openclaw.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 「本机到底支不支持设备朗读」的探测。
 *
 * 为什么需要:设备朗读是**手机先把回复合成成语音**,再编 Opus 下发给设备播放。
 * 如果这台手机压根没有可用的 TTS 引擎(或语音数据没下载),打开开关只会让每条回复都失败 ——
 * 因此这种机器上设置页要直接把开关关掉并置灰(见 SettingsFragment.applyTtsAvailability)。
 *
 * 判定标准(宁可严一点,也不让用户打开一个必然失败的开关):
 *  1. [TextToSpeech] 能初始化(`status == SUCCESS`);
 *  2. 引擎里至少有一种语言可用(中文或系统默认语言,`isLanguageAvailable >= LANG_AVAILABLE`)。
 *
 * 探测本身复用 [AndroidTtsEngine] 的初始化与错误文案,探完立即 `shutdown`,不长期占着引擎。
 * 注意:`TextToSpeech` 需要一个有 Looper 的线程,所以调用点必须留在主线程(协程默认 Dispatchers.Main)。
 */
object TtsSupport {

    /** 探测结果:null = 本机支持;非 null = 不支持的原因(可直接显示给用户)。 */
    suspend fun unsupportedReason(context: Context): String? {
        val engine = AndroidTtsEngine(context)
        return try {
            if (!engine.prepare()) {
                engine.lastError ?: "系统语音引擎不可用"
            } else if (isLanguageUsable(engine.languageAvailability(Locale.SIMPLIFIED_CHINESE)) ||
                isLanguageUsable(engine.languageAvailability(Locale.getDefault()))
            ) {
                null
            } else {
                "系统语音引擎没有可用语言(中文与默认语言的语音数据都缺失)"
            }
        } catch (e: Exception) {
            "语音引擎探测失败:${e.message}"
        } finally {
            engine.shutdown()
        }
    }

    /**
     * `TextToSpeech.isLanguageAvailable` 的返回值是否代表「能念」。
     *
     * 取值:`LANG_AVAILABLE(0) / LANG_COUNTRY_AVAILABLE(1) / LANG_COUNTRY_VAR_AVAILABLE(2)` 可用,
     * `LANG_MISSING_DATA(-1) / LANG_NOT_SUPPORTED(-2)` 不可用;`null`(引擎没起来)同样算不可用。
     */
    fun isLanguageUsable(status: Int?): Boolean = status != null && status >= TextToSpeech.LANG_AVAILABLE
}
