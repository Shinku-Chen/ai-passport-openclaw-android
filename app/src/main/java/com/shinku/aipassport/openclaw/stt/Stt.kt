package com.shinku.aipassport.openclaw.stt

/**
 * 语音识别抽象。
 *
 * 本机用系统 android.speech.SpeechRecognizer(离线优先)。
 * 注意:系统 SpeechRecognizer 没有接收外部 PCM 的公开 API,它只采集本机麦克风,
 * 因此识别的是"手机麦克风"的语音(离线优先,on-device)。BLE 送来的 PCM 不喂给 STT,
 * 仅作为触发上下文;识别由手机麦克风拾音完成。
 */
interface SttEngine {
    /** 引擎是否可用(系统识别服务存在)。 */
    val isAvailable: Boolean

    /** 开始一段新的语音。 */
    fun startTurn()

    /** BLE PCM(保留接口,系统引擎不使用外部 PCM)。 */
    fun feedPcm(pcm: ByteArray)

    /** 结束本段语音,返回识别文本;失败/空返回 null。 */
    suspend fun endTurn(): String?

    /** 打断当前识别(如用户再次按下 PTT)。 */
    fun barge()

    /** 预加载(系统引擎无显著预热,默认空实现)。 */
    fun prewarm() {}

    /** 释放资源。 */
    fun release()
}
