package com.shinku.aipassport.openclaw.tts

/**
 * 设备朗读(下行 TTS)的**可插拔合成引擎**抽象。
 *
 * 与 [TtsEngine](手机本地 `TextToSpeech.speak` 播放)是两条独立通路:
 * 本接口只负责「文本 → PCM」,把 PCM 编成 Opus 并逐帧下发到设备的通路在
 * `service/VoiceBridgeService.kt`(见 `docs/wire-protocol.md` 的 TTS 下行一节)。
 *
 * 约定:
 *  - 合成结果是 **单声道 16bit 小端 PCM**,采样率由实现决定(通过参数请求,实现可忽略);
 *    实际采样率由 [lastSampleRateHz] 回报,只有 16000 / 24000 能编成设备认的下行帧
 *    (见 [TtsFraming.supportedRateKhz])。
 *  - M1 只做「同采样率」:不做重采样。若引擎输出的采样率与请求不一致,实现**只记日志**,
 *    并把实际采样率写进 [lastSampleRateHz],由调用方决定是否放弃本轮下发。
 *  - 失败一律返回 null(不抛异常),原因写在 [lastError] 里(供日志/状态提示),例如
 *    `未安装语音引擎`。
 */
interface DeviceTtsEngine {

    /** 引擎标识(与设置项 `tts_engine` 的取值一致,见 [TtsEngines])。 */
    val id: String

    /**
     * 最近一次 [synthesize] 实际输出的采样率(Hz);`0` = 还没合成过 / 未知。
     *
     * 为什么需要它:系统 TTS 的输出采样率由引擎决定(实测常见 16000 / 24000 / 22050),
     * 而下行帧的帧长按采样率算(16k→960 samples、24k→1440 samples),必须用真实值分帧。
     */
    val lastSampleRateHz: Int
        get() = 0

    /** 最近一次 [prepare] / [synthesize] 失败的可读原因;null = 没失败。 */
    val lastError: String?
        get() = null

    /**
     * 预检:引擎可用/已安装(系统 TTS 需要先等 `TextToSpeech` 初始化回调)。
     *
     * @return true = 可以 [synthesize];false = 不可用(原因见 [lastError])
     */
    suspend fun prepare(): Boolean

    /**
     * 把 [text] 合成成 PCM(单声道 16bit 小端)。
     *
     * @param sampleRateHz 期望采样率(16000 或 24000);实现可忽略(见 [lastSampleRateHz])
     * @return PCM 字节;失败返回 null(原因见 [lastError])
     */
    suspend fun synthesize(text: String, sampleRateHz: Int): ByteArray?
}

/** `tts_engine` 设置项的取值(设置页下拉的选项值,与 [DeviceTtsEngine.id] 对应)。 */
object TtsEngines {
    /** 系统 TextToSpeech(`synthesizeToFile` → WAV → PCM);M1 唯一实现。 */
    const val ANDROID = "android"

    /** OpenAI 兼容 `/v1/audio/speech` 或自建 TTS 服务;M1 只留骨架(服务选型待确认)。 */
    const val HTTP = "http"

    /** 全部合法取值;非法值退回 [ANDROID]。 */
    val ALL = listOf(ANDROID, HTTP)

    /** 归一化设置项里的引擎名(非法/空 → [ANDROID])。 */
    fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in ALL } ?: ANDROID

    /** 设置页下拉展示名(与 [ALL] 顺序一致)。 */
    fun label(engine: String): String = when (normalize(engine)) {
        HTTP -> "HTTP 服务(未实现)"
        else -> "系统 TTS(Android)"
    }
}
