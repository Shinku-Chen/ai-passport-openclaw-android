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

    /**
     * 开始一段新的语音。
     *
     * @param onReady **录音/识别真的就绪**时的回调(设备侧「按下即红、就绪变绿」依赖它下发
     *   `turn_ready`,见 `docs/wire-protocol.md`)。必须晚到「麦克风/识别会话已建立」的那一刻,
     *   **不能**在 [startTurn] 一进来就无条件调用:
     *    - 小智云端:服务器 `hello` 回包后已发 `listen.start`(会话建立、可以收音频);
     *      若本次复用了**常驻预热的热连接**(见 `stt/WarmLink.kt`),`listen.start` 按下即发,
     *      因此该回调**可能同步**触发(毫秒级就绪,目标 < 300ms)—— 但判据不变:只有
     *      `listen.start` 真的发出去了才回调;
     *    - Vosk:识别器 reset 完成、可以吃音频;
     *    - 系统 SpeechRecognizer:`onReadyForSpeech`(麦克风已打开)。
     *   永远不会就绪时可以不调用 —— 设备侧有 800ms 兜底超时,不会一直红屏。
     */
    fun startTurn(onReady: () -> Unit = {})

    /** BLE PCM(保留接口,系统引擎不使用外部 PCM)。
     *  注意 payload 已由 [VoicePipeline] 剥掉首字节 SEQ。
     */
    fun feedPcm(pcm: ByteArray)

    /**
     * 设备已编码的 Opus 包(固件 v1 默认上行)。
     *
     * [VoicePipeline] 已剥掉首字节 SEQ,这里收到的是一个完整的 Opus 包。
     * 默认空实现:
     *  - 能直接吃 Opus 的引擎(小智云端)重写本方法,把包原样作为 WS 二进制帧上送,不再本地编解码;
     *  - 只能吃 PCM 的引擎(Vosk)自行 Opus→PCM 解码后走 [feedPcm];
     *  - 完全不接受外部音频的引擎(系统 SpeechRecognizer 只采本机麦克风)忽略即可。
     */
    fun feedOpus(packet: ByteArray) {}

    /** 结束本段语音,返回识别文本;失败/空返回 null。 */
    suspend fun endTurn(): String?

    /** 打断当前识别(如用户再次按下 PTT)。
     *  对预热型引擎(小智):**保留热连接**,只结束本段识别(见 `XiaozhiStt.barge`),
     *  下一轮按下仍能直接 `listen.start`。
     */
    fun barge()

    /**
     * 设备链路断开(BLE 断开/掉线):立即关闭**常驻热连接**,释放后台资源。
     *
     * 与 [barge] 的区别:[barge] 只打断本轮识别、保留热连接供下一轮复用;
     * 链路已断时留着 socket 没有意义,必须关掉。默认实现 = [barge](不做预热的引擎无需区分)。
     */
    fun onLinkDown() = barge()

    /** 预加载/预热识别通道:建立一条**后台静默的热连接**(小智),下一轮按下即可毫秒级就绪。
     *  允许被重复调用(已有可用热连接时直接返回)。失败只记日志,不影响后续「按下才连」。
     *  默认空实现(无显著预热的引擎)。
     */
    fun prewarm() {}

    /**
     * 识别通道是否已经预热可用（通知栏「语音」状态用）。
     *
     * 默认 false：不支持预热的引擎一律当作"没预热"，通知里显示「预热中…」而不是谎报可用。
     */
    fun isWarmReady(): Boolean = false

    /**
     * 识别通道**当前不可用**的可读原因(如小智模式尚未取得绑定凭据、没连设备);可用时为 null。
     *
     * 为什么要有它:链路压根没建起来时,状态文案不能只说「未识别到语音」—— 那看起来像麦克风/网络
     * 坏了,而实际原因(如「这台设备还没绑到本机」)是用户能直接动手修的。默认 null = 不区分。
     */
    val unavailableReason: String?
        get() = null

    /** 释放资源。 */
    fun release()

    /**
     * 流式识别中间结果回调(partial,Vosk 支持)。系统引擎无此能力,默认 null。
     * 若引擎支持,feedPcm 时不断回调实时识别文本。
     */
    val onPartial: ((String) -> Unit)?
        get() = null
}
