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
 * 小智直通**尾部收尾**的判定(纯逻辑,可 JVM 单测):「本段已推空 + 设备侧静默达上限」就主动
 * `tts_stop`,把设备从播放态(真机表现:「接收中」)放回空闲 —— **不**依赖设备回报。
 *
 * 为什么需要一条不依赖回报的收尾:窗口在 `tts.state=stop` 之后是开着的(迟到帧照常下发),正常
 * 关闭点原本只有设备回报 `tts_playback_done`/`tts_playback_aborted` —— 设备不报(旧固件、解码失败、
 * 回报丢失)时音频早就推完了、设备却一直停在「接收中」。
 *
 * 为什么**不**会切掉还在排队的音频(两条保证):
 *  1. 判定要求「帧已全部写进 BLE 写队列且静默达 [TAIL_IDLE_MS]」—— 队列里没有待写帧;
 *  2. `tts_stop` 的语义是「后面没有音频了,把**已入队的**播完再退出播放态」(
 *     `docs/wire-protocol.md`),它不会丢掉设备已收的音频;而真机正常形态下整段音频早在开播时就
 *     一次性交给队列了(见 [XiaozhiTtsRelay]),静默往往就是“发完了”。
 */
object XiaozhiTailStop {

    /**
     * 尾部静默门槛(ms):最后一帧写进写队列之后多久没新帧就认为本段发完了。
     *
     * 取值理由:开播后整段音频是**一次性**交给队列的,剩下的迟到帧在毫秒级内到齐;而设备的领先量
     * (流控目标 1200ms / 硬上限 2000ms)决定了最后一帧写出后最多约 2s 才播完。取 3s = 上限 + 1s 余量:
     * 既能及时把设备放回空闲(不再停在「接收中」),又不会在真的还有后续分段的可能时抢跑
     * (真的又来帧时计数器会归零重算)。
     */
    const val TAIL_IDLE_MS = 3_000L

    /**
     * 现在该不该主动收尾。
     *
     * @param framesWritten 本段已真正写入 BLE 写队列的帧数(0 = 本段从未开播,无需 `tts_stop`)
     * @param idleMs 队列空的持续时长(每写出一帧就归零)
     * @param alreadyStopped 本段是否已经收过尾(幂等:不重复发 `tts_stop`)
     */
    fun shouldStop(framesWritten: Int, idleMs: Long, alreadyStopped: Boolean): Boolean =
        framesWritten > 0 && !alreadyStopped && idleMs >= TAIL_IDLE_MS
}

/**
 * 「**本轮正文**已上屏」的判定(纯函数,JVM 单测钉住):服务侧每写一条 `TEXT` 就调一次,
 * 只有同时满足下面两条才算信号 —— 否则一个字节的音频都不许下发(声音绝不能跑到文字前面):
 *
 *  1. **角色是网关回复**([REPLY_ROLE] = `'A'`):`'U'`(设备识别原文)、`'R'`(系统提示)以及
 *     其它角色与音频无关;
 *  2. **文本与本轮正文逐字一致**(两侧 trim 后比较):同为 `'A'` 的版本提示、「无语音」、
 *     网关超时/失败原因、空回复兜底都**不是**本轮正文。
 *     本轮正文为空(只有表情/模板)→ 永不算信号。
 *
 * 为什么用「逐字比较」而不是另加一个「这是正文」的布尔参数:会话层装配出的正文就是流水线转手
 * 交给 `sendText('A', …)` 的那个字符串(`XiaozhiReplyText` 清洗后 → 网关 trim → 流水线原样下发),
 * 所以比较是**可构造保证**的;而它真的不一致时(将来有人给正文加包装),日志会把上屏文本与本轮正文
 * 一起打出来,而不会默默地开播。
 */
object XiaozhiScreenSignal {

    /** 网关回复的角色字节(固件 `TEXT` 帧 payload 首字节)。 */
    const val REPLY_ROLE = 'A'

