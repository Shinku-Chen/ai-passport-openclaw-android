package com.shinku.aipassport.openclaw.stt

/**
 * 小智「本轮正文」的**取用规则**(纯逻辑:不碰 Android/网络/时间,可 JVM 单测;见
 * `docs/design/xiaozhi-ai-gateway.md` §6 及其修订)。
 *
 * 为什么不能直接用 `llm.text`:真机上上屏的「回复」是**一个 emoji**(设备屏显示为方块),而用户实际
 * 听到、真正的回复在 `tts` 的句级文本里 —— 小智的 `llm` 报文带 `emotion` 一类非正文字段,且首条
 * `llm.text` 可能只是表情/首片。**用户听到什么,就该看到什么**,所以正文的**首选**是 `tts` 的句级
 * 文本拼接,`llm.text` 只做兜底。
 *
 * 规则(按优先级):
 *  1. **首选 tts 句级文本**:`tts.state=start|sentence_start|sentence_end` 的 `text` 按**到达顺序拼接**
 *     (同一句在 `sentence_start` 与 `sentence_end` 各带一次时**只算一次**;句子在增长时取更长的那份),
 *     清洗后**有可读文字**就用它 —— **只要 tts 句级拼接清洗后非空,就绝不用 `llm.text`**;
 *  2. **兜底 llm.text**:tts 那边**没有可上屏文字**(一条文本都没有,或清洗后只剩表情/模板/占位)时
 *     用它(取**最后一条**非空 `text` —— 服务端可能先推表情/首片、后推正文);
 *  3. 两者都没有**可上屏文字** → 交出一个**空串**,表示「本轮确实没有可上屏正文」(空串同时带上
 *     一句说明哪一层为空 -- [XiaozhiReplyOutcome.detail],调用方据此给可读原因),
 *     **绝不**把表情(`emotion`)或空串当正文上屏(非正文字段本类根本收不到:调用方只喂 `text`)。
 *
 * **上屏前先清洗**(见 [XiaozhiReplySanitizer],纯函数):装配好的整段正文里可能夹着服务端工具
 * 模板残留(`% get_weather…` / `<tool_call>…</tool_call>` / `{{…}}` 占位等),必须在上屏前剔掉 ——
 * 清洗只针对这些模板/占位与多余空白,不碰正文标点。**清洗后为空串**时按「本轮没有可上屏正文」
 * 交出空串(与「只有 emoji」同一条可读原因路径,不产生空气泡)。
 *
 * ## 结算时机(2026-10-06 定案「唯一权威结算点是 `tts.state=stop`」;2026-10 起增补「首句即渐进交付」)
 *
 * **渐进交付(作者决定:第一句就好)**:`tts` 句级文本是**逐句**生成的(真机上从第一句到 `stop`
 * 要 ~16s,长回答更久),等 `stop` 才上屏会让「音频响应」显得很慢。现在每收到一句就把**当前累积的
 * 拼接**清洗后交付一次([progressLocked]):只要清洗后**有可读文字**且与上一次交付的正文**不同**,
 * 就立刻交出去(沿用 [XiaozhiReplyOutcome.changed])—— 直通侧据此在**首句**上屏时即可开播,
 * 屏幕文字随后由补正补齐(先短后长)。**硬约束**:中途交付只认 **tts 句级文本**、且必须清洗后
 * **非空可读** —— 只拿到模板/emoji/空文本时**一个字节都不提前交付**,更**绝不**在这里回退
 * `llm.text`(那正是「中间态文本抢在真答案前面上屏」的成因)。`stop` 仍是**最终结算点**:
 * 它做最后一次补正(与上次相同则不重复交付),并在 tts 清洗后不可上屏时挂起不结算(工具调用静默期)。
 *
 * 真机现象「从小智获取的文本不是完整的」(上屏内容比小智实际说的短/漏句)的直接成因有两条,都改掉:
 *  1. **抢跑结算**:旧实现「最后一句之后 2s 无新增即结算」会在服务端还在**逐句推**或**等待工具调用
 *     结果**(工具轮次里先到的往往只有模板/表情那种不可上屏的文本)时先把正文交出去 —— 之后到达的
 *     正式句级文本因为「本轮已结算」被整段丢掉。现在:
 *     - 收到过 tts 报文 → **只认 `stop`**;空闲窗口退化为一条**长强制兜底**(见 [FORCED_IDLE_GRACE_MS],
 *       只用来救「压根不发 `stop`」的服务端),绝不在半路结算;
 *     - 连一条 tts 报文都没有(纯 `llm` 服务端)→ 才用 [LLM_ONLY_FALLBACK_GRACE_MS] 的空闲窗口,
 *       因为那种服务端**永远**不会给 `stop`(这也正是「先到的是表情/首片」的那段静默);
 *     - 两路都没有可上屏文字 → 挂长窗口(不是短窗口),到点交空串 + 说明(可读原因)。
 *  2. **兜底正文先上屏、完整正文再也上不了**:同一轮里正文**可以变好**(多段 `tts[start…stop]` 的
 *     第二段、迟到的句级文本、以及「先用了 `llm.text` 兜底、随后 tts 句级文本才到」)。因此本类不再
 *     用「只结算一次」的闩,而是**只在正文确实变了时才交出**(见 [XiaozhiReplyOutcome.changed]):调用方
 *     每收到一条新正文就**替换掉屏幕上那条**(见 `XiaozhiGateway` 的正文补正),同一轮内绝不会出现
 *     「兜底先上屏、完整正文再也上不了」。
 *
 * **工具调用静默期不结算**(真机 bug 的正面成因):服务端在工具调用轮次里会先推一条**只含模板/表情**
 * 的 tts 文本(`% get_weather…`),真正的回答要等工具结果回来后才到。这种「tts 有文本但清洗后不可上屏」
 * 的情形在 `stop` 上**挂起不结算**(只记日志等后续分段),因为下一段往往就是真答案;强制兜底到点才交
 * 兜底/可读原因。
 *
 * 线程语义:本类不持有线程/定时器,**由调用方**([XiaozhiSession])在 WS 回调线程喂事件、并按
 * [pendingIdleGraceMs] 安排一次延迟结算;所有状态都在内部锁里,可被多线程调用。
 *
 * @param emit 交出正文的唯一出口(见 [XiaozhiReplyOutcome]):`body` 非空 = 本轮**已清洗**的正文,
 *   空串 = 本轮没有可上屏正文(真的没有文本,或清洗后全是模板/占位/表情);`detail` 说明取自哪一层 /
 *   哪一层为空(日志与可读原因用);`changed` 为 false 时表示这次结算**没有产生新正文**(重复结算、
 *   或工具调用静默期挂起),调用方只记日志、**不得**上屏也不得再通知观察者。
 *   可能在调用方的任意线程上被调用,实现必须线程安全且不得阻塞
 *   (出口下游是网关的等待槽位与观察者)。
 * @param llmOnlyGraceMs 整轮**没有任何 tts 报文**时,兜底正文的等待时长(见该常量)。
 * @param forcedIdleGraceMs 收到过 tts 报文(以 `stop` 为权威结算点)时,「一直没等到 `stop`」的
 *   **强制兜底**时长(见该常量)。
 */
