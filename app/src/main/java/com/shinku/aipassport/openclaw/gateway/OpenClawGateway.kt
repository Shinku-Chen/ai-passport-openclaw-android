package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OpenClaw 网关配置(WebSocket + Ed25519 设备鉴权)。
 *
 * 显式配置对象的意义:设置页保存前的连接校验要用【输入框里的草稿值】,
 * 不能先把草稿写进 SharedPreferences 再回滚,所以适配器改为接受这个配置对象
 * (原 `OpenClawGateway(context, settings)` 构造入口保留为兼容重载)。
 *
 * 字段语义:
 *  - [token]:运行时 secret,只存本机,绝不写进提交的代码
 *  - [wsPath]:对话 WebSocket 路径,空串时退回默认值
 *  - [allowInsecureTls]:仅调试用,允许 wss 自签证书(默认关闭;与 Hermes/OpenAI 通道同款开关,
 *    只作用于本通道,开启后信任所有证书并跳过主机名校验)
 */
data class OpenClawConfig(
    val host: String,
    val port: String,
    val useTls: Boolean = true,
    val token: String = "",
    val wsPath: String = GatewaySettings.DEFAULT_WS_PATH,
    val allowInsecureTls: Boolean = false,
    /** 等待网关最终回复的上限(秒)。Agent 跑工具常要几十秒甚至更久,默认 180s。 */
    val replyTimeoutSeconds: Long = GatewayConfig.DEFAULT_REPLY_TIMEOUT_SECONDS,
    /**
     * 会话名:`sessionKey = agent:main:<sessionName>`。
     *
     * 为什么不再用 main:网关的 `agent:main:main` 是 PC 控制台/微信/飞书共用的那个主会话,
     * 对讲机混进去会把别人的历史(含工具输出)一起带上——实测单次提示 23 万 tokens/359 条消息,
     * 每个回合要几十秒,而且回复会串到别的渠道的话题上。换成独立会话后上下文只有本机的对话。
     */
    val sessionName: String = GatewayConfig.DEFAULT_SESSION_NAME,
) {
    /** Host/端口是否已填。 */
    fun isConfigured(): Boolean = host.isNotBlank() && port.isNotBlank()

    /** 归一化后的会话名(空/非法时退回默认;只留字母数字与 -_ ,避免拼出奇怪的 key)。 */
    fun normalizedSessionName(): String {
        val s = sessionName.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return s.ifBlank { GatewayConfig.DEFAULT_SESSION_NAME }
    }

    /** 实际使用的 sessionKey,形如 `agent:main:passport`。 */
    val sessionKey: String get() = "agent:main:${normalizedSessionName()}"

    /** 归一化后的回复等待上限(越界值夹紧到 15..900 秒)。 */
    fun replyTimeoutMs(): Long =
        replyTimeoutSeconds.coerceIn(
            GatewayConfig.MIN_REPLY_TIMEOUT_SECONDS,
            GatewayConfig.MAX_REPLY_TIMEOUT_SECONDS,
        ) * 1000

    /** REST 基址,形如 `http://host:8035`。 */
    val baseUrl: String get() = "http${if (useTls) "s" else ""}://$host:$port"

    companion object {
        /** 从持久化设置读。 */
        fun of(s: GatewaySettings): OpenClawConfig =
            OpenClawConfig(
                s.host,
                s.port,
                s.useTls,
                s.token,
                s.wsPath,
                s.openclawAllowInsecureTls,
                s.openclawReplyTimeoutSeconds,
                s.openclawSessionName,
            )

        /**
         * 从界面原始值构造草稿:wsPath 空时退回默认、不以 `/` 开头时补上,
         * 与 [GatewaySettings.wsPath] 的归一化规则保持一致。
         */
        fun of(
            host: String,
            port: String,
            useTls: Boolean,
            token: String,
            wsPath: String,
            allowInsecureTls: Boolean = false,
            replyTimeoutSeconds: Long = GatewayConfig.DEFAULT_REPLY_TIMEOUT_SECONDS,
            sessionName: String = GatewayConfig.DEFAULT_SESSION_NAME,
        ): OpenClawConfig {
            val path = wsPath.trim().ifBlank { GatewaySettings.DEFAULT_WS_PATH }
                .let { if (it.startsWith("/")) it else "/$it" }
            return OpenClawConfig(
                host.trim(),
                port.trim(),
                useTls,
                token.trim(),
                path,
                allowInsecureTls,
                replyTimeoutSeconds.coerceIn(
                    GatewayConfig.MIN_REPLY_TIMEOUT_SECONDS,
                    GatewayConfig.MAX_REPLY_TIMEOUT_SECONDS,
                ),
                sessionName.trim().ifBlank { GatewayConfig.DEFAULT_SESSION_NAME },
            )
        }
    }
}

/**
 * OpenClaw 网关 WebSocket 客户端(文本输入 → 文本回复)。
 *
 * 实时协议(已对线上网关逐帧验证):
 *  1. 连接 wss://<host>:<port><wsPath>?sessionKey=main(默认 /message/messages/ws,可配置)
 *     (带 Origin 头 = 网关自身 origin,否则 CONTROL_UI_ORIGIN_NOT_ALLOWED)
 *  2. 服务端发 connect.challenge{nonce};客户端回 connect:
 *     {type:"req", id:<uuid>, method:"connect", params:{minProtocol:4, maxProtocol:4,
 *       client:{id:"openclaw-android",...}, role:"operator", scopes:["operator.write"],
 *       device:{id, publicKey, signature, signedAt, nonce}, caps:["tool-events"],
 *       auth:{token, password}, userAgent, locale}}
 *     - client.id 必须为网关白名单值(openclaw-android 合法)
 *     - device 必须带 publicKey + ed25519 签名;未知设备触发配对(需主机 openclaw devices approve)
 *  3. connect 成功 → res 携带 auth.deviceToken(持久化,之后可免签复用) → 可发 chat.send
 *  4. chat.send{sessionKey, message, idempotencyKey, deliver:false, agentId:"main"} → res 带 runId
 *  5. 服务端以 chat 事件流式返回 assistant 文本(delta/final),收齐 final 即完整回复
 *
 * 帧格式:客户端请求 {type:"req", id:<uuid-string>, method, params};服务端回复 {type:"res", id, ok, payload|error}
 *
 * token 来自 App 内设置(GatewaySettings)。鉴权/配对/超时失败返回 null,流水线静默降级。
 *
 * 本类是 [GatewayAdapter] 的 OpenClaw 实现:协议行为(Ed25519 设备身份、sessionKey、
 * chat.send、事件流收集)与改造前完全一致,只是收敛到接口并暴露 [lastError]。
 * 构造入口接受显式 [OpenClawConfig],便于设置页用草稿值先校验再决定是否落盘。
 *
 * 实例复用:运行时请用 [OpenClawGatewayRegistry.acquire] 而不是直接 new ——
 * 全进程只维持一条对话 WS,避免探针/概览页/服务各建一条连接互相顶掉(见注册表注释)。
 * 本对象的 [close] 是引用计数式的:计数归零才真正断开;[shutdown] 才无条件断开。
 */
