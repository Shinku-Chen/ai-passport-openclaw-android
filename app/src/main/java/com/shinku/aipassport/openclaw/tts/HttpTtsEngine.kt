package com.shinku.aipassport.openclaw.tts

import android.util.Log

/**
 * 设备朗读(下行 TTS)的 **HTTP 引擎骨架** —— M1 不实现网络细节,TTS 服务选型待用户确认。
 *
 * 目标形态(待定稿后在此实现):
 *  - OpenAI 兼容:`POST {baseUrl}/v1/audio/speech`,body
 *    `{"model":…,"voice":…,"input":"<文本>","response_format":"pcm","sample_rate":24000}`,
 *    直接返回单声道 16bit 小端 PCM(省掉 WAV 解析);
 *  - 或自建服务(如本地 piper/ChatTTS 网关):接口形状与鉴权方式按选定方案补。
 *
 * 目前([prepare] 恒 false、[synthesize] 恒 null):调用方会拿到可读原因并跳过本轮下发,
 * 不会发出半个协议帧。要启用时必须做到:
 *  1. `prepare()` 里探活(HTTP 204/200 或 `/models`),并校验返回采样率属于 [TtsFraming.SUPPORTED_RATES_HZ];
 *  2. `synthesize()` 把响应体当 PCM 返回,并把真实采样率写进 [lastSampleRateHz];
 *  3. 失败原因写 [lastError](超时/HTTP 状态码/格式不符),由日志与状态面板暴露。
 */
@Suppress("UNUSED_PARAMETER")
class HttpTtsEngine(
    /** 服务基址,如 `http://192.168.1.10:8880`(设置页字段待定)。 */
    private val baseUrl: String,
    /** 模型名 / 音色名(设置页字段待定)。 */
    private val model: String = "",
    private val voice: String = "",
    /** Bearer token(只存本机,绝不入库)。 */
    private val apiKey: String = "",
) : DeviceTtsEngine {

    override val id: String = TtsEngines.HTTP

    @Volatile
    override var lastSampleRateHz: Int = 0
        private set

    @Volatile
    override var lastError: String? = null
        private set

    override suspend fun prepare(): Boolean {
        // TODO(http-tts): 探活 + 参数校验(见类注释)。M1 只留骨架,服务选型待用户确认。
        lastError = "HTTP TTS 引擎尚未实现(M1 只留骨架,服务选型待确认)"
        Log.w(TAG, lastError!!)
        return false
    }

    override suspend fun synthesize(text: String, sampleRateHz: Int): ByteArray? {
        // TODO(http-tts): POST {baseUrl}/v1/audio/speech,取响应体当 PCM 返回,并回报实际采样率。
        lastError = "HTTP TTS 引擎尚未实现(M1 只留骨架,服务选型待确认)"
        return null
    }

    private companion object {
        const val TAG = "HttpTtsEngine"
    }
}