class XiaozhiReplyText(
    private val emit: (XiaozhiReplyOutcome) -> Unit,
    private val llmOnlyGraceMs: Long = LLM_ONLY_FALLBACK_GRACE_MS,
    private val forcedIdleGraceMs: Long = FORCED_IDLE_GRACE_MS,
) {

    private val lock = Any()

    /** 本轮 tts 句级文本(按到达顺序,已去重/已取更长的那份)。 */
    private val sentences = ArrayList<String>()

    /** 最后一条非空 `llm.text`(兜底候选);null = 还没收到。 */
    private var llmText: String? = null

    /** 上一条已收下的句级文本(去重与「句子在增长」判定用)。 */
    private var lastSentence: String? = null

    /**
     * 本轮是否收到过**任何** tts 报文(哪怕不带文本)。
     *
     * 为什么按它切换结算时机:收到过 tts 报文说明服务端走的是「句级文本 + `stop`」那条路,`stop` 才是
     * 权威结算点;没收到过(纯 `llm` 服务端)才允许用空闲窗口兜底(它永远不会发 `stop`)。
     */
    private var ttsReportsSeen = false

    /** 已上屏的正文(清洗后,非空);null = 本轮还没交过非空正文。 */
    private var emittedBody: String? = null

    /** 本轮**真正交付**正文的次数(渐进交付与最终结算各算一次;「未变化」的诊断不算)。 */
    private var deliveries = 0

    /** 是否已经交过「本轮没有可上屏正文」的空结果(同轮最多一次,后续只记日志)。 */
    private var emittedEmpty = false

    /**
     * 新一轮开始:清空本轮记账(句级文本 / `llm.text` / 已上屏记账)。
     *
     * 幂等;调用方在 `startTurn` / 打断 / 链路断开时调用,保证上一轮的迟到文本不会进新一轮。
     */
    fun onTurnStart() {
        synchronized(lock) {
            sentences.clear()
            llmText = null
            lastSentence = null
            ttsReportsSeen = false
            emittedBody = null
            emittedEmpty = false
            deliveries = 0
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
     * `stop` 是**最终结算点**(最后一次补正;tts 清洗后不可上屏时挂起不结算),其余状态里的非空文本
     * 先进拼接、再尝试一次**渐进交付**([progressLocked]:清洗后可读且与上次不同就立刻交给调用方,
     * 直通侧据此在首句上屏时开播)。
     * 结算后到达的文本**照样收下**:同一轮多段 `tts[start…stop]` 的第二段、以及迟到的句级文本都靠它
     * 参与下一次交付(见类注释第 2 条)。
     */
    fun onTtsState(state: String, text: String) {
        synchronized(lock) {
            if (state.isNotEmpty()) ttsReportsSeen = true
            if (state == STATE_STOP) {
                settleLocked(trigger = XiaozhiReplyTrigger.STOP)
            } else {
                addSentenceLocked(text)
                progressLocked()
            }
        }
    }

    /**
     * 空闲结算(调用方按 [pendingIdleGraceMs] 安排定时器后调用):空闲到点 → 把当前最优的正文整段交出。
     *
     * 触发者按「本轮有没有收到过 tts 报文」判定:
     *  - 没有 tts 报文 → 纯 `llm` 兜底窗口([llmOnlyGraceMs]);
     *  - 有 tts 报文 → **强制兜底**(服务端一直没发 `stop`;正常轮次永远走不到这里)。
     *
     * 到点时能交什么就交什么(可上屏候选 → 正文;只有不可上屏的文本 → 空串 + 说明);
     * 连一条文本都没收到时本方法**什么都不做**([pendingIdleGraceMs] 也返回 null,调用方不会挂定时器)。
     */
    fun onIdle() {
        synchronized(lock) {
            // 连一条文本都没收到:什么都不做(网关自己的回复超时会给出可读原因)。
            if (ttsTextLocked() == null && llmText == null) return
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
        if (ttsTextLocked() == null && llmText == null) return null
        if (ttsReportsSeen) forcedIdleGraceMs else llmOnlyGraceMs
    }

    /**
     * 收下一句句级文本(去重 + 同一句被推两次时取更完整的那份)。
     *
     * 为什么用「包含关系」而不是只看相等:同一句可能被服务端以**略有差异**的文本推两次
     * (`sentence_start` 给部分文本/带标点差异,`sentence_end` 给完整文本,或反过来)。只看相等会让
     * 同一句话在屏上重复一遍 —— 用户听到的是一句,看到的却是两遍,违背「听到什么就看到什么」。
     *
     * 空句与纯空白句直接丢掉(不占位、也不影响 [lastSentence] 的去重判定)。
     */
    private fun addSentenceLocked(text: String) {
        val sentence = text.trim()
        if (sentence.isEmpty()) return
        val last = lastSentence
        when {
            // 同一句在 sentence_start 与 sentence_end 各带一次:只算一次。
            sentence == last -> Unit
            // 后一条更完整(句子在增长):换成它。
            last != null && sentences.isNotEmpty() && sentence.startsWith(last) -> {
                sentences[sentences.size - 1] = sentence
                lastSentence = sentence
            }
            // 后一条是前一条的前缀(start 比 end 短):已经存着更完整的那份,丢掉后一条。
            last != null && sentences.isNotEmpty() && last.startsWith(sentence) -> Unit
            else -> {
                sentences.add(sentence)
                lastSentence = sentence
            }
        }
    }

    /**
     * **渐进交付**(作者决定「第一句就好」):从**首句**起,只要累积的 tts 拼接清洗后**有可读文字**
     * 且与上一次交付的正文**不同**,就立刻交出去一次 —— 直通侧据此在首句上屏时开播(不必等 `stop`),
     * 屏幕文字随后由补正补齐(先短后长)。
     *
     * **硬约束**(上一轮「正文不完整」的根因就在这里,不许破坏):
     *  - 只认 [ttsCandidateLocked](**tts 句级文本**),**不**回退 `llm.text` —— 工具调用轮次的
     *    `llm.text` 常常只是中间态那一句,提前交付它正是「真答案被挡在门外」的成因;
     *  - 清洗后**非空且可读**([XiaozhiReplySanitizer.isDisplayable])才交付:只有模板/emoji/空文本时
     *    什么都不交(不产生 changed),继续等下一句;
     *  - 与上次交付相同则什么都不做(幂等,不重复上屏)。
     * 与 [settleLocked] 的分工:[settleLocked] 是**最终结算**(可以用 `llm.text` 兜底、可以交空串
     * 走可读原因路径),这里只是**中途交付**。
     */
    private fun progressLocked() {
        val candidate = ttsCandidateLocked() ?: return
        if (!XiaozhiReplySanitizer.isDisplayable(candidate.body)) return
        if (candidate.body == emittedBody) return
        deliverLocked(
            candidate,
            trigger = if (deliveries == 0) {
                XiaozhiReplyTrigger.PROGRESS_FIRST
            } else {
                XiaozhiReplyTrigger.PROGRESS_CORRECTION
            },
        )
    }

    /**
     * 交付一次正文([changed] = true 的唯一产生点):记账(已上屏正文 + 交付次数)并交出口。
     *
     * @param candidate 已选定的正文候选(已清洗、已确认可上屏)
     * @param trigger 本次交付的触发者([XiaozhiReplyTrigger];日志用)
     */
    private fun deliverLocked(candidate: Candidate, trigger: XiaozhiReplyTrigger) {
        emittedBody = candidate.body
        deliveries++
        emit(
            XiaozhiReplyOutcome(
                body = candidate.body,
                detail = candidate.detail,
                trigger = trigger,
                changed = true,
                deliveryIndex = deliveries,
            ),
        )
    }

    /**
     * 按下 [XiaozhiReplyTrigger] 结算一次:选最优候选,与已上屏的正文比对,只在**变了**的时候才真正
     * 交出(changed = true),否则只交一条「未变化」的诊断给调用方记日志。
     *
     * 三种不产生新正文的情形:
     *  - **工具调用静默期**:`stop` 上 tts 有文本但清洗后不可上屏(只有模板/表情)→ 挂起,等后续分段;
     *    真机 bug 就出在这里(旧实现在这个 `stop` 上把 `llm.text` 兜底交出去,随后到达的完整句级文本
     *    因为「已结算」被丢掉);
     *  - **重复结算**:同一段正文被 `stop` / 强制窗口各结算一次(文本没变)→ 不重复上屏;
     *  - **可上屏正文已交过、这次仍只有噪声** → 保持屏幕上已有的正文,不退回空串。
     */
    private fun settleLocked(trigger: XiaozhiReplyTrigger) {
        val ttsRaw = ttsTextLocked()
        val ttsBody = ttsRaw?.let { XiaozhiReplySanitizer.clean(it) }
        // 工具调用静默期:tts **已经**给了文本,但清洗后不可上屏(只有模板/表情)。
        // 此时 stop **不能**算整轮结束 —— 真答案通常就在下一个分段/下一句,而 `llm.text` 往往只是
        // 中间态的一句话(真机那个 102 字节的兜底文本就是这么上了屏的)。因此这里挂起,
        // 只让强制兜底窗口最后一批(见类注释「工具调用静默期不结算」)。
        if (trigger == XiaozhiReplyTrigger.STOP &&
            ttsRaw != null &&
            !XiaozhiReplySanitizer.isDisplayable(ttsBody.orEmpty())
        ) {
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
        val preferred = preferredLocked()
        if (preferred == null) {
            if (emittedEmpty) {
                emit(XiaozhiReplyOutcome("", emptyDetailLocked(), trigger, changed = false))
                return
            }
            emittedEmpty = true
            emit(XiaozhiReplyOutcome("", emptyDetailLocked(), trigger, changed = true))
            return
        }
        if (preferred.body == emittedBody) {
            // 同一段正文(没变):只记日志,不重复上屏(设备上会多一个重复气泡)。
            emit(
                XiaozhiReplyOutcome(
                    body = preferred.body,
                    detail = preferred.detail,
                    trigger = trigger,
                    changed = false,
                ),
            )
            return
        }
        deliverLocked(preferred, trigger)
    }

    /**
     * 本轮**可上屏**的正文候选(已清洗,且已确认含有可读文字),null = 没有。
     *
     * 优先级(设计 §6.1 + 作者对真机 bug 的要求):
     *  1. tts 句级文本拼接**有可读文字** → 用它(用户听到什么就看到什么),**绝不用 `llm.text` 覆盖**;
     *  2. 否则用 `llm.text`(只要它有可读文字) —— 这是「兜底」真正的含义:tts 那边不可上屏
     *     (连一条文本都没有 / 清洗后只剩表情与模板)时才是它的场合。
     * 两路都不可上屏 → null(调用方按「没有可上屏正文」处理,但**不**当场景 1 的代替品上屏表情)。
     */
    private fun preferredLocked(): Candidate? {
        val tts = ttsCandidateLocked()
        if (tts != null && XiaozhiReplySanitizer.isDisplayable(tts.body)) return tts
        val llm = llmCandidateLocked()
        if (llm != null && XiaozhiReplySanitizer.isDisplayable(llm.body)) return llm
        return null
    }

    /** tts 句级拼接的候选(已清洗);null = 一条非空句级文本都没有。 */
    private fun ttsCandidateLocked(): Candidate? {
        val concat = ttsTextLocked() ?: return null
        return Candidate(
            body = XiaozhiReplySanitizer.clean(concat),
            detail = "正文取自 tts 句级文本;" + layersLocked(),
            fromTts = true,
        )
    }

    /** `llm.text` 兜底候选(已清洗);null = 没有非空 `llm.text`。 */
    private fun llmCandidateLocked(): Candidate? {
        val raw = llmText ?: return null
        return Candidate(
            body = XiaozhiReplySanitizer.clean(raw),
            detail = "正文取自 llm.text 兜底(tts 侧无可上屏文字);" + layersLocked(),
            fromTts = false,
        )
    }

    /**
     * 「哪一层为空」的说明(只用于日志与可读原因):把 tts / llm 两层的**原始条数**与**清洗前后字数**
     * 都写出来,这样真机上看到「没有可上屏正文」时一眼能判断是「压根没收到文本」还是「收到的全是
     * 表情/模板」,也能看出清洗到底吃掉了多少字符(真机排查「正文变短」的第一个依据)。
     */
    private fun emptyDetailLocked(): String = layersLocked() + ",两路都没有可上屏文字(只表情/模板/占位/空)"

    /** 两层各自的条数与**清洗前后**字数。 */
    private fun layersLocked(): String {
        val tts = ttsTextLocked()
        val ttsPart = if (tts == null) {
            "tts 句级文本 无"
        } else {
            "tts 句级文本 ${sentences.size} 条(原始 ${tts.length} 字→清洗后 " +
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

    /** 本轮 tts 拼接结果;null = 一条非空句级文本都没有。 */
    private fun ttsTextLocked(): String? =
        if (sentences.isEmpty()) null else sentences.joinToString("")

    companion object {
        /** `tts` 的段落结束状态(小智协议)。 */
        const val STATE_STOP = "stop"

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
         * 一轮正文本轮结算的内部候选:已清洗的 [body]、人可读的 [detail]、以及它是否来自 tts 句级文本。
         */
        private data class Candidate(val body: String, val detail: String, val fromTts: Boolean)
    }
}

/**
 * 本次结算的**触发者**(真机排查用:先看「有没有等到 `stop`」,再看正文取自哪一层)。
 *
 * @property logName 日志里的中文说明。
 */
enum class XiaozhiReplyTrigger(val logName: String) {
    /** `tts.state=stop`:**最终结算点**(正常轮次都走它;与上次相同则不重复交付)。 */
    STOP("stop 最终结算"),

    /**
     * **渐进交付的第一条**(作者决定「第一句就好」):首句 tts 文本一清洗出来就交付 ——
     * 直通侧据此在首句上屏时即可开播(不再等 ~16s 的 `stop`)。
     */
    PROGRESS_FIRST("首句"),

    /** 渐进交付的后续每一条:正文因新句到达而变完整,当作**补正**交给调用方替换屏幕上那条。 */
    PROGRESS_CORRECTION("补正"),

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
 * @param detail 人可读的说明:正常时是「取自哪一层」,为空时是「哪一层为空」(tts / llm 两层各自的
 *   条数与**清洗前后**字数),供日志与失败原因(如 `XiaozhiGateway.NO_BODY`)使用 —— **不**参与上屏正文。
 * @param trigger 本次结算由什么触发([XiaozhiReplyTrigger];日志用)。
 * @param changed 正文相对**上一次结算**是否变化。false = 这次结算没有新正文(重复结算 / 工具调用静默期
 *   挂起),调用方**只记日志**:不上屏、不通知观察者(否则设备上会出现重复气泡)。
 */
data class XiaozhiReplyOutcome(
    val body: String,
    val detail: String,
    val trigger: XiaozhiReplyTrigger = XiaozhiReplyTrigger.UNKNOWN,
    val changed: Boolean = true,
    /** 本轮**第几次真正交付正文**(1 起;0 = 这次没有交付新正文)。真机日志用它看出交付了几次。 */
    val deliveryIndex: Int = 0,
) {
    /**
     * 交付日志里的**触发者描述**:`首句` / `补正(第N句)` / `stop 最终结算` / …(见 [XiaozhiReplyTrigger])。
     *
     * 「第 N 句」= 本轮**第几次交付**(渐进交付下就是第 N 句可上屏的真文本):只拿到模板/emoji 的
     * 噪声句**不计数**,这样日志里的句号与用户实际听到的第几句对得上。
     */
    val triggerLabel: String
        get() = when (trigger) {
            XiaozhiReplyTrigger.PROGRESS_CORRECTION -> "补正(第${deliveryIndex}句)"
            else -> trigger.logName
        }
}
