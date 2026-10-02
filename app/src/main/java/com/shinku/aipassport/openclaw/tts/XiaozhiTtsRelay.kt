package com.shinku.aipassport.openclaw.tts

import android.util.Log
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbTtsOpusPayload
import com.shinku.aipassport.openclaw.protocol.encodeTtsOpusPayload
import com.shinku.aipassport.openclaw.stt.XiaozhiTtsObserver

/**
 * 小智 TTS 直通的**下行注入点**:只负责把**已经编码好的** opus 包交给设备,
 * 不做任何合成、解码或重采样(见 `docs/design/xiaozhi-ai-gateway.md` §4.3)。
 *
 * 为什么要与 [DeviceTtsDownlink] 分开:本地合成那条路是「文本进 → 手机合成 PCM → 编 Opus」,
 * 而这里是「小智给的 opus 原样转发」,输入形状根本不同。分开后本地合成路径的行为一行不动,
 * 这里也能被 JVM 单测用假实现覆盖(不依赖 Android/BLE)。
 *
 * 三条 `CONTROL` 事件与设备回报的语义完全复用现有下行([DeviceTtsSession] / [TtsControl]):
 * `start` → `{"ev":"tts_start"}`、`stop` → `{"ev":"tts_stop"}`;
 * 打断用的 `{"ev":"tts_abort"}` 由流水线每轮 `turn_start` 无条件下发(见 [DeviceTtsSession.onTurnStart]),
 * 本接口不重复承担,避免两条路径各发一次。
 */
interface XiaozhiTtsDownlink {

    /** 一段朗读开始:下发 `{"ev":"tts_start"}`(设备进入播放态:暂停上行采集、保持背光)。 */
    fun start()

    /**
     * 交付一帧下行音频。
     *
     * @param rateKhz 采样率(kHz;固件只接受 16/24)
     * @param frameMs 帧长(ms;小智恒为 60)
     * @param payload 已按 `[SEQ][rate_khz][frame_ms] + opus` 组好的 TTS_OPUS 载荷(见 [encodeTtsOpusPayload])
     */
    fun pushFrame(rateKhz: Int, frameMs: Int, payload: ByteArray)

    /** 一段朗读结束:下发 `{"ev":"tts_stop"}`(设备把已入队的播完再退出播放态)。 */
    fun stop()
}

/**
 * 小智 `tts` 状态机 → 设备下行 TTS 生命周期的**纯逻辑映射**(可 JVM 单测,不依赖 Android/coroutines)。
 *
 * 一轮的生命周期(见 `docs/wire-protocol.md` 的 TTS 下行与设计文档 §4.3):
 * ```
 * 设备 turn_start ─(流水线)─> {"ev":"tts_abort"} + relay.onTurnStart()   // 上一轮作废
 * 小智 tts.state=start / sentence_start ─> relay ─> {"ev":"tts_start"}    // 句级开始,不整段等
 * 小智 二进制 opus 帧 ─> relay 组 [SEQ][rate_khz][frame_ms]+opus ─> TTS_OPUS
 * 小智 tts.state=stop ─> relay ─> {"ev":"tts_stop"}
 * 打断 / barge ─> 小智 listen.stop(会话层) + 设备 {"ev":"tts_abort"}(流水线)
 * ```
 *
 * 几条不变量:
 *  - **一段朗读只发一次 `tts_start`**(首个 `start` / `sentence_start` 时发):设备把 `tts_start`
 *    当作「进入播放态」,逐句重发既没有协议含义,也会反复打断它的上行采集;
 *  - **只接受 `start` 之后、`stop` 之前的音频**:`sentence_end` 不算结束(小智按句推,`stop` 才是一段完结);
 *  - **一轮开始必须重置**:打断时服务端不一定回 `stop`,若还认为「本段进行中」,下一轮音频就会缺
 *    `tts_start` 而直接甩给设备(见 [onTurnStart]);
 *  - **设备朗读关闭时一个音频帧都不下发**(`enabled` 为 false;开关语义与本地合成那条路一致),
 *    但已经开始的段落仍照常收尾(发 `tts_stop`),避免设备停在播放态;
 *  - **不做重采样/重编码**:采样率不是 16/24 kHz、帧长非正、opus 包超 512B 的帧一律丢弃并记日志
 *    (丢一帧 ≒ 60ms,不值得为此改动协议或引入重采样)。
 *
 * @param enabled 设备朗读开关(`tts_enabled`)+「当前网关类型是小智 AI」的实时判定。
 *   必须是实时读取:设置页切换即时生效,不需要重启服务。
 * @param downlink 真正的下行实现(BLE 下发 + 流控在服务侧)。
 */
