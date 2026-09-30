package com.shinku.aipassport.openclaw.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一轮网关回复收集的最终结局。
 *
 * 与「chat() 返回 null」的区别:所有结局都携带**已收集到的全部回复正文**
 * ([messages],即 body),超时/断连只是「没有更多了」,不会把已经上屏过的部分回复丢掉。
 */
sealed interface ReplyOutcome {
    /** 本轮已收集到的全部回复正文(body;失败类结局可能是部分,可能为空)。 */
    val messages: List<String>

    /** 正常结束:[messages] 是本轮全部回复(一轮可能多条)。 */
    data class Reply(override val messages: List<String>) : ReplyOutcome

    /** 正常结束但没有任何正文(收到 final / 生命周期结束,文本为空)。 */
    data class EmptyFinal(override val messages: List<String> = emptyList()) : ReplyOutcome

    /** 连接中断,重连后仍未在时限内拿到结束信号;[reason] 是可读原因,已收到的部分消息仍保留。 */
    data class Interrupted(override val messages: List<String>, val reason: String) : ReplyOutcome

    /** 整个等待窗口到期,期间没有连接中断(网关就是没吐结束信号),已收到的部分消息仍保留。 */
    data class Timeout(override val messages: List<String>, val reason: String) : ReplyOutcome

    /** 被新一轮说话打断(barge);已收到的部分消息仍保留(由调用方按 turnId 决定是否采用)。 */
    data class Cancelled(override val messages: List<String>) : ReplyOutcome
}

/**
 * OpenClaw 一轮回复的收集器:纯逻辑(只依赖 gson + 协程),不依赖 Android / 网络,可 JVM 单测。
 *
 * **body / raw 双路**(核心语义):
 *  - **body** = 「正确正文」,给设备屏与 TTS 用,来源按优先级取:
 *    1. `event=agent` + `stream=lifecycle` + `data.phase=end` 的 `data.terminalReply`,
 *       当 `disposition == "visible"`(或 `data.terminalReceipt.terminalDisposition == "visible"`)
 *       → 用 `terminalReply.text`(网关自己标注「该给用户看」的文本);
 *    2. 回退:`[lifecycle phase=start, phase=finishing/end]` 区间内的 `chat state=final`
 *       (多条按 seq 顺序全保留;final 与 end 可能同 seq,边界用 `<=`);
 *    3. 再退:区间内最后一个 `chat state=delta` 的累计全文;
 *    4. 非 `visible` 的 terminalReply、run 结束后(seq > end)才到的 chat 正文**不进 body**;
 *    5. 没有任何帧正文时(仅 [appendMessage] 的非帧路径)→ 用累积消息。
 *  - **raw** = App 调试视图用的完整回传流:[rawEntriesOf] 把每一帧的「有信息条目」按到达顺序
 *    全部收进 [rawEntries],一条都不丢。**raw 绝不下发设备、不喂 TTS**。
 *    终局(final / lifecycle end)之后到达的帧同样进 raw —— 见 [snapshotRawAndArmUpdate]
 *    与 `OpenClawGateway.POST_TERMINAL_GRACE_MS`(终局后宽限窗)与增量回调。
 *
 * 结束信号:
 *  - `agent lifecycle phase=end` 立即定局;
 *  - `phase=finishing` 进入沉降窗口(同一轮的 `end` 帧紧随其后,要在窗口内收下 `terminalReply`);
 *  - `state=final` 也进入沉降窗口,等同一轮可能继续推送的后续消息。
 *
 * 一轮可能有多条回复:网关可能先后推多条 assistant 消息(答案 + 后续状态消息),
 * 旧实现每次 `set(full)` 覆盖,最后一条会冲掉答案;现在按 seq/区间规则收集成列表,
 * 落在区间外的迟到正文只进 raw,不进 body。
 *
 * @param runId 期望的 run 标识;事件帧 runId 不匹配时丢弃
 * @param sessionKey 期望的会话 key(如 `agent:main:passport`);帧里带了就必须一致
 * @param userText 本轮用户文本(重连后重新订阅要用同一份)
 * @param idempotencyKey 本轮幂等键(重连后重发 chat.send 用它避免网关重复执行)
 * @param deadlineAtMs 本轮等待上限的绝对时刻
 * @param scope 用于 final/finishing 后的沉降计时(等待同一轮可能继续推送的后续帧)
 * @param settleMs 收到 final/finishing 后等待「后续帧」的沉降窗口;<=0 表示立即结束本轮
 */
