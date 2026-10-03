package com.shinku.aipassport.openclaw.stt

/**
 * 小智「本轮正文」的**逐段(逐句)取用规则**(纯逻辑:不碰 Android/网络/时间,可 JVM 单测;见
 * `docs/design/xiaozhi-ai-gateway.md` §4.8 按段播放、§5 时序、§6 上屏策略)。
 *
 * ## 作者定的上屏语义(2026-10,**一段一段显示,不累计**)
 * 设备屏上要看到的是「A 段 → B 段 → C 段」三条**各自独立**的文本(并各自配它那一段音频),而不是
 * 「A → AB → ABC」那种**累计**文本 —— 后者会让屏幕上多出 AB、ABC 这些**重复气泡**。小智的回复本来
 * 就是逐句生成的,`tts[sentence_start]` 已经带着**这一句的完整文本**,所以:
 *  - **每次交付 = 当前这一段(这一句)自己的文本**(清洗后非空可读才交),**不**与前后句拼接;
 *  - **首句(第 1 段)立即交付**(延迟优先;TTS 直通侧据此在首句上屏时即可开播);
 *  - `sentence_end` 带来同一句更完整的文本时**只更新本段**(段号不变、不新增一条),更新后的文本再交
 *    一次,调用方就地替换那条字幕。
 *
 * ## 段的划分 / 正文取哪一路
 * 段界由小智自己的句界报文决定:`start`/`sentence_start` = **新的一句开始**;`sentence_end` = 这一句
 * 的文本收口(**不切段**)—— 与 TTS 直通侧([com.shinku.aipassport.openclaw.tts.XiaozhiTtsRelay])
 * 声明段的规则**完全一致**(直通侧也是「一条正文 + 紧随的 start/sentence_start = 一段」),
 * 所以本类给出的段号与直通侧日志里的「第 N 段已声明」对得上。正文来源仍是 **tts 句级文本**
 * (用户听到什么就看到什么):`llm.text` **只**在「本轮连一段 tts 句级文本都没有」时兜底;
 * `emotion` 一类非正文字段永不参与(调用方只喂 `text`)。上屏前统一过 [XiaozhiReplySanitizer]
 * (剔掉服务端工具模板/占位、归一多余空白;**只**删这些,不动正文标点),清洗后不可上屏(只剩
 * 模板/emoji)的段**不上屏**(记一行「跳过」)。
 *
 * ## 结算(`tts.state=stop`):**只补缺,绝不重发累计全文**
 * `stop` 是**最终结算点**,它**不再**交出「累计全文」:
 *  - **各段都已上屏** → `stop` **不产生新的一条**(`changed = false`,只记一行「不重复上屏」);
 *  - **当前段还没上屏 / 它的文本在结算点才变完整** → **只补这一段自己的文本**(那一段缺失时的兜底),
 *    日志写明 `第 N 段字幕(补上:…)`;
 *  - **本轮一段都没有**(纯 `llm` 服务端,或句级文本全为空)→ 整轮当作一段走兜底:**累计 tts 文本优先、
 *    `llm.text` 次之**;两路都不可上屏 → 交空串(调用方给可读原因),**绝不**上屏 emoji/模板;
 *  - **工具调用静默期**:tts 已经给过文本但清洗后不可上屏(只有模板/表情)、**且一段都还没上屏**时,
 *    `stop` **挂起不结算**(`changed = false` + [HELD_FOR_NEXT_SEGMENT_DETAIL])—— `llm.text` 往往是
 *    中间态,在 `stop` 上把它当正文交出去正是「设备屏停在更短文本上」那条老 bug 的成因(§6.4);
 *    真答案一直不来时由**强制兜底窗口**([FORCED_IDLE_GRACE_MS])兜底交出。
 *
 * ## 「无法归段的文本」的判据与取舍(真机排查要看这一条)
 * 段界只认小智的 `start`/`sentence_start`(直通侧也是这么声明段的,两边不能各写一套)。因此:
 *  - `sentence_end` / `stop` 带来的文本**永不**开新段:它是**当前段**的收口/更新。与当前段无关的
 *    文本(既不是它的前缀、也不是它的延长)→ 若当前段**还没上屏**就并入本段(跨报文到达的同一句片段,
 *    如 `<tool_call>` 开标签与闭标签分属两条报文),若当前段**已经上屏**则**只把这条文本当本段的新文本
 *    交出去**(不累计前面已上屏的段 → 不会重新引入 AB/ABC 那种重复气泡);
 *  - 段号 = **本轮第几次真正上屏**(1 起):被跳过(清洗后不可上屏)的段**不占段号**,它的日志用句序
 *    并注明「不占段号」。这样直通侧的「第 N 段已声明」与这里的「第 N 段字幕上屏」在正常流(真机的
 *    「每一句一段」)里逐段对得上。
 *
 * 线程语义:本类不持有线程/定时器,由调用方([XiaozhiSession])在 WS 回调线程喂事件、并按
 * [pendingIdleGraceMs] 安排一次延迟结算;所有状态都在内部锁里,可被多线程调用。
 *
 * @param emit 交出正文的唯一出口(见 [XiaozhiReplyOutcome]):`body` 非空 = 本轮**已清洗**的正文,
 *   空串 = 本轮没有可上屏正文(真的没有文本,或清洗后全是模板/占位/表情);`detail` 说明取自哪一层 /
 *   哪一层为空(日志与可读原因用);`changed` 为 false 时表示这次结算**没有产生新正文**(重复结算、
 *   各段已上屏、或工具调用静默期挂起),调用方只记日志、**不得**上屏也不得再通知观察者。
 *   可能在调用方的任意线程上被调用,实现必须线程安全且不得阻塞(出口下游是网关的等待槽与观察者)。
 * @param llmOnlyGraceMs 整轮**没有任何 tts 报文**时,兜底正文的等待时长(见该常量)。
 * @param forcedIdleGraceMs 收到过 tts 报文(以 `stop` 为权威结算点)时,「一直没等到 `stop`」的
 *   **强制兜底**时长(见该常量)。
 * @param log 段级日志出口(`第 N 段字幕上屏/更新/补上/跳过/不重复`)。本类是纯逻辑、不引 Android 日志,
 *   由会话层把它接到 `Log.i`;默认空实现(单测可直接捕获断言)。
 */
