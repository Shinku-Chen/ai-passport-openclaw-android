package com.shinku.aipassport.openclaw.tts

/**
 * 下行 TTS 的三条 `CONTROL` 事件(逐字与固件 `intercom-wire-protocol.md` 一致)。
 *
 * 均为手机→设备:`tts_start` 开启一段语音、`tts_stop` 声明发完、`tts_abort` 要求立刻丢掉
 * 队列(用户又按了 OK / 网关流断了)。三条都用 `CONTROL` 帧发送但用 `ev` 形状 —— 它们是
 * 「手机观测到的事实」而不是「请设备执行的命令」。
 */
object TtsControl {
    const val START_JSON = "{\"ev\":\"tts_start\"}"
    const val STOP_JSON = "{\"ev\":\"tts_stop\"}"
    const val ABORT_JSON = "{\"ev\":\"tts_abort\"}"
}

/**
 * 设备朗读(下行 TTS)的**下发通路**注入点:流水线只依赖本接口,实现放在
 * `service/VoiceBridgeService.kt`(与现有 `TEXT` / `EVENT` / `CONTROL` 下发并列)。
 *
 * 协议(见 `docs/wire-protocol.md` 的 TTS 下行):
 *  1. `{"ev":"tts_start"}`(CONTROL);
 *  2. 逐帧 `TYPE_TTS_OPUS`(0x06),每帧一个 60ms Opus 包,领先设备 ≤2s;
 *  3. `{"ev":"tts_stop"}`(CONTROL);
 *  4. 被 barge / 取消时立刻 `{"ev":"tts_abort"}` 并停止推送。
 *
 * 所有方法都必须**非阻塞**(下发是异步协程,失败只记日志):调用方在流水线里,绝不能因为
 * 合成/编码/写 BLE 而拖住上屏与下一轮对话。
 */
interface DeviceTtsDownlink {

    /** 异步下发一句回复(合成 → Opus → 逐帧下发);同一时刻只保留最新一次,旧的在途下发会被中止。 */
    fun speak(text: String)

    /**
     * 立刻中止在途下发并向设备发 `{"ev":"tts_abort"}`。
     *
     * **无论当前有没有在途下发都要发**:设备最多缓存约 2s 音频,上一轮 `tts_stop` 之后它
     * 可能还在播旧回复;`tts_abort` 是唯一能让它立刻停手(并恢复上行采集)的信号。
     */
    fun abort()

    /** 设备回报一段 TTS 播放结束([TtsPlaybackReport]):记日志、与本地「已发送」对账。 */
    fun onPlaybackReport(report: TtsPlaybackReport)
}

/**
 * 设备朗读的**编排**(纯逻辑,不依赖 Android/coroutines,可 JVM 单测):决定「什么时候中止」、
 * 「什么时候真的下发」这两条协议要求,流水线只调这里,拿真正的 BLE/合成细节由 [downlink] 负责。
 *
 * 语义:
 *  - [onTurnStart](设备 `turn_start`,即用户按下 OK 开始新一轮):**无条件**发 `tts_abort` ——
 *    设备可能仍在播上一轮回复的缓冲,新一轮必须立刻把旧语音掐掉;这一步与设置开关无关
 *    (关闭设置项时 App 本来就没推过音频,多一条 20B 的 CONTROL 帧是无害的幂等操作);
 *  - [onReply](网关回复已通过 `'A'` 文本帧上屏**之后**):仅在 [enabled] 为真且文本非空时下发,
 *    异步执行、不阻塞上屏与回话流程(见 [DeviceTtsDownlink.speak]);
 *  - [onCancel](barge / 断连 / 服务销毁):中止在途下发;
 *  - [onDeviceEvent](设备 `tts_playback_done` / `tts_playback_aborted`):转给 [downlink] 记日志。
 */
class DeviceTtsSession(
    /** 设置项 `tts_enabled`(默认关,真机验收后再默认开);每次调用时实时读取。 */
    private val enabled: () -> Boolean,
    private val downlink: DeviceTtsDownlink,
) {

    /** 新一轮开始(`turn_start`):无条件中止上一段设备朗读。 */
    fun onTurnStart() {
        downlink.abort()
    }

    /** 本轮被取消(barge / 链路断开 / 服务销毁):中止在途下发。 */
    fun onCancel() {
        downlink.abort()
    }

    /**
     * 网关回复文本已上屏(`'A'` 文本帧已发出)之后触发一次设备朗读。
     * 设置关闭或文本为空(含全空白)时不下发。
     */
    fun onReply(text: String) {
        if (!enabled()) return
        if (text.isBlank()) return
        downlink.speak(text)
    }

    /** 设备回报播放结果:转给下发实现记日志(与本地「已发送」帧数对账)。 */
    fun onDeviceEvent(report: TtsPlaybackReport) {
        downlink.onPlaybackReport(report)
    }

    companion object {
        /**
         * 未接线(默认):两个回调都是 no-op。用于测试与不关心设备朗读的调用方,
         * 语义等于「设置里 `tts_enabled` 关闭」。
         */
        fun disabled(): DeviceTtsSession = DeviceTtsSession(enabled = { false }, downlink = NoopDownlink)

        private object NoopDownlink : DeviceTtsDownlink {
            override fun speak(text: String) = Unit
            override fun abort() = Unit
            override fun onPlaybackReport(report: TtsPlaybackReport) = Unit
        }
    }
}