    /**
     * 这条已入 BLE 写队列的 `TEXT` 帧是否算「本轮正文已上屏」。
     *
     * @param role 上屏角色(`'A'` = 网关回复 / `'U'` = 识别原文 / `'R'` = 系统提示)
     * @param text 已写进队列的整段文本(分片前的完整字符串)
     * @param expectedReplyBody 会话层装配好的本轮正文;null = 本轮还没装配好(什么都没结算)
     */
    fun accepts(role: Char, text: String, expectedReplyBody: String?): Boolean {
        if (role != REPLY_ROLE) return false
        val expected = expectedReplyBody?.trim() ?: return false
        if (expected.isEmpty()) return false
        return text.trim() == expected
    }

    /** 拒绝原因(只用于日志;null = 这不是一次信号尝试中的失败,即已接受)。 */
    fun refuseReason(role: Char, text: String, expectedReplyBody: String?): String = when {
        role != REPLY_ROLE -> "角色不是网关回复(role=$role)"
        expectedReplyBody == null -> "本轮正文还没装配好(信号到得太早)"
        expectedReplyBody.trim().isEmpty() -> "本轮没有可上屏正文(只有表情/模板)"
        else -> "上屏文本与本轮正文不一致(可能是不属于本轮正文的其它 'A' 文本)"
    }
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
 * **触发点 = 「本轮正文已上屏」信号**(渐进交付下 = **首句**上屏那一刻;见
 * `com.shinku.aipassport.openclaw.stt.XiaozhiReplyText` 与 `docs/design/xiaozhi-ai-gateway.md` §6.0):
 * 正文先上屏,**紧接着**开始播 TTS —— 已缓冲的帧按到达顺序下发,`tts_start` 必在首帧前。
 * **播放窗口在 `stop` 之后保持打开**:`stop` 之后继续到达的帧照常下发(一边播放一边缓冲),
 * 直到本轮被收尾才 `tts_stop`(见下)。时序:
 * ```
 * 设备 turn_start ─(流水线)─> {"ev":"tts_abort"} + relay.onTurnStart()   // 上一轮作废 + 关窗口 + 清空缓冲
 * 小智 tts.state=start / sentence_start / sentence_end ─> relay           // 只记账,一个字节都不下发
 * 小智 二进制 opus 帧 ─> relay 组 [SEQ][rate_khz][frame_ms]+opus ─> **先缓冲**
 * 会话层首句正文交付 ─> relay.onReplyBody(首句正文)
 * 流水线把首句正文写成 TEXT('A', 首句正文)(上屏后) ─> relay.onReplyTextDisplayed
 *      ─> {"ev":"tts_start"} → 按既有流控把已缓冲的帧**连续**推给设备
 *      → 之后到达的帧**即时下发**(窗口保持打开)
 * 小智 tts.state=stop(整段正文齐) ─> 只「确认窗口仍开」;文本侧做最后一次补正(与首句不同则再上屏一次)
 * 窗口关闭:新一轮 turn_start / barge / 设备 turn_cancel / 设备回报本段播放结束
 *      ─> 丢弃剩余缓冲 + {"ev":"tts_stop"}(已开段时)
 * ```
 *
 * **已知取舍(作者 2026-10 决定:「第一句就好」)**:提前到**首句**开播把「听到声音」的延迟从整段
 * 生成时间(~16s,长回答更久)压到首句到达(~3s);代价是早期「首句即开段」那个已知听感风险
 * (句与句之间的 TTS 生成间隔可能造成设备侧短暂欠载)会再出现一截 —— 本次按作者要求以**延迟优先**,
 * 句间供不上时仍是早期那套办法(缓冲垫底 + 既有流控),后续如需再调另开一轮。
 * 但**不等整段音频齐**:服务端在 `stop` 之后仍会继续推本段的音频帧(实测存在),这些**迟到帧照常
 * 下发**,不是丢弃 —— 反过来「等整段音频齐」或「`stop` 后丢迟到帧」都是错的(前者把音频永远
 * 挡在门外,后者切掉尾音)。
 *
 * **顺序保证(正文必须先于首帧到设备)**:本类不自己开播,而是等一个**明确的**
 * 「正文已上屏」信号([onReplyTextDisplayed],由服务侧在 `TEXT('A')` 已写进 BLE 串行写队列
 * 之后调用)。于是「正文帧已入 BLE 写队列」在「首帧入队」之前**由构造保证** —— 而不是靠时序碰运气。
 * 这是**唯一**的下发闸门:正常路径、`stop` 路径、缓冲超限、无状态报文兜底四条路径都不许越过它
 * (作者要求:文字先于声音,**降级时宁可稍晚也别抢跑**)。代价:信号之前到达的帧继续留在缓冲里。
 *
 * **信号必须与「本轮正文」对得上号**(2026-10-05 真机修正):`TEXT('A')` 不只用于网关回复 ——
 * 版本提示、「无语音」、网关超时/失败原因、空回复兜底都是 `'A'`,识别原文是 `'U'`。任何一个
 * 上屏就开播的话,本轮音频会在真正文到达之前先出声(或跟一条与音频无关的提示一起播)。因此:
 *  1. 会话层在本轮正文装配好时先调 [onReplyBody];
 *  2. [onReplyTextDisplayed] 只在 `role == 'A'` **且** 上屏文本与本轮正文一致时才开播;
 *  3. 本轮正文为空(只有表情/模板)→ 任何 `'A'` 都不开播(没文字就没有「文字先于声音」可言);
 *  4. [onTurnStart] 清掉本轮正文记录 —— 上一轮的迟到信号也对不上号,开不了新一轮的闸。
 * 判定见 [XiaozhiScreenSignal](纯函数,单测钉住"识别原文/提示/兜底/旧轮信号"四类都不抢跑)。
 *
 * 几条不变量:
 *  - **正文上屏之前一个音频字节都不下发**(**任何**降级/兜底路径都成立):`tts_start` 也**不提前发**
 *    —— 设备把 `tts_start` 当作「进入播放态、暂停上行采集」,提前发就是提前打断它自己的采集;
 *  - **`stop` 之后窗口不关**:迟到帧照常按到达顺序下发(SEQ 连续),这就是「一边播一边缓冲」;
 *  - **缓冲有上限**([maxBufferFrames],默认 [MAX_BUFFER_FRAMES],只作用于正文上屏之前):超限时
 *    丢**最旧**的一帧(内存有界)并记日志,**绝不**为了不丢音频而提前开播 —— 顺序优先;
 *  - **一轮开始必须重置**:打断时服务端不一定回 `stop`,所以 [onTurnStart] 除复位标志位外还要
 *    **清空缓冲**、并关窗口发 `tts_stop` —— 上一轮的残帧绝不能排进下一轮(见 [onTurnStart]);
 *  - **窗口外的帧丢弃**:服务端会发 `tts` 状态报文时,`start`/`sentence_start`/`sentence_end`/`stop`
 *    打开窗口(注意 `stop` **不关**窗口),收尾才关;窗口外的音频是上一段/上一轮的残留,丢弃
 *    (否则会给设备一段没有 `tts_start` 的错位音频);
 *  - **只推二进制音频、一条 `tts` 状态报文都没有的服务端**([stateReportsSeen] 恒 false):
 *    没有窗口、也没有「整段正文齐」的信号,但**仍然只缓冲**、等「正文已上屏」信号 —— 那个信号由
 *    正文装配器([com.shinku.aipassport.openclaw.stt.XiaozhiReplyText] 的空闲兜底窗口 / `llm` 兜底)
 *    上屏后必然发出。若一个信号都没来(本轮压根没有可上屏正文),这段音频按「窗口外」丢弃,
 *    而不是抢在文字前面出声;
 *  - **已知取舍(发了状态报文但漏发 `stop` 的服务端)**:本类**不**用「空闲」当整段正文齐的凭证
 *    (句子之间本来就可能隔几秒,提前 flush 会把一整段拆成几段并多发一组 `tts_start/tts_stop`);
 *    这种服务端下的音频在「正文已上屏」信号一到就开播(信号由正文装配器的空闲兜底窗口触发);
 *  - **正文已上屏、`stop` 才到**(空闲兜底先结算了正文):`stop` 到达即开播,不再等一个不会来的信号。
 *  - **设备朗读关闭时一个音频帧都不下发**(直通门拦截;开关语义与本地合成那条路一致);
 *    若一段**已经**开了段(信号之后才关的开关),收尾的 `tts_stop` 照发,避免设备停在播放态;
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
     * 本段是否已**开始下发**(收到「正文已上屏」信号、或 `stop` 到达时正文已经上屏)。
     *
     * 为真后不再缓冲:每帧直接组帧下发(一边播一边缓冲)。
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
     * **本类唯一的音频下发闸门**:[onTtsAudio] 只在它为真时才组帧下发,否则一律进缓冲 —— 与
     * 「有没有收到 `tts` 状态报文」「缓冲是否超限」都无关(降级不许抢跑)。
     *
     * 为什么单独记:正文可能比 `stop` 早结算(纯 `llm` 兜底 / 空闲兜底窗口先上屏),那时
     * [onReplyTextDisplayed] 先到、`stop` 后到 —— 这种情况下 `stop` 到达即开播,不再等一个
     * 不会再来的信号(否则本段音频会被永远留在缓冲里)。
     */
    private var textScreenPassed = false

