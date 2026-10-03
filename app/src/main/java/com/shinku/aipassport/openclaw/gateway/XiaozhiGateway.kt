package com.shinku.aipassport.openclaw.gateway

import android.util.Log
import com.shinku.aipassport.openclaw.stt.XiaozhiLlmSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「小智 AI」网关:把**识别通道那条已经存在的小智会话**当成 AI 后端。
 *
 * 它与其他四种网关的关键区别:**自己不发任何 HTTP/WS 请求**。一轮的流程是 ——
 * 设备音频经 [com.shinku.aipassport.openclaw.stt.XiaozhiStt] 上送小智(既有 STT 路径),
 * 小智在同一会话里回 `{"type":"stt"}`(识别)与回复(正文 + TTS 音频);
 * 本类只等会话层装配好的**本轮正文**(见 `XiaozhiReplyText`:`tts` 句级文本拼接优先,`llm.text` 兜底),
 * 把它当作**单条回复**交给流水线,于是「上屏 / 对话历史 / TTS」全部复用流水线原有逻辑
 * (见 `docs/design/xiaozhi-ai-gateway.md` §4.2、§6 修订)。
 *
 * 几条实现约束:
 *  - **会话必须共用**:`chatMulti` 不携带、也不转发任何东西给服务端 —— 问答配对由服务端的这一轮会话决定,
 *    另建一条会话不可能收到这次问答的正文(所以构造函数要求注入 [XiaozhiLlmSource]);
 *  - **必须有超时**:等不到正文时按现有网关的失败语义返回可读原因 + 写 [lastError],绝不无限挂着;
 *    会话层明确说「本轮没有可上屏正文」时([NO_BODY])则**立刻**给原因,不白等一个超时;
 *  - **一轮可以有多条正文,后面的是补正**:会话层按 `XiaozhiReplyText` 的规则装配,同一轮里正文
 *    **可以变好**(多段 `tts[start…stop]` 的第二段、迟到的句级文本、以及「先用了 `llm.text` 兜底、
 *    随后 tts 句级文本才到」)。第一条经 [chatMulti] 的返回值交付,之后的**每一条变化**都经
 *    [chatMulti] 登记的[onBodyCorrection] 交付 —— 否则设备屏会永远停在那个更短的兜底正文上
 *    (真机现象「从小智获取的文本不是完整的」);
 *  - **打断必须作废本轮等待**:流水线每轮 `turn_start` 都会调 [interrupt],不能留下悬挂的 deferred;
 *    同一轮的补正出口也一并作废(旧轮的补正不得改写新一轮);
 *  - **[close] 不关会话**:会话归 STT 所有(关掉等于每轮重新握手),这里只摘观察者并作废等待。
 *
 * @param source 共用的小智会话(`XiaozhiStt.session`);null = 没接线(App 内文本输入页没有语音会话),
 *   此时一律返回「小智 AI 只在设备语音链路里工作」的可读原因,不做任何空等。
 * @param replyTimeoutMs 等待正文的上限。小智的 LLM 通常 1–3s 出正文(正文由 `tts` 收尾时交出),
 *   默认 30s 足够宽裕,又不会让设备屏干等;单测可缩短。
 */
