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
 * **[start] / [stop] 是「一段」的括号**(2026-10 作者定的按段播放模型,见
 * `docs/design/xiaozhi-ai-gateway.md` §5):一轮回复里有**多段**,每段一小智自己的句子 ——
 * `start` → 该段的音频帧 → `stop`。设备把这一段播完(回报 `tts_playback_done`)之后才进下一段。
 * 打断用的 `{"ev":"tts_abort"}` 由流水线每轮 `turn_start` 无天下发(见 [DeviceTtsSession.onTurnStart]),
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
 * 小智直通**本段收尾**的判定(纯逻辑,可 JVM 单测):「本段已推空 + 设备侧静默达上限」就
 * **收本段**(`tts_stop`,设备从播放态「接收中」回到空闲)。
 *
 * 为什么需要它:正常路径的段尾是「小智给出下一句文本」那一刻收口(见 [XiaozhiTtsRelay]),但
 * **最后一段**没有下一句 —— 而服务端在 `tts.state=stop` 之后仍可能继续推该段的迟到帧(实测存在),
 * 所以最后一段**不能**在 `stop` 上收口(会把尾音关在门外)。于是给一条不依赖服务端状态报文的确定
 * 收尾:**已经推空、且静默达 [TAIL_IDLE_MS]** 就收本段。
 *
 * 为什么**不**会切掉还在排队的音频(两条保证):
 *  1. 判定要求「帧已全部写进 BLE 写队列且静默达 [TAIL_IDLE_MS]」—— 队列里没有待写帧;
 *  2. `tts_stop` 的语义是「后面没有音频了,把**已入队的**播完再退出播放态」(
 *     `docs/wire-protocol.md`),它不会丢掉设备已收的音频。
 *
 * 收口之后**再来的迟到帧**会续一段(自动补一个 `tts_start`),所以尾音不会被切。
 */
object XiaozhiTailStop {

    /**
     * 静默多久算"本段推完"。
     *
     * 固定 3s 在真机上出过事:小智**一句可以很长**(实测 60 字的句段),而它的音频是**断续**吐出来的 ——
     * 中间静默超过 3s,App 就以为这段完了、发 `tts_stop`,设备提前停口(用户听到"读到一半突然停")。
     * 所以按**该段字幕字数**估一个时长(中文 ≈4.5 字/秒)一起取大:**只放大、不缩小**;
     * 不知道字数(0)时仍是 [TAIL_IDLE_MS]。
     */
    const val TAIL_IDLE_MS = 3_000L

    /** 中文语速估值:每字约多少毫秒(≈4.5 字/秒)。 */
    const val MS_PER_CHAR = 220L

    /**
     * 按本段字幕字数算出的静默阈值 —— **但不超过"我们已经推给设备的音频 + [TAIL_MARGIN_MS]"**。
     *
     * 为什么要这个上限(真机:"卡 5 秒才播下一段"):按字数估出的时长可能是十几秒(60 字),
     * 而服务端可能只给了 2 秒音频就没了 —— 死等到十几秒纯属白等。而"剩余音频到了会重置空闲计时"
     * (帧一到 idleMs 归零),所以用"已推音频 + 余量"封顶是安全的:真还有后续音频,它会自己把计时顶开。
     */
    fun idleThresholdMs(textChars: Int, pushedMs: Long = 0L): Long {
        val byChars = textChars.coerceAtLeast(0).toLong() * MS_PER_CHAR
        return if (pushedMs > 0L) maxOf(TAIL_IDLE_MS, minOf(byChars, pushedMs + TAIL_MARGIN_MS))
        else maxOf(TAIL_IDLE_MS, byChars)
    }

    /** 收本段时允许比"已推音频"多等的余量(设备起播 priming + 最后一帧播放 + 一句内的合成间隙)。 */
    const val TAIL_MARGIN_MS = 1_500L

    /**
     * 现在该收本段吗。
     *
     * @param framesWritten 本段已真写进 BLE 写队列的帧数(0 = 本段还没推出过任何帧:不收)
     * @param idleMs 推空之后的静默时长
     * @param alreadyStopped 本段是否已经收过(幂等)
     * @param textChars 本段字幕字数(0 = 不知道,退回 [TAIL_IDLE_MS])
     * @param pushedMs 本段已推音频时长(ms;0 = 不知道,退回按字数估)
     */
    fun shouldStop(
        framesWritten: Int,
        idleMs: Long,
        alreadyStopped: Boolean,
        textChars: Int = 0,
        pushedMs: Long = 0L,
    ): Boolean = framesWritten > 0 && !alreadyStopped && idleMs >= idleThresholdMs(textChars, pushedMs)
}

/**
 * **段与段之间的等待**判定(纯逻辑,可 JVM 单测):本段 `tts_stop` 之后,等设备回报「这一段播完」
 * ([XiaozhiTtsRelay.onDevicePlaybackFinished])才进下一段;**设备不回报**(旧固件/回报丢失/解码失败)
 * 时按本判定兜底推进,免得整轮卡在第二段的门外。
 *
 * 兜底时刻不是拍脑袋的固定值,而是**按本段音频自己的长度估**:设备自 `tts_start` 起
 * 一边收一边播(60ms/帧),所以「它应该播完」的时刻 ≈ `本段已推帧数 × 60ms + [DEVICE_MARGIN_MS]`。
 * [DEVICE_MARGIN_MS] 要吸收:设备起播 priming(真机约 450ms)、最后一帧的写/播放尾、
 * 回报走 BLE 的往返。
 *
 * **为什么宁可等到估计播完再推进**:推进意味着对设备 `tts_start` 下一段 —— 若设备还在播上一段,
 * 两段就会在设备侧连成一条(听不到段间停顿)。所以兜底只用来救「设备压根不回报」,不抢跑。
 */
object XiaozhiSegmentWait {

    /** 一帧时长(ms;小智 hello 自报 60)。 */
    const val FRAME_MS = 60L

    /** 设备侧余量(ms):起播 priming ≈450ms + 最后一帧写/播放尾 + 回报的 BLE 往返。 */
    const val DEVICE_MARGIN_MS = 1_500L

    /** 最短等待(ms):帧数很少时也要给设备起播与回报留出的时间。 */
    const val MIN_WAIT_MS = 2_000L

    /**
     * @param elapsedSinceSegmentStartMs 自本段 `tts_start` 起的毫秒数
     * @param pushedFrames 本段已推给设备的帧数
     */
    fun reportWaitExpired(elapsedSinceSegmentStartMs: Long, pushedFrames: Int): Boolean {
        if (elapsedSinceSegmentStartMs < 0L) return false
        val estimatedEndMs = pushedFrames.coerceAtLeast(1).toLong() * FRAME_MS + DEVICE_MARGIN_MS
        return elapsedSinceSegmentStartMs >= maxOf(estimatedEndMs, MIN_WAIT_MS)
    }
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

    /**
     * 放行 = 网关是小智 AI 且开关打开。
     *
     * **不再用 `caps` 否决**(作者 2026-10-04 定):AI Passport 设备本来都支持下行语音,
     * 而"有没有上报"这个信号**不可靠** —— 固件只在建立连接/订阅成功那一次报 hello,
     * BLE 抖动重连后就不再报,于是 App 会把"还没说"当成"不支持"而静默不出声(真机就是这么哑的)。
     * 老设备的风险由 App↔固件的**大版本配套检查**兜着(不配套会在两边提示)。
     * [deviceTtsCapable] 保留**仅供诊断日志**,不参与放行。
     */
    val allowed: Boolean
        get() = XiaozhiIdentity.isXiaozhi(gatewayType) && ttsEnabled