    /**
     * **本轮交付过的所有正文**(会话层渐进交付:首句一条、之后每次补正各一条;见
     * `com.shinku.aipassport.openclaw.stt.XiaozhiReplyText`);空 = 本轮还没交付过可上屏正文。
     *
     * 为什么是**列表**而不是「最后一条」:渐进交付下,首句的 `TEXT('A')` 可能仍在流水线/写队列里
     * 排队时,下一次补正的正文就已经通过 [onReplyBody] 到来了(会话层与流水线是两个线程)。
     * 只认「最后一条」会把先发出的首句上屏信号误判成「不是本轮正文」而**拒绝开播** ——
     * 正好破坏「首句即开播」。列表里的每一条都是**本轮**的正文(逐字由会话层装配),
     * 因此仍然满足「只有本轮正文才算信号」这条不变量。
     *
     * 为什么单独记:[onReplyTextDisplayed] 必须能分辨「上屏的是本轮正文」还是「同为 `'A'` 的其它文本」
     * (版本提示/「无语音」/超时与失败原因/空回复兜底),否则那些文本一上屏就会把本轮的音频放出来
     * (声音跑到文字前面)。见 [XiaozhiScreenSignal]。
     */
    private val expectedReplyBodies = ArrayList<String>()

    /** 本轮已接受的「正文上屏」信号次数(1 = 首次;≥2 = 补正)。[onTurnStart] 归零。 */
    private var screenSignals = 0

