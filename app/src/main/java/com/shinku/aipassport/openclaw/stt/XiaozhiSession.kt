package com.shinku.aipassport.openclaw.stt

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import com.shinku.aipassport.openclaw.tts.XiaozhiFrameLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
     * 挂上/替换本轮**正文**观察者(会话层唯一的一条下行正文出口)。
     *
     * 与会话构造参数里的 `onLlm` 各自独立、互不覆盖:那一条留给直接构造会话的调用方。
     *
     * **通知语义**(见 [XiaozhiReplyText] 与 `docs/design/xiaozhi-ai-gateway.md` §6 修订):
     *  - 非空 = **这一段**(小智的这一句)的正文 —— **首选** `tts` 句级文本,**不累计**前面的段
     *    (屏幕上因此只有 A → B → C 三条,不会多出 AB/ABC),`llm.text` 只做兜底;
     *  - 空串 = 会话层已经确认**本轮没有可上屏正文**(只有表情/空文本):实现要给可读原因收尾,
     *    不要空等超时,更不要把 `emotion` 之类的非正文字段或空串当正文上屏;
     *  - **同一轮会通知多次**(详见 `docs/design/xiaozhi-ai-gateway.md` §6.4):首条之后每次都是
     *    「**下一段**的正文」或「**本段**的正文变完整了」——实现应当把它当成**下一段/本段更新**
     *    (再发一帧给设备并就地替换 App 气泡),否则那一段就永远上不了屏。
     */
    fun setLlmObserver(observer: ((String) -> Unit)?)

    /**
     * 本轮正文的**结算说明**(日志与可读原因用):正常时说明取自哪一层(tts 句级文本 / llm.text 兜底),
     * 为空时说明**哪一层为空**;null = 还没结算过。
     *
     * 为什么要有它:[XiaozhiGateway] 拿到空正文时要把「本轮确实没有可上屏正文」这个结论给用户,
     * 而真机排查需要知道到底卡在哪一层(压根没收到文本 / 收到但清洗后只剩表情与工具模板)。
     * 由 [XiaozhiSession.emitReply] 在交出正文前同步写入。
     */
    val lastReplyDetail: String?

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
     * 会话开始新一轮(设备 PTT 按下)**或本轮被取消(barge / 设备 `turn_cancel`)/链路断开**:
     * 把上一段的朗读记账作废,并**关闭直通侧的播放窗口**。
     *
     * 为什么必须由会话层通知:打断(barge)时服务端不一定回 `tts.stop`,若直通方还认为
     * 「本段朗读仍在进行」,下一轮的音频就会缺一个 `tts_start` 而直接甩给设备。
     * 又因为小智的 `tts.stop` 对文本侧只是「最终结算」而**不是**段落结束(窗口在它之后仍
     * 开着,见 [XiaozhiTtsRelay]),`barge` / `turn_cancel` 必须在这里把窗口收干净 —— 否则设备会一直
     * 停在播放态。设备侧的 `tts_abort` 由流水线另行下发(两条路径互不替代;`turn_start` 路径上
     * `tts_abort` 先发,直通侧的 `tts_stop` 因此是空操作)。
     */
    fun onTurnStart()

    /**
     * 会话层已装配好**这一段**的正文(清洗后的这一段自己的文本;空串 = 本轮确实没有可上屏正文)。
     *
     * 为什么需要它:直通侧的音频下发闸门是「本段正文已写进 BLE 队列」,而**不是**任何一个 `TEXT('A')` ——
     * 服务侧还有版本提示、「无语音」、超时与失败原因等同样以 `'A'` 上屏的文本。直通侧用这里给出的
     * 正文做**唯一**依据:只有随后请求上屏的那条 `'A'` 与本段正文一致,才允许写它、开它的段(见
     * [com.shinku.aipassport.openclaw.tts.XiaozhiTtsRelay.onReplySubtitleReady])。
     *
     * 调用时机:在本段正文交出**之前**(`llm` 观察者之前),保证信号到时正文已经对得上号。
     * **同一轮会调多次**(**按段交付**:第 1 段一条、之后每一句各一条;同一句变完整时再一条),直通侧
     * 把这些都记为「本轮已交付的正文」,因此任一段的首条上屏信号都能开播(见
     * [com.shinku.aipassport.openclaw.tts.XiaozhiTtsRelay.replyScreenOrdinal])。
     */
    fun onReplyBody(body: String)

    /** 下行 TTS 状态:`start` / `sentence_start` / `sentence_end` / `stop`(带该句文本)。
     *  `stop` = 「整段正文齐了」(文本侧的**最终结算点**;音频侧只确认窗口仍开),**不是**本段的结束
     *  —— 窗口保持打开,直通侧把已缓冲的帧连续交给设备、之后到达的帧即时下发,直到本轮收尾
     *  (见 [com.shinku.aipassport.openclaw.tts.XiaozhiTtsRelay])。 */
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
 *  3. **回复**:`{"type":"llm","text":…}` 与 `{"type":"tts","text":…}` → 装配成**逐段正文** → [onLlm]
 *     (**按段交付**:一段一段上屏 A → B → C、不累计;首段一清洗出来就交一次,`sentence_end` 的同一句
 *     更新只更新本段,`tts.state=stop` 是**最终结算点**且只补缺;见 [XiaozhiReplyText]);
 *  4. **语音**:`{"type":"tts","state":…}`(JSON)→ [onTtsState],以及**二进制 opus 帧** → [onTtsAudio]
 *     (小智模式:正文上屏之前先缓冲,之后开播并边播边缓冲,见 [XiaozhiTtsObserver])。
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
 * **凭据过期时的自动换新**(见 [XiaozhiCredentialRefresh]):识别 WS 的 `Authorization` 是 OTA 下发的 token,
 * 它**会过期**。因此两条策略在本层兑现:WS 被 401/403 拒、或对端以鉴权类关闭码收掉连接 → 重查一次 OTA
 * 换新凭据并用它重连**一次**(用户无感,不用手动重绑);凭据落盘超过保守阈值 → 建链**之前**先刷新一次。
 * 两条都只对**绑定凭据链路**生效(匿名通道 0 次 OTA,行为与改动前一致),同一次建链尝试最多刷新一次,
 * 刷新后仍被拒就放弃并把可读原因写进 [unavailableReason](绝不无限重连)。
 *
 * @param onStt 识别文本分流(每条 `stt` 回调一次;小智边识边发,可能是部分结果)。
 * @param onLlm 本轮**逐段正文**分流(非空 = 这一段自己的文本;空串 = 本轮没有可上屏正文)。正文按
 *   [XiaozhiReplyText] 的规则装配:`tts` 的句级文本**按段**交付(**不累计**前后段,屏幕上只有 A/B/C),
 *   整轮没有任何 tts 文本时才用 `llm.text` 兜底;`emotion` 一类非正文字段**永不**参与。
 *   **同一轮会多次回调**:每来一句就交一次(首段一清洗出来就第一次交付,直通侧据此在首段上屏时开播,
 *   不再等 `tts.state=stop`);`sentence_end` 的同一句更新只更新**本段**;`stop` 只补没上屏的那一段。
 *   实现要把它当作新的一段/本段更新而不是忽略(见 `docs/design/xiaozhi-ai-gateway.md` §6.4)。
 *   共用本会话的网关不靠它,而是用 [setLlmObserver] 挂观察者(两条出口互不覆盖)。
 * @param onTtsState TTS 状态分流(`state`, `text`);共用本会话的 TTS 直通用 [setTtsObserver]。
 *   `tts.state=stop` = 「整轮文本结算」:正文在这里**只补还没上屏的那一段**(各段都已上屏则不重复交付),
 *   音频直通侧把它当作段机的结算点,窗口仍保持打开直到本轮收尾(见 [XiaozhiTtsObserver] 与
 *   `docs/design/xiaozhi-ai-gateway.md` §4.4)。
 * @param onTtsAudio 下行 TTS 音频分流(`opus`, `rateKhz`, `frameMs`)。
 *   `rateKhz`/`frameMs` 取自服务器 hello 的 `audio_params`(小智为 24 kHz/60 ms),**0 = 尚未上报**。
 * @param linkAuthProvider 本次建链的**鉴权来源**(URL + `Authorization`),按 Device-Id 解析
 *   (见 [XiaozhiCredentialGate]):小智 AI 用绑定得到的凭据,其余网关用匿名地址 + 占位 token。
 *   `null` = 用构造参数里的 `serverUrl` + `token`([serverUrl]/[token] 的原行为,单测与直连会话照旧)。
 *   返回 [XiaozhiLinkAuth.Unavailable] 时**不建链**,原因进 [unavailableReason] 供状态文案。
 */