    /** 拦截原因(放行时为 null):按判定顺序给**第一个**不满足的条件,一句话说清为什么没发。 */
    val blockedReason: String?
        get() = when {
            !XiaozhiIdentity.isXiaozhi(gatewayType) -> "当前网关不是小智 AI(type=$gatewayType)"
            !ttsEnabled -> "设备朗读开关(tts_enabled)关闭"
            else -> null
        }

    /** 日志形态:`type=… enabled=… caps=…`。 */
    fun describe(): String = "type=$gatewayType enabled=$ttsEnabled caps=$deviceTtsCapable"
}

/**
 * 小智下行 TTS 的**按段播放状态机**(纯逻辑 + 一个时钟注入,可 JVM 单测,不依赖 Android/coroutines)。
 *
 * 作者的最终口径(2026-10,`docs/design/xiaozhi-ai-gateway.md` §5):
 * **小智能区分段落,按段落播放对应语音** —— 不是「整轮一串音频一直连续着」。于是 TTS 从「一轮一个段」
 * 改为「**一轮多个段**」:
 * ```
 * 句1 文本到 → 字幕上屏 → tts_start → 推句1的音频帧 → 推完发 tts_stop
 *         ↓ 设备回报「这一段播完」(tts_playback_done;或超时兜底)
 * 句2 文本到 → 字幕上屏 → tts_start → 推句2的音频帧 → tts_stop → …
 *         ↓ 最后一段播完 → 本轮收尾
 * ```
 *
 * ## 段边界:小智自己的分句
 * 一段 = 小智的一句。音频帧本身**没有**句界标记,所以边界只能用两种 App 可观测的信号:
 *  1. **`tts.state=sentence_start`**([onTtsState]):新的一句开始了 —— 它携带着这一句刚交付的正文
 *     (会话层先交付正文、直通侧才收到报文),于是**收上一段**(发它的 `tts_stop`)+ 开新段;
 *  2. **`tts.state=sentence_end`**:这一句的文本收口;若它同时带一条更完整的正文
 *     (`sentence_start` 给半句 / `sentence_end` 给整句那种增长)→ 当成**同一段**的字幕更新,不切段。
 *
 * 服务端**从不发状态报文**(纯音频服务端,实测存在)时没有句界信息:退化为「每次正文交付就是一句」。
 *
 * ## 一轮的状态机
 * ```
 * onTurnStart() (设备按下 OK / barge / turn_cancel / 掉线)   清空全部段 + 关掉已开的一段
 * onReplyBody(正文)        交付正文 → 挂到 pendingBody(等紧接着的句界报文决定它归哪一段)
 * onTtsState(sentence_start) 有新正文 → 收上一段 + 开新段(新段的字幕 = 那条正文)
 * onTtsState(sentence_end)   有成长的正文 → 更新**当前段**字幕(不切段)
 * onTtsState(stop)           整轮文本结算:不再开新段,但**不收段**(尾帧还会来)
 * onTtsAudio(frame)          帧进「当前段」;该段在推 → 立刻下发,否则等开段
 * onReplySubtitleReady(role,text) 服务侧要把本段字幕写出去 → 由段界决定何时真的写([writeSubtitle])
 * advance()                  ① 等上一段的设备回报/超时 → ② 找下一段:轮到它了 → **先写字幕** → tts_start + 推帧
 * onIdleTailStop(帧数)       最后一段「推空 + 静默达 3s」→ 收本段(tts_stop)
 * ```
 *
 * 几条不变量:
 *  - **字幕随段推进**(2026-10 真机修正):第 N 段的字幕只在**第 N 段真正开始**的那一刻
 *    (上一段播完回报 / 兜底超时推进)才写进 BLE 队列;文本可以更早到达,但**只缓存、不提前上屏**
 *    —— 否则下一段的文字会落在上一段音频中间(① 字幕比声音早 ② 文字帧插队打断音频 → 卡顿);
 *  - **字幕必须早于同段音频**:第 N 段的 `tts_start` 只在它的字幕已写进 BLE 串行写队列
 *    ([writeSubtitle])才可能发出 —— 顺序由构造保证(同一写队列,先写字幕再开段);首段仍**立即**上屏开播;
 *  - **段间是自然停顿**:一段的 `tts_stop` 之后**等设备回报**(或按本段时长估计的兜底超时)才开下一段,
 *    绝不在设备还在播上一段时把下一段塞进去;
 *  - **不许丢音频**(有界等待):某段的帧还没到时等它到(等到下一句文本/本段收口/收尾为止),
 *    段与段之间的帧归**下一段**(不丢);**已经收口并收尾的那一段,迟到的帧绝不再回灌**进别的段;
 *  - **缺帧的段跳过**:整段一帧都没有(服务端压根没给音频)→ 跳过(不发空的一对 `tts_start/tts_stop`);
 *  - **缓冲有上限**([maxBufferFrames],只作用于还没开播的段的帧):超限丢**最旧**的一帧(内存有界),
 *    **绝不**为了不丢音频而提前开播 —— 顺序优先;
 *  - **一轮开始必须重置**:打断时服务端不一定回 `stop`,所以 [onTurnStart] 除复位标志位外还要
 *    **清空全部待播段/帧**、并关掉已开的一段(发 `tts_stop`)—— 上一轮的残帧绝不能排进下一轮;
 *  - **设备朗读关闭时一个音频帧都不下发**:段/帧都按 [XiaozhiTtsGate] 实时判定拦下(包括开段之后
 *    被关掉的开关:收尾的 `tts_stop` 照发,免得设备停在播放态);
 *  - **不做重采样/重编码**:采样率不是 16/24 kHz、帧长非正、opus 包超 512B 的帧一律丢弃并记日志
 *    (丢一帧 ≒ 60ms,不值得为此改动协议或引入重采样)。
 *
 * @param gate **直通门**的实时判定(三项条件 + 逐项原因,见 [XiaozhiTtsGate])。必须是实时读取:
 *   设置页切换、设备重连后重新报能力都要立即生效,不需要重启服务。
 * @param downlink 真正的下行实现(BLE 下发 + 流控在服务侧)。
 * @param writeSubtitle **段界上屏**的出口:relay 在推进到本段的那一刻(以及首段收到字幕时)调它,
 *   把这一段字幕写进 BLE 串行写队列;
 *   接下来的 [downlink].start() 就是本段的 `tts_start` —— 「先写这条字幕帧、再开段」由构造保证。
 *   默认空实现 = 调用方自己写(单测不接这一跳)。
 * @param rewriteSubtitle **段内更新**的出口:本段**已在屏上**、同一句的正文又变完整时
 *   ([attachPendingBody] 落到一个已上屏的段上),由 relay 请服务侧重写一遍 ——
 *   服务侧可以按「音频正紧」的让路暂缓(旧行为不变)。默认空实现同上。
 * @param maxBufferFrames 尚未开播的段的帧缓冲上限(帧);单测调小它即可覆盖超限降级路径。
 * @param nowMs 时钟(单测注入假时钟以覆盖「等设备回报超时」那条路)。
 */