    /**
     * **句边界记账**:本轮每条已交付正文 → **交付那一刻已收到的音频帧数**(= 该句的音频起点估计)。
     *
     * 为什么需要它:小智是一句一句来的(文本先到、音频随后),而音频帧本身**不带句界标记**
     * (`[SEQ][rate_khz][frame_ms] + opus`,见 `docs/wire-protocol.md`)。App 唯一能测的句界就是
     * 「这一句的正文到达那一刻,本段音频已经收到了多少帧」—— 于是第 N 句的音频起点 ≈
     * 「第 1..N−1 句的音频总长」,服务侧据此把这条字幕安排在**它自己那句音频即将开播**时上屏
     * ([sentenceStartMs] → `XiaozhiCorrectionPacer` 的句级对齐)。
     *
     * **误差来源**(见设计文档 §5/§6.5):音频略**滞后**于文本(服务端先发文本、再合成并流式推音频),
     * 所以交付时刻收到的帧数会**低估**该句起点 → 字幕偏**早**(安全方向:字幕一定先于它那句的声音);
     * 这种低估通常只有一小段音频的量级,而提前量也留了余量。
     *
     * 键是**清洗后的正文**(与 `onReplyBody`/`sendTextFrame('A')` 转手的同一串文本逐字一致);
     * 噪声句(只拿到模板/emoji,不产生交付)**不记**边界(它本来就没有字幕)。
     */
    private val sentenceStartFrames = LinkedHashMap<String, Int>()