class OpenClawGateway(
    context: Context,
    private val config: OpenClawConfig,
    private val onStatus: (String) -> Unit = {},
) : GatewayAdapter {

    /** 兼容原调用:直接用设置里的持久化配置(语音桥服务/概览页)。 */
    constructor(
        context: Context,
        settings: GatewaySettings,
        onStatus: (String) -> Unit = {},
    ) : this(context, OpenClawConfig.of(settings), onStatus)

    private val tag = "OpenClawGateway"
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = DeviceIdentity(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)          // WS 长连,不因空闲断开
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .apply {
            // 仅调试:wss 自签证书开关(默认关闭)。与 Hermes/自定义 OpenAI 通道共用同一实现,
            // 只作用于本通道;开启即信任所有证书并跳过主机名校验,公网环境必须关闭。
            OpenAiCompat.applyInsecureTlsIfNeeded(this, config.allowInsecureTls)
        }
        .build()

    /** chat.send 会话 key,格式 agent:<agentId>:<rest>(网关卡据此解析 agentId)。
     *  用 agent:main:main —— 网关仅识别 main agent(health 显示 defaultAgentId=main,无 passport agent)。 */
    private val AgentSessionKey = "agent:main:main"

    private val pendingReqs = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()

    /** 最近一次 RPC 错误信息(供控制台分区显示"不可用/需权限"原因)。 */
    @Volatile
    private var lastRpcError: String? = null

    /** 供设置页/状态面板读取的可读错误描述;成功一轮对话后清空。 */
    override val lastError: String?
        get() = lastRpcError

    /** OpenClaw 支持「用 `chat.history` 补正正文」:状态话术 body 可以先缓发等历史判定。 */
    override val supportsBodyCorrection: Boolean get() = true

    /**
     * 是否「等待网关授权」(网关回 `NOT_PAIRED: … device is not approved yet`)。
     *
     * 这【不是】致命错误:网关对每台设备做 ed25519 设备配对,新设备要人在 OpenClaw 控制台批准。
     * 置位后状态词仍是 connecting([mapRpcError] 负责),detail 里写明 deviceId 与「去哪里批准」;
     * 重连/退避循环照常运行,批准后下一次 connect 成功即自动恢复([awaitingPairingState] 清零,
     * 上层走既有 publishGatewayStatus 路径播报「网关已恢复连接」)。
     */
    override val isAwaitingPairing: Boolean
        get() = awaitingPairingState

    @Volatile
    private var ws: WebSocket? = null

    /** 是否处于「等待网关授权」:只由配对类错误置位,只在 connect 成功时清零。 */
    @Volatile
    private var awaitingPairingState: Boolean = false

    /** 鉴权完成(connect.res 返回 ok) */
    @Volatile
    private var connected = false

    /** 当前进行中的一次对话(串行一次一条) */
    @Volatile
    private var activeCollector: ReplyCollector? = null

    /** 服务端 connect.challenge 下发的 nonce,用于签名 */
    @Volatile
    private var pendingNonce: String? = null

    /** 上一次上报的进度阶段与时刻(节流用)。 */
    @Volatile
    private var lastProgressDetail: String? = null

    @Volatile
    private var lastProgressAtMs: Long = 0L

    private val lock = Any()

    /** 引用计数:注册表复用同一实例时递增,[close] 只在归零时真正断开。 */
    private val refs = java.util.concurrent.atomic.AtomicInteger(1)

    /**
     * 加一次引用(注册表复用同一实例时调用)。
     * 对应一次 [close];只要还有使用方(例:前台服务在跑),socket 就不会被 UI 页面销毁带走。
     */
    fun retain() {
        refs.incrementAndGet()
    }

    /**
     * 发一段文本,返回网关的【第一条】回复文本;失败/超时/未配对返回 null。
     *
     * 只想要单条回复的旧调用方(设置页校验等)用它;需要一轮**全部**回复的调用方用 [chatMulti]。
     * 连接中途断开不再直接判失败:断开只标记「连接中断」,在剩余时限内重连并继续等同一个 runId;
     * 到时限仍无结果时才返回 null,并把可读原因写进 [lastError]。
     */
    override suspend fun chat(text: String): String? {
        val reply = chatMulti(text, null, null)
        return when {
            reply.messages.isNotEmpty() -> reply.messages.first()
            reply.error != null -> null
            // 空 final:网关确实结束了但没给正文 —— 真正的「空回复」,不是失败
            else -> ""
        }
    }

    /**
     * 发一段文本,返回本轮【全部】回复消息。
     *
     * 真机现象(本方法的由来):一轮 run 里网关可能先后推多条 assistant 消息(答案 + 后续状态消息),
     * 只保留最后一条会把答案冲掉。这里把 [ReplyCollector] 分段收集到的整轮消息都返回,
     * 由调用方逐条上屏/入对话列表。
     *
     * 失败/超时/被打断时同样返回**已收集到的部分消息**(可能为空),原因在 [ChatReply.error]。
     *
     * 终局后宽限窗(见 [POST_TERMINAL_GRACE_MS]):`awaitOutcome()` 返回后 collector 不会被立刻回收,
     * 而是继续挂在 [activeCollector] 上把迟到帧收进 raw,并通过 [onRawUpdate] 把**新增**条目
     * 增量回调给 App。**body 不等宽限窗** —— 这里拿到结局就立即构造并返回 [ChatReply],
     * 设备屏与 TTS 的时延不受影响。宽限窗同时还用来做一次 `chat.history` 正文补正
     * (见 [onBodyCorrection]):流式 `chat delta/final` 只推本轮**最后一条** assistant 消息,
     * 真正的答案常常只存在于网关历史里。
     *
     * @param onRawUpdate 迟到的 raw 增量(含宽限窗内的帧,以及从 `chat.history` 补进来的历史条目)
     * @param onBodyCorrection 历史补正回调:当 `chat.history` 里本轮的正文与流式 body **不同**时,
     *   用它回调**该补发的正文**(调用方负责再发一帧 `'A'` 给设备并在 App 里以正常气泡展示)。
     *   与 [onRawUpdate] 同样的线程约定:可能在网关线程执行,必须线程安全、不碰 UI/Context;
     *   相同/查不到时**不会**回调(不重复上屏、不重复朗读)。
     */
    override suspend fun chatMulti(
        text: String,
        onRawUpdate: ((List<RawEntry>) -> Unit)?,
        onBodyCorrection: ((String) -> Unit)?,
    ): ChatReply = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext ChatReply(emptyList(), failReason())
        // 幂等键在本轮内不变:重连后重新订阅要用同一个键,网关才不会把同一条消息再执行一遍
        val idempotency = UUID.randomUUID().toString()
        val runId = sendChat(text, idempotency) ?: return@withContext ChatReply(emptyList(), failReason())
        val collector = ReplyCollector(
            runId = runId,
            sessionKey = config.sessionKey,
            userText = text,
            idempotencyKey = idempotency,
            deadlineAtMs = System.currentTimeMillis() + config.replyTimeoutMs(),
            scope = scope,
        )
        activeCollector = collector
        Log.i(tag, "run 开始: runId=$runId 等待上限=${config.replyTimeoutMs() / 1000}s")
        val outcome = try {
            collector.awaitOutcome()
        } catch (e: Throwable) {
            // 异常退出(含协程取消)时立即回收,不留悬挂的在途收集器
            if (activeCollector === collector) activeCollector = null
            throw e
        }
        // 终局(正常结束 / 空 final):先取 raw 首批快照并在同一个锁内装配增量回调,再构造 body。
        // 顺序很关键:快照必须在这里取,否则宽限窗内新到的条目会「既进首批又走回调」(重复)
        // 或「只进 raw 不回 App」(丢失)。非终局结局(超时/中断/被打断)不保留 collector。
        val terminalOutcome = outcome is ReplyOutcome.Reply || outcome is ReplyOutcome.EmptyFinal
        val rawSnapshot = if (terminalOutcome) {
            collector.snapshotRawAndArmUpdate(onRawUpdate)
        } else {
            collector.rawEntries
        }
        val reply = when (outcome) {
            is ReplyOutcome.Reply -> {
                lastRpcError = null
                Log.i(
                    tag,
                    "run 结束: runId=${collector.runId} 收到 ${outcome.messages.size} 条回复," +
                        "共 ${outcome.messages.sumOf { it.length }} 字," +
                        "raw ${collector.rawEntryCount} 条",
                )
                ChatReply(outcome.messages, raw = rawSnapshot)
            }

            is ReplyOutcome.EmptyFinal -> {
                // 拿到 final 且文本为空 —— 这才是真正的「空回复」,不是失败
                lastRpcError = null
                Log.w(tag, "run 结束: runId=${collector.runId} 收到空 final")
                ChatReply(emptyList(), raw = rawSnapshot)
            }

            is ReplyOutcome.Interrupted -> {
                lastRpcError = outcome.reason
                Log.w(
                    tag,
                    "run 中断: runId=${collector.runId} ${outcome.reason}" +
                        "(断连 ${collector.interruptCount} 次,已收 ${outcome.messages.size} 条)",
                )
                ChatReply(outcome.messages, outcome.reason, rawSnapshot)
            }

            is ReplyOutcome.Timeout -> {
                lastRpcError = outcome.reason
                Log.w(
                    tag,
                    "run 超时: runId=${collector.runId} ${outcome.reason}" +
                        "(已收 ${outcome.messages.size} 条)",
                )
                ChatReply(outcome.messages, outcome.reason, rawSnapshot)
            }

            is ReplyOutcome.Cancelled -> {
                Log.i(tag, "run 被打断(barge): runId=${collector.runId} 已收 ${outcome.messages.size} 条")
                ChatReply(outcome.messages, raw = rawSnapshot)
            }
        }
        // 到这里 body 已经构造完毕(本次调用立即返回):宽限窗只延期**回收 collector**,
        // 调用方/设备屏/TTS 绝不等它。非终局结局立即回收,不留旧轮收集器。
        if (terminalOutcome) {
            holdCollectorForGrace(collector, text, reply.messages, onRawUpdate, onBodyCorrection)
        } else if (activeCollector === collector) {
            activeCollector = null
        }
        reply
    }

    /**
     * 终局后:在宽限窗内做一次 `chat.history` 正文补正,窗口走完再回收 collector。
     *
     * 为什么需要(真机抓帧):一轮 run 里有多条 assistant 文本消息(查天气 → 工具结果 → 答案 →
     * MemOS 工具 → **状态话术**),而流式 `chat delta/final` 只推**最后一条**(= 状态话术),
     * 真正的答案只存在于网关历史里;设备屏与 TTS 因此拿不到答案。
     *
     * 时机:**与宽限窗共用这 [POST_TERMINAL_GRACE_MS] 毫秒**,不额外增加设备侧时延 ——
     * body 早已返回并下发,补正只是随后**再补一帧** `'A'`(见 [onBodyCorrection])。
     * 查询失败/超时/返回空一律只记日志,**行为退回现状**。
     */
    private fun holdCollectorForGrace(
        collector: ReplyCollector,
        userText: String,
        streamedBody: List<String>,
        onRawUpdate: ((List<RawEntry>) -> Unit)?,
        onBodyCorrection: ((String) -> Unit)?,
    ) {
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                applyHistoryCorrection(collector, userText, streamedBody, onRawUpdate, onBodyCorrection)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被取消(服务关闭 / 配置重载):立即收手,不再补正
                return@launch
            } catch (e: Throwable) {
                // 补正是体验增强:任何异常都不得影响本轮收尾与下一轮
                Log.d(tag, "历史补正失败(忽略): ${e.message}")
            }
            val left = POST_TERMINAL_GRACE_MS - (System.currentTimeMillis() - startedAt)
            if (left > 0) kotlinx.coroutines.delay(left)
            if (activeCollector === collector) activeCollector = null
        }
    }

    /**
     * 用 `chat.history` 补正本轮正文(判定全部在纯函数 [planHistoryCorrection] 里,便于 JVM 单测)。
     *
     * 行为:
     *  - 从历史里取**本轮**的 assistant 文本(以我们发送的用户文本为界,工具消息不进正文);
     *  - 排除状态话术、取最长的一条当正文;整轮只有状态话术 → 保留流式正文(不凭空造);
     *  - 与流式 body **不同**才回调 [onBodyCorrection](设备屏补一帧 `'A'` + App 正常气泡);
     *  - 历史条目作为 raw 补充交给 [onRawUpdate](与流式条目按文本去重,追加在流式条目之后)。
     *
     * 失败优雅:TIMEOUT/错误/空 payload 只记日志,`lastError` 原样恢复(补正查询不得污染对外错误)。
     */
    private suspend fun applyHistoryCorrection(
        collector: ReplyCollector,
        userText: String,
        streamedBody: List<String>,
        onRawUpdate: ((List<RawEntry>) -> Unit)?,
        onBodyCorrection: ((String) -> Unit)?,
    ) {
        // rpcQuery 会把 lastRpcError 清空/写成历史查询的错误:补正是内部动作,必须恢复原值
        val savedError = lastRpcError
        val result = try {
            // 与宽限窗共用时间:最多等这一窗,等不到就当没有历史(退回流式正文)
            withTimeoutOrNull(POST_TERMINAL_GRACE_MS) { rpcChatHistory() }
        } finally {
            lastRpcError = savedError
        }
        val payload = result?.takeIf { it.ok }?.payload?.takeIf { it.isNotBlank() }
        if (payload == null) {
            val reason = result?.error
            if (reason != null) {
                Log.w(tag, "chat.history 查询失败,跳过正文补正: $reason")
            } else {
                Log.d(tag, "chat.history 返回空,跳过正文补正")
            }
            return
        }
        val plan = planHistoryCorrection(payload, userText, streamedBody, collector.rawEntries)
        if (plan.rawEntries.isNotEmpty()) onRawUpdate?.invoke(plan.rawEntries)
        val send = plan.sendText
        if (send == null) {
            if (plan.correctBody == null) {
                Log.d(tag, "历史补正: 本轮历史没有非状态话术的正文,保留流式正文")
            } else {
                Log.d(tag, "历史补正: 历史正文与流式正文一致(${plan.correctBody.length} 字),不重复上屏")
            }
            return
        }
        if (onBodyCorrection == null) return
        Log.i(
            tag,
            "历史补正: 用 chat.history 的答案替换/补发正文(${send.length} 字," +
                " 流式 body ${streamedBody.sumOf { it.length }} 字)",
        )
        onBodyCorrection.invoke(send)
    }

    /** 可读失败原因(供 [ChatReply.error] 使用);无则 null。 */
    private fun failReason(): String? = lastRpcError?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 建链 + 完成 connect 鉴权;返回是否可用。
     * OpenClaw 的 chat 内部会惰性重连,因此本方法主要用于设置页「保存前校验」与启动预热/连接监控。
     */
    override suspend fun connect(): Boolean = ensureConnected()

    /** 网关是否已鉴权可对话。 */
    override fun isReady(): Boolean = connected

    /**
     * 打断当前在途的网关回复收集(barge:回复中用户再次按 PTT 说话)。
     * 取消旧 activeCollector(终结为 Cancelled,不是「空回复」),让旧轮 chat 尽快返回 null,
     * 释放单例 activeCollector,避免新一轮 chat.send 与之冲突导致回复错配/发送失败。
     */
    override fun interrupt() {
        activeCollector?.cancel()
        activeCollector = null
    }

    /** 设备是否已配对(有 deviceToken)。 */
    fun isPaired(): Boolean = identity.deviceToken.isNotBlank()

    // ---- 网关控制台 RPC 查询(实测可用/需权限的 method) ----

    data class RpcResult(val ok: Boolean, val payload: String?, val error: String?)

    /**
     * 通用 RPC 查询(connect 鉴权后调用):发 method,取 res.payload JSON 字符串。
     * 失败(scope 不足/不支持/超时)返回 ok=false,error 带原因,不抛异常。
     */
    suspend fun rpcQuery(method: String, params: Map<String, Any?> = emptyMap()): RpcResult =
        withContext(Dispatchers.IO) {
            if (!ensureConnected()) {
                return@withContext RpcResult(false, null, "网关未连接或鉴权失败")
            }
            val p = JsonObject()
            params.forEach { (k, v) ->
                when (v) {
                    null -> p.add(k, com.google.gson.JsonNull.INSTANCE)
                    is String -> p.addProperty(k, v)
                    is Number -> p.addProperty(k, v)
                    is Boolean -> p.addProperty(k, v)
                    is List<*> -> p.add(k, gson.toJsonTree(v))
                    else -> p.add(k, gson.toJsonTree(v))
                }
            }
            lastRpcError = null
            val reply = requestSync(method, p)
            if (reply != null) {
                RpcResult(true, reply.toString(), null)
            } else {
                RpcResult(false, null, lastRpcError ?: "网关未响应或超时")
            }
        }

    /** Agent 列表(实测 agent.identity.get → payload.agents)。 */
    suspend fun rpcAgents(): RpcResult = rpcQuery("agent.identity.get")

    /** 模型 + 命令(实测 health → payload.models / payload.commands)。 */
    suspend fun rpcModels(): RpcResult = rpcQuery("health")

    /** 系统信息/概览(实测 system.info)。 */
    suspend fun rpcOverview(): RpcResult = rpcQuery("system.info")

    /** 会话列表(实测 sessions.list)。 */
    suspend fun rpcSessions(): RpcResult = rpcQuery("sessions.list")

    /**
     * 本轮会话的历史(实测 `chat.history{sessionKey, limit}` → `{sessionKey, sessionId, messages:[…]}`)。
     * 一轮里的多条 assistant 文本消息(答案 + 状态话术)只有这里能看到 —— 流式帧只推最后一条。
     */
    suspend fun rpcChatHistory(limit: Int = CHAT_HISTORY_LIMIT): RpcResult =
        rpcQuery(RPC_CHAT_HISTORY, mapOf("sessionKey" to config.sessionKey, "limit" to limit))

    /** 定时任务(实测 cron.list → payload.worktrees;jobs 在 directory.list)。 */
    suspend fun rpcCron(): RpcResult = rpcQuery("cron.list")

    /** 目录/任务(实测 directory.list → payload.jobs)。 */
    suspend fun rpcDirectory(): RpcResult = rpcQuery("directory.list")

    /** 渠道列表(实测需 operator.admin,当前 token 可能无权限)。 */
    suspend fun rpcChannels(): RpcResult = rpcQuery("channels.list")

    /** 技能列表(实测需 operator.admin)。 */
    suspend fun rpcSkills(): RpcResult = rpcQuery("skills")

    /** 用量(实测需 operator.admin)。 */
    suspend fun rpcUsage(): RpcResult = rpcQuery("usage")

    /** 节点列表(实测需 operator.admin)。 */
    suspend fun rpcNodes(): RpcResult = rpcQuery("nodes.list")

    /** 设备列表(实测需 operator.admin)。 */
    suspend fun rpcDevices(): RpcResult = rpcQuery("devices.list")

    /**
     * 释放一次引用。
     *
     * 只有引用计数归零(没人再用这条连接)时才真正取消协程、关闭 socket、终结在途收集。
     * 这样 UI 页面销毁、顶部探针用完「关闭」都不会把前台服务正在用的健康 socket 带走。
     */
    override fun close() {
        val left = refs.decrementAndGet()
        if (left > 0) {
            Log.i(tag, "共享实例仍被引用(refs=$left),保留连接")
            return
        }
        refs.set(0)
        doClose()
    }

    /** 忽略引用计数,真正断开(配置变更时由 [OpenClawGatewayRegistry] 调用)。 */
    fun shutdown() {
        refs.set(0)
        doClose()
    }

    private fun doClose() {
        scope.cancel()
        try { ws?.close(1000, "shutdown") } catch (_: Exception) {}
        pendingReqs.values.forEach { it.complete(null) }
        pendingReqs.clear()
        // 服务/配置变更时真正断开:在途收集算被取消,不当作「空回复」
        activeCollector?.cancel()
        activeCollector = null
    }

    // ---- 连接与鉴权 ----

    private suspend fun ensureConnected(): Boolean {
        if (connected) return true
        synchronized(lock) {
            // 等待授权时旧 socket 已经"半死"(网关拒绝了 connect,却可能不主动断开):
            // 每次重试都重建一条,保证批准后能重新走 challenge → connect 并立即恢复;
            // 否则会一直复用被拒的旧连接,批准了也永远连不上。
            if (awaitingPairingState && ws != null) {
                Log.i(tag, "等待授权中:重建 WS 以重新完成 connect 握手")
                ws?.cancel()
                ws = null
            }
            if (ws == null) openSocket()
        }
        // 等待鉴权完成(connect.res ok)或失败,最多约 15s。
        // 等待授权(NOT_PAIRED)一旦到达就立即返回 false:那不是一个可以"等得到"的连接过程,
        // 早返还能让设置页/监控的自动重试节奏不被 15s 卡住。
        repeat(75) {
            if (connected) return true
            if (ws == null) return false
            if (awaitingPairingState) return false
            kotlinx.coroutines.delay(200)
        }
        if (lastRpcError == null) lastRpcError = "网关连接超时:请检查 Host/端口/网络,并确认设备已配对"
        return false
    }

    private fun openSocket() {
        if (!config.isConfigured()) {
            Log.w(tag, "网关未配置(请在 App 设置里填域名/端口)")
            lastRpcError = "网关未配置:请在设置中填写 Host/端口/Token"
            onStatus("网关未配置,请在 App 设置中填写")
            return
        }
        val request = Request.Builder()
            .url(GatewayConfig.wsUrl(config))
            // 网关要求 Origin 为网关自身 origin,否则 CONTROL_UI_ORIGIN_NOT_ALLOWED
            .addHeader("Origin", GatewayConfig.baseUrl(config))
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isStaleSocket(webSocket)) return
                Log.i(tag, "WS 已连接,等待 challenge")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStaleSocket(webSocket)) return
                handleFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStaleSocket(webSocket)) {
                    // 等待授权重试时会主动 cancel 旧 socket(见 ensureConnected):
                    // 它的失败回调不能把刚建好的新连接也标成断开
                    Log.i(tag, "忽略旧 socket 的失败回调: ${t.message}")
                    return
                }
                Log.e(tag, "WS 失败", t)
                onSocketDown(describeSocketFailure(t, response))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isStaleSocket(webSocket)) {
                    Log.i(tag, "忽略旧 socket 的关闭回调(code=$code)")
                    return
                }
                Log.i(tag, "WS 关闭 $code $reason")
                onSocketDown(
                    if (code == 1000) "网关主动关闭连接"
                    else "连接被网关关闭(code=$code${if (reason.isBlank()) "" else " $reason"})"
                )
            }
        })
        ws = socket
    }

    /**
     * 回调是不是来自已被替换的旧 socket。
     * `ws == null` 时视为当前 socket(赋值可能还没回调快),不做丢弃。
     */
    private fun isStaleSocket(socket: WebSocket): Boolean = ws?.let { it !== socket } ?: false

    /**
     * WS 断开:清连接状态并把【具体原因】写进 [lastError](供顶部状态卡与设置页展示)。
     *
     * 关键(修复「设备说完话 App 立刻显示无回复」):
     * 断开【不是】这一轮回复的结局。旧实现在这里直接 `activeCollector?.finish()`,
     * 用空文本完成收集,`chat()` 立即返回 null,上层就写成「(网关无回复)」——
     * 即使网关的 final 只晚到几百毫秒。现在只标记「连接中断」并启动重连,
     * 由 [startCollectorReconnect] 在剩余时限内重连、继续等同一个 runId。
     */
    private fun onSocketDown(reason: String? = null) {
        connected = false
        ws = null
        val readable = if (reason.isNullOrBlank()) "网关连接已断开" else "网关连接已断开($reason)"
        // 断开原因也要过一遍纯函数:网关也可能在握手/关闭时把"未批准"带在原因里
        val status = mapRpcError(null, reason, shortDeviceId(identity.deviceId))
        when {
            // 断开原因里也带「未批准」:同样归入等待授权(状态词 connecting + 中文 detail)
            status.awaitingPairing -> {
                Log.i(tag, "WS 断开: $readable")
                markAwaitingPairing(status.detail)
            }
            // 已在等待授权:保留授权文案(断开细节只进日志),否则用户会看到「断开」而看不出要等审批
            awaitingPairingState -> Log.i(tag, "WS 断开: $readable(仍在等待网关授权)")
            else -> {
                Log.i(tag, "WS 断开: $readable")
                if (lastRpcError == null) lastRpcError = readable
            }
        }
        pendingReqs.values.forEach { it.complete(null) }
        pendingReqs.clear()
        activeCollector?.takeIf { !it.isTerminal }?.let { collector ->
            collector.onConnectionInterrupted(reason ?: "连接已断开")
            startCollectorReconnect(collector)
        }
    }

    /**
     * 置「等待网关授权」并播报:状态词仍是 connecting,全部信息在 detail(含 deviceId 前 8 位)。
     * 完整 deviceId 只写日志(日志可定位到具体设备,设备屏空间有限)。
     */
    private fun markAwaitingPairing(detail: String) {
        awaitingPairingState = true
        lastRpcError = detail
        Log.w(tag, "等待网关授权: deviceId=${identity.deviceId} detail=$detail")
        onStatus(detail)
    }

    /**
     * 在途回复的重连循环(指数退避):重连成功 → 用同一幂等键重新订阅 → 继续等同一个 runId。
     *
     * 每次重连都打一行带原因与退避时长的日志,便于从 logcat 看出「为什么又建了一条连接」。
     * 与旧行为的关键区别:只在真的断开时才会跑到这里(健康 socket 不会被监控/探针关掉);
     * 连上后不再反复重发 chat.send,而是等本轮终结/超时/连接再次断开。
     */
    private fun startCollectorReconnect(collector: ReplyCollector) {
        synchronized(lock) {
            if (collector.reconnecting) return
            collector.reconnecting = true
        }
        scope.launch {
            val backoff = ReconnectBackoff()
            try {
                while (!collector.isTerminal && !collector.isExpired()) {
                    if (!connected) {
                        val delayMs = backoff.nextDelayMs()
                        Log.i(
                            tag,
                            "回复在途但连接中断,${delayMs}ms 后重连(第 ${backoff.attempts} 次,剩余 ${collector.remainingMs() / 1000}s)" +
                                "原因: ${collector.lastConnectionError}",
                        )
                        onStatus("网关连接中断,正在重连(第 ${backoff.attempts} 次)")
                        kotlinx.coroutines.delay(delayMs)
                        if (collector.isTerminal || collector.isExpired()) return@launch
                        if (!ensureConnected()) {
                            // 仍然连不上:把最新原因记到本轮上(超时后作为中断原因返回),下一轮退避更长
                            collector.onConnectionInterrupted(lastRpcError ?: "重连失败")
                            Log.w(tag, "重连未成功: ${lastRpcError}")
                            continue
                        }
                        Log.i(tag, "重连成功: runId=${collector.runId},重新订阅同一条 run,继续等待")
                        resubscribe(collector)
                    }
                    // 已连上:等本轮终结、超时,或连接再次断开(500ms 粒度,不阻塞其它协程)
                    while (!collector.isTerminal && !collector.isExpired() && connected) {
                        kotlinx.coroutines.delay(500)
                    }
                    if (collector.isTerminal || collector.isExpired()) return@launch
                    collector.onConnectionInterrupted(lastRpcError ?: "连接再次断开")
                    Log.i(tag, "回复仍在途,连接再次断开;继续退避重连")
                }
            } finally {
                synchronized(lock) { collector.reconnecting = false }
            }
        }
    }

    /**
     * 重连后用【同一个幂等键/[ReplyCollector.idempotencyKey]】重发 chat.send,
     * 让网关把后续事件继续流到新连接上(同一 runId 不会重复执行)。
     */
    private suspend fun resubscribe(collector: ReplyCollector) {
        val previousRunId = collector.runId
        val runId = sendChat(collector.userText, collector.idempotencyKey)
        collector.resubscribeCount++
        if (runId == null) {
            Log.w(tag, "重新订阅失败: ${lastRpcError}")
            collector.onConnectionInterrupted(lastRpcError ?: "重新订阅失败")
            return
        }
        if (runId != previousRunId) {
            // 同一幂等键下网关若返回了新 runId,说明旧 run 已结束/不重放,改按新 runId 收集
            Log.w(tag, "重新订阅返回新 runId=$runId(原 $previousRunId),改按新 runId 收集")
            collector.runId = runId
        }
    }

    /** 把 WS 失败异常/HTTP 响应映射成可读原因(token 缺失、连接被拒、超时、TLS 失败等)。 */
    private fun describeSocketFailure(t: Throwable, response: Response?): String {
        val http = response?.code?.let { code ->
            when (code) {
                401, 403 -> "HTTP $code(网关拒绝握手:token 无效或未配对) "
                404 -> "HTTP 404(WS 路径不对或服务未启用) "
                else -> "HTTP $code "
            }
        } ?: ""
        val detail = when (t) {
            is java.net.SocketTimeoutException -> "连接超时:网络或 Tailscale 未就绪"
            is java.net.ConnectException -> "连接被拒绝:域名/端口不可达"
            is java.net.UnknownHostException -> "无法解析主机名 ${config.host}"
            is java.net.UnknownServiceException ->
                "明文连接被网络安全策略拦截(ws:// 未放行):请改用 wss:// 或调整 network_security_config"
            is javax.net.ssl.SSLHandshakeException -> "TLS 证书校验失败(自签证书需在设置里允许)"
            else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        }
        return http + detail
    }

    private fun handleFrame(text: String) {
        try {
            val obj = JsonParser.parseString(text).asJsonObject
            when (obj.get("type")?.asString) {
                "event" -> {
                    when (obj.get("event")?.asString) {
                        "connect.challenge" -> {
                            pendingNonce = obj.getAsJsonObject("payload")?.get("nonce")?.asString
                            sendConnect()
                        }
                        else -> handleReplyEvent(obj)
                    }
                }
                "response", "result", "message", "assistant.message" -> {
                    handleReplyFrame(obj)
                }
                "res" -> {
                    val id = obj.get("id")?.asString ?: return
                    val deferred = pendingReqs.remove(id) ?: return
                    if (obj.get("ok")?.asBoolean == true) {
                        val payload = obj.getAsJsonObject("payload")
                        // connect 成功后持久化 deviceToken
                        if (payload != null && payload.has("auth")) {
                            payload.getAsJsonObject("auth")?.get("deviceToken")?.asString
                                ?.takeIf { it.isNotBlank() }
                                ?.let { identity.deviceToken = it }
                        }
                        deferred.complete(payload)
                    } else {
                        val err = obj.getAsJsonObject("error")
                        val code = err?.get("code")?.asString
                        val message = err?.get("message")?.asString
                        Log.e(tag, "RPC 错误 [$code]: $message")
                        // 错误 → 状态是纯函数(见 OpenClawErrors.kt),这里只负责落地:
                        // 等待授权的原文(detail)进 lastError 并播报,状态词仍是 connecting。
                        val status = mapRpcError(code, message, shortDeviceId(identity.deviceId))
                        if (status.awaitingPairing) {
                            markAwaitingPairing(status.detail)
                        } else {
                            lastRpcError = message?.takeIf { it.isNotBlank() } ?: code
                            if (isMissingScope(message)) {
                                onStatus(status.detail)
                            }
                        }
                        deferred.complete(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "帧解析失败", e)
        }
    }

    private fun sendConnect() {
        val nonce = pendingNonce ?: UUID.randomUUID().toString()
        // scopes 与设备已批准一致(admin 在前,网关卡 devices list 用此顺序)。
        val scopes = "operator.admin,operator.read,operator.write"
        // 实测确认:网关强制 Ed25519 设备签名鉴权,token-first(openclaw-control-ui)被拒。
        // 必须带 device 块:client.id=openclaw-android + device{id,publicKey,signature}。
        // device 用 DeviceIdentity 的持久化 ed25519 keypair 签名 v2 payload。
        val signedAt = System.currentTimeMillis()
        val devId = identity.deviceId
        val pubKey = identity.publicKeyBase64
        val signPayload = "v2|$devId|openclaw-android|webchat|operator|$scopes|$signedAt|${config.token}|$nonce"
        val signature = identity.sign(signPayload)
        val params = JsonObject().apply {
            addProperty("minProtocol", 4)
            addProperty("maxProtocol", 4)
            add("client", JsonObject().apply {
                addProperty("id", "openclaw-android")
                addProperty("version", "0.1.0")
                addProperty("platform", "android")
                addProperty("mode", "webchat")
            })
            addProperty("role", "operator")
            add("scopes", gson.toJsonTree(listOf("operator.admin", "operator.read", "operator.write")))
            add("device", JsonObject().apply {
                addProperty("id", devId)
                addProperty("publicKey", pubKey)
                addProperty("signature", signature)
                addProperty("signedAt", signedAt)
                addProperty("nonce", nonce)
            })
            add("caps", gson.toJsonTree(listOf("tool-events")))
            add("commands", gson.toJsonTree(emptyList<String>()))
            add("permissions", JsonObject())
            add("auth", JsonObject().apply {
                // 运行时注入的网关 token(设置页 SharedPreferences 或草稿配置)。
                addProperty("token", config.token)
                addProperty("password", "")
            })
            addProperty("locale", "zh-CN")
            addProperty("userAgent", "passport-android/0.1.0")
        }
        request("connect", params)?.let { deferred ->
            scope.launch {
                val ok = withTimeoutOrNull(10_000) { deferred.await() } != null
                connected = ok
                if (ok) {
                    // 批准后第一次成功连接:清零等待授权并清掉授权文案 → 上层监控/探针播报「网关已恢复连接」
                    if (awaitingPairingState) {
                        Log.i(tag, "设备已被网关批准(deviceId=${identity.deviceId}),等待授权结束")
                    }
                    awaitingPairingState = false
                    lastRpcError = null
                } else if (awaitingPairingState) {
                    // 已播报「等待网关授权:…」:不要再被通用的「鉴权失败」文案盖掉(否则用户看不出该去批准)
                    Log.w(tag, "网关 connect 未通过:设备等待授权(deviceId=${identity.deviceId})")
                } else {
                    // 区分两种失败:token 不匹配(换 token 后旧值失效)与设备未批准。
                    // 旧实现一律报「请在网关主机 openclaw devices approve 设备」——真机实测 token 失效时
                    // 也会走到这里,把用户引向错误的操作。
                    val mismatch = isTokenMismatch(null, lastRpcError)
                    if (mismatch) {
                        Log.w(tag, "网关 connect 鉴权失败:token 不匹配(请在设置页更新 Token)")
                        onStatus("网关 token 不匹配:旧 token 已失效,请在设置页更新 Token 后保存")
                    } else {
                        Log.w(tag, "网关 connect 鉴权失败(设备可能未在网关 approve)")
                        onStatus("网关鉴权失败:请在网关主机 openclaw devices approve 设备")
                    }
                }
            }
        }
    }

    // ---- chat.send 与回复收集 ----

    /**
     * 发一次 chat.send。
     *
     * @param idempotencyKey 本轮幂等键:重连后重新订阅要用同一个值,网关才不会重复执行同一条消息
     */
    private suspend fun sendChat(text: String, idempotencyKey: String): String? {
        val params = JsonObject().apply {
            // sessionKey 必须是 agent:<agentId>:<rest> 格式,网关卡从 sessionKey 解析 agentId。
            // agentId 固定 main(网关只认识 main agent),<rest> 用本机自己的会话名,
            // 这样对讲机不再和 PC 控制台/微信/飞书共用上下文。
            addProperty("sessionKey", config.sessionKey)
            addProperty("message", text)
            addProperty("deliver", false)
            addProperty("idempotencyKey", idempotencyKey)
            addProperty("agentId", "main")
        }
        val reply = requestSync("chat.send", params)
        if (reply == null && lastRpcError == null) lastRpcError = "chat.send 无响应或超时"
        return reply?.get("runId")?.asString
    }

    /**
     * 处理下行事件(event 包),按 OpenClaw 通用结构解析文本。
     *
     * 日志降噪:event 帧只打【摘要】且降为 DEBUG。
     * 旧实现用 INFO 把整个帧打出来,单条 agent/tool 事件可达 140KB,logcat 被刷屏,
     * 真正要看的状态行(鉴权/连接/run 起止)反而被冲掉。
     */
    private fun handleReplyEvent(obj: JsonObject) {
        val payload = obj.getAsJsonObject("payload") ?: return
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, "event=${obj.get("event")?.asString ?: "?"} 摘要: ${summarizeFrame(payload)}")
        }
        handleReplyPayload(payload, obj.get("event")?.asString, obj.toString())
    }

    /** 处理非 event 类型下行帧(response/result/message/assistant.message):只取进度,不作为正文。 */
    private fun handleReplyFrame(obj: JsonObject) {
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, "下行帧 type=${obj.get("type")?.asString ?: "?"} 摘要: ${summarizeFrame(obj)}")
        }
        handleReplyPayload(obj, null, null)
    }

    /**
     * 帧摘要:只保留定位问题需要的字段(event 名/state/phase/runId/文本长度),
     * 不打正文(正文可能很长且含用户内容)。
     */
    private fun summarizeFrame(payload: JsonObject): String {
        val sb = StringBuilder()
        sb.append("state=").append(payload.get("state")?.takeIf { it.isJsonPrimitive }?.asString ?: "-")
        sb.append(" phase=").append(payload.get("phase")?.takeIf { it.isJsonPrimitive }?.asString ?: "-")
        sb.append(" runId=").append(payload.get("runId")?.takeIf { it.isJsonPrimitive }?.asString ?: "-")
        progressDetail(payload)?.let { sb.append(" detail=").append(it) }
        sb.append(" 文本长度=").append(extractText(payload)?.length ?: 0)
        return sb.toString()
    }

    /**
     * 从帧里抽出可展示的进度阶段:phase(preparing_context 等)/ run_status / 工具名。
     * 抽不到返回 null(这种帧不算进度,不上状态面板)。
     */
    private fun progressDetail(payload: JsonObject): String? {
        val toolElement = payload.get("tool")
        val tool = when {
            toolElement == null -> null
            toolElement.isJsonPrimitive -> toolElement.asString
            toolElement.isJsonObject ->
                toolElement.asJsonObject.get("name")?.asString
                    ?: toolElement.asJsonObject.get("tool")?.asString

            else -> null
        }
        val phase = payload.get("phase")?.takeIf { it.isJsonPrimitive }?.asString
        val runStatus = (payload.get("runStatus") ?: payload.get("run_status"))
            ?.takeIf { it.isJsonPrimitive }?.asString
        val status = payload.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        return when {
            !tool.isNullOrBlank() -> "调用工具 $tool"
            !phase.isNullOrBlank() -> phase
            !runStatus.isNullOrBlank() -> runStatus
            !status.isNullOrBlank() -> status
            else -> null
        }
    }

    /**
     * 把网关进度【节流】转成状态面板/设备屏文案(如「网关工作中: preparing_context」)。
     *
     * 节流规则:距上次上报 < 2s 一律丢弃;同一阶段最多每 6s 重复上报一次。
     * 只在有在途收集时才会被调到,因此不影响空闲时的日志/状态。
     */
    private fun reportProgress(payload: JsonObject) {
        val detail = progressDetail(payload) ?: return
        val now = System.currentTimeMillis()
        if (now - lastProgressAtMs < PROGRESS_MIN_INTERVAL_MS) return
        if (detail == lastProgressDetail && now - lastProgressAtMs < PROGRESS_REPEAT_MS) return
        lastProgressDetail = detail
        lastProgressAtMs = now
        val text = "$PROGRESS_PREFIX $detail"
        Log.i(tag, text)
        onStatus(text)
    }

    /**
     * 下行 payload 处理。
     *
     * body / raw 双路(真机抓帧结论,见 `RawEntries.kt` / `ReplyCollector.kt`):
     *  - **raw**:把每一帧交给 [ReplyCollector.acceptFrame],状态/生命周期/步骤/工具/
     *    工具输出/正文/终局/用量全部按到达顺序收下(一条不丢),只给 App 调试视图;
     *  - **body**(正确正文):由 collector 按优先级算(terminalReply(visible) → 区间内 final
     *    → 区间内最后一个 delta),给设备屏与 TTS;
     *  - `agent stream=lifecycle phase=finishing/end` 由 collector 内部处理结束语义。
     */
    private fun handleReplyPayload(payload: JsonObject, eventName: String?, rawFrame: String?) {
        val collector = activeCollector ?: return
        val evRunId = payload.get("runId")?.takeIf { it.isJsonPrimitive }?.asString
        collector.learnRunId(evRunId)
        // 注意:这里【不再】因为 runId 不匹配就整帧丢弃——raw 要"一条不丢"(重试会换 runId)。
        // body(正文)的归属过滤在 collector 内部完成。
        val runMatches = collector.matchesRun(evRunId)

        // 在途等待期间:把 phase/tool 之类的进度节流转到状态面板与设备屏。
        // 已定局(终局宽限窗)时不再播报进度:这一轮早已落幕,迟到的工具帧不该把状态卡
        // 从「已就绪」拉回「网关工作中」;它们只进 raw(调试视图)。
        if (!collector.isTerminal) reportProgress(payload)

        if (rawFrame == null) return

        val lifecyclePhase = if (eventName == "agent" && payload.get("stream")?.asString == "lifecycle") {
            payload.getAsJsonObject("data")?.get("phase")?.takeIf { it.isJsonPrimitive }?.asString
        } else null

        // raw + body + 生命周期结束信号都在这里收(collector 内部做归属过滤)
        val consumed = collector.acceptFrame(rawFrame)
        if (Log.isLoggable(tag, Log.DEBUG)) {
            val stream = payload.get("stream")?.takeIf { it.isJsonPrimitive }?.asString
            val state = payload.get("state")?.takeIf { it.isJsonPrimitive }?.asString
            val seq = payload.get("seq")?.takeIf { it.isJsonPrimitive }?.asInt
            Log.d(
                tag,
                "帧 event=${eventName ?: "?"} stream=$stream state=$state seq=$seq" +
                    " runId=${evRunId?.take(8) ?: "-"} 本轮=${runMatches}" +
                    " consumed=$consumed raw=${collector.rawEntryCount} body=${collector.messageCount}",
            )
        }
        val isFinal = payload.get("state")?.takeIf { it.isJsonPrimitive }?.asString == "final"
        if (consumed && isFinal) {
            Log.i(
                tag,
                "收到 final: runId=${collector.runId} 已收 ${collector.messageCount} 条 body" +
                    " ${collector.textLength} 字 / ${collector.rawEntryCount} 条 raw",
            )
        }
        if (lifecyclePhase == "finishing" || lifecyclePhase == "end") {
            Log.i(
                tag,
                "收到 lifecycle 结束: phase=$lifecyclePhase runId=${collector.runId}" +
                    " 已收 ${collector.messageCount} 条 body / ${collector.rawEntryCount} 条 raw",
            )
        }
    }

    /** 尽量从各种结构的 JSON 里抠出文本。 */
    private fun extractText(payload: JsonObject): String? {
        // 0. 网关 chat 事件: message.content 是数组 [{"type":"text","text":"..."}] 或原始字符串
        payload.getAsJsonObject("message")?.let { m ->
            val content = m.get("content")
            if (content != null) {
                if (content.isJsonArray) {
                    // content 数组: 取各 text 块拼起来(或取首个 text)
                    content.asJsonArray.mapNotNull { el ->
                        if (el.isJsonObject) {
                            val obj = el.asJsonObject
                            val t = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                            if (!t.isNullOrBlank()) t else null
                        } else null
                    }.joinToString("").takeIf { it.isNotBlank() }?.let { return it }
                } else if (content.isJsonPrimitive) {
                    content.asString.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
            m.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // 2. {choices:[{message:{content}}]}
        payload.getAsJsonArray("choices")?.let { choices ->
            if (choices.size() > 0) {
                val first = choices[0]
                if (first.isJsonObject) {
                    first.asJsonObject.getAsJsonObject("message")
                        ?.let { m ->
                            val c = m.get("content")
                            if (c != null && c.isJsonArray) {
                                c.asJsonArray.mapNotNull { el ->
                                    if (el.isJsonObject) el.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString else null
                                }.joinToString("").takeIf { it.isNotBlank() }?.let { return it }
                            } else if (c != null && c.isJsonPrimitive) {
                                c.asString.takeIf { it.isNotBlank() }?.let { return it }
                            }
                        }
                }
            }
        }
        // 3. 顶层文本字段
        listOf("text", "content", "reply", "response", "answer", "deltaText").forEach { k ->
            payload.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // ---- 底层请求 ----

    private fun request(method: String, params: JsonObject): CompletableDeferred<JsonObject?>? {
        val socket = ws ?: return null
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject?>()
        pendingReqs[id] = deferred
        val frame = JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        return try {
            socket.send(frame.toString())
            deferred
        } catch (e: Exception) {
            pendingReqs.remove(id)
            Log.e(tag, "发送 $method 失败", e)
            null
        }
    }

    private suspend fun requestSync(method: String, params: JsonObject): JsonObject? {
        val deferred = request(method, params) ?: return null
        return withTimeoutOrNull(GatewayConfig.TIMEOUT_SECONDS * 1000) { deferred.await() }
    }

    companion object {
        /** 进度文案前缀:状态面板与设备屏据此识别「网关工作中」状态。 */
        const val PROGRESS_PREFIX = "网关工作中:"

        /** 进度节流:两次上报至少间隔 2s(避免工具事件密集时刷屏)。 */
        private const val PROGRESS_MIN_INTERVAL_MS = 2_000L

        /** 同一阶段最多每 6s 重复上报一次(间隔很久的同类事件说明任务还活着)。 */
        private const val PROGRESS_REPEAT_MS = 6_000L

        /**
         * 终局后宽限窗(毫秒):定局后把 collector 继续挂在 activeCollector 上这么久再回收。
         *
         * 真机现象:`agent lifecycle phase=end` 与 `chat final` 几乎同一时刻到达(实测两者 seq 相同),
         * `end` 触发定局后紧随其后的 `final`、以及迟到的 `item/tool/command_output` 结果帧,
         * 在「一返回就回收」的旧实现里全部被丢弃(App 侧 `chat/final` 收到数恒为 0)。
         * 宽限窗只延期**回收 collector**;body 早已返回并下发设备/TTS,不会让用户多等。
         *
         * 这一窗**同时**用来做一次 `chat.history` 正文补正(见 [applyHistoryCorrection]):
         * 流式帧只推本轮最后一条 assistant 消息(常常是状态话术),真正的答案只在历史里,
         * 因此要在窗口内把历史取回来、再补发一帧给设备;查询失败只记日志,不影响任何既有行为。
         */
        const val POST_TERMINAL_GRACE_MS = 3_000L
    }
}