class XiaozhiReplyText(
    private val emit: (XiaozhiReplyOutcome) -> Unit,
    private val llmOnlyGraceMs: Long = LLM_ONLY_FALLBACK_GRACE_MS,
    private val forcedIdleGraceMs: Long = FORCED_IDLE_GRACE_MS,
    private val log: (String) -> Unit = {},
) {

    private val lock = Any()

    /**
     * 一轮里的**段** = 小智自己的一句(段界见类注释)。
     *
     * @property ordinal 段号(1 起):**第几次真正上屏**时分配 —— 与直通侧「第 N 段已声明」同一套编号;
     *   0 = 这一段还没上屏(被跳过 / 还没轮到)。
     * @property raw 本段原文(`sentence_end`/`stop` 会让它变完整;跨报文到达的同一句片段会并入)。
     * @property displayedBody 本段**已上屏**的正文(清洗后);null = 本段还没上屏。
     * @property skipLogged 「跳过」日志是否已打过(同一段只打一行,避免每次事件刷屏)。
     */
    private class Segment {
        var ordinal = 0
        var raw = ""
        var displayedBody: String? = null
        var skipLogged = false
    }

    /** 一段正文是怎么上屏的(只决定日志动词;见 [deliverOpenLocked])。 */
    private enum class Vk { FIRST, NEXT, UPDATE, SUPPLEMENT }

    /** 本轮的段(声明顺序 = 小智的句序)。 */
    private val segments = ArrayList<Segment>()

    /** 正在收口的段(`sentence_end` / `stop` 的文本归它);null = 还没声明过段。 */
    private var open: Segment? = null

    /** 最后一条非空 `llm.text`(兜底候选);null = 还没收到。 */
    private var llmText: String? = null

    /**
     * 本轮是否收到过**任何** tts 报文(哪怕不带文本)。
     *
     * 为什么按它切换结算窗口:收到过 tts 报文说明服务端走的是「句级文本 + `stop`」那条路,`stop` 才是
     * 权威结算点;没收到过(纯 `llm` 服务端)才允许用空闲窗口兜底(它永远不会发 `stop`)。
     */
    private var ttsReportsSeen = false

    /** 本轮是否收到过**非空**的 tts 文本(「工具调用静默期」与整轮兜底的判据)。 */
    private var ttsTextSeen = false

    /** 本轮已上屏的**段数**(= 当前最大的段号)。 */
    private var displayedSegments = 0

    /** 本轮**真正交付**正文的次数(段的上屏与同一段的更新各算一次)。 */
    private var deliveries = 0

    /** 整轮兜底(本轮一段都没有)上一次交付的正文;null = 还没交过。 */
    private var wholeTurnBody: String? = null

    /** 是否已经交过「本轮没有可上屏正文」的空结果(同轮最多一次,后续只记日志)。 */
    private var emittedEmpty = false

    /**
     * 新一轮开始:清空本轮记账(段 / `llm.text` / 已上屏记账)。
     *
     * 幂等;调用方在 `startTurn` / 打断 / 链路断开时调用,保证上一轮的迟到文本不会进新一轮。
     */
    fun onTurnStart() {
        synchronized(lock) {
            segments.clear()
            open = null
            llmText = null
            ttsReportsSeen = false
            ttsTextSeen = false
            displayedSegments = 0
            deliveries = 0
            wholeTurnBody = null
            emittedEmpty = false
        }
    }

    /** 收一条 `llm` 报文的 `text`(非正文字段如 `emotion` **不经过这里**)。 */
    fun onLlm(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        synchronized(lock) {
            // 取**最后**一条:服务端可能先推表情/首片再推正文,后面的更接近完整正文。
            llmText = body
        }
    }

    /**
     * 收一条 `tts` 状态报文(`state` 与 `text` 原样来自报文)。
     *
     * - `start` / `sentence_start`:**新的一句开始**(文本已带则该段立刻尝试交付);
     * - `sentence_end`:同一句的文本收口(**不切段**,更新后立刻尝试再交付一次);
     * - `stop`:**最终结算点**([settleLocked]:只补缺,绝不重发累计全文)。
     * 未知状态按句界处理(老实现也认它带来的文本,不丢内容)。
     */
    fun onTtsState(state: String, text: String) {
        synchronized(lock) {
            if (state.isNotEmpty()) ttsReportsSeen = true
            if (text.isNotEmpty()) ttsTextSeen = true
            when (state) {
                STATE_STOP -> {
                    mergeIntoOpenLocked(text)
                    settleLocked(XiaozhiReplyTrigger.STOP)
                }

                STATE_SENTENCE_END -> {
                    mergeIntoOpenLocked(text)
                    deliverOpenLocked(supplementTrigger = null)
                }

                else -> {
                    openSegmentLocked(text)
                    deliverOpenLocked(supplementTrigger = null)
                }
            }
        }
    }

    /**
     * 空闲结算(调用方按 [pendingIdleGraceMs] 安排定时器后调用):空闲到点 → 把还没上屏的那一段/整轮兜底
     * 交出(见 [settleLocked])。
     *
     * 触发者按「本轮有没有收到过 tts 报文」判定:
     *  - 没有 tts 报文 → 纯 `llm` 兜底窗口([llmOnlyGraceMs]);
     *  - 有 tts 报文 → **强制兜底**(服务端一直没发 `stop`;正常轮次永远走不到这里)。
     *
     * 连一条文本都没收到时本方法**什么都不做**([pendingIdleGraceMs] 也返回 null,调用方不会挂定时器)。
     */
    fun onIdle() {
        synchronized(lock) {
            if (segments.isEmpty() && llmText == null) return
            settleLocked(
                trigger = if (ttsReportsSeen) {
                    XiaozhiReplyTrigger.FORCED
                } else {
                    XiaozhiReplyTrigger.IDLE_LLM_FALLBACK
                },
            )
        }
    }

    /**
     * 现在该按多长空闲窗口安排结算;null = 当前没有可结算候选(不必挂定时器)。
     *
     * 为什么要交给调用方:本类不做时间/线程(纯逻辑可测),定时器由会话层挂,并且**每次事件后
     * 都重新问一次** —— 新的 tts 报文会让「纯 llm 兜底」的长窗口立刻作废,改成「等 `stop`」。
     *
     * 窗口选择(真机 bug 的正向要求):
     *  - 收到过 tts 报文 → [forcedIdleGraceMs](**只**用来兜住不发 `stop` 的服务端,绝不在半路结算);
     *  - 没有 tts 报文但已有 `llm.text` → [llmOnlyGraceMs](那个服务端永远不会给 `stop`,只能空闲兜底);
     *  - 连一条文本都没有 → null(交给网关自己的超时给可读原因)。
     */
    fun pendingIdleGraceMs(): Long? = synchronized(lock) {
        if (segments.isEmpty() && llmText == null) return null
        if (ttsReportsSeen) forcedIdleGraceMs else llmOnlyGraceMs
    }

    // ---- 段的划分 ----

    /**
     * 新的句界(`start` / `sentence_start`):**开一段**(空文本的句界不开空段 —— 空句不占位)。
     *
     * 与当前段原文相同时视为**重复的句界报文**(同一句被推两次),不重复开段 —— 否则同一句话会被上屏
     * 两遍(用户听到一句、看到两条,违背「听到什么就看到什么」)。
     */
    private fun openSegmentLocked(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val cur = open
        if (cur != null && cur.raw == body) return
        logSkipIfNeededLocked(cur)
        val seg = Segment().also { it.raw = body }
        segments.add(seg)
        open = seg
    }

    /**
     * `sentence_end` / `stop` 带来的文本归**当前段**(判别与取舍见类注释「无法归段的文本」)。
     *
     * 没有当前段(`end`/`stop` 先到、没有配对的 `start`)时才开一段 —— 内容不能丢。
     */
    private fun mergeIntoOpenLocked(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val cur = open
        if (cur == null) {
            val seg = Segment().also { it.raw = body }
            segments.add(seg)
            open = seg
            return
        }
        when {
            // 同一句在 sentence_start 与 sentence_end 各带一次:只算一次。
            body == cur.raw -> Unit
            // 这一句还在长(`start` 给半句 / `end` 给整句):取更完整的那份。
            body.startsWith(cur.raw) -> cur.raw = body
            // 后到的更短:留更完整的那份。
            cur.raw.startsWith(body) -> Unit
            // 本段**还没上屏**而文本与它无关:跨报文到达的同一句片段(`<tool_call>` 开闭标签分属两条
            // 报文那种),并入本段 —— 绝不能在这里开新段(段界只认 start/sentence_start)。
            cur.displayedBody == null -> cur.raw = cur.raw + body
            // 本段**已经上屏**而文本与它无关:只把**这一条**当本段的新文本(不累计前面已上屏的段,
            // 所以不会重新出现 AB/ABC 那种重复气泡)。
            else -> cur.raw = body
        }
    }

    // ---- 交付 ----

    /**
     * 交付**当前段**的正文 —— 逐段语义下唯一的「上屏」产生点。
     *
     * 硬约束(不回退「正文不完整」与「不抢跑」):
     *  - 只认 **tts 句级文本**(当前这一段),**不**在这里回退 `llm.text` —— 那是工具调用轮次里
     *    中间态文本抢在真答案前面的成因;
     *  - 清洗后**非空可读**([XiaozhiReplySanitizer.isDisplayable])才交:只拿到模板/emoji/空文本时
     *    一个字节都不交(继续等这一句变完整);
     *  - 与**本段**已经上屏的正文相同则什么都不做(幂等,不重复上屏)。
     *
     * @param supplementTrigger 非 null = 这次交付发生在**结算点**上(段此前没上屏,或本段文本在结算点
     *   才变完整):日志写 `补上`、触发者用它;null = 常规逐段交付(首段/下一段/本段更新)。
     * @return 是否真的交付了一条新正文
     */
    private fun deliverOpenLocked(supplementTrigger: XiaozhiReplyTrigger?): Boolean {
        val seg = open ?: return false
        val body = XiaozhiReplySanitizer.clean(seg.raw)
        if (!XiaozhiReplySanitizer.isDisplayable(body)) return false
        if (body == seg.displayedBody) return false
        val isNew = seg.displayedBody == null
        if (isNew) seg.ordinal = ++displayedSegments
        seg.displayedBody = body
        val first = deliveries == 0
        deliveries++
        val verb = when {
            supplementTrigger != null -> Vk.SUPPLEMENT
            isNew && first -> Vk.FIRST
            isNew -> Vk.NEXT
            else -> Vk.UPDATE
        }
        val trigger = supplementTrigger ?: when (verb) {
            Vk.FIRST -> XiaozhiReplyTrigger.PROGRESS_FIRST
            Vk.UPDATE -> XiaozhiReplyTrigger.PROGRESS_UPDATE
            else -> XiaozhiReplyTrigger.PROGRESS_NEXT
        }
        val line = when (verb) {
            Vk.FIRST, Vk.NEXT -> "第 ${seg.ordinal} 段字幕上屏(本段 ${body.length} 字): $body"
            Vk.UPDATE -> "第 ${seg.ordinal} 段字幕更新(本段 ${body.length} 字): $body"
            Vk.SUPPLEMENT ->
                "第 ${seg.ordinal} 段字幕(补上:$SUPPLEMENT_REASON,本段 ${body.length} 字): $body"
        }
        log(line)
        emit(
            XiaozhiReplyOutcome(
                body = body,
                detail = "正文取自 tts 句级文本(第 ${seg.ordinal} 段,本段 ${body.length} 字);" +
                    layersLocked(),
                trigger = trigger,
                changed = true,
                deliveryIndex = seg.ordinal,
            ),
        )
        return true
    }

    // ---- 结算 ----

    /**
     * 按下 [XiaozhiReplyTrigger] 结算一次(四种情形见类注释「结算」):
     * ① 当前段还没上屏 / 文本在结算点才变完整 → **只补这一段自己的文本**;
     * ② 本轮一段都没有 → 整轮当作一段走兜底(累计 tts 文本 / `llm.text`);
     * ③ 有段级文本但一段都没上屏 → `stop` 上按「工具调用静默期」挂起,强制窗口才兜底;
     * ④ 各段都已上屏、也没有缺段 → **不重复上屏**(绝不发累计全文)。
     */
    private fun settleLocked(trigger: XiaozhiReplyTrigger) {
        // ① 只补「当前段」自己的文本(它此前没上屏,或它的文本在结算点才变完整)。
        if (deliverOpenLocked(supplementTrigger = trigger)) return
        logSkipsLocked()

        // ② 本轮连一段 tts 句级文本都没有(纯 llm 服务端 / 句级文本全为空):整轮当作一段走兜底。
        if (segments.isEmpty()) {
            settleWholeTurnLocked(trigger)
            return
        }

        // ③ 有段级文本,但一段都还没上屏:工具调用静默期在 stop 上挂起(等后续分段),其余窗口兜底。
        if (displayedSegments == 0) {
            if (trigger == XiaozhiReplyTrigger.STOP && ttsTextSeen) {
                log("第 1 段字幕(暂缓:$HELD_FOR_NEXT_SEGMENT_DETAIL)")
                emit(
                    XiaozhiReplyOutcome(
                        body = "",
                        detail = HELD_FOR_NEXT_SEGMENT_DETAIL + ";" + layersLocked(),
                        trigger = trigger,
                        changed = false,
                    ),
                )
                return
            }
            settleWholeTurnLocked(trigger)
            return
        }

        // ④ 各段都已上屏、也没有缺段可补:**绝不再发累计全文**(那正是重复气泡的来源)。
        log("第 $displayedSegments 段字幕(各段均已上屏,共 $displayedSegments 段):不重复上屏")
        emit(
            XiaozhiReplyOutcome(
                body = "",
                detail = "各段字幕均已上屏(共 $displayedSegments 段):不再发累计全文;" + layersLocked(),
                trigger = trigger,
                changed = false,
            ),
        )
    }

    /**
     * 整轮兜底(本轮**一段可上屏的段级文本都没有**):**累计 tts 文本优先、`llm.text` 次之**;
     * 两路都不可上屏 → 交空串(调用方按「本轮没有可上屏正文」给可读原因),**绝不**上屏 emoji/模板。
     *
     * 「累计」在这里是**兜底**而不是常规形态:它只在「逐段交付一段都没发生」时用(例如服务端把整句
     * 只放在 `stop` 的文本里、或纯 `llm` 服务端),所以不会重新引入「A → AB → ABC」那种重复气泡。
     */
    private fun settleWholeTurnLocked(trigger: XiaozhiReplyTrigger) {
        val ttsBody = XiaozhiReplySanitizer.clean(accumulatedTtsLocked().orEmpty())
        val llmBody = XiaozhiReplySanitizer.clean(llmText.orEmpty())
        val displayableTts = XiaozhiReplySanitizer.isDisplayable(ttsBody)
        val body = when {
            displayableTts -> ttsBody
            XiaozhiReplySanitizer.isDisplayable(llmBody) -> llmBody
            else -> ""
        }
        if (body.isEmpty()) {
            if (emittedEmpty) {
                emit(XiaozhiReplyOutcome("", emptyDetailLocked(), trigger, changed = false))
                return
            }
            emittedEmpty = true
            emit(XiaozhiReplyOutcome("", emptyDetailLocked(), trigger, changed = true))
            return
        }
        if (body == wholeTurnBody) {
            emit(
                XiaozhiReplyOutcome(
                    body = body,
                    detail = "整轮兜底正文未变化;" + layersLocked(),
                    trigger = trigger,
                    changed = false,
                ),
            )
            return
        }
        wholeTurnBody = body
        val ordinal = ++displayedSegments
        deliveries++
        val fromLlm = !displayableTts
        val source = if (fromLlm) "llm.text 兜底" else "tts 句级文本(累计)"
        log("第 $ordinal 段字幕(补上:本轮一段都没逐段上屏,用 $source 兜底,本段 ${body.length} 字): $body")
        emit(
            XiaozhiReplyOutcome(
                body = body,
                detail = "正文取自 $source(整轮兜底);" + layersLocked(),
                trigger = trigger,
                changed = true,
                deliveryIndex = ordinal,
            ),
        )
    }

    // ---- 诊断 ----

    /**
     * 给**还没上屏**的段各补一行「跳过」日志(同一段只打一次):它们清洗后不可上屏(只剩工具模板/
     * emoji/空文本),**不占段号** —— 日志用句序(即列表位置),这样与「第 N 段已声明」的段号不会互相
     * 冒充。真机靠这一行判断「某句话为什么没上屏」。
     */
    private fun logSkipsLocked() {
        segments.forEach { logSkipIfNeededLocked(it) }
    }

    private fun logSkipIfNeededLocked(seg: Segment?) {
        if (seg == null || seg.displayedBody != null || seg.skipLogged) return
        val cleaned = XiaozhiReplySanitizer.clean(seg.raw)
        if (XiaozhiReplySanitizer.isDisplayable(cleaned)) return
        seg.skipLogged = true
        val position = segments.indexOf(seg) + 1
        log(
            "第 $position 段字幕(跳过:本段清洗后无可上屏文字(工具模板/表情/空文本),不占段号):" +
                "原文 ${seg.raw.length} 字→清洗后 ${cleaned.length} 字",
        )
    }

    /**
     * 「哪一层为空」的说明(只用于日志与可读原因):把 tts / llm 两层的**原始条数**与**清洗前后字数**
     * 都写出来,这样真机上看到「没有可上屏正文」时一眼能判断是「压根没收到文本」还是「收到的全是
     * 表情/模板」,也能看出清洗到底吃掉了多少字符(真机排查「正文变短」的第一个依据)。
     */
    private fun emptyDetailLocked(): String =
        layersLocked() + ",两路都没有可上屏文字(只表情/模板/占位/空)"

    /** 两层各自的条数与**清洗前后**字数(tts 侧 = 各段原文拼接)。 */
    private fun layersLocked(): String {
        val tts = accumulatedTtsLocked()
        val ttsPart = if (tts == null) {
            "tts 句级文本 无"
        } else {
            "tts 句级文本 ${segments.size} 条(原始 ${tts.length} 字→清洗后 " +
                "${XiaozhiReplySanitizer.clean(tts).length} 字)"
        }
        val llm = llmText
        val llmPart = if (llm == null) {
            "llm.text 无"
        } else {
            "llm.text(原始 ${llm.length} 字→清洗后 ${XiaozhiReplySanitizer.clean(llm).length} 字)"
        }
        return ttsPart + ";" + llmPart
    }

    /** 本轮 tts 各段原文的拼接(只用于整轮兜底与诊断);null = 一段文本都没有。 */
    private fun accumulatedTtsLocked(): String? =
        if (segments.isEmpty()) null else segments.joinToString("") { it.raw }

    companion object {
        /** `tts` 的段落结束状态(小智协议)。 */
        const val STATE_STOP = "stop"

        /** `tts` 的句尾状态:这一句的文本收口(**不切段**,见类注释)。 */
        const val STATE_SENTENCE_END = "sentence_end"

        /**
         * 整轮没有任何 tts 报文(纯 `llm` 服务端)时,兜底正文的等待时长。
         *
         * 为什么远长于正常轮次的静默:这段时间正是「表情/首片 `llm` 已到、tts 首条还没到」的窗口 ——
         * 抢跑就会重演「设备屏上只有一个 emoji」的 bug。只要任何一条 tts 报文到达就作废它
         * (改走 `stop`),所以这个偏大的取值只影响「服务端真的不推 tts」这种退化路径的延迟
         * (仍早于网关 30s 的回复超时)。
         */
        const val LLM_ONLY_FALLBACK_GRACE_MS = 8_000L

        /**
         * 收到过 tts 报文(以 `stop` 为权威结算点)时,「一直没等到 `stop`」的**强制兜底**时长。
         *
         * 为什么需要它:实测云端会在 `sentence_end` 后毫秒级发 `stop`,但**漏发 `stop`** 的服务端
         * (或 `stop` 丢失)会让整轮卡死(设备屏空着、直通音频也拿不到「正文已上屏」信号)。
         * 为什么必须**远长于**旧实现的 2s:那句「最后一句之后 2s 无新增即结算」正是真机「文本不完整」的
         * 成因 —— 工具调用轮次的真答案往往在几秒之后才作为新的分段到达,2s 就把兜底正文交出去了。
         * 取 20s:明显长于正常工具调用/生成(几秒级),又短于网关 30s 的回复超时,
         * 保证「漏发 stop」时设备仍能拿到正文/可读原因,而不是白等一个超时。
         */
        const val FORCED_IDLE_GRACE_MS = 20_000L

        /**
         * 「工具调用静默期挂起」的诊断说明(只进日志/[XiaozhiReplyOutcome.detail],不上屏)。
         *
         * 真机 bug 的现场形状:tts 已经推了一条只含 `% get_weather…` 的句级文本就发了 `stop`,
         * 真答案在下一个分段里 —— 这里挂起就是「不把它当成整轮结束」的明确理由。
         */
        const val HELD_FOR_NEXT_SEGMENT_DETAIL =
            "tts 已收到文本但清洗后不可上屏(工具模板/表情),按「工具调用静默期」挂起,等后续分段"

        /**
         * 结算点上补那一段时的原因说明(进日志,便于真机分辨「这一段为什么晚到 / 为什么是补的」):
         * 段此前一直没上屏(清洗后不可读),或它的文本在结算点才变完整。
         */
        const val SUPPLEMENT_REASON = "本段此前没上屏,结算点只补这一段自己的文本(不累计)"
    }
}

