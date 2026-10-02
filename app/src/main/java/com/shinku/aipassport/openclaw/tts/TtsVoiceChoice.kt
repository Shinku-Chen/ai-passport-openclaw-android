package com.shinku.aipassport.openclaw.tts

/**
 * 「选哪个系统音色」的纯逻辑（JVM 单测可覆盖）：从引擎枚举出的音色里挑一个用于**设备朗读**的。
 *
 * 为什么要显式挑：系统 TTS 的音色由引擎决定，`setLanguage(zh)` 只是让引擎自己选 —— 选到什么
 * **不可控** ✗：可能选到**需要联网**的在线音色（锁屏/外出时没有余量，还会静默消耗流量 ✗），
 * 也可能选到质量最差的本地兜底音色 ✗。设备朗读是"替用户说话"，必须稳定、离线、尽量好听：
 *
 *  1. **能用**：语音包已下载（不带 `KEY_FEATURE_NOT_INSTALLED`）；
 *  2. **离线**：`isNetworkConnectionRequired == false`（不靠网络，也不靠猜音色名 —— Google 的
 *     `-network` 后缀只是命名习惯，真正可靠的是这个字段）；
 *  3. **中文**：语言标签匹配中文。注意 Google 的中文音色标成 `cmn-Hans-CN`（不是 `zh`），
 *     所以不能只比 `"zh"`；
 *  4. 质量高（`quality` 大）→ 延迟低（`latency` 小）→ 名字字典序（同条件时结果稳定，
 *     不会每次启动选到不同音色）；
 *  5. 一个都挑不到就返回 null，**保持引擎默认**（绝不硬塞一个英文音色去念中文 ✗）。
 */
object TtsVoiceChoice {

    /** 中文语言标签（Google 用 `cmn`，粤语用 `yue`）。 */
    fun isChinese(language: String): Boolean {
        val l = language.lowercase()
        return l.startsWith("zh") || l.startsWith("cmn") || l == "yue"
    }

    /**
     * 音色的关键信息：把 Android 的 `TextToSpeech.Voice` 拍平，便于纯逻辑单测。
     *
     * @param quality `TextToSpeech.Voice.QUALITY_*`（越大越好）
     * @param latency `TextToSpeech.Voice.LATENCY_*`（越小越好）
     * @param requiresNetwork 引擎自己声明"要联网"（`isNetworkConnectionRequired`）
     * @param notInstalled 语音包没下载（features 里带 `KEY_FEATURE_NOT_INSTALLED`）
     */
    data class VoiceInfo(
        val name: String,
        val language: String,
        val country: String = "",
        val quality: Int = 0,
        val latency: Int = 0,
        val requiresNetwork: Boolean = false,
        val notInstalled: Boolean = false,
    ) {
        /** 语音包已下载、可以真用。 */
        val downloaded: Boolean get() = !notInstalled

        /** 离线可用。 */
        val offline: Boolean get() = !requiresNetwork
    }

    /**
     * 挑一个设备朗读用的音色；挑不到返回 null（调用方保持引擎默认）。
     *
     * @param chineseOnly true（默认）= 只在中文音色里挑，一个都没有时返回 null（宁可退回引擎默认，
     *   也不拿英文音色念中文）；false = 可以退到任意离线音色（引擎明确不支持中文时才用）。
     */
    fun pick(voices: List<VoiceInfo>, chineseOnly: Boolean = true): VoiceInfo? {
        val usable = voices.filter { it.downloaded && it.offline }
        val chinese = usable.filter { isChinese(it.language) }
        val pool = when {
            chinese.isNotEmpty() -> chinese
            chineseOnly -> return null
            else -> usable
        }
        return pool.minWithOrNull(ORDER)
    }

    /** 质量高优先 → 延迟低优先 → 名字升序（保证稳定）。 */
    private val ORDER: Comparator<VoiceInfo> =
        compareByDescending<VoiceInfo> { it.quality }
            .thenBy { it.latency }
            .thenBy { it.name }

    /** 一行描述（日志与设置页下拉通用），如 `cmn-Hans-CN · zh-cn-x-ccc-local · 离线 · 质量=4 延迟=3`。 */
    fun describe(v: VoiceInfo): String {
        val locale = if (v.country.isBlank()) v.language else "${v.language}-${v.country}"
        val net = if (v.requiresNetwork) "需联网" else "离线"
        val pack = if (v.notInstalled) "语音包未下载" else "已下载"
        return "$locale · ${v.name} · $net · $pack · 质量=${v.quality} 延迟=${v.latency}"
    }
}
