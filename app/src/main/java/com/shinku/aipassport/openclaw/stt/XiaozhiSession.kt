package com.shinku.aipassport.openclaw.stt

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [XiaozhiSession] 对「小智 AI 网关」暴露的**最小能力面**(见 `docs/design/xiaozhi-ai-gateway.md` §4.2)。
 *
 * 为什么单独抽接口:网关只用到会话的三件事 —— 挂一个 `llm` 正文观察者、确认会话可用、复用同一条热连接;
 * 而 [XiaozhiSession] 是构造即持 OkHttp 客户端的 WS 会话层(连不了假服务器)。有了这个面,
 * [com.shinku.aipassport.openclaw.gateway.XiaozhiGateway] 的 JVM 单测可以注入假会话,
 * 覆盖「拿到 llm 正文 / 超时 / barge 打断」三条路径,不依赖网络与 Android。
 */
interface XiaozhiLlmSource {

    /** 会话是否可用(有服务端地址);false 时网关立刻给可读原因,不空等一个超时。 */
    val isAvailable: Boolean

    /** 建立/维持常驻热连接([XiaozhiSession.prewarm]):与识别通道共用同一条 socket,幂等。 */
    fun prewarm()

    /** 热连接是否已握手可收发([XiaozhiSession.isWarmReady])。 */
    fun isWarmReady(): Boolean

    /**
     * 挂上/替换 `llm` 正文观察者(会话层唯一的一条下行正文出口)。
     *
     * 与会话构造参数里的 `onLlm` 各自独立、互不覆盖:那一条留给直接构造会话的调用方。
     */
    fun setLlmObserver(observer: ((String) -> Unit)?)

    /**
     * 仅当当前观察者**仍是** [observer] 时摘掉它。
     *
     * 为什么不能盲摘:网关实例会被设置页保存后的重建替换,而旧实例的 `close()` 可能晚于
     * 新实例挂观察者 —— 盲摘会把新实例的观察者一起摘掉,下一轮就永远等不到 `llm`。
     */
    fun clearLlmObserver(observer: (String) -> Unit)
}

/**
 * 小智**下行 TTS 音频**的观察者(见 `docs/design/xiaozhi-ai-gateway.md` §4.3)。
 *
 * 为什么与 [XiaozhiLlmSource] 分开:正文与音频是两条独立的下行,消费方也不同 —— 正文归
 * [com.shinku.aipassport.openclaw.gateway.XiaozhiGateway](回答文本),音频归 TTS 直通
 * (原样转发给设备播放)。两条出口各自可挂/可摘,互不影响。
 *
 * 与 [XiaozhiLlmSource.setLlmObserver] 同一套线程语义:回调可能运行在 OkHttp 的 WS 回调线程,
 * 实现必须线程安全且**不得阻塞**(设备推送要另起协程)。
 */
interface XiaozhiTtsObserver {

    /**
     * 会话开始新一轮(设备 PTT 按下):把上一轮的朗读记账作废。
     *
     * 为什么必须由会话层通知:打断(barge)时服务端不一定回 `tts.stop`,若直通方还认为
     * 「本段朗读仍在进行」,下一轮的音频就会缺一个 `tts_start` 而直接甩给设备。
     * 设备侧的 `tts_abort` 由流水线另行下发(两条路径互不替代)。
     */
    fun onTurnStart()

    /** 下行 TTS 状态:`start` / `sentence_start` / `sentence_end` / `stop`(带该句文本)。 */
    fun onTtsState(state: String, text: String)

    /**
     * 下行 TTS 的一帧 opus 音频(原样转发,**不**解码/重编码)。
     *
     * @param rateKhz 采样率(kHz),取自 server hello 的 `audio_params.sample_rate`(小智 24);
     *   `0` = 服务器还没上报(此时不会有音频帧)
     * @param frameMs 帧长(ms),取自 hello(`frame_duration`,小智 60);`0` = 未上报
     */
    fun onTtsAudio(opus: ByteArray, rateKhz: Int, frameMs: Int)
}