class XiaozhiGateway(
    private val source: XiaozhiLlmSource?,
    private val replyTimeoutMs: Long = DEFAULT_REPLY_TIMEOUT_MS,
) : GatewayAdapter {

    private val tag = "XiaozhiGateway"

    /**
     * 观察者实例:必须是**同一个** lambda,才能让 [XiaozhiLlmSource.clearLlmObserver]
     * 按身份摘除(旧实例的 close() 不能摘掉新实例的观察者)。
     */
    private val observer: (String) -> Unit = { text -> onLlm(text) }

    /** 等待/暂存/错误几处状态的锁(WS 回调线程与流水线 IO 协程都会动它们)。 */
    private val lock = Any()

    /** 本轮等待 `llm` 正文的挂点;null = 当前没人等。 */
    private var pending: CompletableDeferred<Outcome>? = null

    /** `llm` 比 `chatMulti` 早到时暂存的正文(见 [onLlm]);null = 无。 */
    private var buffered: Buffered? = null

    /**
     * 本轮**已交付**的正文(非空;null = 还没交过正文,或交过的是「没有可上屏正文」)。
     *
     * 用它与新到的正文比对:[onLlm] 只在文本**变了**时候才当补正交出去(重复交付会让设备屏多一个
     * 重复气泡、App 气泡重复替换)。
     */
    private var deliveredBody: String? = null

    /** 本轮是否已经交付过结论(正文或「没有可上屏正文」),见 [onLlm] 的两条分支。 */
    private var deliveredSomething: Boolean = false

    /**
     * 本轮登记的**正文补正**出口([chatMulti] 的 `onBodyCorrection`)。
     *
     * 为什么小智也要用它:小智那条会话是流式的,同一轮的正文可能在首条之后继续变完整;而
     * [chatMulti] 早已返回,唯一能把「更好的正文」再送到设备屏/App 的出口就是它(流水线收到后会补发一帧
     * `TEXT('A')` 并就地替换 App 的正文气泡)。每轮登记、[interrupt] 作废。
     */
    private var corrector: ((String) -> Unit)? = null

    @Volatile
    private var closed = false

    private var _lastError: String? = null

    override val lastError: String?
        get() = _lastError

    override fun clearLastError() {
        _lastError = null
    }

    init {
        // 一开始就挂上观察者(而不是等到 chatMulti):正文可能在 listen.stop 之后几百毫秒内就到,
        // 那时流水线还在等 endTurn 的识别结果,等挂得太晚就会漏掉这一条。
        source?.setLlmObserver(observer)
    }

    /**
     * 小智网关没有独立的连接:上行音频与下行 `llm` 都跑在**识别通道那条会话**上,
     * 建链/保活/重连由 [com.shinku.aipassport.openclaw.stt.XiaozhiSession] 自己负责(见 `SttEngine.prewarm`)。
     *
     * 所以这里只做两件事:确认会话可用 + 幂等触发一次预热(共用连接,不另建 socket)。
     */
    override suspend fun connect(): Boolean {
        if (closed) return failed(CLOSED)
        val src = source ?: return failed(NO_SESSION)
        if (!src.isAvailable) return failed(NO_SESSION)
        src.prewarm()
        _lastError = null
        return true
    }

    /**
     * 「能收发」= 共用会话已握手(热连接可用):与 STT 的就绪判定同一条 —— 本来就是同一条连接。
     * 会话未就绪时状态卡会停在「网关 连接中」,由服务的连接监控继续探活/重连。
     */
    override fun isReady(): Boolean = source?.let { it.isAvailable && it.isWarmReady() } == true

    /**
     * 小智本轮朗读用的是它自己的音色:音频随同一条会话以 opus 直通给设备(见 `XiaozhiTtsRelay`),
     * 因此流水线**不做**本地合成/手机朗读,否则两种声音会叠着播。
     */
    override val providesDeviceTtsAudio: Boolean get() = true

    /** 单条回复版:正文由 [chatMulti] 给出,失败/打断时为 null(失败原因在 [lastError])。 */
    override suspend fun chat(text: String): String? = chatMulti(text).messages.firstOrNull()

    /**
     * 等本轮的**小智回复正文**(由会话层按 `XiaozhiReplyText` 的规则装配好)。
     *
     * 注意 [text] 只是本地识别原文(与小智服务端听到的同一段音频对应),**不发给任何服务端** ——
     * 正文由服务端在这一轮会话上主动推来,所以这里只登记「本轮在等」并交给 [onLlm] 完成。
     * 返回单元素列表,流水线据此走原有的「上屏一次整段 / 写历史 / 朗读」流程(设计 §6 方案 A)。
     */
    override suspend fun chatMulti(
        text: String,
        onRawUpdate: ((List<RawEntry>) -> Unit)?,
        onBodyCorrection: ((String) -> Unit)?,
    ): ChatReply {
        if (closed) return fail(CLOSED)
        if (source == null || !source.isAvailable) return fail(NO_SESSION)

        // 正文可能比这次调用早到几百毫秒(endTurn 等到最终 stt 后,llm 往往紧接着就到):
        // 暂存窗口内直接用它,不为一个已经到的回复白等一个超时。
        val early = synchronized(lock) {
            val cached = buffered
            buffered = null
            cached?.takeIf { System.currentTimeMillis() - it.atMs <= BUFFER_FRESH_MS }
        }
        if (early != null) {
            _lastError = null
            // 暂存正文就是本轮的首条交付:之后更完整的正文要经补正出口送到设备屏。
            synchronized(lock) {
                deliveredBody = early.text
                deliveredSomething = true
                corrector = onBodyCorrection
            }
            Log.i(tag, "本轮回复直接用暂存的 llm 正文(${early.text.length} 字)")
            return ChatReply(listOf(early.text))
        }

        val deferred = CompletableDeferred<Outcome>()
        synchronized(lock) {
            pending = deferred
            // 新一轮的记账:首条交付走本方法的返回值,之后的每条变化走 corrector。
            deliveredBody = null
            deliveredSomething = false
            corrector = onBodyCorrection
        }
        val outcome = try {
            withTimeoutOrNull(replyTimeoutMs) { deferred.await() }
        } finally {
            // 超时/打断后不能把这个挂点留给下一轮(否则下一次 chatMulti 会接到旧轮的完成信号)。
            synchronized(lock) { if (pending === deferred) pending = null }
        }
        return when (outcome) {
            null -> fail("小智没有返回回复(等待 ${replyTimeoutMs / 1000} 秒超时)")
            // 打断不算网关故障:只作为本轮结果上抛,不写 lastError(否则状态卡/连接监控会把它当网关故障)。
            Outcome.Aborted -> ChatReply(emptyList(), ABORTED)
            is Outcome.Text -> {
                _lastError = null
                Log.i(tag, "小智回复已就绪(${outcome.text.length} 字): ${outcome.text.take(40)}")
                ChatReply(listOf(outcome.text))
            }
            // 会话层确认本轮没有可上屏正文(只有表情/空文本):按失败语义给可读原因,不上屏表情/空串。
            is Outcome.Failed -> fail(outcome.reason)
        }
    }

    /**
     * 打断在途等待(barge:回复还没到用户又按了 OK)。
     *
     * 流水线每轮 `turn_start` 都会调它,因此**绝不会留下悬挂的 deferred**;同一轮里
     * 会话层的等待作废由 STT 侧负责([com.shinku.aipassport.openclaw.stt.XiaozhiSession.barge]),
     * 本类不碰共用的会话。
     */
    override fun interrupt() {
        val waiter: CompletableDeferred<Outcome>?
        synchronized(lock) {
            waiter = pending
            pending = null
            buffered = null   // 旧轮暂存的正文一并作废,不当作下一轮的回复
            // 旧轮的补正出口与已交付记账一并作废:打断后迟到的正文绝不能改写新一轮。
            corrector = null
            deliveredBody = null
            deliveredSomething = false
        }
        // 用「已完成(Aborted)」而不是 cancel():cancel 会让 await 抛 CancellationException,
        // 把调用方(流水线的那一轮协程)一起取消;这里只是把本轮判为打断,让它照常返回。
        waiter?.complete(Outcome.Aborted)
    }

    /**
     * 摘观察者 + 作废在途等待。**绝不关闭共用的会话** —— 那条连接是识别通道的,
     * 关掉就让下一轮必须重新握手(正是要避免的空窗)。
     */
    override fun close() {
        closed = true
        interrupt()
        try {
            source?.clearLlmObserver(observer)
        } catch (e: Exception) {
            Log.w(tag, "摘 llm 观察者失败(会话可能已释放)", e)
        }
    }

    /**
     * 会话层推来的**本轮正文**(可能运行在 OkHttp 的 WS 回调线程),按四种情形分派:
     *  1. 有人在等 → 作为本轮**首条**正文完成 [chatMulti] 的等待;
     *  2. 本轮已交过结论、而这条正文**不同** → 作为**补正**交给 [chatMulti] 登记的出口(流水线补发一帧
     *     `TEXT('A')` 并就地替换 App 气泡)—— 这是「兜底正文先上屏、完整正文随后到达」的修法;
     *  3. 还没人等也没交过结论(`llm` 早于 `chatMulti` 的几百毫秒竞态)→ 暂存,等他来取;
     *  4. 与已交付正文相同 / 已无补正出口(旧轮)→ 丢弃,不重复上屏。
     *
     * 空串不是「没到」而是**明确语义**:会话层已经确认本轮没有可上屏正文(只有表情/空文本) ——
     * 此时立刻给可读原因收尾,既不要把空串/表情当正文,也不要空等一个超时(见 [XiaozhiLlmSource])。
     */
    private fun onLlm(text: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            onNoBody()
            return
        }
        val waiter: CompletableDeferred<Outcome>?
        val correction: ((String) -> Unit)?
        val staged: Boolean
        synchronized(lock) {
            waiter = pending
            pending = null
            correction = when {
                // 有人等 = 本轮首条正文:交给他(照旧)。
                waiter != null -> {
                    deliveredBody = body
                    deliveredSomething = true
                    null
                }
                // 本轮已经交过结论(正文 / 「没有可上屏正文」),而现在来的正文不同
                // → **补正**:再发一帧给设备屏并就地替换 App 的正文气泡。
                deliveredSomething && body != deliveredBody && corrector != null -> {
                    Log.i(
                        tag,
                        "收到更完整的本轮正文(${body.length} 字;上一条 ${deliveredBody?.length ?: 0} 字)"
                            + ",作为补正交给流水线",
                    )
                    deliveredBody = body
                    corrector
                }
                // 还没交过任何结论 = `llm` 早于 `chatMulti`(几百毫秒的竞态):暂存等他来取。
                !deliveredSomething -> {
                    buffered = Buffered(System.currentTimeMillis(), body)
                    null
                }
                // 与已交付的正文相同,或本轮已经没有补正出口(旧轮/未登记):丢掉,不重复上屏。
                else -> {
                    Log.d(tag, "忽略与已交付正文相同/无处交付的小智正文(${body.length} 字)")
                    null
                }
            }
            staged = buffered?.text == body
        }
        if (waiter != null) {
            Log.i(tag, "收到小智正文(${body.length} 字),完成本轮等待")
            waiter.complete(Outcome.Text(body))
            return
        }
        if (correction != null) {
            correction(body)
            return
        }
        if (staged) Log.d(tag, "收到小智正文(${body.length} 字),暂存等 chatMulti")
    }

    /**
     * 本轮**没有可上屏正文**:立刻以可读原因收尾(不写暂存 —— 空串没有内容可供下一轮使用)。
     *
     * 为什么不空等到 [DEFAULT_REPLY_TIMEOUT_MS]:会话层是在「整轮已结束且 `llm`/`tts` 都没有可上屏文字」
     * 时才这么说的,再等下去只会让设备屏白等 —— 用户该看到的是原因,而不是一个方块/空气泡。
     *
     * 原因里带一句**会话层的结算说明**([XiaozhiLlmSource.lastReplyDetail]):把 `tts` / `llm` 两层各自的
     * 条数与清洗后字数写清楚 —— 真机看到这句话时就能分辨是「压根没收到文本」还是「收到的全是
     * 表情/工具模板」(真机那几轮就是后者:tts 句级文本里混着 `% get_weather…` 与 emoji)。
     */
    private fun onNoBody() {
        val waiter: CompletableDeferred<Outcome>?
        synchronized(lock) {
            waiter = pending
            pending = null
            buffered = null
            // 「没有可上屏正文」也是一条结论:之后若真的来了正文,要走补正把它替换掉
            // (工具调用静默期先报原因、真答案随后到达就是这种形态)。
            deliveredBody = null
            deliveredSomething = true
        }
        val reason = noBodyReason()
        _lastError = reason
        Log.w(tag, reason)
        waiter?.complete(Outcome.Failed(reason))
    }

    /** 会话层给出的「哪一层为空」说明(没有就空串;只用于拼可读原因)。 */
    private fun noBodyDetail(): String = source?.lastReplyDetail?.trim().orEmpty()

    /** 最终可读原因:基串 + 会话层的结算说明(写清哪一层为空)。 */
    private fun noBodyReason(): String {
        val detail = noBodyDetail()
        return if (detail.isEmpty()) NO_BODY else "$NO_BODY:$detail"
    }

    /** 记录可读原因并返回空结果(不抛异常,与 [GatewayAdapter] 的约定一致)。 */
    private fun fail(reason: String): ChatReply {
        _lastError = reason
        Log.w(tag, reason)
        return ChatReply(emptyList(), reason)
    }

    /** 同上,但给 [connect] 用(那里要的是 Boolean)。 */
    private fun failed(reason: String): Boolean {
        _lastError = reason
        Log.w(tag, reason)
        return false
    }

    /** 一轮等待的结果;用「已完成」表达打断,避免 await 抛异常(见 [interrupt])。 */
    private sealed interface Outcome {
        data class Text(val text: String) : Outcome
        data object Aborted : Outcome

        /** 会话层已确认本轮没有可上屏正文(只有表情/空文本);[reason] 是可读原因。 */
        data class Failed(val reason: String) : Outcome
    }

    /** `llm` 早到时暂存的正文与到达时刻(新鲜度见 [BUFFER_FRESH_MS])。 */
    private data class Buffered(val atMs: Long, val text: String)

    companion object {
        /** 等待小智回复的默认上限:小智的 LLM 通常 1–3s 出正文,30s 足够宽裕又不至于让设备屏干等。 */
        const val DEFAULT_REPLY_TIMEOUT_MS = 30_000L

        /**
         * `llm` 早到暂存的新鲜度上限。
         *
         * 超过它就当旧轮的残留丢弃:用户打断后重说的下一轮早在几秒之后,
         * 这样旧正文不会被当成新一轮的回复(残余风险见设计文档 §7 的待确认项)。
         */
        private const val BUFFER_FRESH_MS = 2_500L

        /** 没接线时(App 内文本输入页没有语音会话)的可读原因。 */
        private const val NO_SESSION = "小智 AI 只在设备语音链路里工作(当前没有可用的小智会话)"

        /** 已关闭(设置保存后换实例)时快速失败,不白等一个超时。 */
        private const val CLOSED = "小智网关已关闭(请重新保存网关设置)"

        /** 打断本轮:只作为结果上抛,不写 [lastError]。 */
        const val ABORTED = "本轮已打断"

        /**
         * 会话层确认本轮**没有可上屏正文**(整轮的 `llm`/`tts` 都没有可上屏文字,只有表情/工具模板一类
         * 非正文字段)。
         *
         * 为什么不静默丢弃:设备屏需要一个**可读原因**(否则用户只看到一个空气泡或方块),
         * 而原因要走既有失败语义(状态卡 + 一条 `TEXT('A')`)才与其它网关一致。
         * 实际文案会在后面拼上会话层的结算说明([XiaozhiLlmSource.lastReplyDetail]),
         * 写清哪一层为空 —— 拼接入口是 [XiaozhiGateway.noBodyReason]。
         */
        const val NO_BODY = "小智这一轮没有返回可上屏的正文(只有表情/空文本或工具模板)"
    }
}