class ReplyCollector(
    @Volatile var runId: String?,
    val sessionKey: String,
    val userText: String,
    val idempotencyKey: String,
    val deadlineAtMs: Long,
    private val scope: CoroutineScope,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
) {
    private val lock = Any()

    /** 累积的正文(回退路径用:非帧来源 / 无 seq 的帧)。 */
    private val messages = mutableListOf<String>()

    /** 本轮的完整回传流(raw;App 调试视图用,一条不丢)。 */
    private val raw = mutableListOf<RawEntry>()

    /** 区间内的 `chat state=final`(按到达顺序;seq 可能为 null)。 */
    private val finals = mutableListOf<SeqText>()

    /** `chat state=delta` 的累计全文(按到达顺序;取区间内最后一个)。 */
    private val deltas = mutableListOf<SeqText>()

    /** lifecycle `phase=start` 的 seq(body 区间左边界)。 */
    private var startSeq: Int? = null

    /** lifecycle `phase=finishing/end` 的 seq(body 区间右边界)。 */
    private var endSeq: Int? = null

    /** 网关标注为可见的终局回复文本(body 第一优先级)。 */
    private var terminalReplyText: String? = null

    /** 是否处理过任何帧正文(用于决定「区间外丢弃」还是「回退到累积消息」)。 */
    private var hasFrameBody = false

    /** 终结结局;null = 仍在收集中。 */
    @Volatile
    private var terminal: ReplyOutcome? = null

    /** 增量 raw 回调(终局宽限窗内由 chatMulti 装配);null = 未装配,不回调。 */
    @Volatile
    private var rawUpdateSink: ((List<RawEntry>) -> Unit)? = null

    /** 增量回调游标:已作为「首批」交给 App 的 raw 条目数(装配时 = 首批快照条数)。 */
    private var rawDeliveredCount = 0

    private val done = CompletableDeferred<ReplyOutcome>()

    /** final/finishing 后的沉降计时任务(新的帧到达 / 立即结束信号到达时取消)。 */
    private var settleJob: Job? = null

    /** 最近一次连接中断原因(重连成功不清空:本轮确实断过,超时后要按「中断」报原因)。 */
    @Volatile
    var lastConnectionError: String? = null
        private set

    /** 本轮连接中断次数。 */
    @Volatile
    var interruptCount: Int = 0
        private set

    /** 重连后重新订阅的次数。 */
    @Volatile
    var resubscribeCount: Int = 0

    /** 网关侧标记:重连循环是否在跑(避免同一条轮次起多个重连协程)。 */
    @Volatile
    var reconnecting: Boolean = false

    /** 是否已终结(拿到结束信号 / 被 barge 取消 / 超时)。 */
    val isTerminal: Boolean get() = terminal != null

    /**
     * 本轮的完整回传流快照(给 App 调试视图)。
     *
     * **按 `seq` 升序**输出(同一 seq 或缺失 seq 的条目保持到达顺序,排序是稳定的):
     * 多路帧(chat 的 status/delta/final 与 agent 的 run_status/lifecycle/item/tool/…)在同一 run 内
     * 共享一份 seq,只按到达顺序展示会因网络/处理次序出现「finishing 排在 model 前」这类乱序,
     * 按 seq 排一次即与网关侧真实顺序一致(真机实测:model(5) → finishing(18) → end(20))。
     */
    val rawEntries: List<RawEntry>
        get() = synchronized(lock) { raw.sortedWith(SEQ_ORDER) }

    /**
     * 终局后:取 raw「首批」快照并**在同一个锁内**装配增量回调(供终局宽限窗使用)。
     *
     * 为什么必须同一个锁:
     *  - 返回值 = 此刻 raw 的全部条目(与 [rawEntries] 相同的 seq 升序),即交给 App 的首批;
     *  - 游标固定为快照条数,此后 [acceptFrame] 新收下的条目全部经 [sink] 分批回调(增量);
     *  - 「取快照 + 设游标 + 装回调」原子完成,所以宽限窗内任何时刻到达的帧要么在首批里、
     *    要么在增量里,**不会重复也不会丢**。
     *
     * @param sink 增量回调(null = 不回调,只保留「终局后继续收 raw」的行为)
     */
    fun snapshotRawAndArmUpdate(sink: ((List<RawEntry>) -> Unit)?): List<RawEntry> =
        synchronized(lock) {
            val snapshot = raw.sortedWith(SEQ_ORDER)
            rawDeliveredCount = raw.size
            rawUpdateSink = sink
            snapshot
        }

    /** 本轮 raw 条目数(日志摘要用)。 */
    val rawEntryCount: Int get() = synchronized(lock) { raw.size }

    /** 已收集的 body(正确正文)只读快照(按优先级规则计算,按到达顺序)。 */
    val collectedMessages: List<String> get() = synchronized(lock) { bodyMessages() }

    /** 已收集的 body 条数。 */
    val messageCount: Int get() = synchronized(lock) { bodyMessages().size }

    /** 已收集 body 的总字数(日志摘要用)。 */
    val textLength: Int get() = synchronized(lock) { bodyMessages().sumOf { it.length } }

    /** 是否已过等待上限。 */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= deadlineAtMs

    /** 距等待上限还剩多少毫秒(可为 0)。 */
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        (deadlineAtMs - nowMs).coerceAtLeast(0L)

    /**
     * 帧里的 runId 是否属于本轮(进度 / 生命周期帧的归属过滤)。
     * [frameRunId] 为空(null/空白)时不做限制 —— 部分帧不带 runId。
     */
    fun matchesRun(frameRunId: String?): Boolean {
        val current = runId ?: return true
        return frameRunId.isNullOrBlank() || frameRunId == current
    }

    /**
     * 用帧里带的 runId 学习本轮标识(仅在本轮还不知道时;首帧就是用来学习 runId 的)。
     * @return 是否学到了新值
     */
    fun learnRunId(frameRunId: String?): Boolean {
        if (runId != null || frameRunId.isNullOrBlank()) return false
        runId = frameRunId
        return true
    }

    /**
     * 接收一帧原始 JSON:同时收 **raw**(完整回传流)与 **body**(正确正文来源),
     * 并处理 lifecycle 结束信号。
     *
     * @return 是否是本轮的正文帧(chat delta/final,已消费);非正文 / 归属不符 / 已终结返回 false
     */
    fun acceptFrame(frameJson: String): Boolean {
        val currentRun = runId ?: ""
        val events = replyMessagesOf(frameJson, sessionKey, currentRun)
        // raw 不过滤 runId(传空串):用户要求"网关返回的消息一条不丢"。
        // 同一轮里网关可能因重试而切换 runId(实测一轮出现多个 model/usage 段),
        // 若照 runId 过滤会丢掉后半程的 item/tool/command_output 帧。只保留 sessionKey 过滤。
        val rawOfFrame = rawEntriesOf(frameJson, sessionKey, "")
        val lifecycle = lifecycleEventOf(frameJson, sessionKey, currentRun)

        var seal = false
        var consumed = false
        var lateRaw: List<RawEntry> = emptyList()
        synchronized(lock) {
            // raw 在【终局之后也要继续收】:实测 final/lifecycle end 之后仍会来帧,
            // 旧实现用 `if (terminal == null)` 把它们全丢了 → App 里看到的回传流不完整。
            raw.addAll(rawOfFrame)
            // 终局后(宽限窗内)新到的条目走增量回调,不重复首批
            if (terminal != null) lateRaw = takeNewRawLocked()
            if (terminal == null) {
                lifecycle?.let { recordLifecycle(it) }
                for (e in events) {
                    if (runId == null && !e.runId.isNullOrBlank()) runId = e.runId
                    if (e.text.isNotBlank()) {
                        mergeMessages(messages, e.text)
                        if (e.isFinal) finals += SeqText(e.seq, e.text)
                        else deltas += SeqText(e.seq, e.text)
                    }
                    if (e.isFinal) seal = true
                }
                if (events.isNotEmpty()) hasFrameBody = true
                consumed = events.isNotEmpty()
            }
        }
        if (lateRaw.isNotEmpty()) deliverRawUpdate(lateRaw)
        if (lifecycle != null && (lifecycle.phase == "finishing" || lifecycle.phase == "end")) {
            // 结束信号:end 立即定局;finishing 进沉降窗口等紧随其后的 end(要收它的 terminalReply)
            onLifecycle(lifecycle.phase)
        } else if (seal) {
            scheduleSettle()
        }
        return consumed
    }

    /**
     * 直接追加一段正文(body;非帧来源,测试 / 兼容路径使用)。
     * raw 不受影响(它只来自下行帧)。
     * @param isFinal 这一条消息是否由 final 结束(结束则进入沉降窗口)
     */
    fun appendMessage(text: String, isFinal: Boolean = false) {
        var seal = false
        synchronized(lock) {
            if (terminal != null) return
            mergeMessages(messages, text)
            if (isFinal) seal = true
        }
        if (seal) scheduleSettle()
    }

    /**
     * agent lifecycle 阶段:`phase=end` 立即定局;`phase=finishing` 进沉降窗口
     * (同一轮的 end 帧紧随其后,窗口内收下 `terminalReply` 后以 body 第一优先级采用)。
     * @return 是否是结束阶段(已消费)
     */
    fun onLifecycle(phase: String): Boolean {
        if (phase != "finishing" && phase != "end") return false
        if (phase == "end" || settleMs <= 0L) completeCollected() else scheduleSettle()
        return true
    }

    /** 显式结束本轮(无 lifecycle 的实现 / 测试):以已收集消息立即定局。 */
    fun finishFinal() = completeCollected()

    /** barge 打断:终结本轮并让 chat() 尽快返回(已收到的部分消息仍保留)。 */
    fun cancel() = complete(ReplyOutcome.Cancelled(collectedMessages))

    /** WS 断开:只记录,不终结本轮。 */
    fun onConnectionInterrupted(reason: String?) {
        interruptCount++
        lastConnectionError = reason?.takeIf { it.isNotBlank() } ?: "连接已断开"
    }

    /**
     * 等本轮结局。
     *
     * @param timeoutMs 本次等待上限;null = 用到 [deadlineAtMs] 为止
     * @return 终结结局;到点仍未收到结束信号时返回 [ReplyOutcome.Interrupted](断开过)或
     *   [ReplyOutcome.Timeout],两者都带上已收到的部分消息
     */
    suspend fun awaitOutcome(
        timeoutMs: Long? = null,
        nowMs: () -> Long = System::currentTimeMillis,
    ): ReplyOutcome {
        terminal?.let { return it }
        val limit = timeoutMs ?: (deadlineAtMs - nowMs())
        if (limit <= 0) return timeoutOutcome()
        return withTimeoutOrNull(limit) { done.await() } ?: timeoutOutcome()
    }

    /** 到点后的结局:断过连接就报中断原因,否则报等待超时。 */
    private fun timeoutOutcome(): ReplyOutcome {
        terminal?.let { return it }
        val partial = collectedMessages
        val reason = lastConnectionError
        return if (interruptCount > 0 || reason != null) {
            ReplyOutcome.Interrupted(partial, "网关连接中断:${reason ?: "连接已断开"}")
        } else {
            ReplyOutcome.Timeout(partial, "等待网关回复超时:网关在时限内未返回最终结果")
        }
    }

    /**
     * final / finishing 到达:进入沉降窗口,等同一轮可能继续推送的后续帧。
     * 窗口内又有新帧 → 重新计时;窗口内没有更多 → 以已收集消息定局。
     */
    private fun scheduleSettle() {
        synchronized(lock) {
            if (terminal != null) return
        }
        if (settleMs <= 0L) {
            completeCollected()
            return
        }
        val job = scope.launch {
            delay(settleMs)
            completeCollected()
        }
        val previous = synchronized(lock) {
            val prev = settleJob
            settleJob = job
            prev
        }
        previous?.cancel()
    }

    /** 记录 lifecycle 帧:body 区间边界 + 网关标注的可见终局回复。 */
    private fun recordLifecycle(event: LifecycleEvent) {
        when (event.phase) {
            "start" -> if (startSeq == null) startSeq = event.seq
            "finishing", "end" -> endSeq = event.seq ?: endSeq
        }
        if (event.phase == "end" && event.terminalReplyVisible) {
            event.terminalReplyText?.takeIf { it.isNotBlank() }?.let { terminalReplyText = it }
        }
    }

    /**
     * body 优先级(见类注释)。调用方必须持有 [lock]。
     */
    private fun bodyMessages(): List<String> {
        terminalReplyText?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val lo = startSeq ?: Int.MIN_VALUE
        val hi = endSeq ?: Int.MAX_VALUE
        val inRange = finals
            .filter { it.seq == null || it.seq in lo..hi }
            .map { it.text }
            .filter { it.isNotBlank() }
        if (inRange.isNotEmpty()) return inRange
        // 区间内最后一个 delta 的累计全文(迟到的区间外 delta 不影响选择)
        deltas.lastOrNull { it.seq == null || it.seq in lo..hi }
            ?.takeIf { it.text.isNotBlank() }
            ?.let { return listOf(it.text) }
        // 没有任何帧正文(仅 appendMessage 的非帧路径)才回退到累积消息;
        // 否则区间外的迟到正文已被明确排除,不能借累积列表重新混进 body。
        return if (hasFrameBody) emptyList() else messages.toList()
    }

    /** 以当前已收集 body 定局(幂等:终结只认第一次)。 */
    private fun completeCollected() {
        val list = collectedMessages
        complete(if (list.isEmpty()) ReplyOutcome.EmptyFinal() else ReplyOutcome.Reply(list))
    }

    /** 一次性终结(先到者胜,重复调用无副作用)。 */
    private fun complete(outcome: ReplyOutcome) {
        synchronized(lock) {
            if (terminal != null) return
            terminal = outcome
            settleJob?.cancel()
            settleJob = null
        }
        // 在锁外完成,避免唤醒等待协程时重入本对象的同步块
        done.complete(outcome)
    }

    /** 取出「游标之后」新到的 raw 条目并推进游标(调用方必须持有 [lock])。 */
    private fun takeNewRawLocked(): List<RawEntry> {
        if (rawUpdateSink == null || rawDeliveredCount >= raw.size) return emptyList()
        val slice = raw.subList(rawDeliveredCount, raw.size).toList()
        rawDeliveredCount = raw.size
        return slice.sortedWith(SEQ_ORDER)
    }

    /**
     * 把增量条目交给装配的回调(锁外调用,避免回调重入本对象的同步块)。
     * 回调只做 App 展示写入;它抛异常也不能影响本轮收集与定局。
     */
    private fun deliverRawUpdate(entries: List<RawEntry>) {
        val sink = rawUpdateSink ?: return
        try {
            sink(entries)
        } catch (_: Exception) {
            // 展示回调异常不得影响收集:raw 已经收下,丢的只是这一次增量展示
        }
    }

    /** 带 seq 的一段文本(final / delta 的定位信息)。 */
    private data class SeqText(val seq: Int?, val text: String)

    companion object {
        /** final / finishing 后的默认沉降窗口:足够覆盖「同一轮紧随其后的 end / 第二条消息」。 */
        const val DEFAULT_SETTLE_MS = 800L

        /** raw 输出顺序:按 `seq` 升序(缺 seq 排最后,排序稳定)。[rawEntries] 与增量回调共用。 */
        private val SEQ_ORDER: Comparator<RawEntry> = compareBy(nullsLast()) { it.seq }
    }
}
