package com.shinku.aipassport.openclaw.stt

/**
 * 小智(xiaozhi.me)云端流式中文识别 —— 一个**薄适配器**:把 [XiaozhiSession] 的
 * `{"type":"stt","text":…}` 分流接成 [SttEngine]。
 *
 * 只保留一条路:**识别文本 → [onPartial]**(现有用法是让设备屏实时上屏 + 喂网关)。
 * WS 连接/hello 握手/opus 上行(16 kHz/60 ms)/常驻预热(热连接)/断线重连重放(TurnRecovery)
 * 全部下沉到 [XiaozhiSession](见 `docs/design/xiaozhi-ai-gateway.md` §4.1),
 * 这样「小智 AI 网关」能复用同一条会话去接 `llm` / `tts` / 下行音频。
 *
 * 公开方法与行为**与抽离前逐字一致**(startTurn/feedPcm/feedOpus/endTurn/barge/onLinkDown/
 * prewarm/isWarmReady/isConnected/release),日志也仍由会话层以同一个 TAG 打出。
 *
 * 链路(manual 模式,对应设备 PTT 的 turn_start/turn_end):
 *  设备麦克风 → [固件 Opus 编码] → BLE → App feedOpus(已剥掉 SEQ) → 原样转发小智
 *  → 小智 server 跑 ASR → 回 {"type":"stt","text":"..."} → 只取 text 喂网关。
 *
 * 两条上行路径:
 *  - [feedOpus](默认):固件已编码,v1 帧 payload = [SEQ][Opus 包],App 剥掉 SEQ 后
 *    直接把 Opus 包当 WS 二进制帧上送,不做任何解码/重编码;
 *  - [feedPcm](兜底):固件只能给 PCM 时,App 用 libopus 编成小智要的 16k 60ms 帧再上送。
 */
class XiaozhiStt(
    serverUrl: String,                      // 如 wss://api.tenclass.net/xiaozhi/v1/
    token: String,                          // 如 test-token
    /** 小智 Device-Id 的按需解析回调(每次建链时按当时的网关类型 + 已连接设备解析;见 [XiaozhiIdentity])。 */
    deviceIdProvider: () -> String,
    onPartial: ((String) -> Unit)? = null,
    recovery: TurnRecovery.Budget = TurnRecovery.Budget(),
    /**
     * **非小智网关模式**下,一轮识别结束后是否向小智补发一次中止(会话层的
     * [XiaozhiSession.sttAbortAfterEndTurn])。
     *
     * 由调用方按**唯一**的网关类型判定注入(见 [SttFactory] / [XiaozhiIdentity]);
     * 默认 false = 不发(未接线的调用方/单测保持原行为)。
     */
    sttAbortAfterEndTurn: () -> Boolean = { false },
) : SttEngine {

    /**
     * Device-Id 已固定的调用方(单测 / 直连会话)用这个重载:等价于恒返回 [deviceId] 的回调。
     * 服务侧请用回调重载 —— 设备是后连的,定值会永远停在「没设备」那一刻。
     */
    constructor(
        serverUrl: String,
        token: String,
        deviceId: String,
        onPartial: ((String) -> Unit)? = null,
        recovery: TurnRecovery.Budget = TurnRecovery.Budget(),
        sttAbortAfterEndTurn: () -> Boolean = { false },
    ) : this(serverUrl, token, { deviceId }, onPartial, recovery, sttAbortAfterEndTurn)

    /** 流式中间结果回调(构造参数原样保留):会话层的 `onStt` 与对外 [onPartial] 共用同一个实例。 */
    private val partialCallback: ((String) -> Unit)? = onPartial

    /**
     * 会话层实例:本适配器的所有调用都委托给它。
     *
     * 对外**只读**暴露,**唯一**用途是把它交给「小智 AI 网关」共用
     * (见 `docs/design/xiaozhi-ai-gateway.md` §4.2):语音上行与 `llm` 回复必须在**同一条** WS 会话里,
     * 所以网关绝不能另建一条会话(那会既收不到音频、也收不到正文)。
     */
    val session = XiaozhiSession(
        serverUrl = serverUrl,
        token = token,
        deviceIdProvider = deviceIdProvider,
        recovery = recovery,
        sttAbortAfterEndTurn = sttAbortAfterEndTurn,
        // 唯一保留的一条路:stt → 文本(其余 llm/tts/音频分流本适配器不接;
        // llm 正文由共用本会话的「小智 AI 网关」用观察者取走,见 XiaozhiGateway)。
        onStt = { text -> partialCallback?.invoke(text) },
    )

    override val isAvailable: Boolean
        get() = session.isAvailable

    /** 识别文本(含中间 stt)每句回调。 */
    override val onPartial: ((String) -> Unit)?
        get() = partialCallback

    /** 小智 websocket 是否当前活跃连接(供连接监控器定时检测)。 */
    fun isConnected(): Boolean = session.isConnected()

    /** 对外暴露热连接状态(通知栏「语音」行用)。 */
    override fun isWarmReady(): Boolean = session.isWarmReady()

    override fun prewarm() = session.prewarm()

    override fun startTurn(onReady: () -> Unit) = session.startTurn(onReady)

    override fun feedPcm(pcm: ByteArray) = session.feedPcm(pcm)

    override fun feedOpus(packet: ByteArray) = session.feedOpus(packet)

    override suspend fun endTurn(): String? = session.endTurn()

    override fun barge() = session.barge()

    override fun onLinkDown() = session.onLinkDown()

    /**
     * 网关类型(Device-Id 的来源)变了:丢掉热连接,下一次建链按新标识重连
     * (见 [XiaozhiSession.resetDeviceId])。
     */
    fun resetDeviceId() = session.resetDeviceId()

    override fun release() = session.release()
}
