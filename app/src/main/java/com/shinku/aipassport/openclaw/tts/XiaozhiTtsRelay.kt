package com.shinku.aipassport.openclaw.tts

import android.util.Log
import com.shinku.aipassport.openclaw.protocol.VbFrame
import com.shinku.aipassport.openclaw.protocol.VbTtsOpusPayload
import com.shinku.aipassport.openclaw.protocol.encodeTtsOpusPayload
import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity
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
 * 直通链路上**逐帧诊断日志的唯一节流规则**(会话层收到 / relay 组帧 / BLE 实际写入三处共用)。
 *
 * 为什么需要:一轮回复有几十到几百个 60ms 帧,逐帧全打会把自己淹掉,而「帧到底走到哪一跳断了」
 * 又只能靠这些行判断。统一成「每段前 [HEAD] 帧 + 之后每 [EVERY] 帧一条」后,三跳的行号可逐条对照,
 * 且不会刷屏。
 */
object XiaozhiFrameLog {

    /** 每段打印的头部帧数(前几帧逐帧可见,便于看清 SEQ/长度/速率)。 */
    const val HEAD = 3

    /** 头部之后的打印间隔(帧):30 帧 ≈ 1.8s 音频一条。 */
    const val EVERY = 30

    /** 第 [index] 帧(从 1 开始)是否该打。 */
    fun shouldLog(index: Int): Boolean = index <= HEAD || index % EVERY == 0
}

/**
 * **直通门**的判定输入:三个条件放在同一个值对象里,判定与日志用**同一份数据** ——
 * 日志里看到的 `放行/拦截(原因)` 就是这一帧实际用的判定,不会出现「日志说放行、代码走了别的分支」。
 *
 * @param gatewayType 当前生效的网关类型(见 `GatewaySettings`);「是不是小智 AI」由**唯一**判定来源
 *   [XiaozhiIdentity.isXiaozhi] 给出,不在这里再写一份字符串比较。
 * @param ttsEnabled 设置项 `tts_enabled`(**读设置时用它自己的默认 true**;「没这个键」不等于关闭)。
 * @param deviceTtsCapable **当前这台设备**在 hello 里报了 `caps:["tts_opus"]`(换设备/重连要重新等它报)。
 */