class XiaozhiSession(
    private val serverUrl: String,          // 如 wss://api.tenclass.net/xiaozhi/v1/
    /**
     * **匿名通道**的占位 token(如 `test-token`):只在 [linkAuthProvider] 为 null(单测/直连会话)
     * 或非小智网关(全零 MAC)时原样作为 `Authorization` 头使用;小智 AI 模式改用绑定凭据。
     */
    private val token: String,
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
    /**
     * **非小智网关模式**:一轮识别真的结束后,是否向小智补发一次中止([abortSttAfterTurn])。
     *
     * 为什么需要:非小智网关下小智通道只做**识别**,回复由 App 自己的网关产出;而小智云端拿到
     * 识别文本后会接着跑走它自己的 LLM + TTS —— 这段回复没人用,纯属白耗额度,所以要中止掉。
     * 小智 AI 模式下必须是 false:那一轮的 `llm` 正文与 TTS 正是我们要的。
     *
     * 为什么是注入的 provider 而不是会话层自己比较网关类型:「当前是不是小智网关」只有一个判定来源
     * ([XiaozhiIdentity.isXiaozhi],调用方在 [SttFactory] 里注入),会话层不持有设置、也不重复写一份比较。
     * 默认 false = 不发(未接线的调用方/单测保持原行为)。
     */
    private val sttAbortAfterEndTurn: () -> Boolean = { false },
    /**
     * 本次建链的鉴权来源(URL + `Authorization`),按 Device-Id 解析;见 [XiaozhiCredentialGate]。
     *
     * 为什么是回调而不是定值:凭据是「这台已连接设备的」,而且用户可能刚绑定完(凭据刚落盘)、
     * 也可能中途换了网关类型(从小智 AI 切回匿名)—— 只有建链那一刻按当时的事实取值才对。
     * 默认 null = 不注入,行为与改动前逐字一致(单测/直连会话)。
     */
    private val linkAuthProvider: ((String) -> XiaozhiLinkAuth)? = null,
    /**
     * 本次建链的 **Client-Id** 来源(入参 = 本次的 Device-Id);**唯一**来源见 [XiaozhiClientId]。
     *
     * 为什么必须注入持久化实现:[SttFactory] 注入按 Device-Id 落盘的那一份(独立 prefs
     * `xiaozhi_client_id`),于是 App 重启 / 重连 / 重新绑定都用同一个值,且与 OTA 请求用的是**同一个**
     * —— 服务端按 (client_id, device_id) 校验凭据,两处不一致就是「升级 101 通过后立刻被切断」。
     * `null` = 不注入(单测/直连会话),退回 [XiaozhiClientId] 的进程内兜底(仍只生成一份、进程内复用)。
     */
    private val clientIdProvider: ((String) -> String)? = null,
    /**
     * 凭据刷新能力面(重查 OTA → 落盘 → 用新凭据重连一次);见 [XiaozhiCredentialRefreshSource]。
     *
     * 为什么是注入的:刷新需要网络(OTA)+ 存储(SharedPreferences),会话层不该自己 new 一套请求;
     * `savedAtMs`/`nowMs` 注入后,「凭据偏旧」那条兜底阈值就能在 JVM 单测里用固定时间戳验证。
     * 默认 null = 不刷新(单测/直连会话)。注意匿名通道**不会**用到它:
     * [XiaozhiLinkAuth.Ok.refreshable] 为 false,刷新入口直接返回。
     */
    private val credentialRefresh: XiaozhiCredentialRefreshSource? = null,
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

    /**
     * 本轮正文装配器:`llm`/`tts` 两条下行按设计文档 §4.8/§5/§6 修订的规则拼成**逐段正文**,
     * **按段交付**(一段一段上屏:A → B → C,不累计;首段一清洗出来就交一次,`sentence_end` 的
     * 同一句更新只更新本段,`stop` 是最终结算点且只补缺)经 [emitReply] 交出。
     *
     * `log` 出口把段级日志(`第 N 段字幕上屏/更新/补上/跳过/不重复`)接到本类的 `Log.i` ——
     * 本类保持**纯逻辑**(不引 Android 日志),日志文案由装配器给出、只在这一处落地。
     */
    private val replyText = XiaozhiReplyText(
        emit = { outcome -> emitReply(outcome) },
        log = { line -> Log.i(tag, line) },
    )

    /** [replyText] 与空闲结算定时器的锁(WS 回调线程 / 定时器协程都会动它们)。 */
    private val replyLock = Any()

    /** 空闲结算定时器代次:每次喂事件都递增,旧定时器醒来发现代次变了就自行放弃。 */
    private var replyIdleToken = 0L

    /**
     * 空闲结算定时器所在的作用域。
     *
     * 为什么单独一个而不是复用 [refreshScope]:那个作用域是「凭据刷新」的语义,这里只是延迟结算的
     * 定时器 —— 分开后 [release] 能各自取消,读代码时也不会把两件事混起来。
     */
    private val replyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [setTtsObserver]/[clearTtsObserver] 的锁(同 [llmObserverLock] 的理由)。 */
    private val ttsObserverLock = Any()

    /**
     * 本会话的 Client-Id(按 Device-Id 取,**与 OTA 同一份**;见 [XiaozhiClientId])。
     *
     * 为什么每次建链现取而不是构造时定死:Device-Id 本身是**建链时**解析的(小智 AI 用设备真 MAC、
     * 其余网关用全零匿名),而 Client-Id 是「跟着身份走」的 —— 身份变了就要换一份新的,
     * 否则拿旧身份的 client_id 去握手又会与云端记录对不上。
     */
    private fun clientIdFor(deviceId: String): String =
        clientIdProvider?.invoke(deviceId) ?: XiaozhiClientId.forDevice(deviceId)

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

    /**
     * 识别通道**当前不可用**的可读原因(如小智模式尚未取得绑定凭据、小智模式没连设备);可用时为 null。
     *
     * 为什么要有它:链路压根没建起来时,上一层的状态文案不能只说「未识别到语音」—— 那看起来像
     * 麦克风/网络坏了,而实际原因是「这台设备还没绑到本机」。流水线用这个原因当状态文案
     * (见 `VoicePipeline` 的「本轮没识别到」分支)。
     */
    @Volatile
    var unavailableReason: String? = null
        private set

    /**
     * 最近一次本轮正文的结算说明(见 [XiaozhiLlmSource.lastReplyDetail]):正常时说明正文取自哪一层,
     * 为空时说明哪一层为空。由 [emitReply] 在交出正文**之前**写入,供网关构造可读原因。
     */
    @Volatile
    override var lastReplyDetail: String? = null
        private set

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

    /**
     * 本次「建链尝试」是否已经刷新过凭据:同一次尝试**最多刷新一次**。
     *
     * 防死循环:刷新后仍被拒就放弃并给可读原因,绝不为一份不该成功的凭据无限重连。
     * 复位点:[startTurn](一轮 = 一次尝试)与 hello 成功(这次尝试真的成功了,允许后续再刷)。
     */
    @Volatile
    private var authRefreshed = false

    /**
     * 当前连接是否用**绑定凭据**握手([XiaozhiLinkAuth.Ok.refreshable]);只有它会触发刷新。
     * 匿名通道(全零 MAC + 占位 token)永远是 false,因此刷新逻辑一行都走不到(行为与改动前一致)。
     */
    @Volatile
    private var linkAuthRefreshable = false

    /**
     * 凭据刷新的后台协程作用域。
     *
     * 为什么必须异步:[prewarm]/[startTurn] 跑在主线程或 BLE 回调线程上,而一次刷新要发 OTA 请求
     * (硬超时 [XiaozhiActivator.CLOUD_QUERY_TIMEOUT_MS])—— 绝不能阻塞它们。
     */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * 本轮是否已经补发过中止([abortSttAfterTurn]):每轮最多一次,重复收尾不得重复发。
     * 每轮 [startTurn] 复位。
     */
    @Volatile
    private var sttAbortSentThisTurn = false

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

    /**
     * 本轮收到的下行 TTS 音频帧数（只用于日志）:新一轮 [startTurn] 时归零并打一行上一轮的合计。
     *
     * 为什么要合计:**轮级**的「收了几帧」比逐帧行更能一眼判定 —— 设备侧 TTS 计数为 0 而这里
     * 每轮几十帧,说明断点一定在会话层之后的某一跳(直通门/接收窗口/BLE 下发)。
     */
    @Volatile private var ttsFramesThisTurn = 0

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
        val lastTurnFrames = ttsFramesThisTurn
        ttsFramesThisTurn = 0
        if (lastTurnFrames > 0) {
            Log.i(
                tag,
                "上一轮小智下行音频帧共 $lastTurnFrames 帧(TTS 观察者=${if (ttsObserver != null) "有" else "无"})",
            )
        }
        ttsObserver?.onTurnStart()
        // 正文记账同理作废:上一轮迟到的句级文本/兜底正文绝不能进这一轮。
        resetReply()
        // 立即允许 feedPcm/feedOpus 累积编码上送(不等握手,避免开头 PCM 丢失)
        listening = false
        turnRunning = true          // 先置 true:闲置超时定时器据此不动热连接
        turnSeq++                   // 新一轮:在途的重连重放(上一轮)据此自行放弃
        turnRetried = false
        sttSeenThisTurn = false
        sttAbortSentThisTurn = false
        clearTurnFrames()
        lastStt = ""
        pcmLen = 0
        warmFallbackAttempts = 0
        authRefreshed = false   // 新一轮 = 新的建链尝试:允许再刷一次(但每轮最多一次)
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
        // 非小智网关:识别真的结束、最终文本已定时,补发一次中止,掐掉小智云端接着跑的 LLM + TTS
        // (那一段回复没人用,白耗额度)。顺序不能提前到「拿到最终文本」之前:那时云端还在出 stt,
        // 多一条收尾消息可能把自己的识别结果一起掐掉,也会影响本方法的返回值。
        // 只在「仍是本轮」时发:迟到的收尾绝不能把已经开始的新一轮停掉。
        if (turnSeq == mySeq) abortSttAfterTurn()
        // 不再关 socket:保持热连接(hello 结果仍在),下一轮按下可直接 listen.start(毫秒级就绪)。
        keepWarm()
        result
    }

    /**
     * 向小智补发一次**中止**([sttAbortAfterEndTurn] 命中时的实际动作)。
     *
     * 消息格式**完全复用既有打断路径**:与 [barge] / [endTurn] 一样走 [stopListening](`listen.stop`,
     * 同一 `session_id` 与字段),不新增第二种消息格式,也**不关 socket**(热连接保留给下一轮)。
     *
     * 三条不变量:
     *  - 模式门是注入的 [sttAbortAfterEndTurn](唯一来源见 [XiaozhiIdentity])—— 小智 AI 模式下一个字节都不多发;
     *  - 每轮最多一次([sttAbortSentThisTurn]):重复收尾(如连调两次 [endTurn])不重复发;
     *  - 幂等且不抛:没有连接([stopListening] 自带判空)、或调用方判定为小智模式,都只是安静返回。
     */
    private fun abortSttAfterTurn() {
        if (!sttAbortAfterEndTurn()) return
        if (sttAbortSentThisTurn) return
        sttAbortSentThisTurn = true
        Log.i(tag, "本轮识别结束(非小智网关):补发 listen.stop 中止云端 LLM/TTS")
        stopListening()
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
        resetReply()          // 本轮正文记账同理作废(打断后上一轮的文本不再上屏)
        // 直通侧的播放窗口也一并关闭(barge / 设备 turn_cancel:窗口在 tts.stop 之后是开着的,
        // 不关就会把本轮的音频继续推给设备、并让设备一直停在播放态)。
        ttsObserver?.onTurnStart()
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
        resetReply()
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
        // 在途的凭据刷新一并取消(服务已停,刷完也没人用;OTA 请求本身有硬超时兜底)。
        refreshScope.cancel()
        // 在途的空闲结算一并取消(服务已停,结算也没人用)。
        replyScope.cancel()
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
        // 鉴权链路的「可刷新」标记随连接一起作废:它描述的是**当前那条** socket。
        linkAuthRefreshable = false
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
            unavailableReason = XiaozhiIdentity.NO_DEVICE_REASON
            Log.w(tag, "小智 Device-Id 未就绪(小智网关需先连接设备),跳过建链")
            onReady(false)
            return
        }
        // 鉴权只有一处决策([XiaozhiCredentialGate]):小智 AI 必须用**绑定得到的凭据**;
        // 没有凭据就不建链(绝不用占位 token 硬撞),原因写进 unavailableReason 给上层文案。
        val auth = resolveAuth(deviceId)
        if (auth is XiaozhiLinkAuth.Unavailable) {
            unavailableReason = auth.reason
            Log.w(tag, "小智识别通道不可用,本轮不建链:${auth.reason}")
            onReady(false)
            return
        }
        unavailableReason = null
        val ok = auth as XiaozhiLinkAuth.Ok
        // 兜底刷新(策略①):token 的过期时间在**加密 payload** 里,App 读不出 `exp`,只能按「存了多久」
        // 保守判断 —— 落盘超过 [XiaozhiCredentialRefresh.STALE_AFTER_MS] 的凭据在建链**之前**先换一份。
        if (shouldRefreshBeforeConnect(ok, deviceId)) {
            startCredentialRefresh(deviceId, "凭据落盘时间偏旧(超过兜底阈值)", onReady)
            return
        }
        linkAuthRefreshable = ok.refreshable
        logLinkHeaders("识别通道建链", deviceId, ok)
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
                ws = client.newWebSocket(buildRequest(deviceId, ok), listener())
            }
        }
        if (alreadyWarm) {
            Log.i(tag, "已有可用热连接,跳过握手")
            onReady(true)
        }
    }

    /**
     * 本次建链的鉴权:**唯一**决策点 [XiaozhiCredentialGate];未注入 provider 时用构造参数(单测/直连会话)。
     */
    private fun resolveAuth(deviceId: String): XiaozhiLinkAuth =
        linkAuthProvider?.invoke(deviceId)
            ?: XiaozhiLinkAuth.Ok(serverUrl, token.ifBlank { XiaozhiSettings.ANONYMOUS_PLACEHOLDER_TOKEN })

    /**
     * 建链前是否要先刷新凭据(策略①:保守兜底)。
     *
     * 三个条件缺一不可:这条链路用绑定凭据([XiaozhiLinkAuth.Ok.refreshable])、
     * 本次尝试还没刷过([authRefreshed])、凭据落盘时间已超过阈值(或未知)。
     */
    private fun shouldRefreshBeforeConnect(auth: XiaozhiLinkAuth.Ok, deviceId: String): Boolean {
        val source = credentialRefresh ?: return false
        if (!XiaozhiCredentialRefresh.shouldRefreshOnRejection(auth.refreshable, authRefreshed)) return false
        val savedAt = try {
            source.savedAtMs(deviceId)
        } catch (e: Exception) {
            Log.w(tag, "读取凭据落盘时间失败,按偏旧处理", e)
            null
        }
        return XiaozhiCredentialRefresh.isStale(savedAt, source.nowMs())
    }

    /**
     * 重新查 OTA 拿新凭据并落盘(异步),完成后用新凭据**重连一次**。
     *
     * 为什么占住「连接建立中」的槽位([connecting]/[onHelloCallback]):刷新期间别的调用方
     * ([startTurn]/[prewarm])不该另建一条 socket —— 按既有语义把回调放进同一个槽位(最新调用方胜出),
     * hello 回来时统一回调,于是「刷新 + 重连」对上层完全不可见。
     *
     * @param reason 触发原因(写日志,便于真机取证:是「被拒」还是「凭据偏旧」)
     * @param onReady 这次建链的等待者;刷新失败时以 false 收尾(可读原因进 [unavailableReason])
     */
    private fun startCredentialRefresh(deviceId: String, reason: String, onReady: (Boolean) -> Unit) {
        val source = credentialRefresh
        if (source == null || deviceId.isEmpty()) {
            onReady(false)
            return
        }
        synchronized(linkLock) {
            authRefreshed = true   // 同一次尝试最多一次(先占位再发请求,并发路径也不会重复刷)
            connecting = true
            onHelloCallback = onReady
        }
        Log.i(tag, "小智识别凭据需要刷新($reason) → 重新查询 OTA…")
        refreshScope.launch {
            val fresh = try {
                source.refresh(deviceId)
            } catch (e: Exception) {
                Log.w(tag, "重新查询 OTA 失败", e)
                null
            }
            if (fresh == null) {
                val cb = synchronized(linkLock) {
                    connecting = false
                    val c = onHelloCallback
                    onHelloCallback = null
                    c
                }
                unavailableReason = XiaozhiCredentialRefresh.REFRESH_FAILED_REASON
                Log.w(tag, "凭据刷新失败(OTA 未下发新凭据),本次不建链")
                cb?.invoke(false)
                return@launch
            }
            // 脱敏取证:token 只打长度与前 4 位,明文绝不进日志。
            Log.i(
                tag,
                "已取到新凭据:url=${fresh.url} token=${XiaozhiOtaRequest.describeSecret(fresh.token)}" +
                    " → 用新凭据重连一次",
            )
            reconnectAfterRefresh(deviceId)
        }
    }

    /**
     * 刷新完成后用**新凭据**重连:重新走一次 [resolveAuth](此时闸门读到的是刚落盘的凭据)。
     *
     * 与 [connectAndHello] 的区别:不新建等待槽位 —— 槽位从刷新开始就被占着(可能已被更新的调用方替换),
     * 这里只在「仍处在本次建立中」时把 socket 建起来,hello 回来统一回调。
     */
    private fun reconnectAfterRefresh(deviceId: String) {
        val auth = resolveAuth(deviceId)
        if (auth is XiaozhiLinkAuth.Unavailable) {
            val cb = synchronized(linkLock) {
                connecting = false
                val c = onHelloCallback
                onHelloCallback = null
                c
            }
            unavailableReason = auth.reason
            Log.w(tag, "刷新后仍取不到凭据,本次不建链:${auth.reason}")
            cb?.invoke(false)
            return
        }
        val ok = auth as XiaozhiLinkAuth.Ok
        unavailableReason = null
        linkAuthRefreshable = ok.refreshable
        logLinkHeaders("识别通道建链(凭据刷新后)", deviceId, ok)
        val created = synchronized(linkLock) {
            if (!connecting || ws != null) {
                false
            } else {
                ws = client.newWebSocket(buildRequest(deviceId, ok), listener())
                true
            }
        }
        if (!created) {
            // 期间已被放弃(release/onLinkDown/resetDeviceId)或已有连接:让等待者立刻结束,不留悬空回调。
            val cb = synchronized(linkLock) {
                val c = onHelloCallback
                onHelloCallback = null
                c
            }
            cb?.invoke(false)
        }
    }

    /**
     * @param deviceId 本次建链使用的 Device-Id(已由 [connectAndHello] 解析并校验非空)
     * @param auth 本次建链的鉴权([XiaozhiCredentialGate] 解析结果:绑定凭据或匿名占位)
     */
    private fun buildRequest(deviceId: String, auth: XiaozhiLinkAuth.Ok): Request {
        // Client-Id:**唯一**来源 [XiaozhiClientId](按 Device-Id 持久化),必须与 OTA 请求用的那个相同
        // —— 服务端按 (client_id, device_id) 签发/校验凭据(见 XiaozhiClientId 的类注释)。
        val clientId = clientIdFor(deviceId)
        val builder = Request.Builder()
            .url(auth.url)
            // 小智:必须先带 Device-Id/Client-Id/Protocol-Version 握手头 + 发 hello,否则立即 close
            // Device-Id 按当前网关类型解析(小智 AI=已连接设备真 MAC,与 OTA/绑定同一个值;其余网关=全零匿名)。
            // Authorization:小智 AI = OTA 下发的凭据(Bearer token,与官方固件同一规则);
            // 匿名通道 = 原有占位 token 原样(行为不变)。
            .addHeader("Protocol-Version", "1")
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
        auth.authorization?.takeIf { it.isNotEmpty() }
            ?.let { builder.addHeader("Authorization", it) }
        return builder.build()
    }

    /**
     * 把**本次建链实际用到的握手头**打一行(值脱敏):`Device-Id`/`Client-Id`/`Authorization` 长度。
     *
     * 为什么要单成一行:真机上「OTA 与 WS 的 Client-Id 是不是同一个」只能靠日志比对 —— 所以这一行
     * 与 OTA 那一行([XiaozhiActivator.postOta])用**同一套**脱敏描述([XiaozhiClientId.describe]),
     * 前缀(前 8 位)+ 长度一致就说明是同一份;`Authorization` 只打长度(明文绝不进日志,
     * 且 `len = 7 + token长度`,可与 OTA 下发 token 的描述对得上)。
     */
    private fun logLinkHeaders(phase: String, deviceId: String, auth: XiaozhiLinkAuth.Ok) {
        Log.i(
            tag,
            "$phase:url=${auth.url} Device-Id=$deviceId" +
                " Client-Id=${XiaozhiClientId.describe(clientIdFor(deviceId))}" +
                " Authorization=${if (auth.authorization.isNullOrEmpty()) "无" else "len=${auth.authorization.length}"}",
        )
    }

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
            // 逐跳取证（节流）：「收到了帧」与「帧交给了谁」是两件事 —— 真机上设备侧 TTS 计数为 0 时，
            // 这一行（观察者 有/无）就能区分「会话没分流」与「直通门/窗口把帧拦了」。
            val observer = ttsObserver
            val index = ++ttsFramesThisTurn
            if (XiaozhiFrameLog.shouldLog(index)) {
                Log.d(
                    tag,
                    "小智下行音频帧 #$index ${bytes.size}B(下行 ${ttsRateKhz}kHz/${ttsFrameMs}ms)" +
                        " → 观察者(${if (observer != null) "有" else "无"})" +
                        " 构造回调(${if (onTtsAudio != null) "有" else "无"})",
                )
            }
            // 两条出口：构造参数（直接构造会话的调用方）与观察者（共用本会话的 TTS 直通）。
            onTtsAudio?.invoke(bytes.toByteArray(), ttsRateKhz, ttsFrameMs)
            observer?.onTtsAudio(bytes.toByteArray(), ttsRateKhz, ttsFrameMs)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 升级失败(非 101)也走这里:HTTP 状态码只在 response 里,异常信息在 t 里。
            Log.e(
                tag,
                "小智 WS 失败 HTTP=${response?.code ?: "无"} ${response?.message ?: ""}" +
                    " 异常=${t.javaClass.simpleName}: ${t.message ?: "(无异常信息)"}",
            )
            handleSocketDown(webSocket, response?.code, null, "建链被拒 HTTP=${response?.code ?: "无"}")
        }

        /**
         * 对端主动关闭:这是**最早**能拿到关闭码的时机。
         *
         * 为什么不能只靠 [onClosed]:实测在假服务端/部分真实服务端下,对端发完关闭帧
         * 并不立刻结束 TCP,onClosed 可能很久才到(甚至不到);鉴权类关闭码必须在这里就处理,
         * 否则「凭据过期 → 自动换新」就永远不会触发。
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(tag, "小智 WS 收到对端关闭 code=$code reason=${describeReason(reason)}")
            handleSocketDown(webSocket, null, code, "对端关闭 code=$code reason=${describeReason(reason)}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(tag, "小智 WS 关闭 code=$code reason=${describeReason(reason)}")
            // 与 [onClosing] 同一套收尾;幂等(严格按 socket 身份判定,已处理过的不再重复)。
            handleSocketDown(webSocket, null, code, "连接被关闭 code=$code reason=${describeReason(reason)}")
        }
    }

    /**
     * 关闭 reason 的日志形态:空 reason **明确写成 `(空)`**。
     *
     * 为什么不能直接拼 `$reason`:真机上「1005 无 reason」正是关键证据(1005 = 对端没给关闭码,
     * 服务端多半是被动断开/未按协议关闭);直接拼会让日志看起来像是忘了打这个字段。
     */
    private fun describeReason(reason: String): String =
        if (reason.isBlank()) "(空)" else "\"$reason\""

    /**
     * 当前 socket 失败/被关闭时的**统一收尾**([onFailure]/[onClosing]/[onClosed] 共用)。
     *
     * 三件事做全,顺序不能变:
     *  1. **先按 socket 身份判定**:只有仍是当前连接的才处理(旧 socket 的迟到回调不能影响新连接);
     *     同时把热连接状态/等待者摘干净,并把「这条链路的凭据可刷新」标记跟着作废;
     *  2. **鉴权失败 → 刷新一次**:升级 401/403、或对端以鉴权类码关闭(1008/4001…)
     *     都按凭据过期处理 —— 重查 OTA 换新凭据后用新凭据重连一次(等待者交给刷新流程统一回调,
     *     用户侧因此无感)。只对**绑定凭据**链路([XiaozhiLinkAuth.Ok.refreshable])且
     *     同一次尝试没刷过([authRefreshed])时才刷,匿名通道行为与改动前逐字一致;
     *  3. **其余情况**:等待者立刻收到 false(否则握手没回来就断链时它会一直等下去);
     *     若鉴权失败且已经刷过,则给可读原因([XiaozhiCredentialRefresh.STILL_REJECTED_REASON]),
     *     绝不为一份不该成功的凭据无限重连。
     *
     * @param httpCode 非 101 升级响应的状态码(来自 `onFailure` 的 response)
     * @param wsCloseCode 对端关闭帧的码(来自 `onClosing`/`onClosed`)
     * @param label 写日志/刷新的原因描述(区分「被拒」与「被关闭」)
     */
    private fun handleSocketDown(
        socket: WebSocket,
        httpCode: Int?,
        wsCloseCode: Int?,
        label: String,
    ) {
        val rejected = XiaozhiCredentialRefresh.isAuthRejection(httpCode, wsCloseCode)
        val cb: ((Boolean) -> Unit)?
        val refreshable: Boolean
        synchronized(linkLock) {
            if (ws !== socket) return   // 旧 socket 的迟到回调:丢掉
            connecting = false
            warmReady = false
            ws = null
            cancelWarmIdleTimer()
            listening = false
            refreshable = linkAuthRefreshable
            linkAuthRefreshable = false
            cb = onHelloCallback
            onHelloCallback = null
        }
        if (rejected && XiaozhiCredentialRefresh.shouldRefreshOnRejection(refreshable, authRefreshed)) {
            Log.w(tag, "小智识别通道鉴权失败($label):按凭据过期处理,重查 OTA 换新凭据后重连一次")
            startCredentialRefresh(deviceIdProvider().trim(), "鉴权失败($label)", cb ?: {})
            return
        }
        if (rejected && refreshable) {
            unavailableReason = XiaozhiCredentialRefresh.STILL_REJECTED_REASON
            Log.w(tag, "凭据刷新后仍鉴权失败($label),本次放弃不再重连")
        }
        cb?.invoke(false)
    }

    private fun sendHello(webSocket: WebSocket) {
        val hello = JsonObject().apply {
            addProperty("type", "hello")
            addProperty("version", 1)
            // 能力声明:官方固件恒发 mcp:true,但本项目**不实现 MCP**——真机 A/B 证明声明它后
            // 服务端会改走带 MCP 工具调用的应答路径(日志里出现 `% get_weather...` 模板),
            // 那条路径**不下发 TTS 音频**(App 侧 0 帧),而修 1005 的真正原因是 Device-Id 小写。
            // 因此**不声明** mcp:按"只做识别+LLM+TTS"的用法让服务端走普通应答路径。
            addProperty("transport", "websocket")
            add("audio_params", JsonObject().apply {
                addProperty("format", "opus")
                addProperty("sample_rate", 16000)   // 上行 ASR 用 16k;服务器下行 TTS 24k 不影响上行
                addProperty("channels", 1)
                addProperty("frame_duration", 60)
            })
        }
        // 取证:**发出的报文原文**进日志(便于事后判断「是不是 hello 的内容被拒」),
        // 只把密钥类字段脱敏 —— hello 本身没有凭据字段,这条规则是为以后加字段兜底。
        Log.i(tag, "发送客户端 hello: ${XiaozhiOtaRequest.describeOutgoing(hello)}")
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
                // 小智的回复/TTS:先喂给**正文装配器**([replyText]),由它按**逐段**规则经
                // [emitReply] 交出正文(构造参数的 onLlm + [llmObserver] 两条出口);
                // **按段交付**:每来一句就把**这一句自己的文本**交一次(不累计 → 屏幕上只有 A/B/C,
                // 不会多出 AB/ABC),首段交付即上屏(直通侧据此在首段上屏时开播),同一句在
                // `sentence_end` 变完整只更新本段,`stop` 是最终结算点且只补缺。
                // TTS 状态另外分流给直通观察者([XiaozhiTtsObserver],音频那一路)。
                "llm" -> {
                    // 取证:小智的回复报文**全字段**(键名 + 值)进日志(与既有脱敏同一套规则,
                    // `emotion` 这类非密钥字段**照打**)。为什么必须打全:真机上出现「上屏的回复只有一个
                    // emoji(设备屏是方块)」时,只有看到报文里到底哪个字段是表情才能定论 —— 本仓库任何
                    // 地方都不读 `emotion`,所以正文只可能来自 `text`,但服务端是否把表情也写进 `text`
                    // (或把表情当成首片 `text` 推)必须靠这行日志确证。
                    Log.i(tag, "收到小智 llm 报文(全字段): ${XiaozhiOtaRequest.describeOutgoing(obj)}")
                    val reply = obj.stringOrNull("text").orEmpty()
                    feedReply { replyText.onLlm(reply) }
                }
                "tts" -> {
                    val state = obj.stringOrNull("state").orEmpty()
                    val sentence = obj.stringOrNull("text").orEmpty()
                    // 取证:**每条 tts 报文的完整文本**(不节流、不截断;句级文本本来就不长)。
                    // 为什么必须打全:真机「上屏的正文比小智实际说的短/漏句」只能靠「服务端发来了哪几句、
                    // 什么顺序、有没有 stop」与结算日志逐条对照才能定案(截断/节流会正好把要看的证据吃掉)。
                    Log.i(tag, "收到小智 tts[$state] 文本(${sentence.length} 字): $sentence")
                    onTtsState?.invoke(state, sentence)
                    // 正文装配优先喂:`tts.state=stop` 是文本侧的**最终结算点**(只补还没上屏的那一段;
                    // 各段都已上屏则不重复交付);直通侧的段机也在这里推进。
                    // 两阶段的**顺序保证**不靠这里的先后:直通侧要等服务侧交来本段字幕
                    // (`XiaozhiTtsRelay.onReplySubtitleReady`),由它在本段轮到的那一刻回写 BLE
                    // 串行写队列(首段立即),所以首帧一定晚于字幕帧(见 `docs/design/xiaozhi-ai-gateway.md` §4.8)。
                    feedReply { replyText.onTtsState(state, sentence) }
                    ttsObserver?.onTtsState(state, sentence)
                }
                "error" -> Log.w(tag, "小智端错误: ${obj.toString()}")
                else -> Unit
            }
        } catch (e: Exception) {
            Log.e(tag, "解析小智消息失败", e)
        }
    }

    /**
     * 喂一条**正文来源**事件(`llm` / `tts`)给 [replyText],并按它给出的时长安排一次空闲结算。
     *
     * 为什么事件与定时器要放在同一个锁里:两者必须是一个原子动作 —— 否则「A 算完时长、B 先挂了新定时器」
     * 会让旧时长把新一轮提前结算(把回复截断)。代次([replyIdleToken])则保证已经挂上的旧定时器醒来后
     * 一律放弃:新的 tts 报文一到达,「纯 llm 兜底」的长窗口立刻作废。
     */
    private fun feedReply(event: () -> Unit) {
        val grace: Long?
        val token: Long
        synchronized(replyLock) {
            event()
            grace = replyText.pendingIdleGraceMs()
            token = ++replyIdleToken
        }
        if (grace == null) return
        replyScope.launch {
            delay(grace)
            synchronized(replyLock) {
                if (token != replyIdleToken) return@synchronized
                replyText.onIdle()
            }
        }
    }

    /**
     * 作废本轮正文记账与在途的空闲结算([startTurn] / 打断 / 链路断开时调用,幂等)。
     *
     * 为什么必须连定时器一起作废:迟到的结算绝不能被上一轮的文本带进新一轮(那会让设备屏上出现
     * 一个与当前问题无关的气泡)。
     */
    private fun resetReply() {
        synchronized(replyLock) {
            replyIdleToken++
            replyText.onTurnStart()
        }
    }

    /**
     * 本轮**逐段正文**的唯一出口:非空 = 这一段自己的文本,空串 = 本轮没有可上屏正文(调用方据此给可读原因)。
     *
     * 三条出口与改动前一致(顺序有语义):
     *  0. **先**把这一段正文告诉 TTS 直通观察者([XiaozhiTtsObserver.onReplyBody])—— 直通侧只认
     *     「上屏文本 == 已交付的某段正文」的信号,所以必须在正文交给网关/流水线之前对好号,
     *     否则那一条 `TEXT('A')` 发出来时直通侧还不知道本段正文是什么;
     *  1. 构造参数的 [onLlm](直连会话的调用方);
     *  2. [llmObserver](共用本会话的「小智 AI 网关」)。
     * **非正文字段(`emotion` 等)永不参与** —— 它根本不会进 [replyText]。
     */
    private fun emitReply(outcome: XiaozhiReplyOutcome) {
        val body = outcome.body
        lastReplyDetail = outcome.detail
        // 本次结算没产生新正文(重复结算 / 各段都已上屏 / 工具调用静默期挂起):只记日志,绝不上屏、也不
        // 通知观察者 —— 否则设备屏上会出现重复气泡(旧实现发累计全文就是这样多出 AB、ABC 的)。
        if (!outcome.changed) {
            Log.i(
                tag,
                "小智本轮正文未产生新正文(触发者=${outcome.triggerLabel}):${outcome.detail}" +
                    if (body.isNotEmpty()) " 当前正文=$body" else "",
            )
            return
        }
        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (body.isBlank()) {
            Log.w(
                tag,
                "小智本轮没有可上屏正文(触发者=${outcome.triggerLabel},${outcome.detail}):按失败语义给可读原因",
            )
        } else {
            // 真机对照用:这一行就是「设备屏上应该出现什么」,与 sendTextFrame 的「全文=…」逐字对得上。
            // 「第 N 段字幕上屏(本段 L 字)」与直通侧的「第 N 段已声明」同一套编号(**按段交付**:
            // 每次上屏只发这一句自己的文本,不累计 → 屏幕上只有 A/B/C 三条,不会多出 AB/ABC)。
            Log.i(
                tag,
                "第 ${outcome.deliveryIndex} 段字幕上屏(本段 ${body.length} 字," +
                    "触发者=${outcome.triggerLabel}): ${outcome.detail} | " +
                    "${body.length} 字/${bytes} 字节 | 全文=«$body»",
            )
        }
        ttsObserver?.onReplyBody(body)
        onLlm?.invoke(body)
        llmObserver?.invoke(body)
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
            // 这次建链真的成功(凭据被云端接受了):允许后续的建链尝试再刷一次(每轮仍最多一次)。
            authRefreshed = false
            cb = onHelloCallback
            onHelloCallback = null
        }
        Log.i(tag, "小智 hello 回包: ${obj.toString()}")
        // 纯预热连接(没有轮次在跑)挂上闲置超时兜底;轮内连接由 endTurn 的 keepWarm 挂。
        if (!turnRunning) armWarmIdleTimer()
        cb?.invoke(true)
    }
}