/**
 * 本次结算的**触发者**(真机排查用:先看「有没有等到 `stop`」,再看正文取自哪一层)。
 *
 * @property logName 日志里的中文说明。
 */
enum class XiaozhiReplyTrigger(val logName: String) {
    /** `tts.state=stop`:**最终结算点**(正常轮次都走它;各段都已上屏时不重复交付,只补缺)。 */
    STOP("stop 最终结算"),

    /**
     * **逐段交付的第一段**(作者决定「第一句就好」,延迟优先):首句 tts 文本一清洗出来就交付 ——
     * 直通侧据此在首句上屏时即可开播(不必等 `stop`)。
     */
    PROGRESS_FIRST("首段"),

    /** 逐段交付的后续每一段:新的一句进来就**只交这一句自己的文本**(不累计前面的段)。 */
    PROGRESS_NEXT("下一段"),

    /** 同一段(同一句)的文本在 `sentence_end`/`stop` 上变得更完整:更新**本段**的正文,不新增一条。 */
    PROGRESS_UPDATE("本段更新"),

    /** 整轮没有任何 tts 报文,`llm.text` 兜底的空闲窗口到点。 */
    IDLE_LLM_FALLBACK("空闲窗口(llm 兜底)"),

    /** 收到过 tts 报文却一直没等到 `stop`:强制兜底(退化路径,真机上出现就该查服务端)。 */
    FORCED("强制兜底(一直没等到 stop)"),