class XiaozhiTtsRelay(
    private val gate: () -> XiaozhiTtsGate,
    private val downlink: XiaozhiTtsDownlink,
    private val writeSubtitle: (String) -> Unit = {},
    private val rewriteSubtitle: (String) -> Unit = {},
    private val maxBufferFrames: Int = MAX_BUFFER_FRAMES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : XiaozhiTtsObserver {

    private val tag = "XiaozhiTtsRelay"

    /**
     * 已通过校验、还没推给下行的一帧(存在段自己的待推列表里)。
     *
     * 为什么存**原始 opus**(而不是组好的 payload):SEQ 只在实际下发的帧上递增(与改动前一致),
     * 否则「因门关闭被丢掉的那些帧」也会吃掉 SEQ,设备侧的缺口统计就变成了假丢帧。
     */
    private data class BufferedFrame(val rateKhz: Int, val frameMs: Int, val opus: ByteArray)

    /**
     * 一轮里的一段 = 小智的一句话(见类注释)。
     *
     * 同一轮里按 [ordinal] 顺序处理:只有「上一段已收尾」才可能开下一段。
     */
    private class Segment(val ordinal: Int) {

        /** 该段的小智字幕(= 会话层交给流水线、再由服务侧写进 BLE 队列的那串文本);null = 还没交付。 */
        var subtitle: String? = null

        /**
         * 服务侧已经要求把本段字幕上屏([onReplySubtitleReady] 到了)。
         *
         * 与 [screenPassed] 分开的原因:**文本可以早到,但上屏不早于本段开始** —— 本标志只表示
         * 「这条字幕就绪了」,真正写进 BLE 队列的时机由 [advance] 按段界决定(首段立即)。
         */
        var displayRequested = false

        /** 该段字幕已写进 BLE 写队列(开播条件之一)。 */
        var screenPassed = false

        /** 本段已收口:不会再收新帧(收尾/下一句已开始)。 */
        var sealed = false

        /** 已下发过 `tts_start`。 */
        var started = false

        /** 已下发过 `tts_stop`(推完),正等设备回报。 */
        var stopped = false

        /** 本段已收尾(设备回报到 / 超时兜底),可以推进到下一段。 */
        var finished = false

        /** 还没推给下行的帧。 */
        val pending = ArrayList<BufferedFrame>()

        /** 已推给下行的帧数(日志 + 等设备回报的兜底估计)。 */
        var pushed = 0

        /** 本段 `tts_start` 的时刻(等设备回报的兜底估计基准;0 = 还没开段)。 */
        var startedAtMs = 0L
    }

    /** 本轮的段(声明顺序 = 小智的句序)。 */
    private val segments = ArrayList<Segment>()

    /** 还没有归属段的帧(段与段之间 / 正文还没交付时到的帧);会并进**下一个**声明的段。 */
    private val pool = ArrayList<BufferedFrame>()

    /** 正在推的段([start] 已发、[stop] 未发)。 */
    private var active: Segment? = null

    /** 已 `tts_stop`、等设备回报(或兜底超时)的那一段。 */
    private var awaitingReport: Segment? = null

    /** 本轮文本侧已结束(收到 `tts.stop`):不会再声明新段,池里的迟到帧会续一段。 */
    private var turnTextComplete = false

    /**
     * **刚交付、还没归段的正文**(由紧接着的那条 `tts` 报文决定它归哪一段)。
     *
     * 为什么要这个中转:真实链路上同一个报文的处理顺序是「装配器先交付正文 → 直通侧再收到 `tts`
     * 状态报文」(见 `XiaozhiSession.handleServerMessage`),所以正文总是**早于**它的句界信息到达。
     * 先挂在这里,等 `sentence_start` 说明「新的一句开始了」就收上一段 + 用这条正文开新段;
     * 等 `sentence_end` 说明「同一句的文本还能更长」就当成当前段的字幕更新(不切段)。
     */
    private var pendingBody: String? = null

    /** [pendingBody] 是否已经被服务侧要求上屏(真实链路上后续段就是这样:文本先到、句界报文随后)。 */
    private var pendingBodyRequested = false

    /**
     * 最近一次**段的推进**原因(只进日志):`播完回报` / `兜底超时`。
     *
     * 它给「第 N 段推进(…) → 先上屏第 N 段字幕 → tts_start」那一行用,真机上一眼能看出本段是被设备
     * 回报还是被兜底超时放行的。用完即复位成 [DEFAULT_ADVANCE_REASON]。
     */
    private var advanceReason = DEFAULT_ADVANCE_REASON

    /** 本轮是否已经声明过段(声明过就说明「本轮真的开始了」,之后的帧都是本轮的迟到帧)。 */
    private var declaredThisTurn = false

    /** 本轮是否收到过 `tts` 状态报文(句界信息);与 [stateReportsSeen] 一起判定「未归属的帧」的归属。 */
    private var stateSeenThisTurn = false

    /** 本轮收到的音频帧总数(句起点估计/日志用;不随段重置)。 */
    private var receivedFrames = 0

    /** 下行 SEQ(1 字节回绕,与设备侧缺口统计同义;见 [VbTtsOpusPayload.nextSeq])。 */
    private var seq = 0

    /** 诊断计数:本轮已下发/丢弃的帧数(只用于日志)。 */
    private var pushedFrames = 0
    private var droppedFrames = 0

    /**
     * **本轮交付过的所有正文**(会话层**按段交付**:第 1 段一条、之后每一段各一条;同一句变完整时再一条;见
     * `com.shinku.aipassport.openclaw.stt.XiaozhiReplyText`);空 = 本轮还没交付过可上屏正文。
     *
     * 为什么是**列表**而不是「最后一条」:**按段交付**下,第 1 段的 `TEXT('A')` 可能仍在流水线/写队列里
     * 排队时,下一段的正文就已经通过 [onReplyBody] 到来了(会话层与流水线是两个线程)。
     * 只认「最后一条」会把先发出的首句上屏信号误判成「不是本轮正文」而**拒绝开播** ——
     * 正好破坏「首句即开播」。列表里的每一条都是**本轮**的正文(逐字由会话层装配),
     * 因此仍然满足「只有本轮正文才算信号」这条不变量。
     */
    private val expectedReplyBodies = ArrayList<String>()

    /** 本轮已接受的「正文上屏」信号次数(1 = 第 1 段;≥2 = 后面各段)。[onTurnStart] 归零。 */
    private var screenSignals = 0

    /**
     * **句起点记账**(证据/日志用):本轮每条已交付正文 → **交付那一刻已收到的音频帧数**。
     *
     * 按段播放之后,「字幕落在它自己那句音频之前」由**段的开播闸门**保证(段必须等字幕入队),
     * 不再需要靠这个估计去卡时刻(见 [sentenceStartMs]);保留它是因为真机排查要看
     * 「第 N 段的音频起点 ≈ 第 1..N−1 段的音频总量」这一列([XiaozhiPacingLog.line] 的 `本段起点`)。
     */
    private val sentenceStartFrames = LinkedHashMap<String, Int>()

    /** 本轮是否收到过**可下发的**音频帧(直通门放行且校验通过);[sentenceStartMs] 的可用性由它决定。 */
    private var audioFramesSeen = false

    /**
     * **本会话是否见过任何 `tts` 状态报文**(start/sentence_start/sentence_end/stop 之一)。
     *
     * 为什么按「会话级」而不是「本轮」记账:这是**服务端行为**的特征 ——
     *  - 见过 → 段边界由句界报文决定(见类注释的段边界);本轮的帧在该报文没来/已归完时归「池」,
     *    而**本轮什么都没发生**时到达的帧判为换轮前的残帧(丢弃);
     *  - 从没见过(实测存在这种服务端实现:只推二进制音频、一条 `tts` JSON 都没有)→ 既没有句界
     *    也没有 `sentence_end` 这种「同一句还在生长」的信号,只能退化为「**每次正文交付就是一句**」; 
     *    帧不丢(先进池,交付时并进那一段),但仍只在「字幕已上屏」之后才下发 —— 顺序优先于及时。
     * 因此 [onTurnStart] **不**清它。
     */
    private var stateReportsSeen = false

    /** 最近一次直通门日志的形态(判定/输入没变就不重复打,避免逐帧刷屏)。 */
    @Volatile
    private var lastGateLog: String? = null

    // ---- 一轮的开始 / 打断 ----

    /**
     * 会话开始新一轮(设备 `turn_start`)或本轮被打断(`barge` / 设备 `turn_cancel` / 链路断开):
     * **关掉已开的一段 + 丢弃全部待播段与帧 + 作废记账**。
     *
     * 为什么必须由会话层通知:打断时服务端不一定回 `tts.stop`,若直通方还认为「本段朗读仍在进行」,
     * 下一轮的音频就会缺一个 `tts_start` 而直接甩给设备;而且**上一轮的残帧绝不能排进下一轮**
     * (作者对本次按段播放的硬要求)。
     *
     * 已开段时收尾要走 `tts_stop`(见 [sealAndStop]);`turn_start` 路径上流水线**已经**先发过
     * `tts_abort`(`DeviceTtsSession.onTurnStart`),下游的 `stop()` 因此是空操作 ——
     * 不会多出一条多余的 `tts_stop`。幂等:重复调用只是把标志位/缓冲再清一次。
     * 注意**不**清 [stateReportsSeen]:那是服务端行为的特征,不随轮次变化。
     */
    @Synchronized
    override fun onTurnStart() {
        if (active != null || segments.isNotEmpty() || pool.isNotEmpty()) {
            Log.d(
                tag,
                "新一轮/打断:关闭本段播放窗口,丢弃全部待播段" +
                    "(段数=${segments.size},池中 ${pool.size} 帧,本轮已下发 $pushedFrames 帧,丢弃 $droppedFrames 帧)",
            )
        }
        endTurnSegment()
        segments.clear()
        pool.clear()
        active = null
        awaitingReport = null
        turnTextComplete = false
        pendingBody = null
        pendingBodyRequested = false
        advanceReason = DEFAULT_ADVANCE_REASON
        declaredThisTurn = false
        stateSeenThisTurn = false
        // 上一轮的正文记录一并作废:旧轮的迟到「上屏信号」绝不能再把新一轮的闸门打开。
        expectedReplyBodies.clear()
        screenSignals = 0
        // 句起点与「本轮有没有音频」同样按轮清:上一轮的音频起点绝不能用来解释新一轮的字幕。
        sentenceStartFrames.clear()
        audioFramesSeen = false
        receivedFrames = 0
        pushedFrames = 0
        droppedFrames = 0
    }

    /** 一轮结束(或被打断)时关掉已开的那一段:已开段才发 `tts_stop`(幂等)。 */
    private fun endTurnSegment() {
        val seg = active ?: return
        active = null
        seg.stopped = true
        seg.finished = true
        Log.i(tag, "第 ${seg.ordinal} 段因本轮结束/打断收尾(已转发 ${seg.pushed} 帧):下发 tts_stop")
        downlink.stop()
    }

    // ---- 会话层喂进来的四类事件 ----

    /**
     * 会话层告知**本轮正文**(见 [XiaozhiTtsObserver.onReplyBody])。
     *
     * **按段交付**下同一轮会调多次(第 1 段 + 之后每一段 + 同一句变完整)。这里**只记账**(不下发任何东西),并把这条正文
     * 挂到 [pendingBody] 上等紧接着的 `tts` 句界报文决定它归哪一段:
     *  - 紧接着是 `sentence_start`(新的一句开始)→ 收上一段、用这条正文开新段;
     *  - 紧接着是 `sentence_end`(同一句的文本还能更长,`start` 给半句 / `end` 给整句那种)→
     *    当成**当前段**的字幕更新,不切段;
     *  - **服务端从不发状态报文**(纯音频服务端,没有句界信息)→ 交付即新段。
     */
    @Synchronized
    override fun onReplyBody(body: String) {
        if (body.isBlank()) {
            Log.i(tag, "本轮正文为空(只有表情/模板/空文本):不播音频(没有文字就没有「文字先于声音」可言)")
            return
        }
        if (!expectedReplyBodies.contains(body)) expectedReplyBodies.add(body)
        val key = body.trim()
        // 句起点(证据):本条正文交付那一刻已收到的帧数 = 这一句音频的起点估计。
        // 先到者为准:同一条正文重复告知不该把边界往后挪。
        if (!sentenceStartFrames.containsKey(key)) sentenceStartFrames[key] = receivedFrames

        // 上一条还没归段就又来了一条(罕例:服务端没给句界报文):先把它归到当前段。
        attachPendingBody()
        pendingBody = body
        if (!stateReportsSeen) {
            // 没有句界信息(纯音频服务端):交付就是一句 → 直接开段。
            attachPendingBody()
        }
    }

    /**
     * 小智 `tts` 状态报文:既是**段的句界**、也是「上一条交付的正文归哪一段」的决定点。
     *
     * - `sentence_start` / `start`:**新的一句开始**(它的正文与句首在同一条报文里刚交付)→
     *   收上一段 + 用这条正文开新段;没有新正文(噪声句 / 重复文本)→ 不动段结构;
     * - `sentence_end`:这一句的**文本**收口了 —— 若它同时带来一条更完整的正文(句子在增长),
     *   当成当前段的字幕更新(不切段);音频帧仍会继续到本段;
     * - `stop`:整轮文本结算完成(**不再声明新段**),但**不收段** —— 服务端在 `stop` 之后仍可能推
     *   本段的迟到帧(实测存在),收段会把尾音关在门外;最后一段由「推空 + 静默」收(见 [onIdleTailStop])。
     * - 未知状态不产生任何动作(也**不**据此认定服务端会发状态报文)。
     */
    @Synchronized
    override fun onTtsState(state: String, text: String) {
        when (state) {
            "start", "sentence_start" -> {
                stateReportsSeen = true
                stateSeenThisTurn = true
                val body = pendingBody
                val requested = pendingBodyRequested
                pendingBody = null
                pendingBodyRequested = false
                if (body != null) {
                    // 新的一句:上一段到此为止(它的音频到这句文本为止),这一句开新段。
                    segments.lastOrNull()?.takeIf { !it.sealed && !it.stopped }
                        ?.let { sealAndStop(it, "下一句文本已到") }
                    val seg = declareSegment(body, screenPassed = false)
                    // 文本可能已经先到(服务侧已要求上屏):那就是「本段字幕就绪」,段界到了再写。
                    seg.displayRequested = requested
                    Log.i(
                        tag,
                        "第 ${seg.ordinal} 段已声明(字幕 ${body.length} 字,当前 ${seg.pending.size} 帧," +
                            "字幕就绪=${requested}):等它轮到才上屏+开播",
                    )
                }
            }

            "sentence_end" -> {
                stateReportsSeen = true
                stateSeenThisTurn = true
                // 同一句的正文变完整(`start` 给半句 / `end` 给整句):只更新当前段字幕,不切段。
                attachPendingBody()
            }

            "stop" -> {
                stateReportsSeen = true
                stateSeenThisTurn = true
                attachPendingBody()
                turnTextComplete = true
                Log.i(
                    tag,
                    "整轮文本已结算(tts stop):不再声明新段;最后一段的迟到帧照收," +
                        "由「推空 + 静默」收本段(段数=${segments.size})",
                )
            }

            else -> Unit
        }
        advance()
    }

    /**
     * 把 [pendingBody] 归到**当前段**(同一句的正文变完整就更新字幕;没有当前段就开一新段)。
     *
     * 调用点:`sentence_end`(同句变长)、`stop`(轮末最后一条正文)、新的交付到来(罕见的没句界情形)、
     * 以及「服务端从不发状态报文」的交付时机。
     */
    private fun attachPendingBody() {
        val body = pendingBody ?: return
        val requested = pendingBodyRequested
        pendingBody = null
        pendingBodyRequested = false
        val current = segments.lastOrNull()?.takeIf { !it.sealed && !it.stopped }
        if (current != null) {
            val previous = current.subtitle
            current.subtitle = body
            if (current.screenPassed) {
                // 段内更新(本段已在屏上):由服务侧重写一遍(它可以按让路暂缓),不重新开段。
                // 同一文本不重写:设备侧一条 `TEXT` 就是一个气泡,重写会多一个重复气泡。
                if (requested && previous?.trim() != body.trim()) rewriteSubtitle(body)
            } else if (requested) {
                // 本段还没轮到:照旧待命,段界到了写**最新**的这版。
                current.displayRequested = true
            }
            Log.i(
                tag,
                "第 ${current.ordinal} 段字幕更新(同一句的正文变完整,${body.length} 字):不切段," +
                    "已在屏上=${current.screenPassed},就绪=$requested",
            )
            return
        }
        val seg = declareSegment(body, screenPassed = false)
        seg.displayRequested = requested
        Log.i(
            tag,
            "第 ${seg.ordinal} 段已声明(字幕 ${body.length} 字,当前 ${seg.pending.size} 帧," +
                "字幕就绪=${requested}):等它轮到才上屏+开播",
        )
    }

    /**
     * 一帧下行 opus 音频:校验后进**当前段**;当前段正在推 → 立刻下发(不等 [advance]),
     * 否则进它的待推列表并让 [advance] 看看现在能不能开播。
     *
     * 归属规则(段的顺序即句序):
     *  - 有「还没收尾」的段 → 归它(即使它已收口:收口只表示不再收**文本**,音频到齐之前仍可进);
     *  - 全部段都已收尾(段与段之间 / 本轮收尾之后的迟到帧)→ 进 [pool]:迟到尾帧不丢 ——
     *    本轮文本已结算时 [advance] 会给它续一段,否则并进下一个声明的段(**每个段的括号不受影响**:
     *    一段 `tts_stop` 之后绝不会再有帧插进那一段,它们一定在新段自己的 `tts_start` 之后发);
     *  - **本轮还一段都没声明过**时(只有「服务端会发状态报文」且它什么也没发的情形)→ 判为上一轮/
     *    换轮前在途的残帧,**丢弃**:换轮绝不把上一段的帧排进下一段(作者硬要求)。
     */
    @Synchronized
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
            // 不重采样:设备帧头只认 16/24 kHz(见 docs/wire-protocol.md 的 TTS 下行)。
            drop("采样率 ${rateKhz}kHz 不是 16/24,无法组帧")
            return
        }
        if (frameMs <= 0) {
            drop("帧长 ${frameMs}ms 非法")
            return
        }
        // 单个 opus 包 > 512B:协议上限,不发(发了会被设备当错位帧丢掉后面那一帧)。
        // 在**进缓冲之前**判掉:超限包不进内存。
        if (opus.size > VbFrame.TTS_OPUS_PAYLOAD_MAX) {
            drop("opus 包 ${opus.size}B 超过 ${VbFrame.TTS_OPUS_PAYLOAD_MAX}B")
            return
        }
        // 收到过一帧**可下发的**音频:本轮的句起点估计从此可用(证据/日志)。
        audioFramesSeen = true
        receivedFrames++
        val frame = BufferedFrame(rateKhz, frameMs, opus)
        val target = segments.lastOrNull()?.takeIf { !it.finished && !it.stopped }
        if (target == null) {
            // 没有可归属的段:两种情形必须分开(作者对本次按段播放的硬要求)
            //  · **本轮还没任何段/句界** —— 服务端会发状态报文却什么都没有,说明这些帧不属于本轮
            //    (上一轮的残帧/换轮前在途的帧):丢弃,换轮绝不串段;
            //  · 其它情况(本轮声明过段、或服务端从不发状态报文的纯音频服务端)→ 进池:
            //    迟到尾帧不丢(文本已结算时 [advance] 会给它续一段,否则并进下一个声明的段)。
            if (!declaredThisTurn && !stateSeenThisTurn && stateReportsSeen) {
                drop("本轮还没任何段/句界(上一轮/换轮前在途的残帧)")
                return
            }
            pool.add(frame)
            trimBuffer(pool, "池")
            advance()
            return
        }
        if (target === active) {
            pushToDownlink(target, frame)
            return
        }
        target.pending.add(frame)
        trimBuffer(target.pending, "第 ${target.ordinal} 段待推")
        // 字幕已上屏、上一段也已收尾时,这一帧正好让本段可以开播。
        advance()
    }

    // ---- 「字幕已上屏」信号(段的开播条件)----

    /**
     * 预判这条 `TEXT` 是不是「本轮正文已上屏」信号(只读,不改状态)。
     *
     * 为什么要单独暴露:服务侧要用它**在调 [onReplyTextDisplayed] 之前**定好「记不记上屏时刻」——
     * 那个时刻是给日志里 `距上屏 Xms` 用的,而被拒的文本(版本提示/「无语音」/超时原因)不该记。
     * 判定与 [onReplyTextDisplayed] 用的是**同一个**纯函数,不会出现两处规则分叉。
     *
     * @param logRefusal 拒绝时是否记一行警告(true = 默认,真机排查靠它看“为什么没开播”)
     */
    @Synchronized
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
     * 这条 `TEXT` 若被接受,将是**本轮第几次**「正文上屏」(= 段号:1 = 第 1 段,≥2 = 后面各段)。
     *
     * null = 本通道/本轮不适用(还没交付过任何本轮正文 —— 例如非小智网关,或本轮正文还没到);
     * 0 = 本轮已有正文,但这条不是本轮正文(不该开播)。
     *
     * 只读、**不打日志**([acceptsScreenSignal] 才负责拒绝时的警告):服务侧在写 `TEXT`
     * 之前用它标出「首次上屏 / 第 N 段字幕上屏」。
     */
    @Synchronized
    fun replyScreenOrdinal(role: Char, text: String): Int? {
        if (expectedReplyBodies.isEmpty()) return null
        if (!acceptsLocked(role, text)) return 0
        // 先按「字幕文本一致」找它属于哪一段(段号 = 段序,日志与 pacing 行同号);
        // 同一句被更新过时旧文本对不上号,退到「最早还没上屏的那一段」。
        segments.firstOrNull { it.subtitle?.trim() == text.trim() }?.let { return it.ordinal }
        return segments.firstOrNull { !it.finished && !it.screenPassed }?.ordinal ?: (screenSignals + 1)
    }

    /**
     * **服务侧要把本条 `'A'` 字幕写给设备**:问 relay「什么时候写」—— 这就是「字幕随段推进」的入口。
     *
     * 作者的真机反馈(本轮目标):上一段的声音还没播完,下一段的文字就先上屏了,而那次文字写入又
     * 让音频卡了一下。所以现在:
     *  - **首段**(本轮第一条字幕,前面没有在播/在等的段)→ 立即上屏(延迟优先,不变);
     *  - **第 N(≥2)段** → 文本可以早到,但**只缓存**([Segment.subtitle] / [pendingBody]);
     *    等上一段播完回报 / 兜底超时推进到本段的那一刻,才由 [writeSubtitle] 写进 BLE 串行写队列 ——
     *    顺序由构造保证:**先写这条字幕帧,再 [downlink].start()**(同一写队列);
     *  - **本段已在屏上**(同一句的正文又变完整了)→ 这是**段内更新**,返回 false:服务侧照旧立刻写
     *    (并仍受 `XiaozhiCorrectionPacer` 的让路约束)。
     *
     * **歧义怎么处理**:真实链路上后续段的正文**先于**它的句界报文到达(见类注释),所以「这条是下一段
     * 的首条字幕」还是「已上屏那一段的更新」在这一刻还定不下来。这里先**接管**(只缓存),等紧随的
     * `tts` 句界报文让它落位([attachPendingBody]):落到**未上屏**的段 → 段界再写;
     * 落到**已上屏**的段(段内更新)→ 由 relay 调 [rewriteSubtitle] 请服务侧重写一遍。两路都不丢。
     *
     * 只认**本轮正文**(判定见 [acceptsScreenSignal]):`'U'`/`'R'`、版本提示、「无语音」、超时与失败
     * 原因、空回复兜底一律不算(否则本轮音频会抢在真正文前面出声)。
     *
     * @return true = relay 接管了这条字幕的上屏时机(可能已经写了,也可能留到本段段界再写);
     *   false = 服务侧照旧立刻写(不是本轮正文,或本段已在屏上的段内更新)
     */
    @Synchronized
    fun onReplySubtitleReady(role: Char, text: String): Boolean {
        if (!acceptsScreenSignal(role, text)) return false
        val key = text.trim()
        // 本段已在屏上:同一句的正文又变完整了(段内更新)。这条**允许**立刻写(旧行为不变),不接管。
        if (segments.any { it.screenPassed && it.subtitle?.trim() == key }) return false
        val target = segments.firstOrNull { !it.finished && !it.screenPassed && it.subtitle?.trim() == key }
            ?: segments.firstOrNull { !it.finished && !it.screenPassed }
        when {
            target != null -> target.displayRequested = true
            // 正文已交付、段还没声明(真实链路上后续段就是这样:正文先到、句界报文随后):先挂上「就绪」,
            // 段一声明就带着它(见 [onTtsState] / [attachPendingBody])。
            pendingBody?.trim() == key -> pendingBodyRequested = true
            // 既没有可对应的段、也没有在等的正文:不是本轮的段字幕,交给服务侧照旧写。
            else -> return false
        }
        val ordinal = target?.ordinal ?: (segments.size + 1)
        if (active != null || awaitingReport != null) {
            Log.i(
                tag,
                "第 $ordinal 段字幕延后(上一段未播完: 等回报/兜底超时):文本已到,只缓存," +
                    "段界才写(本段 ${key.length} 字)",
            )
        }
        advance()
        return true
    }

    /** 本条 `TEXT` 是不是**本轮任何一条**已交付的正文(判定仍用同一套纯函数,不会分叉)。 */
    private fun acceptsLocked(role: Char, text: String): Boolean =
        expectedReplyBodies.any { XiaozhiScreenSignal.accepts(role, text, it) }

    /**
     * **「本轮正文已写进 BLE 队列」通知**(服务侧自己写完一条本段字幕之后调用)。
     *
     * 2026-10「字幕随段推进」之后,段的首次上屏由 relay 自己在段界写([writeSubtitle],见
     * [onReplySubtitleReady]);本方法只剩两条来路:
     *  1. **段内更新**(本段已在屏上的同一句变完整)——服务侧照旧立刻写,写完在这里对账;
     *  2. **让路补上**(`XiaozhiCorrectionPacer` 把攒下的字幕吐出来)——写完在这里对账。
     *
     * 它同时是**段的开播条件**:这条正文属于哪一段,就把那一段标成「字幕已上屏」——第 N 段的
     * `tts_start` 只可能在它的字幕入队之后发出,所以「字幕先于该段音频」由构造保证。
     *
     * **只认本轮正文**(判定见 [acceptsScreenSignal]):`'U'` 识别原文、同为 `'A'` 的版本提示/
     * 「无语音」/超时与失败原因/空回复兜底都不算信号(否则本轮音频会抢在真正文前面出声)。
     *
     * @return 是否被当作本轮正文的上屏信号(服务侧据此记「正文已上屏」的时刻,避开被拒的文本)
     */
    @Synchronized
    fun onReplyTextDisplayed(role: Char, text: String): Boolean {
        // 拒绝时留一行带原因的警告(与 acceptsScreenSignal 的默认行为一致),判定只有一处。
        if (!acceptsScreenSignal(role, text)) return false
        screenSignals++
        // 这条字幕属于哪一段:优先按「字幕文本一致」找,其次退到「最早还没上屏的那一段」
        // (同一句的正文被更新过时,旧文本已对不上号,但段的顺序没变)。
        val seg = segments.firstOrNull { !it.finished && it.subtitle?.trim() == text.trim() }
            ?: segments.firstOrNull { !it.finished && !it.screenPassed }
        if (seg != null && !seg.screenPassed) {
            seg.screenPassed = true
            Log.i(
                tag,
                "第 ${seg.ordinal} 段字幕已上屏(本轮第 $screenSignals 次上屏):本段音频可在其后开播",
            )
        }
        advance()
        return true
    }

    // ---- 设备回报 / 段间推进 ----

    /**
     * 设备回报本段播放结束(`tts_playback_done` / `tts_playback_aborted`):**本段播完,可以进下一段**。
     *
     * 为什么由设备回报来推进:只有设备知道自己把这一段播完了(服务端不会给「播完了」的信号)。
     * 回报不带段号,所以只在「本段已推完并且正等它」的时候接受;迟到/早到的回报(本段还在推、
     * 或已经按超时兜底推进过)一律忽略并记一行 —— 否则会把下一段误判成播完。
     */
    @Synchronized
    fun onDevicePlaybackFinished() {
        val seg = awaitingReport
        if (seg == null) {
            Log.d(tag, "忽略设备播放回报(当前没有「已推完、等回报」的段:属上一段/上一轮的迟到回报)")
            return
        }
        advanceReason = "播完回报"
        finishSegment(seg)
        Log.i(
            tag,
            "第 ${seg.ordinal} 段播完回报(tts_playback_done,已转发 ${seg.pushed} 帧)= ${segmentProgressLog()}",
        )
        advance()
    }

    /**
     * **服务侧观测到「本段已推空 + 设备侧静默达上限」时的段尾收口**(见 `VoiceBridgeService.drainXiaozhiTts`
     * 的尾部看门狗与 [XiaozhiTailStop])。
     *
     * 为什么要它:正常路径的段尾是「小智给出下一句文本」那一刻收口,唯独**最后一段**没有下一句 ——
     * 而服务端在 `tts.state=stop` 之后仍可能继续推迟到帧(实测存在),所以最后一段不能靠 `stop` 收口。
     * 这里给出确定收尾:本段已推空、且静默达 [XiaozhiTailStop.TAIL_IDLE_MS] → 收本段(`tts_stop`)。
     *
     * 收口之后**再来的迟到帧会续一段**(自动补一个 `tts_start`),所以尾音不会被切;已收尾的段里
     * 绝不会再插进新帧(不串段)。
     */
    @Synchronized
    fun onIdleTailStop(framesWritten: Int) {
        val seg = active
        if (seg == null) {
            Log.d(tag, "忽略「推空且静默」的收尾请求(当前没有正在推的段:已收口或还没开段)")
            return
        }
        Log.i(
            tag,
            "第 ${seg.ordinal} 段已推空且设备侧静默(已转发 $framesWritten 帧):收本段 → tts_stop",
        )
        sealAndStop(seg, "推空且设备侧静默")
        advance()
    }

    /**
     * **段机推进**(服务侧在推送循环的空闲分支里按秒级/十毫秒级调一次,代价极低):
     * ① 等设备回报超时 → 放弃等待;② 找下一段 → 字幕已上屏 + 有帧 → 开段。
     *
     * 为什么要服务侧来推:本类不持有线程/定时器(纯逻辑可测),而「设备不回报」的兜底必须有人看表。
     */
    @Synchronized
    fun pumpSegments() {
        advance()
    }

    /**
     * 还有没有"未完成的段"(正在推 / 等设备回报 / 已声明但还没开播)。
     *
     * 服务侧用它决定"心跳还要不要继续跳":段机推进不能只靠 drain 循环 —— 真机 bug:
     * 本轮最后一段在 `tts.stop` 之后才收到文本/帧,而那时 drain 已经退出,于是没人再
     * [pumpSegments],那一段的字幕与音频**永远送不出去**(设备上就停在倒数第二句)。
     */
    /** 正在播/正要播的那一段的字幕字数(给服务侧算"静默多久算推完";0 = 没有段)。 */
    val currentTextChars: Int
        get() = segments.firstOrNull { !it.finished }?.subtitle?.length ?: 0

    val hasPendingWork: Boolean
        get() = active != null || awaitingReport != null || segments.any { !it.finished }

    // ---- 内部:段的收口 / 开播 / 推进 ----

    /** 段机推进(所有入口都过这里,状态只在这一处改变)。 */
    private fun advance() {
        if (turnTextComplete) ensureTailSegmentForPool()
        if (active != null) return
        awaitingReport?.let { seg ->
            if (!XiaozhiSegmentWait.reportWaitExpired(nowMs() - seg.startedAtMs, seg.pushed)) return
            advanceReason = "兜底超时"
            finishSegment(seg)
            Log.i(
                tag,
                "第 ${seg.ordinal} 段播完回报超时(已推送 ${seg.pushed} 帧 ≈" +
                    "${seg.pushed * FRAME_MS_DEFAULT}ms,等满 ${nowMs() - seg.startedAtMs}ms):" +
                    "不再等设备 → ${segmentProgressLog()}",
            )
        }
        while (true) {
            val seg = segments.firstOrNull { !it.finished } ?: return
            // 【字幕随段推进】本段轮到(前面没有在播/在等的段)才把它的字幕写进 BLE 队列;
            // 上屏必须先于本段的 tts_start(同一写队列,顺序由构造保证)。
            if (!seg.screenPassed && !writeSubtitleFor(seg)) {
                // 真机 bug(长回答的最后一段):那一段的音频服务端**根本没发**,段界到了也没人来"认领"它的
                // 字幕(displayRequested=false),于是它既不上屏也不跳过 —— 设备上就永远缺最后一句。
                // 本轮文本已结算(turnTextComplete)后不再等:直接把这段自己的字幕写出去。
                if (!turnTextComplete || seg.subtitle == null) return
                seg.displayRequested = true
                if (!writeSubtitleFor(seg)) return
            }
            if (seg.sealed && !seg.started && seg.pending.isEmpty()) {
                // 整段没有音频(服务端没给/帧都被门拦下):跳过,不发空的一对 tts_start/tts_stop。
                Log.i(tag, "第 ${seg.ordinal} 段没有音频(缺帧/无音频):跳过本段")
                seg.finished = true
                continue
            }
            if (seg.pending.isEmpty()) {
                // 同上:本轮已结算、这段一直没等到音频 —— 不能再等(等下去设备就永远缺这一段)。
                if (turnTextComplete && !seg.started) {
                    Log.i(tag, "第 ${seg.ordinal} 段没有等到音频(本轮已结算):收本段(字幕已上屏)")
                    seg.finished = true
                    continue
                }
                return
            }
            startSegment(seg)
            if (seg.sealed) sealAndStop(seg, "本段已收口")
            return
        }
    }

    /**
     * 把第 [seg] 段的字幕**写进 BLE 串行写队列**(段的开播前置条件)。
     *
     * 为什么由 relay 在推进时调、而不是让服务侧一到文本就写:作者的真机反馈 —— 上一段的声音还没播完,
     * 下一段的文字就先上屏了,而那次文字写入又让音频卡了一下。现在文本可以早到,但只缓存
     * ([Segment.subtitle]):到本段真正轮到的那一刻才写 —— 首段立即(延迟优先),后续各段在
     * 「上一段播完回报 / 兜底超时」推进时;段与段之间的停顿里没有音频在推,这次写帧也就不会插谁的队。
     *
     * @return true = 本段字幕已上屏(或本段本来就没字幕可写);false = 还没轮到/文本还没到
     */
    private fun writeSubtitleFor(seg: Segment): Boolean {
        val body = seg.subtitle ?: return false
        if (!seg.displayRequested) return false
        seg.displayRequested = false
        if (seg.ordinal <= 1) {
            Log.i(tag, "第 1 段字幕上屏(本段 ${body.length} 字:首段立即上屏) → tts_start")
        } else {
            Log.i(
                tag,
                "第 ${seg.ordinal} 段推进($advanceReason) → 先上屏第 ${seg.ordinal} 段字幕" +
                    "(本段 ${body.length} 字) → tts_start",
            )
        }
        advanceReason = DEFAULT_ADVANCE_REASON
        // 写帧由服务侧的回调做(**同一** BLE 串行写队列):它必须在下面的 downlink.start() 之前完成。
        writeSubtitle(body)
        seg.screenPassed = true
        screenSignals++
        return true
    }

    /** 声明一段(段号自增);池里的帧并进这一段的开头(不丢音频)。 */
    private fun declareSegment(subtitle: String?, screenPassed: Boolean): Segment {
        val seg = Segment(segments.size + 1)
        seg.subtitle = subtitle
        seg.screenPassed = screenPassed
        if (pool.isNotEmpty()) {
            seg.pending.addAll(pool)
            pool.clear()
        }
        segments.add(seg)
        declaredThisTurn = true
        return seg
    }

    /**
     * 本轮文本已结算(`tts.stop`)之后仍有**没有归属段**的帧:续一段(尾帧不丢,不串段)。
     *
     * 前提:本轮已经有**字幕上过屏**的段 —— 没有可上屏正文时,音频不许抢在文字前面出声
     * (那正是「设备屏上一个方块」那条老 bug 的形态),这时池里的帧按「没有正文」丢弃。
     */
    private fun ensureTailSegmentForPool() {
        if (pool.isEmpty()) return
        if (segments.lastOrNull()?.let { !it.stopped } == true) return
        val owner = segments.lastOrNull { it.screenPassed && it.subtitle != null }
        if (owner == null) {
            Log.w(
                tag,
                "收尾后有 ${pool.size} 帧迟到音频,但本轮没有任何正文上过屏:按「没有可上屏正文」丢弃" +
                    "(声音不抢在文字前)",
            )
            droppedFrames += pool.size
            pool.clear()
            return
        }
        // 字幕沿用上一段(它已经在屏上):尾帧本来就是上一段的尾巴。
        val seg = declareSegment(owner.subtitle, screenPassed = true)
        seg.displayRequested = true
        Log.i(
            tag,
            "第 ${seg.ordinal} 段(尾帧续段):本轮文本已结算后仍有 ${seg.pending.size} 帧迟到音频 → 续一段" +
                "(字幕沿用第 ${owner.ordinal} 段,不丢尾音)",
        )
    }

    /** 开一段:先声明段开始(`tts_start`),再把已有的帧按到达顺序推出去。 */
    private fun startSegment(seg: Segment) {
        val frames = seg.pending.size
        seg.started = true
        seg.startedAtMs = nowMs()
        active = seg
        Log.i(
            tag,
            "第 ${seg.ordinal} 段开始(帧数=$frames 时长≈${frames * FRAME_MS_DEFAULT}ms" +
                ",字幕 ${seg.subtitle?.length ?: 0} 字) → tts_start",
        )
        downlink.start()
        flushPending(seg)
    }

    /** 把本段已收的帧按到达顺序推完(开段时、收口时各调一次)。 */
    private fun flushPending(seg: Segment) {
        if (seg.pending.isEmpty()) return
        val frames = seg.pending.toList()
        seg.pending.clear()
        frames.forEach { pushToDownlink(seg, it) }
    }

    /**
     * 收一段(`sealed`):不再收新帧,并(若正在推)把剩下的帧推完、发 `tts_stop`、进入「等设备回报」。
     *
     * 还没开播的段只标记收口 —— 它开播时会「推完立刻停」([advance] 里的那段),同样满足
     * 「每段各一对 `tts_start`/`tts_stop`」。
     */
    private fun sealAndStop(seg: Segment, reason: String) {
        if (!seg.sealed) {
            seg.sealed = true
            Log.i(tag, "第 ${seg.ordinal} 段收口($reason)")
        }
        if (active !== seg) return
        flushPending(seg)
        active = null
        seg.stopped = true
        awaitingReport = seg
        downlink.stop()
        Log.i(
            tag,
            "第 ${seg.ordinal} 段推完(${seg.pushed} 帧 ≈${seg.pushed * FRAME_MS_DEFAULT}ms) → tts_stop;" +
                "等设备回报(或 ≈${XiaozhiSegmentWait.DEVICE_MARGIN_MS}ms 余量后超时)",
        )
    }

    /** 一段彻底收尾(设备回报到 / 兜底超时):可以推进到下一段。 */
    private fun finishSegment(seg: Segment) {
        seg.finished = true
        seg.sealed = true
        if (awaitingReport === seg) awaitingReport = null
    }

    /** 「下一段/收尾」的日志尾巴(段间推进的原因靠它一眼看清)。 */
    private fun segmentProgressLog(): String {
        val next = segments.firstOrNull { !it.finished }
        return if (next == null) {
            if (turnTextComplete) "本轮音频收尾" else "等下一句文本"
        } else {
            "下一段=第 ${next.ordinal} 段(字幕已上屏=${next.screenPassed},待推 ${next.pending.size} 帧)"
        }
    }

    /**
     * 这一条正文对应那一段的**音频起点估计(ms)**:交付它时本轮已收到的帧数 × 帧长。
     *
     * @return null = **本类不提供该估计**(本轮一帧可下发的音频都没收到,或这条正文没有边界记录);
     *   调用方(服务侧的取证日志)只把它当**证据**显示 —— 按段播放 + 字幕随段推进之后,
     *   「字幕先于它那句的声音」由**段界上屏闸门**保证([onReplySubtitleReady]),不再依赖这个估计去卡时刻。
     */
    @Synchronized
    fun sentenceStartMs(body: String): Long? {
        if (!audioFramesSeen) return null
        val frames = sentenceStartFrames[body.trim()] ?: return null
        return frames.toLong() * FRAME_MS_DEFAULT
    }

    // ---- 组帧 / 缓冲 / 日志 ----

    /** 组帧并下发一帧(SEQ 只在这里递增:只有真的交给下行的帧才占序号)。 */
    private fun pushToDownlink(seg: Segment, frame: BufferedFrame) {
        val payload = try {
            encodeTtsOpusPayload(seq, frame.rateKhz, frame.frameMs, frame.opus)
        } catch (e: IllegalArgumentException) {
            drop("opus 包 ${frame.opus.size}B 超过 ${VbFrame.TTS_OPUS_PAYLOAD_MAX}B")
            return
        }
        seq = VbTtsOpusPayload.nextSeq(seq)
        pushedFrames++
        seg.pushed++
        downlink.pushFrame(frame.rateKhz, frame.frameMs, payload)
        if (XiaozhiFrameLog.shouldLog(seg.pushed)) {
            Log.i(
                tag,
                "第 ${seg.ordinal} 段已组帧 seq=${payload[0].toInt() and 0xFF} ${payload.size}B → 下发" +
                    "(第 ${seg.pushed} 帧,opus ${frame.opus.size}B,${frame.rateKhz}kHz/${frame.frameMs}ms)",
            )
        }
    }

    /** 内存有界:某一处待推/池里的帧超过上限时丢**最旧**的一帧(绝不为了不丢音频而提前开播)。 */
    private fun trimBuffer(frames: MutableList<BufferedFrame>, what: String) {
        if (frames.size <= maxBufferFrames) return
        frames.removeAt(0)
        droppedFrames++
        if (XiaozhiFrameLog.shouldLog(droppedFrames)) {
            Log.w(
                tag,
                "$what 超过 $maxBufferFrames 帧(≈ ${maxBufferFrames * FRAME_MS_DEFAULT / 1000}s):" +
                    "丢最旧的一帧以保持内存有界(累计丢弃 $droppedFrames 帧)",
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
         * **尚未开播的段**的帧缓冲上限:**300 帧**(小智 60ms/帧 → **18 秒**音频)。
         *
         * 按段播放之后,正常轮次里同时在等的只有「下一段」的那几十帧(一段 5s ≈ 83 帧),
         * 所以这个上限只在链路/服务端异常(帧一直不来、文本也不来)时才会碰到。
         *
         * 超限时**丢最旧的一帧**(内存必须有界),并且**绝不**提前开播 —— 作者要求
         * 「降级时宁可稍晚也别抢跑」:声音不能跑到文字前面。
         *
         * 取值理由:
         *  - **够覆盖异常时的常见回复**:300 帧 ≈ 18s,约合 80–110 个汉字的正常语速朗读;
         *  - **内存有界**:每帧 ≤ \[512B opus + 3B 帧头\],缓冲峰值 ≤ 约 155KB;
         *  - **小于服务侧待发队列上限**(`VoiceBridgeService.MAX_XIAOZHI_TTS_QUEUE_FRAMES` = 400):
         *    开一段时会把该段已收的帧一口气交给服务侧队列,队列必须留得下它们 + `tts_stop` 标记。
         */
        const val MAX_BUFFER_FRAMES = 300

        /** 单帧时长兜底值(ms):只用于日志里把帧数换算成秒(小智恒为 60)。 */
        private const val FRAME_MS_DEFAULT = 60

        /** [advanceReason] 的初始值(没有「上一段为何推进」可说时的形态,只进日志)。 */
        private const val DEFAULT_ADVANCE_REASON = "段界"
    }
}