    /**
     * 本轮是否收到过**可下发的**音频帧(直通门放行且校验通过)。
     *
     * 为什么需要它:非小智网关 / 设备朗读开关关闭 / 服务端只给文本不给音频时,本轮**不会有声音**,
     * 也就没有「按句落位」可言 —— 这时 [sentenceStartMs] 返回 null,补正照旧**立刻**上屏
     * (不能因为等一个永远不会来的音频到点而把文案拖到收尾/兜底时限)。
     */
    private var audioFramesSeen = false

    /**
     * **本会话是否见过任何 `tts` 状态报文**(start/sentence_start/sentence_end/stop 之一)。
     *
     * 为什么按「会话级」而不是「本轮」记账:这是**服务端行为**的特征 ——
     *  - 见过 → 以状态报文的窗口为准:窗口外的音频是上一段/上一轮的残留,丢弃;
     *  - 从没见过(实测存在这种服务端实现:只推二进制音频、一条 `tts` JSON 都没有)→ 既没有窗口,
     *    也没有 `stop` 这个「整段齐了」的信号;这种帧**不按窗口丢弃**(否则整段音频永远发不出去),
     *    但仍然只在「正文已上屏」信号之后才下发 —— 顺序优先于及时。
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
        // 上一轮的正文记录一并作废:旧轮的迟到「上屏信号」绝不能再把新一轮的闸门打开。
        expectedReplyBodies.clear()
        screenSignals = 0
        // 句边界与「本轮有没有音频」同样按轮清:上一轮的音频起点绝不能用来安排新一轮的字幕。
        sentenceStartFrames.clear()
        audioFramesSeen = false
        buffered.clear()
        pushedFrames = 0
        droppedFrames = 0
    }

    /**
     * 会话层告知**本轮正文**(见 [com.shinku.aipassport.openclaw.stt.XiaozhiTtsObserver.onReplyBody])。
     *
     * 只记账,不下发任何东西(也是 [onReplyTextDisplayed] 判定「这条 `TEXT('A')` 是不是本轮正文」
     * 的唯一依据 —— 见 [XiaozhiScreenSignal])。
     * 调用时机保证早于那一条 `TEXT('A')` 入队:会话层在把正文交给网关/流水线**之前**就调了它。
     */
    override fun onReplyBody(body: String) {
        if (body.isNotBlank() && !expectedReplyBodies.contains(body)) expectedReplyBodies.add(body)
        // 句边界:本条正文交付那一刻已收到的音频帧数(见 [sentenceStartFrames])。
        // 先到者为准(`containsKey`):同一条正文重复告知不该把边界往后挪。
        if (body.isNotBlank()) {
            val key = body.trim()
            if (!sentenceStartFrames.containsKey(key)) {
                sentenceStartFrames[key] = pushedFrames + buffered.size
            }
        }
        Log.i(
            tag,
            if (body.isBlank()) {
                "本轮正文为空(只有表情/模板/空文本):不播音频(没有文字就没有「文字先于声音」可言)"
            } else {
                "本轮正文已装配(${body.length} 字):等它上屏后开播(当前缓冲 ${buffered.size} 帧)"
            },
        )
    }

    /**
     * 预判这条 `TEXT` 是不是「本轮正文已上屏」信号(只读,不改状态)。
     *
     * 为什么要单独暴露:服务侧要用它**在调 [onReplyTextDisplayed] 之前**定好「记不记上屏时刻」——
     * 那个时刻是给日志里 `距上屏 Xms` 用的,而被拒的文本(版本提示/「无语音」/超时原因)不该记。
     * 判定与 [onReplyTextDisplayed] 用的是**同一个**纯函数,不会出现两处规则分叉。
     *
     * @param logRefusal 拒绝时是否记一行警告(true = 默认,真机排查靠它看“为什么没开播”)
     */
    fun acceptsScreenSignal(role: Char, text: String, logRefusal: Boolean = true): Boolean {
        val ok = acceptsLocked(role, text)
        if (!ok && logRefusal) {
            Log.w(
                tag,
                "忽略「正文上屏」信号(${XiaozhiScreenSignal.refuseReason(role, text, expectedReplyBodies.lastOrNull())}):" +
                    "不抢跑(role=$role 文本前 20 字=${text.take(20)})",
            )
        }
        return ok
    }