    /** 未标注(默认值;只为保持 `data class` 的兼容性)。 */
    UNKNOWN("未标注"),
}

/**
 * [XiaozhiReplyText] 交给调用方的一轮正文结果。
 *
 * @param body **已清洗**的正文;空串 = 本轮确实没有可上屏正文(只有表情/空文本/工具模板)。
 *   逐段语义下每次交付的正文 = **那一段自己的文本**(不累计前后段)。
 * @param detail 人可读的说明:正常时是「取自哪一层、第几段、本段多少字」,为空时是「哪一层为空」
 *   (tts / llm 两层各自的条数与**清洗前后**字数),供日志与失败原因(如 `XiaozhiGateway.NO_BODY`)使用
 *   —— **不**参与上屏正文。
 * @param trigger 本次结算由什么触发([XiaozhiReplyTrigger];日志用)。
 * @param changed 正文相对**上一次结算**是否变化。false = 这次结算没有新正文(重复结算 / 各段都已上屏 /
 *   工具调用静默期挂起),调用方**只记日志**:不上屏、不通知观察者(否则设备上会出现重复气泡)。
 * @param deliveryIndex 本次交付的**段号**(1 起,= 本轮第几次真正上屏);0 = 这次没有交付新正文。
 *   真机日志用它把「第 N 段字幕上屏」与直通侧的「第 N 段已声明」对上。
 */
data class XiaozhiReplyOutcome(
    val body: String,
    val detail: String,
    val trigger: XiaozhiReplyTrigger = XiaozhiReplyTrigger.UNKNOWN,
    val changed: Boolean = true,
    val deliveryIndex: Int = 0,
) {
    /**
     * 交付日志里的**触发者描述**:`首段` / `第N段` / `第N段更新` / `stop 最终结算` / …(见
     * [XiaozhiReplyTrigger])。
     *
     * 「第 N 段」= 本轮**第几次上屏**(= 那次交付的段号):清洗后不可上屏的噪声句**不占段号**,
     * 所以日志里的段号与直通侧「第 N 段已声明」对得上。
     */
    val triggerLabel: String
        get() = when (trigger) {
            XiaozhiReplyTrigger.PROGRESS_NEXT -> "第${deliveryIndex}段"
            XiaozhiReplyTrigger.PROGRESS_UPDATE -> "第${deliveryIndex}段更新"
            else -> trigger.logName
        }
}