/**
 * 小智(xiaozhi.me)云端的 **WebSocket 会话层** —— 连接/握手/上行音频/预热/重连重放,
 * 并把服务器消息按类型分流给回调。
 *
 * 为什么单独成文件(见 `docs/design/xiaozhi-ai-gateway.md` §4.1):一条小智会话里同时承载四件事,
 * 而 [XiaozhiStt] 以前只用了第一件,导致其余三件无处安放 —— 抽出来供「小智 AI 网关」共用同一会话:
 *  1. **上行**:设备音频 → App → 小智(opus 16 kHz/60 ms;见 [feedOpus]/[feedPcm]);
 *  2. **识别**:`{"type":"stt","text":…}` → [onStt](现有 STT 只用这一条,[XiaozhiStt] 因此退化为薄适配器);
 *  3. **回复**:`{"type":"llm","text":…}` → [onLlm];
 *  4. **语音**:`{"type":"tts","state":…}`(JSON)→ [onTtsState],以及**二进制 opus 帧** → [onTtsAudio]。
 *
 * 后三组回调是**接入面**:STT 路径的行为、日志文案与级别、超时数值、线程语义与抽离前逐字一致。
 * 「小智 AI 网关」(增量 2)通过 [setLlmObserver] 取 `llm.text` 作为本轮回复;
 * TTS 音频直通(增量 3)通过 [setTtsObserver] 取 `tts` 状态与 opus 音频帧(见 [XiaozhiTtsObserver])。
 *
 * 链路(manual 模式,对应设备 PTT 的 turn_start/turn_end):
 *  设备麦克风 → [固件 Opus 编码] → BLE → App feedOpus(已剥掉 SEQ) → 原样转发小智
 *  → 小智 server 跑 ASR → 回 {"type":"stt","text":"..."} → [onStt] 出去(历史用法 = 喂网关)。
 *
 * 两条上行路径:
 *  - [feedOpus](默认):固件已编码,v1 帧 payload = [SEQ][Opus 包],App 剥掉 SEQ 后
 *    直接把 Opus 包当 WS 二进制帧上送,不做任何解码/重编码;
 *  - [feedPcm](兜底):固件只能给 PCM 时,App 用 libopus 编成小智要的 16k 60ms 帧再上送。
 *
 * WS 协议(已实测):连接 wss://api.tenclass.net/xiaozhi/v1/,HTTP 头带
 * Device-Id/Client-Id/Protocol-Version;必须先发客户端 hello,否则服务器立即 close。
 *  服务器回 hello{sample_rate:24000(下行TTS),session_id};发 listen.start(manual);
 *  上送 Opus 二进制帧;发 listen.stop;收 stt 文本。
 *
 * **识别通道常驻预热(热连接)**:握手 + `hello` 往返实测 0.5–2s,若每轮都重做,设备「按下→可说话」
 * 就有明显空窗(整屏红等待)。因此:
 *  - 一轮结束([endTurn])后**不关 socket**,保留 hello 结果作「热连接」;
 *  - 下一轮 [startTurn] 若热连接仍可用(见 [WarmLink.decide])→ **直接发 `listen.start`** 并回调
 *    `onReady()`(毫秒级);不可用/已断开/闲置超时 → 走原有「按下才连」的 [connectAndHello] 路径;
 *  - 预热时机:[prewarm](服务启动 / BLE 链路就绪时由 [SttEngine.prewarm] 静默调用)+ 每轮结束后保留;
 *  - 闲置超过 [WarmLink.IDLE_TIMEOUT_MS] 主动关闭;[release]/[onLinkDown](服务停止 / 断开 BLE)立即关闭;
 *  - 复用失败(`listen.start` 发不出去)自动兜底重连**一次**,绝不因此让整轮识别失败。
 *
 * 正确性约定不变:
 *  - [turnRunning] 在 [startTurn] 一开始就置 true,按下起的 PCM/Opus 立即累积并上送(绝不丢开头);
 *  - `onReady` 仍然只在**本轮识别会话真的建立**(`listen.start` 已发出)时回调(见 [WarmLink.sessionEstablished]);
 *  - 连接/预热失败只记日志,退回「按下才连」的既有行为,不让整轮失败。
 *
 * **本轮无结果时的补救(重连重放)**:热连接的闲置超时必须**短于服务端会话寿命** ——
 * 小智服务端闲置约 60s 后静默废弃连接上的识别会话(传输层 ping/pong 仍通、不报 error、也不立刻断链),
 * 此时若用户按下,整轮音频会被发进一个死会话:等满超时也拿不到 `stt`,只能回「无语音」——
 * 用户说的话就丢了。两层防护:
 *  - **预防**:[WarmLink.IDLE_TIMEOUT_MS] 取 45s(实测闲置 ≤53.8s 正常、≥64s 全失败),不让死会话留到下一轮;
 *  - **兜底**(见 [TurnRecovery]):本轮每一帧 Opus 都留一份缓存,一旦「已上送音频却一条 stt 都没等到」,
 *    就重连并**把这一轮重放**上去 —— 结果只是晚 1–2s,而不是变成「无语音」。
 *
 * @param onStt 识别文本分流(每条 `stt` 回调一次;小智边识边发,可能是部分结果)。
 * @param onLlm 回复正文分流(`{"type":"llm"}`)。
 *   共用本会话的网关不靠它,而是用 [setLlmObserver] 挂观察者(两条出口互不覆盖)。
 * @param onTtsState TTS 状态分流(`state`, `text`);共用本会话的 TTS 直通用 [setTtsObserver]。
 * @param onTtsAudio 下行 TTS 音频分流(`opus`, `rateKhz`, `frameMs`)。
 *   `rateKhz`/`frameMs` 取自服务器 hello 的 `audio_params`(小智为 24 kHz/60 ms),**0 = 尚未上报**。
 */