    /**
     * 这条 `TEXT` 若被接受,将是**本轮第几次**「正文上屏」(1 = 首次,≥2 = 补正)。
     *
     * null = 本通道/本轮不适用(还没交付过任何本轮正文 —— 例如非小智网关,或本轮正文还没到);
     * 0 = 本轮已有正文,但这条不是本轮正文(不该开播)。
     *
     * 只读、**不打日志**([acceptsScreenSignal] 才负责拒绝时的警告):服务侧在写 `TEXT`
     * 之前用它标出「首次上屏 / 补正上屏」。
     */
    fun replyScreenOrdinal(role: Char, text: String): Int? {
        if (expectedReplyBodies.isEmpty()) return null
        return if (acceptsLocked(role, text)) screenSignals + 1 else 0
    }

    /** 本条 `TEXT` 是不是**本轮任何一条**已交付的正文(判定仍用同一套纯函数,不会分叉)。 */
    private fun acceptsLocked(role: Char, text: String): Boolean =
        expectedReplyBodies.any { XiaozhiScreenSignal.accepts(role, text, it) }

    /**
     * **这条正文的音频起点估计(ms)**:它交付那一刻本段已收到的音频帧数 × 帧长(见 [sentenceStartFrames])。
     *
     * 用途:服务侧把**第 N 句(≥2)的字幕**安排在「它自己那句音频即将开播」时上屏
     * (字幕落位贴着该句音频起点,而不是一收到文本就上屏;见 `XiaozhiCorrectionPacer.SENTENCE_PRELOAD_MS`)。
     * 首句(第 1 句)不走这条判定 —— 它上屏即开播,时机一点不变。
     *
     * @return null = **本类不提供句级对齐**(本轮一帧可下发的音频都没收到,或这条正文没有边界记录):
     *   调用方应照旧立刻上屏,不要为了等一个不会到来的音频到点而把文案拖到收尾。
     */
    fun sentenceStartMs(body: String): Long? {
        if (!audioFramesSeen) return null
        val frames = sentenceStartFrames[body.trim()] ?: return null
        return frames.toLong() * FRAME_MS_DEFAULT
    }