class XiaozhiTtsRelay(
    private val enabled: () -> Boolean,
    private val downlink: XiaozhiTtsDownlink,
) : XiaozhiTtsObserver {

    private val tag = "XiaozhiTtsRelay"

    /** 本段朗读是否已发过 `tts_start`(一段只发一次)。 */
    private var started = false

    /** 是否处于「接受音频」窗口:`start`/`sentence_start` 打开,`stop`/新一轮关闭。 */
    private var accepting = false

    /** 下行 SEQ(1 字节回绕,与设备侧缺口统计同义;见 [VbTtsOpusPayload.nextSeq])。 */
    private var seq = 0

    /** 诊断计数:本段已转发/丢弃的帧数(只用于日志)。 */
    private var pushedFrames = 0
    private var droppedFrames = 0

    /**
     * 会话开始新一轮:作废上一段的记账。
     *
     * 幂等:重复调用只是把两个标志位再置 false —— 与 `tts_abort` 一样可以随便调。
     */
    override fun onTurnStart() {
        if (started || accepting) {
            Log.d(tag, "新一轮开始:丢弃上一段朗读状态(已转发 $pushedFrames 帧,丢弃 $droppedFrames 帧)")
        }
        started = false
        accepting = false
        pushedFrames = 0
        droppedFrames = 0
    }

    /**
     * 小智 `tts` 状态映射。
     *
     * - `start` / `sentence_start`:打开音频窗口;首个这类状态发 `tts_start`(句级流式,不整段等);
     * - `sentence_end`:段内分隔,不结束一段(小智按句推音频,`stop` 才是整段完结);
     * - `stop`:关闭窗口;若本段已开始则发 `tts_stop`(开关关掉也要收尾,否则设备停在播放态)。
     */
    override fun onTtsState(state: String, text: String) {
        when (state) {
            "start", "sentence_start" -> {
                if (!enabled()) return
                accepting = true
                ensureStarted()
            }

            "stop" -> endUtterance()

            // 段内分隔:不是一段的结束,不产生任何下发。
            else -> Unit
        }
    }

    /**
     * 一帧下行 opus 音频:按 `[SEQ][rate_khz][frame_ms] + opus` 组帧后原样转发。
     *
     * 只在本段窗口内、且设备朗读打开时转发;音频先到而状态没到(个别服务端实现)时兜底补发 `tts_start`。
     */
    override fun onTtsAudio(opus: ByteArray, rateKhz: Int, frameMs: Int) {
        if (!enabled()) return
        if (!accepting) return
        if (opus.isEmpty()) return
        if (rateKhz != RATE_16K && rateKhz != RATE_24K) {
            // M1 不重采样:设备帧头只认 16/24 kHz(见 docs/wire-protocol.md 的 TTS 下行)。
            drop("采样率 ${rateKhz}kHz 不是 16/24,无法组帧")
            return
        }
        if (frameMs <= 0) {
            drop("帧长 ${frameMs}ms 非法")
            return
        }
        val payload = try {
            encodeTtsOpusPayload(seq, rateKhz, frameMs, opus)
        } catch (e: IllegalArgumentException) {
            // 单个 opus 包 > 512B:协议上限,不发(发了会被设备当错位帧丢掉后面那一帧)。
            drop("opus 包 ${opus.size}B 超过 ${VbFrame.TTS_OPUS_PAYLOAD_MAX}B")
            return
        }
        ensureStarted()
        seq = VbTtsOpusPayload.nextSeq(seq)
        pushedFrames++
        downlink.pushFrame(rateKhz, frameMs, payload)
    }

    /** 首个状态/首帧到达时声明一段开始(幂等)。 */
    private fun ensureStarted() {
        if (started) return
        started = true
        Log.i(tag, "小智 TTS 直通开始(句级流式,采样率/帧长随帧头携带)")
        downlink.start()
    }

    /** 一段收尾:关窗口;已开始的发 `tts_stop`(与开关无关,否则设备停在播放态)。 */
    private fun endUtterance() {
        accepting = false
        if (!started) return
        started = false
        Log.i(tag, "小智 TTS 直通结束(已转发 $pushedFrames 帧,丢弃 $droppedFrames 帧)")
        downlink.stop()
    }

    private fun drop(reason: String) {
        droppedFrames++
        Log.w(tag, "丢弃一帧小智 TTS 音频:$reason")
    }

    companion object {
        /** 固件只接受 16/24 kHz 的下行帧(`oc_tts.c`)。 */
        const val RATE_16K = 16
        const val RATE_24K = 24
    }
}