data class XiaozhiTtsGate(
    val gatewayType: String,
    val ttsEnabled: Boolean,
    val deviceTtsCapable: Boolean,
) {

    /** 放行 = 三项全真(与改动前的 `enabled` 逐项同义)。 */
    val allowed: Boolean
        get() = XiaozhiIdentity.isXiaozhi(gatewayType) && ttsEnabled && deviceTtsCapable

    /** 拦截原因(放行时为 null):按判定顺序给**第一个**不满足的条件,一句话说清为什么没发。 */
    val blockedReason: String?
        get() = when {
            !XiaozhiIdentity.isXiaozhi(gatewayType) -> "当前网关不是小智 AI(type=$gatewayType)"
            !ttsEnabled -> "设备朗读开关(tts_enabled)关闭"
            !deviceTtsCapable -> "设备未在 hello 报 caps:[\"tts_opus\"]"
            else -> null
        }

    /** 日志形态:`type=… enabled=… caps=…`。 */
    fun describe(): String = "type=$gatewayType enabled=$ttsEnabled caps=$deviceTtsCapable"
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
 *  - **服务端只推音频、不发 `tts` 状态报文时兜底开段**(见 [stateReportsSeen]):这种实现下
 *    首帧即当作一段开始(`tts_start` 先于首帧下发),否则整段音频会被窗口挡在门外 —— 真机上表现为
 *    「App 日志收到几十帧、设备侧 TTS 计数为 0」;
 *  - **一轮开始必须重置**:打断时服务端不一定回 `stop`,若还认为「本段进行中」,下一轮音频就会缺
 *    `tts_start` 而直接甩给设备(见 [onTurnStart]);
 *  - **设备朗读关闭时一个音频帧都不下发**(直通门拦截;开关语义与本地合成那条路一致),
 *    但已经开始的段落仍照常收尾(发 `tts_stop`),避免设备停在播放态;
 *  - **不做重采样/重编码**:采样率不是 16/24 kHz、帧长非正、opus 包超 512B 的帧一律丢弃并记日志
 *    (丢一帧 ≒ 60ms,不值得为此改动协议或引入重采样)。
 *
 * @param gate **直通门**的实时判定(三项条件 + 逐项原因,见 [XiaozhiTtsGate])。必须是实时读取:
 *   设置页切换、设备重连后重新报能力都要立即生效,不需要重启服务。
 * @param downlink 真正的下行实现(BLE 下发 + 流控在服务侧)。
 */
class XiaozhiTtsRelay(
    private val gate: () -> XiaozhiTtsGate,
    private val downlink: XiaozhiTtsDownlink,
) : XiaozhiTtsObserver {

    private val tag = "XiaozhiTtsRelay"

    /** 本段朗读是否已发过 `tts_start`(一段只发一次)。 */
    private var started = false

    /** 是否处于「接受音频」窗口:`start`/`sentence_start`/`sentence_end` 打开,`stop` 关闭。 */
    private var accepting = false

    /**
     * **本会话是否见过任何 `tts` 状态报文**(start/sentence_start/sentence_end/stop 之一)。
     *
     * 为什么按「会话级」而不是「本轮」记账:这是**服务端行为**的特征 ——
     *  - 见过 → 以状态报文的窗口为准:窗口外的音频是上一段/上一轮的残留,丢弃(否则会给设备一段
     *    没有 `tts_start` 的错位音频);
     *  - 从没见过(实测存在这种服务端实现:只推二进制音频、一条 `tts` JSON 都没有)→ 状态窗口无从谈起,
     *    首帧即兜底开段,否则**整段音频都会被丢**,用户听到的就是一片安静(设备侧计数恒为 0)。
     */
    private var stateReportsSeen = false

    /** 下行 SEQ(1 字节回绕,与设备侧缺口统计同义;见 [VbTtsOpusPayload.nextSeq])。 */
    private var seq = 0

    /** 诊断计数:本段已转发/丢弃的帧数(只用于日志)。 */
    private var pushedFrames = 0
    private var droppedFrames = 0

    /** 最近一次直通门日志的形态(判定/输入没变就不重复打,避免逐帧刷屏)。 */
    @Volatile
    private var lastGateLog: String? = null

    /**
     * 会话开始新一轮:作废上一段的记账。
     *
     * 幂等:重复调用只是把标志位再置 false —— 与 `tts_abort` 一样可以随便调。
     * 注意**不**清 [stateReportsSeen]:那是服务端行为的特征,不随轮次变化。
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
     * - `sentence_end`:段内分隔,不结束一段(小智按句推音频,`stop` 才是整段完结),但同样说明
     *   「这里是句内音频」,因此也打开窗口;
     * - `stop`:关闭窗口;若本段已开始则发 `tts_stop`(开关关掉也要收尾,否则设备停在播放态)。
     */
    override fun onTtsState(state: String, text: String) {
        when (state) {
            "start", "sentence_start" -> {
                stateReportsSeen = true
                val g = gate()
                if (!g.allowed) {
                    logGate(g)
                    return
                }
                accepting = true
                ensureStarted()
            }

            "sentence_end" -> {
                stateReportsSeen = true
                accepting = true
            }

            "stop" -> {
                stateReportsSeen = true
                endUtterance()
            }

            // 未知状态:不产生任何下发(也**不**据此认定服务端会发状态报文)。
            else -> Unit
        }
    }

    /**
     * 一帧下行 opus 音频:按 `[SEQ][rate_khz][frame_ms] + opus` 组帧后原样转发。
     *
     * 顺序:先过**直通门**(不由这里放行的帧一个字节都不下发)→ 再校验帧头参数 →
     * 再确认本段窗口(服务端只推音频时先用首帧兜底开段)→ 组帧 → 下发。
     */
    override fun onTtsAudio(opus: ByteArray, rateKhz: Int, frameMs: Int) {
        val g = gate()
        if (!g.allowed) {
            // 拦截:只在判定/输入变化时打一行(逐帧打会刷屏),计数器另有 `drop` 的节流日志兜底。
            logGate(g)
            droppedFrames++
            return
        }
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
        if (!accepting) {
            if (stateReportsSeen) {
                // 服务端会发状态报文,而这一帧落在窗口之外:上一段/上一轮的残留,丢弃。
                drop("不在接收窗口内(本段还没收到 tts start/sentence_start)")
                return
            }
            // 兜底:服务端只推二进制音频(没有 tts 状态报文),首帧即开段 —— 否则整段都会被丢掉。
            accepting = true
            Log.i(tag, "未见过任何 tts 状态报文:按首帧兜底开段(${rateKhz}kHz/${frameMs}ms,${opus.size}B)")
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
        if (XiaozhiFrameLog.shouldLog(pushedFrames)) {
            Log.i(
                tag,
                "已组帧 seq=${payload[0].toInt() and 0xFF} ${payload.size}B → 下发" +
                    "(第 $pushedFrames 帧,opus ${opus.size}B,${rateKhz}kHz/${frameMs}ms)",
            )
        }
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

    /**
     * 直通门日志(状态变化时打一行):`type=… enabled=… caps=… → 放行/拦截(原因)`。
     *
     * 为什么要这一行:真机上「App 收了几十帧、设备 TTS 计数为 0」有两种完全不同的原因 ——
     * 直通门拦了(网关类型/开关/设备能力),或窗口没开(服务端没发状态报文)。这一行把前者钉死,
     * 后者由 [onTtsAudio] 的兜底日志区分。
     */
    private fun logGate(g: XiaozhiTtsGate) {
        val line = g.describe() + if (g.allowed) " → 放行" else " → 拦截(${g.blockedReason})"
        if (line == lastGateLog) return
        lastGateLog = line
        if (g.allowed) Log.i(tag, "直通门: $line") else Log.w(tag, "直通门: $line")
    }

    /** 丢弃一帧(计数 + 节流日志:前几帧逐条给原因,之后每 30 帧一条带累计数)。 */
    private fun drop(reason: String) {
        droppedFrames++
        if (XiaozhiFrameLog.shouldLog(droppedFrames)) {
            Log.w(tag, "丢弃小智 TTS 音频帧:$reason(累计丢弃 $droppedFrames 帧)")
        }
    }

    companion object {
        /** 固件只接受 16/24 kHz 的下行帧(`oc_tts.c`)。 */
        const val RATE_16K = 16
        const val RATE_24K = 24
    }
}