    /**
     * **「本轮正文已上屏」信号**(服务侧在整段正文 `TEXT('A')` 已写进 BLE 串行写队列之后调用)。
     *
     * 为什么需要它:`stop`(整段正文齐)只说明「可以开播了」,而设备屏上的那条气泡与音频帧走的是
     * 同一个 BLE 串行写队列 —— 只要开播发生在正文写入之前,用户就会先听到声音、后看到字。这里把
     * 开播**挂在正文已经入队之后**,于是「正文先于首帧到设备」由构造保证(见类注释)。
     *
     * **只认本轮正文**(判定见 [acceptsScreenSignal]):角色必须是
     * [XiaozhiScreenSignal.REPLY_ROLE] 且文本与本轮正文逐字一致 —— `'U'` 识别原文、同为 `'A'` 的
     * 版本提示/「无语音」/超时与失败原因/空回复兜底都不算信号(否则本轮音频会抢在真正文前面出声)。
     * 本轮正文为空(只有表情/模板)→ 永远不算信号。
     *
     * **所有路径的唯一开播点**(含缓冲超限 / 无 `tts` 状态报文的兜底):信号一到就把已缓冲的帧
     * 按到达顺序连续推出去,并让之后的帧即时下发;缓冲为空时只记一行(不提前发 `tts_start`,
     * 设备只在真要有音频时才该进播放态)。幂等:重复调用只是把标志位再置一次。
     *
     * @return 是否被当作本轮正文的开播信号(服务侧据此记「正文已上屏」的时刻,避开被拒的文本)
     */
    fun onReplyTextDisplayed(role: Char, text: String): Boolean {
        if (!acceptsScreenSignal(role, text)) return false
        screenSignals++
        // 先记「本轮正文已上屏」:正文可能比 `stop` 早结算(空闲兜底 / 纯 llm 兜底),那时这个信号
        // 先到、`stop` 后到 —— `stop` 一到就该开播,不必再等一个不会来的信号。
        textScreenPassed = true
        val pendingStop = awaitingTextScreen
        awaitingTextScreen = false
        if (buffered.isEmpty()) {
            Log.i(tag, "正文已上屏:本段暂无音频帧,首帧到达即下发(信号已过,不再缓冲等待)")
            return true
        }
        Log.i(
            tag,
            "正文已上屏:开始下发已缓冲的 ${buffered.size} 帧(tts_start 在首帧前" +
                (if (pendingStop) ",stop 早已到达)" else ")"),
        )
        beginPlaying()
        return true
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
                    streaming -> Log.d(tag, "收到 stop:正文已上屏、本段已在即时下发,继续按到达顺序下发")
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
     * 一帧下行 opus 音频:校验后按**唯一闸门**处理 —— **正文上屏之前只缓冲、不下发**;正文上屏之后
     * (含 `stop` 之后到达的迟到帧)即时下发。
     *
     * `tts` 状态报文只决定「窗口」语义(窗口外的残留帧丢弃),**不决定**能不能下发:唯一的下发条件是
     * [textScreenPassed]。因此「无状态报文的服务端」与「缓冲超限」都不再抢在正文前面出声。
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
            // 但帧绝不能因此被丢:记为「在窗口内」并进缓冲,等「正文已上屏」信号一起下发。
            segmentOpen = true
            Log.i(
                tag,
                "未见过任何 tts 状态报文:本帧按在窗口内处理并缓冲(等「正文已上屏」后一起下发;" +
                    "${rateKhz}kHz/${frameMs}ms,${opus.size}B)",
            )
        }
        // 收到过一帧**可下发的**音频:本轮的句级字幕对齐从此可用(见 [sentenceStartMs])。
        audioFramesSeen = true
        val frame = BufferedFrame(rateKhz, frameMs, opus)
        if (textScreenPassed && segmentOpen) {
            // 正文已上屏、且本段窗口开着:组帧后立刻推 —— 只有服务侧流水线里必要的 BLE 串行写队列,
            // 这里不引入任何等待(节奏与在途上限由服务侧 [com.shinku.aipassport.openclaw.service.VoiceBridgeService]
            // 的 TtsFlowControl 掌控)。
            // 窗口已关(设备回报播完 / 本段收尾)时不会走到这里:stateReportsSeen 为真时上面已按
            // 「窗口外」丢掉;无状态报文的服务端则由下面的兜底重新开窗。
            ensureStarted()
            push(frame)
            return
        }
        buffered.add(frame)
        if (buffered.size > maxBufferFrames) {
            // 超限策略:丢**最旧**的一帧(内存有界),**不**为了保住音频而提前开播 ——
            // 作者要求「降级时宁可稍晚也别抢跑」:声音绝不能跑到文字前面。
            buffered.removeAt(0)
            droppedFrames++
            if (XiaozhiFrameLog.shouldLog(droppedFrames)) {
                Log.w(
                    tag,
                    "正文上屏前缓冲超过 $maxBufferFrames 帧(≈ ${maxBufferFrames * FRAME_MS_DEFAULT / 1000}s):" +
                        "丢最旧的一帧以保持内存有界(累计丢弃 $droppedFrames 帧);" +
                        "仍等「正文已上屏」信号,不抢跑",
                )
            }
        }
    }

    /**
     * 把已收的缓冲交给下行(先 [ensureStarted] 声明一段,再按到达顺序连续推)。
     *
     * 两个调用点:正文已上屏([onReplyTextDisplayed] → [beginPlaying]);`stop` 到达时正文已经上屏
     * ([onTtsState])。两者都在「正文已上屏」之后,所以顺序保证不被破坏。
     *
     * 没有缓冲、门被关掉的地方三种收尾各按不变量处理:
     *  - 缓冲为空 → 什么都不做(本段可能已开始即时下发,或服务端只有状态没有音频);
     *  - 门关闭 → 这段一个字节都不下发(既不开段也不推帧),把缓冲丢掉并计数;
     *    若这段**已经**开过段(上屏信号之后才被关的开关),由 [endUtterance] 负责 `tts_stop` 收尾。
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

    /**
     * **服务侧观测到「本段已推空 + 设备侧静默达上限」时的主动收尾**(见
     * `VoiceBridgeService.drainXiaozhiTts` 的尾部看门狗与 [XiaozhiTailStop])。
     *
     * 为什么要它:窗口在 `tts.state=stop` 之后是开着的,而正常关闭点原本只有「设备回报本段播完」——
     * 一旦设备(旧固件/解码失败/回报丢失)不报,设备就一直停在播放态(真机表现:手机上早就收完了,
     * 设备还显示「接收中」)。这里给出一条**确定**的收尾路径:本段帧已全部写进 BLE 写队列、且
     * 静默达 [XiaozhiTailStop.TAIL_IDLE_MS] 后主动 `tts_stop`(`tts_stop` 的语义是「后面没有音频了,
     * 把已入队的播完就退出播放态」—— **不**会切掉设备已收的音频)。
     *
     * 与 [onDevicePlaybackFinished] 的区别(两者互补,都幂等):
     *  - 这里是**超时兜底**,不依赖设备回报,也不关窗口 —— `segmentOpen`/`textScreenPassed` 保留,
     *    所以后续迟到帧仍照常续一段(自动重新 `tts_start`),不会把尾音切掉;
     *  - 设备回报到达时说明「它真的播完了」,那时才把窗口一并关掉。
     */
    fun onIdleTailStop(framesWritten: Int) {
        if (!started) {
            Log.d(tag, "忽略「推空且静默」的收尾请求(本段没有开过段,无需 tts_stop)")
            return
        }
        Log.i(
            tag,
            "本段已推空且设备侧静默:主动收尾(tts_stop,已转发 $framesWritten 帧;" +
                "窗口保持打开,迟到帧仍会续一段)",
        )
        endUtterance()
    }

    /** 首个「真的一帧要发」的时刻声明一段开始(幂等):设备的 `tts_start` 在这里、且只在这里下发。 */
    private fun ensureStarted() {
        if (started) return
        // 【抢跑护栏】到这里已经越过了唯一闸门(`textScreenPassed` 为真);万一将来有人新增一条
        // 绕过闸门的调用路径,这里会拒绝开段并留下错误日志,而不是静默地先出声。
        if (!textScreenPassed) {
            Log.e(tag, "拒绝开段:正文还没上屏(唯一闸门失效,本帧不下发)")
            return
        }
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
     * 为什么要这一行:真机上「App 收了几十帧、设备 TTS 计数为 0」的原因只可能是三类 ——
     * 直通门拦了(网关类型/开关/设备能力)、本帧就不该发(RATE/帧长/包长)、或下发链路本身
     * (服务侧 BLE 写)。这一行把第一类钉死;第二类由 [drop] 打;第三类看服务侧
     * `实际写入 BLE 帧` / `首帧音频写出` 两行日志。
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
         * 的缓冲(信号之后帧即时下发,不再受它约束,见 [onTtsAudio])。
         *
         * 超限时**丢最旧的一帧**(不是提前开播、也不是丢整段):内存必须有界,而顺序优先 ——
         * 作者要求「降级时宁可稍晚也别抢跑」。
         *
         * 取值理由:
         *  - **够覆盖常见回复**:300 帧 ≈ 18s,约合 80–110 个汉字的正常语速朗读(两三句的答案),
         *    只有超长回复才会真的丢帧;
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