class XiaozhiSession(
    private val serverUrl: String,          // 如 wss://api.tenclass.net/xiaozhi/v1/
    private val token: String,              // 如 test-token
    /**
     * 小智 Device-Id 的**按需解析**回调(每次建链时调用一次;取值规则见 [XiaozhiIdentity])。
     *
     * 为什么用回调而不是定值:服务先起、设备后连,而且用户可能中途改网关类型 ——
     * 只有建链时按当时的类型/设备取值才是「现在真正该用的标识」;返回空串 = 不可建链
     * (小智模式但设备未连接)→ **不建链**(见 [connectAndHello]),也绝不退回手机侧标识。
     */
    private val deviceIdProvider: () -> String,
    /** 重连重放的等待预算:默认取 [TurnRecovery] 的实机常量(见 [TurnRecovery.Budget]),单测可缩短。 */
    private val recovery: TurnRecovery.Budget = TurnRecovery.Budget(),
    private val onStt: ((String) -> Unit)? = null,
    private val onLlm: ((String) -> Unit)? = null,
    private val onTtsState: ((String, String) -> Unit)? = null,
    private val onTtsAudio: ((ByteArray, Int, Int) -> Unit)? = null,
) : XiaozhiLlmSource {

    private val tag = "XiaozhiStt"
    private val gson = Gson()

    /** `llm` 正文观察者(构造参数之外的第二条接入口,供「小智 AI 网关」挂载;见 [setLlmObserver])。 */
    @Volatile
    private var llmObserver: ((String) -> Unit)? = null

    /** [setLlmObserver]/[clearLlmObserver] 的锁:保证「挂」与「按身份摘」不交叉。 */
    private val llmObserverLock = Any()

    /** TTS 直通观察者(下行 `tts` 状态与 opus 音频;见 [XiaozhiTtsObserver])。 */
    @Volatile
    private var ttsObserver: XiaozhiTtsObserver? = null

    /** [setTtsObserver]/[clearTtsObserver] 的锁(同 [llmObserverLock] 的理由)。 */
    private val ttsObserverLock = Any()

    /** 小智 Client-Id:每次 App 启动随机生成(同一进程内稳定,重启换新),避免旧会话占位被拒。 */
    private val clientId: String = UUID.randomUUID().toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // 保活+死链检测:真机上热连接会被中间设备/服务端静默回收(实测 `小智 WS 失败 code=null null: null`,
        // 无错误码),一旦静默掉线而 App 不自知,用户按下就只能现场握手 → 「等好久才变绿」。
        // 周期 ping 既维持连接,也能在链路死掉时尽快触发 onFailure,交给预热看门狗重建。
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // ---- 热连接(识别通道常驻预热)状态 ----

    /** 热连接状态的修改锁:startTurn / 闲置超时线程 / WS 回调都会改这几个字段。 */
    private val linkLock = Any()

    /** 当前 WebSocket(热连接或本轮新连的);null = 无连接。跨线程读写(OkHttp 回调/主线程/定时器)。 */
    @Volatile
    private var ws: WebSocket? = null

    /** 热连接是否可用:WS 已 open + 服务器 hello 已回 + 未失败/未关闭(可直接 listen.start)。 */
    @Volatile
    private var warmReady = false

    /** 对外暴露热连接状态(通知栏「语音」行用)。 */
    override fun isWarmReady(): Boolean = warmReady

    override fun setLlmObserver(observer: ((String) -> Unit)?) {
        synchronized(llmObserverLock) { llmObserver = observer }
    }

    override fun clearLlmObserver(observer: (String) -> Unit) {
        // 身份比较:旧网关实例的 close() 不能摘掉新实例刚挂上的观察者(见 [XiaozhiLlmSource])。
        synchronized(llmObserverLock) { if (llmObserver === observer) llmObserver = null }
    }

    /** 挂上/替换 TTS 直通观察者(下行 `tts` 状态与 opus 音频的唯一出口)。 */
    fun setTtsObserver(observer: XiaozhiTtsObserver?) {
        synchronized(ttsObserverLock) { ttsObserver = observer }
    }

    /** 仅当当前观察者**仍是** [observer] 时摘掉它(与 [clearLlmObserver] 同一套身份比较理由)。 */
    fun clearTtsObserver(observer: XiaozhiTtsObserver) {
        synchronized(ttsObserverLock) { if (ttsObserver === observer) ttsObserver = null }
    }

    /** 热连接最近一次活动(建立/复用/一轮结束)的时刻,用于闲置超时判定。 */
    @Volatile
    private var warmActiveAtMs = 0L

    /** 是否有一条连接正在建立(hello 还没回):预热与「按下才连」不重复建两条 socket。 */
    @Volatile
    private var connecting = false

    /** 闲置超时定时器:后台把超过 [WarmLink.IDLE_TIMEOUT_MS] 没人用的热连接关掉。 */
    private val warmIdleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xiaozhi-warm-idle").apply { isDaemon = true }
    }

    @Volatile
    private var warmIdleTask: ScheduledFuture<*>? = null

    /** 本轮因热连接复用失败而自动重连的次数(最多一次,见 [WarmLink.shouldFallbackReconnect])。 */
    @Volatile
    private var warmFallbackAttempts = 0

    // ---- 本轮音频缓存(重连重放兜底,见 [TurnRecovery]) ----

    /**
     * 本轮已上送的 Opus 帧缓存(重连重放用):按下起每一帧都留一份,上限 [TurnRecovery.MAX_BUFFER_FRAMES]。
     * 400 帧 ≈ 24s 语音 ≈ 50KB,正常一轮几 KB。
     */
    private val turnFrames = ArrayList<ByteArray>()

    /** turnFrames 的锁(BLE 线程缓存、IO 线程取快照并清空)。 */
    private val turnFramesLock = Any()

    /** 本轮是否收到过任何 `stt`(重试判据之一:收到过就说明链路是好的,不改动它)。 */
    @Volatile
    private var sttSeenThisTurn = false

    /** 轮次序号:startTurn / barge / onLinkDown / release 递增;在途的重连重放据此自行放弃,不插队到新一轮。 */
    @Volatile
    private var turnSeq = 0

    /** 本轮是否已经重连重放过(每轮最多一次,见 [TurnRecovery.shouldRetry])。 */
    @Volatile
    private var turnRetried = false

    // ---- 会话状态 ----

    /** 本 turn 最新一条 stt 文本(小智边识边发,取最后一次作结果)。 */
    @Volatile private var lastStt = ""

    // 缓存 session_id 与 downstream 参数(必要时回填)
    private var sessionId: String? = null

    /**
     * 服务器 hello 自报的**下行 TTS** 音频参数:采样率(kHz)与帧长(ms),用于给 [onTtsAudio] 标注单位。
     * 小智自报 24000/60 → 24/60;`0` 表示还没收到 hello(此时也不会有音频帧),不去猜默认值。
     */
    @Volatile private var ttsRateKhz = 0

    @Volatile private var ttsFrameMs = 0

    @Volatile private var listening = false

    /** turn 是否在进行(startTurn 置 true,endTurn/barge 置 false)。feedPcm 据此刻允许上送,不依赖 hello 回包。
     *  注意:它**不**代表「识别会话已建立」—— 后者才触发 startTurn(onReady) 的就绪回调。 */
    @Volatile private var turnRunning = false

    /** Opus 编码器(libopus JNI):把设备 PCM 编成小智要的 16k 裸 Opus 帧。 */
    private var opusEncoder: Opus? = null

    /** 16k 60ms 帧 = 960 samples = 1920 字节 int16。 */
    private val opusFrameBytes = 960 * 2

    /** PCM 累积缓冲:喂不满一帧的余量,凑满 60ms 再编码。 */
    private val pcmBuffer = ByteArray(opusFrameBytes * 2)

    /** pcmBuffer 当前已填字节数。 */
    private var pcmLen = 0

    /** 收到服务器 hello(成功)/ 连接失败(失败)后回调一次;供 startTurn / prewarm 决定下一步。 */
    @Volatile
    private var onHelloCallback: ((Boolean) -> Unit)? = null

    override val isAvailable: Boolean
        get() = serverUrl.isNotBlank()

    /** 小智 websocket 是否当前活跃连接(供连接监控器定时检测)。 */
    fun isConnected(): Boolean = ws != null

    // ---- 预热 / 一轮的起止 ----

    /**
     * 建立一条**后台静默的热连接**(识别通道常驻预热):服务启动、BLE 链路就绪时调用。
     *
     * 不影响 UI;失败只记日志(下一轮按下仍会走「按下才连」,行为与未预热时一致)。
     * 已有可用热连接时直接返回;闲置超时的旧连接先关再建(不泄漏 socket)。
     */
    override fun prewarm() {
        if (!isAvailable) return
        when (WarmLink.decide(warmState(), System.currentTimeMillis())) {
            WarmLink.WarmDecision.REUSE -> return   // 已有热连接,不重复建
            WarmLink.WarmDecision.RECONNECT_IDLE_TIMEOUT -> {
                Log.i(tag, "热连接闲置超时,已关闭")
                dropWarmLink()
            }
            WarmLink.WarmDecision.RECONNECT_DISCONNECTED -> Unit
        }
        Log.i(tag, "识别通道预热中…")
        connectAndHello { ok ->
            if (ok) {
                Log.i(tag, "识别通道常驻预热已建立")
            } else {
                Log.w(tag, "识别通道预热失败(不影响按下时就绪),将在按下时重连")
            }
        }
    }

    fun startTurn(onReady: () -> Unit) {
        // 新一轮开始:先把上一轮的 TTS 直通记账作废(打断时服务端不一定回 `tts.stop`,
        // 否则下一段音频会缺一个 `tts_start` 而直接甩给设备)。设备侧 `tts_abort` 由流水线另发。
        ttsObserver?.onTurnStart()
        // 立即允许 feedPcm/feedOpus 累积编码上送(不等握手,避免开头 PCM 丢失)
        listening = false
        turnRunning = true          // 先置 true:闲置超时定时器据此不动热连接
        turnSeq++                   // 新一轮:在途的重连重放(上一轮)据此自行放弃
        turnRetried = false
        sttSeenThisTurn = false
        clearTurnFrames()
        lastStt = ""
        pcmLen = 0
        warmFallbackAttempts = 0
        initOpusEncoder()

        // ① 优先复用热连接:按下时直接 listen.start,毫秒级就绪(不用再等一次 hello 往返)。
        // 判定与 listen.start 放在同一把锁里,避免与「hello 刚回来 / 闲置定时器刚好到点」交叉。
        val decision: WarmLink.WarmDecision
        val sessionReady: Boolean
        synchronized(linkLock) {
            decision = WarmLink.decide(warmState(), System.currentTimeMillis())
            sessionReady = decision == WarmLink.WarmDecision.REUSE &&
                WarmLink.sessionEstablished(startListening())
        }
        if (sessionReady) {
            listening = true
            warmActiveAtMs = System.currentTimeMillis()
            cancelWarmIdleTimer()
            Log.i(tag, "复用热连接,直接 listen.start(按下即可说话)")
            // 识别会话真的建立(listen.start 已发出、服务器可用)才回调:
            // 设备侧「按下即红、就绪变绿」据此变绿,表示「现在说话一定能被识别」。
            Log.i(tag, "小智识别会话已建立,本轮可以说话")
            onReady()
            return
        }
        if (decision == WarmLink.WarmDecision.REUSE) {
            // 热连接看着可用但 listen.start 发不出去(socket 刚好被对端关掉):
            // 兜底重连一次,绝不因此让整轮识别失败。
            Log.w(tag, "热连接不可用,重连中:listen.start 发送失败")
            dropWarmLink()
            if (!WarmLink.shouldFallbackReconnect(warmFallbackAttempts)) {
                Log.w(tag, "本轮已重连过,不再重试(等设备侧 800ms 兜底)")
                return
            }
            warmFallbackAttempts++
        } else {
            Log.i(tag, "热连接不可用,重连中:${decision.reason}")
            if (decision == WarmLink.WarmDecision.RECONNECT_IDLE_TIMEOUT) {
                Log.i(tag, "热连接闲置超时,已关闭")
                dropWarmLink()
            }
        }

        // ② 冷路径(与未预热时完全一致):现连现握手,hello 回包后发 listen.start 再回调 onReady。
        // 失败:listening 保持 false,endTurn 已 guard 返回 null;设备侧另有 800ms 兜底超时。
        // 若预热握手刚好已回来(warmReady 变 true),connectAndHello 直接当就绪,不再建第二条 socket。
        connectAndHello { ok ->
            if (!ok) return@connectAndHello
            if (WarmLink.sessionEstablished(startListening())) {
                listening = true
                warmActiveAtMs = System.currentTimeMillis()
                cancelWarmIdleTimer()
                Log.i(tag, "小智识别会话已建立,本轮可以说话")
                onReady()
            } else {
                Log.w(tag, "listen.start 未发出(连接不可用),本轮不就绪")
            }
        }
    }

    /** 初始化 Opus 编码器(16k 单声道,audio 应用模式,兼容小智上行)。 */
    private fun initOpusEncoder() {
        try {
            if (opusEncoder == null) {
                val enc = Opus()
                enc.encoderInit(
                    Constants.SampleRate._16000(),
                    Constants.Channels.mono(),
                    Constants.Application.audio(),
                )
                opusEncoder = enc
                Log.i(tag, "Opus 编码器已初始化 16k/mono")
            }
        } catch (e: Throwable) {
            // 连 Throwable 一起兜:libopus 是 JNI(aar 里的 .so),加载失败是 Error 不是 Exception,
            // 不能让它把整轮识别带崩(拿不到编码器时退化为不上行,而不是进程/任务崩)。
            Log.e(tag, "Opus 编码器初始化失败", e)
            opusEncoder = null
        }
    }

    fun feedPcm(pcm: ByteArray) {
        // 兜底路径:固件给的是【原始 PCM int16 16k】;App 累积到 60ms(960 samples/1920B)编成 Opus 帧上送小智。
        val enc = opusEncoder ?: return
        if (!turnRunning) return   // 用 turnRunning(不等 hello 回包),避免开头 PCM 丢失
        try {
            // 累积输入缓冲(天然是整数个 1024B 块,但 1920B/帧 不整除 → 需跨块缓冲)
            var idx = 0
            while (idx < pcm.size) {
                // 计算缓冲剩余空间
                val space = pcmBuffer.size - pcmLen
                if (space <= 0) break   // 缓冲满,丢弃多余(丢帧)
                val take = minOf(space, pcm.size - idx)
                System.arraycopy(pcm, idx, pcmBuffer, pcmLen, take)
                pcmLen += take
                idx += take
                // 凑齐一帧(1920B=960 samples=60ms)就编码上送
                while (pcmLen >= opusFrameBytes) {
                    val frame = pcmBuffer.copyOfRange(0, opusFrameBytes)
                    val opusData = enc.encode(frame, Constants.FrameSize._960())
                    if (opusData != null && opusData.isNotEmpty()) {
                        emitOpus(opusData)   // 二进制帧(opcode 0x2)
                    }
                    // 挪走已编码的前 1920B
                    System.arraycopy(pcmBuffer, opusFrameBytes, pcmBuffer, 0, pcmLen - opusFrameBytes)
                    pcmLen -= opusFrameBytes
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "feedPcm 编码/上送失败", e)
        }
    }

    /**
     * 设备已编码的 Opus 包(固件 v1 默认上行):[VoicePipeline] 已剥掉首字节 SEQ。
     * 直接作为 WebSocket 二进制帧原样上送小智,不再本地解码/重编码。
     */
    fun feedOpus(packet: ByteArray) {
        if (!turnRunning) return   // 与 feedPcm 一致:不等 hello 回包,避免开头丢帧
        emitOpus(packet)
    }

    /**
     * 上送一帧 Opus,同时给「重连重放」留一份缓存。
     *
     * 与之前直接 `ws.send` 的关键区别:连接不在(或已经死掉)时**仍然缓存** —— 正是靠这份缓存,
     * 本轮才能在一分钟后重连重放,不然用户的语音就真丢了。
     */
    private fun emitOpus(packet: ByteArray) {
        if (packet.isEmpty()) return
        synchronized(turnFramesLock) {
            if (!TurnRecovery.bufferFull(turnFrames.size)) turnFrames.add(packet)
        }
        val socket = ws ?: return
        try {
            socket.send(okio.ByteString.of(*packet))   // 二进制帧(opcode 0x2)
        } catch (e: Exception) {
            Log.e(tag, "Opus 上送失败", e)
        }
    }

    private fun snapshotTurnFrames(): List<ByteArray> =
        synchronized(turnFramesLock) { ArrayList(turnFrames) }

    private fun clearTurnFrames() {
        synchronized(turnFramesLock) { turnFrames.clear() }
    }

    suspend fun endTurn(): String? = withContext(Dispatchers.IO) {
        val mySeq = turnSeq
        val frames = snapshotTurnFrames()
        val wasListening = listening
        if (wasListening) {
            // 发 listen.stop 结束本段,小智会停止本次识别;socket **不关**(保留做热连接)。
            stopListening()
            listening = false
        }
        turnRunning = false
        // 第一段只等 [TurnRecovery.FIRST_WAIT_MS]:正常一轮实测 ~0.2s 就回 stt,收到即返回(不白等)。
        // 识别会话压根没建立时不必空等(本轮没上送过东西,等也不会有结果)。
        var result = if (wasListening) awaitStt(recovery.firstWaitMs) else null
        if (result == null &&
            TurnRecovery.shouldRetry(frames.size, turnRetried, sttSeenThisTurn)
        ) {
            // 已上送音频却一条 stt 都没等到:典型是连接被服务端静默废弃(闲置超时的死会话)。
            // 重连一次并重放本轮音频 —— 用户说的话不因为链路坏掉就丢失。
            turnRetried = true
            Log.w(tag, "本轮无识别结果(已上送 ${frames.size} 帧),重连并重放…")
            result = reconnectAndReplay(mySeq, frames)
        }
        clearTurnFrames()
        // 不再关 socket:保持热连接(hello 结果仍在),下一轮按下可直接 listen.start(毫秒级就绪)。
        keepWarm()
        result
    }

    /** 轮询等待 `stt` 文本(小智识别通常几百 ms);超时返回 null。 */
    private suspend fun awaitStt(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (lastStt.isBlank() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(200)
        }
        return lastStt.takeIf { it.isNotBlank() }
    }

    /**
     * 兜底补救:重连(建链 + hello)后**把本轮音频原样重放**一遍,再等结果。
     *
     * 几条不变量:
     *  - 每轮最多一次(由 [TurnRecovery.shouldRetry] 的 `alreadyRetried` 保证);
     *  - 重连前先 `dropWarmLink()`:旧 socket 的迟到 `stt` 会被 `ws !== socket` 判定丢弃,不会串结果;
     *  - 期间若用户又按了([turnSeq] 变了,新一轮已开始)立即放弃,不插队、不污染新一轮;
     *  - 失败只记日志并返回 null:上层行为与「本轮没识别到」完全一致(设备仍会收到「无语音」)。
     */
    private suspend fun reconnectAndReplay(mySeq: Int, frames: List<ByteArray>): String? {
        if (turnSeq != mySeq) return null
        dropWarmLink()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        connectAndHello { success ->
            ok.set(success)
            done.countDown()
        }
        val connected = try {
            done.await(recovery.reconnectWaitMs, TimeUnit.MILLISECONDS) && ok.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!connected) {
            Log.w(tag, "重连重放:重连失败,本轮无结果")
            return null
        }
        if (turnSeq != mySeq) return null
        // 新连接 = 新会话:清掉上一段的残留结果与判据,再重放(旧 socket 的迟到 stt 已被丢弃)。
        lastStt = ""
        sttSeenThisTurn = false
        val sessionReady = synchronized(linkLock) { WarmLink.sessionEstablished(startListening()) }
        if (!sessionReady) {
            Log.w(tag, "重连重放:listen.start 未发出,本轮无结果")
            return null
        }
        listening = true
        var sent = 0
        for (frame in frames) {
            if (turnSeq != mySeq) break
            val socket = ws ?: break
            try {
                socket.send(okio.ByteString.of(*frame))
                sent++
            } catch (e: Exception) {
                Log.w(tag, "重连重放:第 $sent 帧发送失败", e)
                break
            }
        }
        stopListening()
        listening = false
        val text = awaitStt(recovery.replayWaitMs)
        if (text != null) {
            Log.i(tag, "重连重放成功(重放 $sent 帧): $text")
        } else {
            Log.w(tag, "重连重放仍未识别到语音(重放 $sent 帧)")
        }
        return text
    }

    fun barge() {
        // 打断:取消本轮等待、结束本段识别,但**保留热连接**(下一轮 startTurn 可直接复用)。
        // 因此这里只发 listen.stop,不关 socket —— 关掉就等于每轮都要重新握手(正是要修的空窗)。
        val wasListening = listening
        listening = false
        turnRunning = false
        turnSeq++             // 本轮作废:在途的重连重放立即放弃,不插队到新一轮
        clearTurnFrames()     // 本轮音频不再需要(重放只服务于本轮的识别结果)
        if (wasListening) stopListening()
        pcmLen = 0   // 丢掉不满一帧的余量,不跨轮拼接
    }

    fun onLinkDown() {
        // 设备链路断开(BLE 断开/掉线):留热连接没有意义,立即关闭。
        abandonLink()
    }

    /**
     * Device-Id 的取值来源变了(网关类型切换):丢掉当前热连接,让**下一次**建链按新标识重新握手。
     *
     * 为什么必须丢掉而不是留着:热连接是拿建链那一刻的 Device-Id 握手建起来的 —— 网关类型在小智 AI
     * 与其它网关之间切换,意味着标识在「设备真 MAC」与「全零匿名」之间切换(见 [XiaozhiIdentity]),
     * 复用旧 socket 等于继续用旧标识。没有连接时只是把状态复位,幂等。
     */
    fun resetDeviceId() = abandonLink()

    /** 丢弃当前连接的公共收尾:作废在途重放与本轮缓存,并关掉热连接(下一轮按下/预热会重连)。 */
    private fun abandonLink() {
        listening = false
        turnRunning = false
        turnSeq++
        clearTurnFrames()
        pcmLen = 0
        dropWarmLink()
    }

    fun release() {
        // 服务停止/引擎释放:立即关闭热连接并释放编码器(不留后台资源)。
        listening = false
        turnRunning = false
        turnSeq++
        clearTurnFrames()
        dropWarmLink()
        cancelWarmIdleTimer()
        warmIdleScheduler.shutdownNow()
        releaseOpus()
    }

    private fun releaseOpus() {
        try { opusEncoder?.encoderRelease() } catch (_: Exception) {}
        opusEncoder = null
        pcmLen = 0
    }

    // ---- 热连接簿记 ----

    /** 热连接状态快照(供 [WarmLink] 判定)。 */
    private fun warmState(): WarmLink.WarmLinkState =
        WarmLink.WarmLinkState(connected = warmReady && ws != null, lastActiveAtMs = warmActiveAtMs)

    /**
     * 一轮结束后把 socket 留作热连接:标记可用、刷新活动时间、挂上闲置超时定时器。
     * 若已开新一轮(存在并发 old endTurn 回调),不动记账。
     */
    private fun keepWarm() {
        synchronized(linkLock) {
            if (turnRunning || listening) return
            if (ws == null) {
                warmReady = false
                cancelWarmIdleTimer()
                return
            }
            warmReady = true
            warmActiveAtMs = System.currentTimeMillis()
            armWarmIdleTimer()
            Log.d(tag, "本轮结束:保留热连接(下一轮按下可直接 listen.start)")
        }
    }

    /** 挂上闲置超时定时器(到点只在「真的一直没人用」时才关)。 */
    private fun armWarmIdleTimer() {
        cancelWarmIdleTimer()
        if (!isAvailable) return
        warmIdleTask = try {
            warmIdleScheduler.schedule(
                { closeWarmLinkIfIdle() },
                WarmLink.IDLE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: Exception) {
            Log.w(tag, "热连接闲置定时器启动失败", e)
            null
        }
    }

    private fun cancelWarmIdleTimer() {
        warmIdleTask?.cancel(false)
        warmIdleTask = null
    }

    /** 闲置超时:只在【没有轮次在跑、且确实闲置超时】时关掉热连接,避免长期占用 socket/服务器会话。 */
    private fun closeWarmLinkIfIdle() {
        synchronized(linkLock) {
            if (turnRunning || listening) return   // 期间被复用/正在用
            if (WarmLink.decide(warmState(), System.currentTimeMillis()) !=
                WarmLink.WarmDecision.RECONNECT_IDLE_TIMEOUT
            ) {
                return
            }
            Log.i(tag, "热连接闲置超时,已关闭")
            closeSocketLocked()
        }
    }

    /** 丢弃热连接(关 socket、清标记、停定时器);下一轮按下会重连。 */
    private fun dropWarmLink() {
        cancelWarmIdleTimer()
        synchronized(linkLock) { closeSocketLocked() }
    }

    /** 关掉当前 socket 并清掉热连接标记;调用方需持有 [linkLock]。 */
    private fun closeSocketLocked() {
        warmReady = false
        warmActiveAtMs = 0L
        // 连接一旦丢弃,「正在建立」也随之作废:否则旧握手的回调可能被 ws 判定拦下,
        // connecting 会永久停在 true,下一次 connectAndHello 就会误以为「已在建立中」而一直等。
        connecting = false
        val socket = ws
        ws = null
        try { socket?.close(1000, "done") } catch (_: Exception) {}
    }

    // ---- 连接与协议 ----

    /**
     * 建链 + 发客户端 hello;服务器回 hello 时回调 [onReady](true),失败回调 false。
     *
     * 若已有一条连接正在建立(通常是预热),不重复建 socket,只把回调换成本次的 ——
     * 这样「预热握手还没回来时用户就按下」也能直接等这次握手,不做两次往返。
     */
    private fun connectAndHello(onReady: (Boolean) -> Unit) {
        // 标识不可用(只有小智模式没连设备才会如此)时:不建链、不猜(退回全零/手机侧标识会把平台那台设备认错)。
        // 设备一连上,服务的预热巡检(prewarm)会带着真实 Device-Id 重新走到这里。
        val deviceId = deviceIdProvider().trim()
        if (deviceId.isEmpty()) {
            Log.w(tag, "小智 Device-Id 未就绪(小智网关需先连接设备),跳过建链")
            onReady(false)
            return
        }
        val alreadyWarm: Boolean
        synchronized(linkLock) {
            if (connecting) {
                Log.i(tag, "连接已在建立中,复用在途握手")
                onHelloCallback = onReady
                return
            }
            // 握手在这之前刚完成(通常是预热):直接当就绪,不再建第二条 socket
            alreadyWarm = warmReady && ws != null
            if (!alreadyWarm) {
                connecting = true
                // 必须先存回调再建 socket:hello 可能在 newWebSocket 返回后极快到达,
                // 存晚了就会丢掉本次调用方(→ 冷路径永远收不到 onReady、设备收不到 turn_ready)。
                onHelloCallback = onReady
                ws = client.newWebSocket(buildRequest(deviceId), listener())
            }
        }
        if (alreadyWarm) {
            Log.i(tag, "已有可用热连接,跳过握手")
            onReady(true)
        }
    }

    /** @param deviceId 本次建链使用的 Device-Id(已由 [connectAndHello] 解析并校验非空)。 */
    private fun buildRequest(deviceId: String): Request = Request.Builder()
        .url(serverUrl)
        // 小智:必须先带 Device-Id/Client-Id/Protocol-Version 握手头 + 发 hello,否则立即 close
        // Device-Id 按当前网关类型解析(小智 AI=已连接设备真 MAC,与 OTA/绑定同一个值;其余网关=全零匿名)。
        // Client-Id 每次 App 启动随机生成(进程内稳定,重启换新)。
        .addHeader("Authorization", token.ifBlank { "test-token" })
        .addHeader("Protocol-Version", "1")
        .addHeader("Device-Id", deviceId)
        .addHeader("Client-Id", clientId)
        .build()

    private fun listener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(tag, "小智 WS 已连接 code=${response.code} ${response.message},发送 hello")
            sendHello(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(tag, "收到小智文本: ${text.take(200)}")
            handleServerMessage(text, webSocket)
        }

        /**
         * 下行**二进制帧** = 小智 TTS 的 opus 音频(见 [onTtsAudio])。
         * 旧 socket 的迟到帧丢弃(与 `stt` 同一判据),避免污染新一轮。
         */
        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (ws !== webSocket) return
            Log.d(tag, "收到小智音频帧 ${bytes.size}B(下行 ${ttsRateKhz}kHz/${ttsFrameMs}ms)")
            // 两条出口:构造参数(直接构造会话的调用方)与观察者(共用本会话的 TTS 直通)。
            onTtsAudio?.invoke(bytes.toByteArray(), ttsRateKhz, ttsFrameMs)
            ttsObserver?.onTtsAudio(bytes.toByteArray(), ttsRateKhz, ttsFrameMs)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(tag, "小智 WS 失败 code=${response?.code} ${response?.message}: ${t.message}")
            // 旧 socket 的迟到回调不能影响当前(新)连接:只有确定是自己的连接才处理
            val cb: ((Boolean) -> Unit)?
            synchronized(linkLock) {
                if (!(ws === webSocket || ws == null)) return
                connecting = false
                warmReady = false
                ws = null
                cancelWarmIdleTimer()
                listening = false
                cb = onHelloCallback
                onHelloCallback = null
            }
            cb?.invoke(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(tag, "小智 WS 关闭 $code $reason")
            synchronized(linkLock) {
                if (!(ws === webSocket || ws == null)) return
                connecting = false
                warmReady = false
                ws = null
                cancelWarmIdleTimer()
                listening = false
            }
        }
    }

    private fun sendHello(webSocket: WebSocket) {
        val hello = JsonObject().apply {
            addProperty("type", "hello")
            addProperty("version", 1)
            addProperty("transport", "websocket")
            add("audio_params", JsonObject().apply {
                addProperty("format", "opus")
                addProperty("sample_rate", 16000)   // 上行 ASR 用 16k;服务器下行 TTS 24k 不影响上行
                addProperty("channels", 1)
                addProperty("frame_duration", 60)
            })
        }
        webSocket.send(hello.toString())
    }

    /**
     * 发 `listen.start`(manual 模式)。
     *
     * @return 是否已被 OkHttp 写队列接受 —— **true 才算「本轮识别会话真的建立」**,
     *   `onReady`(设备 `turn_ready` 变绿)只用它放行(见 [WarmLink.sessionEstablished])。
     */
    private fun startListening(): Boolean {
        val socket = ws ?: return false
        val sid = sessionId ?: "probe"
        val start = JsonObject().apply {
            addProperty("session_id", sid)
            addProperty("type", "listen")
            addProperty("state", "start")
            addProperty("mode", "manual")
        }
        return try {
            socket.send(start.toString())
        } catch (e: Exception) {
            Log.e(tag, "listen.start 发送失败", e)
            false
        }
    }

    private fun stopListening() {
        val socket = ws ?: return
        val sid = sessionId ?: "probe"
        val stop = JsonObject().apply {
            addProperty("session_id", sid)
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        try {
            socket.send(stop.toString())
        } catch (e: Exception) {
            Log.w(tag, "listen.stop 发送失败", e)
        }
    }

    private fun handleServerMessage(text: String, socket: WebSocket) {
        try {
            val obj = gson.fromJson(text, JsonObject::class.java) ?: return
            when (obj.get("type")?.asString) {
                "hello" -> onServerHello(obj, socket)
                "stt" -> {
                    // 识别结果。小智边识边发 stt(每条可能是部分/最终),取最新一条作最终结果,
                    // 同时分流给 onStt(现有用法是让设备屏实时上屏 + 喂网关)。
                    // 旧连接(已被替换/关闭)的迟到 stt 丢弃,避免污染本轮结果。
                    if (ws !== socket) return
                    val textVal = obj.stringOrNull("text")
                    if (!textVal.isNullOrBlank()) {
                        lastStt = textVal
                        sttSeenThisTurn = true
                        onStt?.invoke(textVal)
                    }
                }
                // 小智的回复/TTS:分流给两条出口 —— 构造参数的 onLlm/onTtsState(直连会话的调用方),
                // 以及观察者([llmObserver],共用本会话的「小智 AI 网关」)。TTS 音频转发仍是下一增量。
                "llm" -> {
                    val reply = obj.stringOrNull("text").orEmpty()
                    Log.d(tag, "收到小智 llm: ${reply.take(200)}")
                    onLlm?.invoke(reply)
                    llmObserver?.invoke(reply)
                }
                "tts" -> {
                    val state = obj.stringOrNull("state").orEmpty()
                    val sentence = obj.stringOrNull("text").orEmpty()
                    Log.d(tag, "收到小智 tts[$state]: ${sentence.take(200)}")
                    onTtsState?.invoke(state, sentence)
                    ttsObserver?.onTtsState(state, sentence)
                }
                "error" -> Log.w(tag, "小智端错误: ${obj.toString()}")
                else -> Unit
            }
        } catch (e: Exception) {
            Log.e(tag, "解析小智消息失败", e)
        }
    }

    /** 取 JSON 字符串字段(非字符串原语/缺失 → null)。 */
    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** 取 JSON 整数字段(非数字原语/缺失 → null),用于 hello 的 `audio_params`。 */
    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    /**
     * 服务器 `hello` 回包:这条 socket 从此是「热连接」(下一轮可直接 `listen.start`)。
     * 状态更新在同一把锁里完成,避免与 `startTurn` 的复用判定交叉(不多建 socket、不误报就绪)。
     */
    private fun onServerHello(obj: JsonObject, socket: WebSocket) {
        val cb: ((Boolean) -> Unit)?
        synchronized(linkLock) {
            if (ws !== socket) {
                Log.w(tag, "忽略旧连接的 hello(连接已更换)")
                return
            }
            obj.stringOrNull("session_id")?.let { sessionId = it }
            // 下行 TTS 参数(小智自报 24000/60):给 onTtsAudio 标注单位,不参与 STT 路径。
            obj.get("audio_params")?.takeIf { it.isJsonObject }?.asJsonObject?.let { ap ->
                ap.intOrNull("sample_rate")?.let { ttsRateKhz = it / 1000 }
                ap.intOrNull("frame_duration")?.let { ttsFrameMs = it }
            }
            connecting = false
            warmReady = true
            warmActiveAtMs = System.currentTimeMillis()
            cb = onHelloCallback
            onHelloCallback = null
        }
        Log.i(tag, "小智 hello 回包: ${obj.toString()}")
        // 纯预热连接(没有轮次在跑)挂上闲置超时兜底;轮内连接由 endTurn 的 keepWarm 挂。
        if (!turnRunning) armWarmIdleTimer()
        cb?.invoke(true)
    }
}
