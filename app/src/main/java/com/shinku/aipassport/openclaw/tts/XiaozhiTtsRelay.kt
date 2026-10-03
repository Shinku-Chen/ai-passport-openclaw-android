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
 *
 * [stop] 的语义是「本段不会再有音频了」:它不是由小智的 `tts.state=stop` 触发的,而由播放窗口
 * 收尾触发(新一轮 / barge / 设备 `turn_cancel` / 设备回报本段播放结束),见 [XiaozhiTtsRelay]。
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
 * **触发点 = `stop`(整段正文齐)**:正文先上屏,**紧接着**开始播 TTS —— 已缓冲的帧按到达顺序
 * 下发,`tts_start` 必在首帧前。**播放窗口在 `stop` 之后保持打开**:`stop` 之后继续到达的帧照常
 * 下发(一边播放一边缓冲),直到本轮被收尾才 `tts_stop`(见下)。时序:
 * ```
 * 设备 turn_start ─(流水线)─> {"ev":"tts_abort"} + relay.onTurnStart()   // 上一轮作废 + 关窗口 + 清空缓冲
 * 小智 tts.state=start / sentence_start / sentence_end ─> relay           // 只记账,一个字节都不下发
 * 小智 二进制 opus 帧 ─> relay 组 [SEQ][rate_khz][frame_ms]+opus ─> **先缓冲**
 * 小智 tts.state=stop(整段正文齐了)─> relay                              // 只「武装」:仍不下发
 * 流水线把整段正文写成 TEXT('A')(上屏后) ─> relay.onReplyTextDisplayed()
 *      ─> {"ev":"tts_start"} → 按既有流控把已缓冲的帧**连续**推给设备
 *      → 之后到达的帧**即时下发**(窗口保持打开)
 * 窗口关闭:新一轮 turn_start / barge / 设备 turn_cancel / 设备回报本段播放结束
 *      ─> 丢弃剩余缓冲 + {"ev":"tts_stop"}(已开段时)
 * ```
 *
 * 为什么不再「首句即开段、边收边播」(真机听感**一句一顿**):小智按句推音频,句与句之间的生成
 * 间隔不定,而设备按 60ms/帧实时播 —— 句间一旦断供只能补静音。正文整段齐(`stop`)之前一个
 * 音频字节都不下发,所以 `stop` 一开播就有成段的音频垫底,句间空档被缓冲吸收。
 * 但**不等整段音频齐**:服务端在 `stop` 之后仍会继续推本段的音频帧(实测存在),这些**迟到帧照常
 * 下发**,不是丢弃 —— 反过来「等整段音频齐」或「`stop` 后丢迟到帧」都是错的(前者把音频永远
 * 挡在门外,后者切掉尾音)。
 *
 * **顺序保证(正文必须先于首帧到设备)**:本类不自己在 `stop` 时开播,而是等一个**明确的
 * 「正文已上屏」信号**([onReplyTextDisplayed],由服务侧在 `TEXT('A')` 已写进 BLE 串行写队列
 * 之后调用)。于是「正文帧已入 BLE 写队列」在「首帧入队」之前**由构造保证** —— 而不是靠时序碰运气。
 * 代价:`stop` 之后、正文上屏之前到达的帧继续留在缓冲里(此时按作者要求**不再受**
 * [maxBufferFrames] 约束),但这段窗口只有一次 `TEXT('A')` 写入的时间(毫秒级)。
 *
 * 几条不变量:
 *  - **正文上屏之前一个音频字节都不下发**:`tts_start` 也**不提前发** —— 设备把 `tts_start` 当作
 *    「进入播放态、暂停上行采集」,提前发就是提前打断它自己的采集;
 *  - **`stop` 之后窗口不关**:迟到帧照常按到达顺序下发(SEQ 连续),这就是「一边播一边缓冲」;
 *  - **缓冲有上限**([maxBufferFrames],默认 [MAX_BUFFER_FRAMES],只作用于正文上屏之前):超限时
 *    **不丢整段**,而是「先把已收的推出去(此时才开段)+ 之后改为即时下发」,退化成旧的流式形态 ——
 *    宁可损失一点连续性,也不丢用户会听到的音频(这条退化路径下「正文先于首帧」不成立,见下);
 *  - **一轮开始必须重置**:打断时服务端不一定回 `stop`,所以 [onTurnStart] 除复位标志位外还要
 *    **清空缓冲**、并关窗口发 `tts_stop` —— 上一轮的残帧绝不能排进下一轮(见 [onTurnStart]);
 *  - **窗口外的帧丢弃**:服务端会发 `tts` 状态报文时,`start`/`sentence_start`/`sentence_end`/`stop`
 *    打开窗口(注意 `stop` **不关**窗口),收尾才关;窗口外的音频是上一段/上一轮的残留,丢弃
 *    (否则会给设备一段没有 `tts_start` 的错位音频);
 *  - **只推二进制音频、一条 `tts` 状态报文都没有的服务端**([stateReportsSeen] 恒 false):
 *    既没有窗口、也没有「整段正文齐」的信号 → 保持既有兜底(首帧即开段、即时下发),
 *    否则整段音频会被永远挡在「等 stop」后面(真机表现就是设备侧 `TTS=0`);
 *  - **已知取舍(发了状态报文但漏发 `stop` 的服务端)**:本类**不**用「空闲」当整段正文齐的凭证
 *    (句子之间本来就可能隔几秒,提前 flush 会把一整段拆成几段并多发一组 `tts_start/tts_stop`);
 *    这种服务端下音频要等缓冲超限的退化路径才会下发(正文仍由
 *    [com.shinku.aipassport.openclaw.stt.XiaozhiReplyText] 的空闲兜底窗口上屏) —— 实测云端在
 *    `sentence_end` 后毫秒级就会发 `stop`,属于退化路径。
 *  - **正文已上屏、`stop` 才到**(空闲兜底先结算了正文):`stop` 到达即开播,不再等一个不会来的信号。
 *  - **设备朗读关闭时一个音频帧都不下发**(直通门拦截;开关语义与本地合成那条路一致);
 *    若一段**已经**开了段(超限降级后才关的开关),收尾的 `tts_stop` 照发,避免设备停在播放态;
 *  - **不做重采样/重编码**:采样率不是 16/24 kHz、帧长非正、opus 包超 512B 的帧一律丢弃并记日志
 *    (丢一帧 ≒ 60ms,不值得为此改动协议或引入重采样)。
 *
 * @param gate **直通门**的实时判定(三项条件 + 逐项原因,见 [XiaozhiTtsGate])。必须是实时读取:
 *   设置页切换、设备重连后重新报能力都要立即生效,不需要重启服务。
 * @param downlink 真正的下行实现(BLE 下发 + 流控在服务侧)。
 * @param maxBufferFrames 正文上屏之前的缓冲上限(帧);单测调小它即可覆盖超限降级路径。
 */
class XiaozhiTtsRelay(
    private val gate: () -> XiaozhiTtsGate,
    private val downlink: XiaozhiTtsDownlink,
    private val maxBufferFrames: Int = MAX_BUFFER_FRAMES,
) : XiaozhiTtsObserver {

    private val tag = "XiaozhiTtsRelay"

    /**
     * 已通过校验、等着「正文上屏后开播」时统一下发的一帧。
     *
     * 为什么存**原始 opus**(而不是组好的 payload):SEQ 只在实际下发的帧上递增(与改动前一致),
     * 否则「因门关闭被丢掉的那些帧」也会吃掉 SEQ,设备侧的缺口统计就变成了假丢帧。
     */
    private data class BufferedFrame(val rateKhz: Int, val frameMs: Int, val opus: ByteArray)

    /** 正文上屏之前的音频缓冲(开播时按到达顺序连续下发)。上限 [maxBufferFrames]。 */
    private val buffered = ArrayList<BufferedFrame>()

    /** 本段是否已下发过 `tts_start`(一段只发一次;开了段才允许推帧)。 */
    private var started = false

    /**
     * 是否处于「本段音频窗口」内:`start`/`sentence_start`/`sentence_end`/`stop` 打开,收尾时关闭。
     *
     * `stop` **不**关窗口:它只表示「整段正文齐了、可以开播」,服务端之后仍会继续推本段音频
     * (迟到帧照常下发,见类注释)。
     */
    private var segmentOpen = false

    /**
     * 本段是否已**开始下发**(缓冲超限降级,或收到「正文已上屏」信号后开播)。
     *
     * 为真后不再缓冲:每帧直接组帧下发(旧的流式行为)。
     */
    private var streaming = false

    /**
     * 已收到 `stop`(整段正文齐)但**还没等到「正文已上屏」信号**:帧继续缓冲、不下发。
     *
     * 顺序保证就落在这里:开播(因而首帧)只可能发生在 [onReplyTextDisplayed] 之后,而那个信号是
     * 服务侧在正文 `TEXT('A')` 已写进 BLE 串行写队列之后才发的。
     */
    private var awaitingTextScreen = false

    /**
     * 本轮正文是否已经上屏(收到了 [onReplyTextDisplayed] 信号)。
     *
     * 为什么单独记:正文可能比 `stop` 早结算(纯 `llm` 兜底 / 空闲兜底窗口先上屏),那时
     * [onReplyTextDisplayed] 先到、`stop` 后到 —— 这种情况下 `stop` 到达即开播,不再等一个
     * 不会再来的信号(否则本段音频会被永远留在缓冲里)。
     */
    private var textScreenPassed = false

    /**
     * **本会话是否见过任何 `tts` 状态报文**(start/sentence_start/sentence_end/stop 之一)。
     *
     * 为什么按「会话级」而不是「本轮」记账:这是**服务端行为**的特征 ——
     *  - 见过 → 以状态报文的窗口为准:窗口外的音频是上一段/上一轮的残留,丢弃;
     *  - 从没见过(实测存在这种服务端实现:只推二进制音频、一条 `tts` JSON 都没有)→ 既没有窗口,
     *    也没有 `stop` 这个「整段齐了」的信号,绝不能缓冲等待,首帧即兜底开段、即时下发。
     * 因此 [onTurnStart] **不**清它。
     */
    private var stateReportsSeen = false

    /** 下行 SEQ(1 字节回绕,与设备侧缺口统计同义;见 [VbTtsOpusPayload.nextSeq])。 */
    private var seq = 0

    /** 诊断计数:本段已下发/丢弃的帧数(只用于日志)。 */
    private var pushedFrames = 0
    private var droppedFrames = 0

    /** 最近一次直通门日志的形态(判定/输入没变就不重复打,避免逐帧刷屏)。 */
    @Volatile
    private var lastGateLog: String? = null

    /**
     * 会话开始新一轮(设备 `turn_start`)或本轮被打断(`barge` / 设备 `turn_cancel`):
     * **关窗口 + 丢弃未播缓冲 + 作废记账**。
     *
     * 为什么要把「关窗口」也放在这里:窗口在 `stop` 之后是开着的(`stop` 不是结束信号),所以必须有
     * 一个明确的收尾点,否则设备会一直停在播放态、本轮的迟到帧也会被推到下一轮。已开段时收尾要走
     * `tts_stop`(见 [endUtterance]);`turn_start` 路径上流水线**已经**先发过 `tts_abort`
     * (`DeviceTtsSession.onTurnStart`),下游的 `stop()` 因此是空操作 —— 不会多出一条多余的 `tts_stop`。
     *
     * 幂等:重复调用只是把标志位/缓冲再清一次 —— 与 `tts_abort` 一样可以随便调。
     * 注意**不**清 [stateReportsSeen]:那是服务端行为的特征,不随轮次变化。
     */
    override fun onTurnStart() {
        if (started || buffered.isNotEmpty()) {
            Log.d(
                tag,
                "新一轮/打断:关闭本段播放窗口,丢弃未播缓冲" +
                    "(已下发 $pushedFrames 帧,丢弃 $droppedFrames 帧,缓冲 ${buffered.size} 帧)",
            )
        }
        endUtterance()
        segmentOpen = false
        streaming = false
        awaitingTextScreen = false
        textScreenPassed = false
        buffered.clear()
        pushedFrames = 0
        droppedFrames = 0
    }

    /**
     * **「正文已上屏」信号**(服务侧在整段正文 `TEXT('A')` 已写进 BLE 串行写队列之后调用)。
     *
     * 为什么需要它:`stop`(整段正文齐)只说明「可以开播了」,而设备屏上的那条气泡与音频帧走的是
     * 同一个 BLE 串行写队列 —— 只要开播发生在正文写入之前,用户就会先听到声音、后看到字。这里把
     * 开播**挂在正文已经入队之后**,于是「正文先于首帧到设备」由构造保证(见类注释)。
     *
     * 幂等:无论有没有在等信号,都先记下「本轮正文已上屏」;真正开播只在「已收到 `stop` 还在等
     * 信号」时发生。
     */
    fun onReplyTextDisplayed() {
        // 先记「本轮正文已上屏」:正文可能比 `stop` 早结算(空闲兜底 / 纯 llm 兜底),那时这个信号
        // 先到、`stop` 后到 —— `stop` 一到就该开播,不必再等一个不会来的信号。
        textScreenPassed = true
        if (!awaitingTextScreen) return
        Log.i(tag, "正文已上屏:开始下发已缓冲的 ${buffered.size} 帧(tts_start 在首帧前)")
        beginPlaying()
    }

    /**
     * 设备回报本段播放结束(`tts_playback_done` / `tts_playback_aborted`):**关闭播放窗口**。
     *
     * 为什么由设备回报来收尾:窗口在 `stop` 之后是开着的,而服务端不会再给「音频到底发完没有」的
     * 信号 —— 只有设备知道自己把队列播完了(或已丢弃余下音频),因此以它的回报作为本轮的正常收尾点:
     * 丢弃剩余缓冲并 `tts_stop`,之后到达的迟到帧一律按「窗口外」丢弃。
     *
     * 为什么只在**已开段**时收尾:回报不带轮号,而新一轮的 `tts_abort` 也会让设备回一条
     * `tts_playback_aborted` —— 那条属于**上一轮**,此时新一轮可能正在缓冲(还没开段),不能因为一条
     * 迟到的旧回报把新一轮的音频清掉。已开段 = 回报对应的是当前这段,才收尾。
     *
     * 同一轮里若服务端还有下一段(再次 `start`…`stop`),窗口会被那个 `stop` 重新打开
     * (不清 [textScreenPassed]:正文已经上屏了)—— 不把分段服务端的第二段整段丢掉。
     */
    fun onDevicePlaybackFinished() {
        if (!started) {
            Log.d(tag, "忽略设备播放回报(本段没有开过段,属上一轮的迟到回报)")
            return
        }
        Log.i(tag, "设备回报本段播放结束:关闭播放窗口(已下发 $pushedFrames 帧)")
        endUtterance()
        segmentOpen = false
        streaming = false
        awaitingTextScreen = false
        buffered.clear()
    }

    /**
     * 小智 `tts` 状态映射。
     *
     * - `start` / `sentence_start` / `sentence_end`:打开音频窗口(这三者都说明「后面的帧属于本段」),
     *   **不下发任何东西** —— 正文上屏前只缓冲;
     * - `stop`:**整段正文齐了** —— 只「武装」等待「正文已上屏」信号(`tts_start` 与首帧都在这之后);
     *   若正文此前已经上屏(空闲兜底先结算)则立即开播;窗口**保持打开**(迟到帧照常下发);
     * - 未知状态不产生任何下发(也**不**据此认定服务端会发状态报文)。
     */
    override fun onTtsState(state: String, text: String) {
        when (state) {
            "start", "sentence_start", "sentence_end" -> {
                stateReportsSeen = true
                segmentOpen = true
            }

            "stop" -> {
                stateReportsSeen = true
                // 关键:stop **不关**窗口 —— 之后到达的帧仍是本段的音频,要照常下发(一边播一边缓冲)。
                segmentOpen = true
                when {
                    streaming -> Log.d(tag, "收到 stop:本段已在即时下发(缓冲超限/无状态兜底),继续按到达顺序下发")
                    textScreenPassed -> {
                        Log.i(tag, "收到 stop:正文此前已上屏,立即开始下发已缓冲的 ${buffered.size} 帧")
                        beginPlaying()
                    }
                    else -> {
                        awaitingTextScreen = true
                        Log.i(tag, "整段正文已齐(tts stop):等「正文已上屏」后开播(当前缓冲 ${buffered.size} 帧)")
                    }
                }
            }

            else -> Unit
        }
    }

    /**
     * 一帧下行 opus 音频:校验后先按相位处理 —— **正文上屏之前只缓冲、不下发**;正文上屏之后
     * (含 `stop` 之后到达的迟到帧)即时下发。缓冲超限或「无状态服务端」同样转入即时下发。
     *
     * 顺序:先过**直通门**(不由这里放行的帧一个字节都不下发)→ 再校验帧头参数 →
     * 再确认本段窗口(窗口外丢弃;无状态服务端用首帧兜底开段)→ 缓冲或即时下发。
     */
    override fun onTtsAudio(opus: ByteArray, rateKhz: Int, frameMs: Int) {
        val g = gate()
        if (!g.allowed) {
            // 拦截:只在判定/输入变化时打一行(逐帧打会刷屏),计数器另有 `drop` 的节流日志兜底。
            // 门关闭期间收到的帧**不进缓冲**:用户关了开关,这段就不该有声音。
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
        // 单个 opus 包 > 512B:协议上限,不发(发了会被设备当错位帧丢掉后面那一帧)。
        // 在**入缓冲之前**判掉:超限包不进内存。
        if (opus.size > VbFrame.TTS_OPUS_PAYLOAD_MAX) {
            drop("opus 包 ${opus.size}B 超过 ${VbFrame.TTS_OPUS_PAYLOAD_MAX}B")
            return
        }
        if (!segmentOpen) {
            if (stateReportsSeen) {
                // 服务端会发状态报文,而这一帧落在窗口之外:上一段/上一轮的残留,丢弃。
                drop("不在接收窗口内(本段还没收到 tts start/sentence_start)")
                return
            }
            // 兜底:服务端只推二进制音频(没有 tts 状态报文)—— 没有 stop 这个「整段齐了」信号,
            // 绝不能缓冲等待,否则整段音频永远发不出去;保持「首帧即开段、即时下发」的既有行为。
            segmentOpen = true
            streaming = true
            Log.i(tag, "未见过任何 tts 状态报文:无法判定整段结束,按首帧兜底开段并即时下发(${rateKhz}kHz/${frameMs}ms,${opus.size}B)")
        }
        val frame = BufferedFrame(rateKhz, frameMs, opus)
        if (streaming) {
            // 已开始下发(开播 / 缓冲超限降级 / 无状态服务端):组帧后立刻推。
            ensureStarted()
            push(frame)
            return
        }
        buffered.add(frame)
        // `stop` 之后(等「正文已上屏」的窗口内)不再受缓冲上限约束:作者明确「stop 之后交给既有
        // TtsFlowControl 在途上限」,而且这个窗口只有一次 TEXT('A') 写入的时间(毫秒级)。
        // 只在这个窗口里越过上限时提醒一次(不丢帧):真要一直不越,说明上屏信号没了,日志能定位
        // 「帧都在 relay 里没出去」。
        if (awaitingTextScreen && buffered.size == maxBufferFrames) {
            Log.w(
                tag,
                "正文已上屏信号还没到:本段缓冲已越过 $maxBufferFrames 帧仍继续收(这段不下发,等信号/收尾)",
            )
        }
        if (!awaitingTextScreen && buffered.size >= maxBufferFrames) {
            // 超限策略:不丢整段 —— 先把已收的帧推出去(此时才开段),之后一直即时下发到本轮收尾。
            // 取「边推已收 + 继续收集」而不是「丢整段」:被丢的帧是用户本该听到的语音,而连续性只是观感。
            // 已知代价:这条退化路径下「正文先于首帧」不再成立(开播不再等 [onReplyTextDisplayed])。
            streaming = true
            Log.w(
                tag,
                "本段缓冲已达上限(${maxBufferFrames} 帧 ≈ ${maxBufferFrames * FRAME_MS_DEFAULT / 1000}s):" +
                    "先下发已收帧,后续帧改为即时下发(不丢整段)",
            )
            flushBuffered()
        }
    }

    /**
     * 把已收的缓冲交给下行(先 [ensureStarted] 声明一段,再按到达顺序连续推)。
     *
     * 两个调用点:正文已上屏 → 开播([beginPlaying]);缓冲超限 → 降级即时下发([onTtsAudio])。
     *
     * 没有缓冲、门被关掉的地方三种收尾各按不变量处理:
     *  - 缓冲为空 → 什么都不做(本段可能已开始即时下发,或服务端只有状态没有音频);
     *  - 门关闭 → 这段一个字节都不下发(既不开段也不推帧),把缓冲丢掉并计数;
     *    若这段**已经**开过段(超限降级后才被关的开关),由 [endUtterance] 负责 `tts_stop` 收尾。
     */
    private fun flushBuffered() {
        if (buffered.isEmpty()) return
        val g = gate()
        if (!g.allowed) {
            logGate(g)
            droppedFrames += buffered.size
            Log.w(tag, "整段缓冲 ${buffered.size} 帧因直通门拦截全部不下发(${g.blockedReason})")
            buffered.clear()
            return
        }
        ensureStarted()
        val frames = buffered.toList()
        buffered.clear()
        frames.forEach { push(it) }
        Log.i(tag, "整段缓冲已交给下行:本次 ${frames.size} 帧(累计 $pushedFrames 帧)")
    }

    /** 开播:标记「正文已上屏」这一关已过,并把已缓冲的帧连续交给下行(幂等)。 */
    private fun beginPlaying() {
        awaitingTextScreen = false
        textScreenPassed = true
        streaming = true
        flushBuffered()
    }

    /** 首个「真的一帧要发」的时刻声明一段开始(幂等):设备的 `tts_start` 在这里、且只在这里下发。 */
    private fun ensureStarted() {
        if (started) return
        started = true
        Log.i(tag, "小智 TTS 直通开始(先下发已缓冲的帧,迟到帧即时下发;采样率/帧长随帧头携带)")
        downlink.start()
    }

    /**
     * 一段收尾:已开始的一段发 `tts_stop`(与开关无关,否则设备停在播放态)。
     *
     * 幂等:没有开过段时什么都不做(收尾可能重复到达,或本段压根没有音频)。
     * 调用方负责把窗口(见 [segmentOpen])与缓冲一起收干净([onTurnStart] / [onDevicePlaybackFinished])。
     */
    private fun endUtterance() {
        if (!started) {
            streaming = false
            return
        }
        started = false
        streaming = false
        Log.i(tag, "小智 TTS 直通结束(已转发 $pushedFrames 帧,丢弃 $droppedFrames 帧)")
        downlink.stop()
    }

    /** 组帧并下发一帧(SEQ 只在这里递增:只有真的交给下行的帧才占序号)。 */
    private fun push(frame: BufferedFrame) {
        val payload = try {
            encodeTtsOpusPayload(seq, frame.rateKhz, frame.frameMs, frame.opus)
        } catch (e: IllegalArgumentException) {
            drop("opus 包 ${frame.opus.size}B 超过 ${VbFrame.TTS_OPUS_PAYLOAD_MAX}B")
            return
        }
        seq = VbTtsOpusPayload.nextSeq(seq)
        pushedFrames++
        downlink.pushFrame(frame.rateKhz, frame.frameMs, payload)
        if (XiaozhiFrameLog.shouldLog(pushedFrames)) {
            Log.i(
                tag,
                "已组帧 seq=${payload[0].toInt() and 0xFF} ${payload.size}B → 下发" +
                    "(第 $pushedFrames 帧,opus ${frame.opus.size}B,${frame.rateKhz}kHz/${frame.frameMs}ms)",
            )
        }
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

        /**
         * 本段音频缓冲上限:**300 帧**(小智 60ms/帧 → **18 秒**音频)—— 只约束**正文上屏之前**
         * 的缓冲(开播后帧即时下发,不再受它约束,见 [onTtsAudio])。
         *
         * 取值理由:
         *  - **够覆盖常见回复**:300 帧 ≈ 18s,约合 80–110 个汉字的正常语速朗读(两三句的答案),
         *    只有超长回复才会走超限降级(见 [onTtsAudio]:先推已收 + 继续收集,**不丢整段**);
         *  - **内存有界**:每帧 ≤ \[512B opus + 3B 帧头\],缓冲峰值 ≤ 约 155KB —— 对 App 无压力,
         *    但绝非「无上限」(链路异常时不能让它一直涨);
         *  - **必须小于服务侧待发队列上限**(`VoiceBridgeService.MAX_XIAOZHI_TTS_QUEUE_FRAMES` = 400):
         *    开播时是一口气把已缓冲的几百帧交给服务侧队列的,队列必须留得下它们 + `tts_stop` 标记,
         *    否则会走「队列满 → 清队列直接 stop」的降级路径而丢掉尾部音频。
         */
        const val MAX_BUFFER_FRAMES = 300

        /** 单帧时长兜底值(ms):只用于日志里把帧数换算成秒(小智恒为 60)。 */
        private const val FRAME_MS_DEFAULT = 60
    }
}
