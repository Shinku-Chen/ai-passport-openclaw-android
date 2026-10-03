package com.shinku.aipassport.openclaw.stt

/**
 * 小智「本轮正文」的**取用规则**(纯逻辑:不碰 Android/网络,可 JVM 单测;见
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
 *     清洗后**有可读文字**就用它;
 *  2. **兜底 llm.text**:tts 那边**没有可上屏文字**(一条文本都没有,或清洗后只剩表情/模板/占位)时
 *     用它(取**最后一条**非空 `text` —— 服务端可能先推表情/首片、后推正文);
 *  3. 两者都没有**可上屏文字** → 交出一个**空串**,表示「本轮确实没有可上屏正文」(空串同时带上
 *     一句说明哪一层为空 -- [XiaozhiReplyOutcome.detail],调用方据此给可读原因),
 *     **绝不**把表情(`emotion`)或空串当正文上屏(非正文字段本类根本收不到:调用方只喂 `text`)。
 *
 * **「可上屏」的判定**(真机 bug 的反面要求):只要收到过**非空、清洗后含文字**的 tts 句级文本,
 * 就**必须**上屏,不许退化成「没有可上屏的文本」;
 * `llm.text` 只做兜底 —— 而且这里的「兜底」比改动前更准:tts 拼接结果如果**清洗后只剩 emoji/模板**
 * (真机那个带 `% get_weather…` 的工具调用轮次),就用 `llm.text`(它可能正是用户听到的正文),
 * 而不是直接报「本轮没有可上屏正文」。反过来「可上屏」也不许把正常正文吃光:
 * [XiaozhiReplySanitizer] 只剔模板/占位与空白,并用 [XiaozhiReplySanitizer.isDisplayable]
 * 确认「至少有一个字母/数字」,不误删正文标点与文字(真机样本见单测)。
 *
 * **上屏前先清洗**(见 [XiaozhiReplySanitizer],纯函数):装配好的整段正文里可能夹着服务端工具
 * 模板残留(`% get_weather…` / `<tool_call>…</tool_call>` / `{{…}}` 占位等),必须在上屏前剔掉 ——
 * 清洗只针对这些模板/占位与多余空白,不碰正文标点。**清洗后为空串**时按「本轮没有可上屏正文」
 * 交出空串(与「只有 emoji」同一条可读原因路径,不产生空气泡)。
 *
 * 结算(把整段一次性交出)的时机,对齐设计文档 §6 的方案 A(一条回复一个气泡,不做逐句碎气泡):
 *  - `tts.state=stop`(一段朗读结束;真机上 `sentence_end` 到 `stop` 只差毫秒级,所以不拖);
 *  - 或**最后一句之后 `sentenceIdleGraceMs` 内没有新增**([pendingIdleGraceMs]/[onIdle],兜住不发 `stop`
 *    或最后一个 `stop` 丢失的服务端);两个时机都幂等,每轮只结算一次。
 *
 * **不能提前交出「没有可上屏正文」**(真机 bug 的正面成因):工具调用轮次里,服务端可能先推一条
 * 只含模板/表情的 tts 文本(`% get_weather…`),真正的回答要等工具调用完成后才到 —— 如果这时按
 * `sentenceIdleGraceMs`(2s)结算,就会把「本轮没有可上屏正文」抢在真答案前面交出去(设备屏上
 * 就是那句话),而随后的真答案因为本轮已结算而被丢。因此:
 *  - **没有任何可上屏文字时不用短窗口**:`pendingIdleGraceMs` 改挂 [noiseOnlyGraceMs](更长);
 *  - 已有的可上屏正文照旧用 [sentenceIdleGraceMs] / [llmOnlyGraceMs]。
 *
 * 线程语义:本类不持有线程/定时器,**由调用方**([XiaozhiSession])在 WS 回调线程喂事件、并按
 * [pendingIdleGraceMs] 安排一次延迟结算;所有状态都在内部锁里,可被多线程调用。
 *
 * @param emit 交出正文的唯一出口(见 [XiaozhiReplyOutcome]):`body` 非空 = 本轮**已清洗**的正文,
 *   空串 = 本轮没有可上屏正文(真的没有文本,或清洗后全是模板/占位/表情);`detail` 说明取自哪一层 /
 *   哪一层为空(日志与可读原因用)。可能在调用方的任意线程上被调用,实现必须线程安全
 *   且不得阻塞(出口下游是网关的等待槽位与观察者)。
 * @param sentenceIdleGraceMs 已有可上屏的 tts 文本时,「最后一句之后无新增」的判定时长。
 * @param llmOnlyGraceMs 可上屏正文来自 `llm` 兜底时,「最后一条之后无新增」的判定时长(见该常量)。
 * @param noiseOnlyGraceMs 收到过文本但**全部不可上屏**(只表情/模板/空白)时,「最后一条之后无新增」
 *   的判定时长(见该常量;比前两者都长,因为这段静默可能正是工具调用/生成的真答案在路上的时间)。
 */
class XiaozhiReplyText(
    private val emit: (XiaozhiReplyOutcome) -> Unit,
    private val sentenceIdleGraceMs: Long = SENTENCE_IDLE_GRACE_MS,
    private val llmOnlyGraceMs: Long = LLM_ONLY_FALLBACK_GRACE_MS,
    private val noiseOnlyGraceMs: Long = NOISE_ONLY_IDLE_GRACE_MS,
) {

    private val lock = Any()

    /** 本轮 tts 句级文本(按到达顺序,已去重/已取更长的那份)。 */
    private val sentences = ArrayList<String>()

    /** 最后一条非空 `llm.text`(兜底候选);null = 还没收到。 */
    private var llmText: String? = null

    /** 上一条已收下的句级文本(去重与「句子在增长」判定用)。 */
    private var lastSentence: String? = null

    /** 本轮是否已结算(结算后的事件一律忽略,不让迟到的文本进同一个气泡)。 */
    private var settled = false

    /**
     * 新一轮开始:清空本轮记账。
     *
     * 幂等;调用方在 `startTurn` / 打断 / 链路断开时调用,保证上一轮的迟到文本不会进新一轮。
     */
    fun onTurnStart() {
        synchronized(lock) {
            sentences.clear()
            llmText = null
            lastSentence = null
            settled = false
        }
    }

    /** 收一条 `llm` 报文的 `text`(非正文字段如 `emotion` **不经过这里**)。 */
    fun onLlm(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        synchronized(lock) {
            if (settled) return
            // 取**最后**一条:服务端可能先推表情/首片再推正文,后面的更接近完整正文。
            llmText = body
        }
    }

    /**
     * 收一条 `tts` 状态报文(`state` 与 `text` 原样来自报文)。
     *
     * `stop` 触发结算,其余状态里的非空文本进拼接(不带文本的状态不影响候选)。
     */
    fun onTtsState(state: String, text: String) {
        synchronized(lock) {
            if (settled) return
            if (state == STATE_STOP) {
                settleLocked()
            } else {
                addSentenceLocked(text)
            }
        }
    }

    /**
     * 空闲结算(调用方按 [pendingIdleGraceMs] 安排定时器后调用):空闲到点 → 把当前最优的正文整段交出。
     *
     * 三种情形(与 [pendingIdleGraceMs] 的三个窗口一一对应):
     *  - 有可上屏候选(tts 或 `llm` 兜底)→ 用它结算;
     *  - 只有不可上屏的噪声(只表情/模板/空白)→ 已按 [noiseOnlyGraceMs] 等过了,按「本轮没有可上屏正文」
     *    交空串(带说明),让调用方给**可读原因**而不是无限挂着;
     *  - 连一条文本都没收到 → 什么都不做(绝不因为「空闲」就交出一个空串)。
     */
    fun onIdle() {
        synchronized(lock) {
            if (settled) return
            val preferred = preferredLocked()
            when {
                preferred != null -> settleWithLocked(preferred.body, preferred.detail)
                noiseOnlyLocked() -> settleWithLocked("", emptyDetailLocked())
            }
        }
    }

    /**
     * 现在该按多长空闲窗口安排结算;null = 当前没有可结算候选(不必挂定时器)。
     *
     * 为什么要交给调用方:本类不做时间/线程(纯逻辑可测),定时器由会话层挂,并且**每次事件后
     * 都重新问一次** —— 新的 tts 报文会让「纯 llm 兜底」的长窗口立刻作废,改成正常路径。
     *
     * 窗口选择(真机 bug 的正向要求):**没有可上屏文字时绝不用短窗口**。
     *  - 可上屏候选来自 tts → [sentenceIdleGraceMs](正常情形,真机紧跟 `stop`);
     *  - 可上屏候选来自 `llm` 兜底 → [llmOnlyGraceMs];
     *  - 收到过文本但全部不可上屏(只表情/模板)→ [noiseOnlyGraceMs]。
     */
    fun pendingIdleGraceMs(): Long? = synchronized(lock) {
        if (settled) return null
        val preferred = preferredLocked()
        when {
            preferred != null -> if (preferred.fromTts) sentenceIdleGraceMs else llmOnlyGraceMs
            noiseOnlyLocked() -> noiseOnlyGraceMs
            else -> null
        }
    }

    /**
     * 收下一句句级文本(去重 + 同一句被推两次时取更完整的那份)。
     *
     * 为什么用「包含关系」而不是只看相等:同一句可能被服务端以**略有差异**的文本推两次
     * (`sentence_start` 给部分文本/带标点差异,`sentence_end` 给完整文本,或反过来)。只看相等会让
     * 同一句话在屏上重复一遍 —— 用户听到的是一句,看到的却是两遍,违背「听到什么就看到什么」。
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

    /** 按期结算(调用方已确认时机到了):选最优候选,连可上屏文字都没有时交空串 + 说明哪一层为空。 */
    private fun settleLocked() {
        if (settled) return
        val preferred = preferredLocked()
        if (preferred != null) {
            settleWithLocked(preferred.body, preferred.detail)
            return
        }
        settleWithLocked("", emptyDetailLocked())
    }

    /**
     * 结算的唯一出口:标记已结算后调用 [emit](先清洗已由候选选定阶段完成)。
     *
     * @param body 要上屏的正文(空串 = 本轮没有可上屏正文)
     * @param detail 人可读的说明:正常时「取自哪一层」,为空时「哪一层为空」([XiaozhiReplyOutcome.detail])
     */
    private fun settleWithLocked(body: String, detail: String) {
        settled = true
        emit(XiaozhiReplyOutcome(body, detail))
    }

    /**
     * 本轮**可上屏**的正文候选(已清洗,且已确认含有可读文字),null = 没有。
     *
     * 优先级(设计 §6.1 + 作者对真机 bug 的要求):
     *  1. tts 句级文本拼接**有可读文字** → 用它(用户听到什么就看到什么);
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

    /** 收到过文本、但**没有任何可上屏正文**(只表情/模板/空白)。 */
    private fun noiseOnlyLocked(): Boolean =
        ttsTextLocked() != null || llmText != null

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
     * 「哪一层为空」的说明(只用于日志与可读原因):把 tts / llm 两层的**原始条数与清洗后字数**都写出来,
     * 这样真机上看到「没有可上屏正文」时一眼能判断是「压根没收到文本」还是「收到的全是表情/模板」。
     */
    private fun emptyDetailLocked(): String = layersLocked() + ",两路都没有可上屏文字(只表情/模板/占位/空)"

    /** 两层各自的条数与清洗后字数。 */
    private fun layersLocked(): String {
        val tts = ttsTextLocked()
        val ttsPart = if (tts == null) {
            "tts 句级文本 无"
        } else {
            "tts 句级文本 ${sentences.size} 条(清洗后 ${XiaozhiReplySanitizer.clean(tts).length} 字)"
        }
        val llm = llmText
        val llmPart = if (llm == null) {
            "llm.text 无"
        } else {
            "llm.text ${llm.length} 字(清洗后 ${XiaozhiReplySanitizer.clean(llm).length} 字)"
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
         * 有 tts 文本时「最后一句之后无新增」的判定时长。
         *
         * 取值理由:正常收尾是 `tts.state=stop`(实测紧跟 `sentence_end` 毫秒级),这个窗口只用来兜住
         * **不发 `stop`** 的服务端;取 2s 是为了不误判「服务端还在逐句推」的中途停顿(那样会把回复截断),
         * 又不至于让设备屏久等。
         */
        const val SENTENCE_IDLE_GRACE_MS = 2_000L

        /**
         * 整轮没有任何 tts 报文(纯 `llm` 服务端)时,兜底正文的等待时长。
         *
         * 为什么远长于 [SENTENCE_IDLE_GRACE_MS]:这段时间正是「表情/首片 `llm` 已到、tts 首条还没到」的
         * 窗口 —— 抢跑就会重演「设备屏上只有一个 emoji」的 bug。只要任何一条 tts 报文到达就会作废它,
         * 所以这个偏大的取值只影响「服务端真的不推 tts」这种退化路径的延迟(仍早于网关 30s 的回复超时)。
         */
        const val LLM_ONLY_FALLBACK_GRACE_MS = 8_000L

        /**
         * 收到过文本、但**全部不可上屏**(清洗后只剩表情/模板/空白)时,「最后一条之后无新增」的判定时长。
         *
         * 为什么必须比 [SENTENCE_IDLE_GRACE_MS] 长得多(真机 bug 的成因):工具调用轮次里服务端会先推
         * 一条只含 `% get_weather…`/表情的 tts 文本,真正的回答要等工具结果回来后才会作为新的句级文本推来 ——
         * 这段静默就是「答案在路上」。若按 2s 结算,就会把「本轮没有可上屏正文」抢在真答案之前给出(设备屏
         * 就是那句话),而真答案到达时本轮已结算、被丢弃。取 10s 的理由:正常轮次的静默不超过 1–2s,
         * 而慢的工具调用/长生成也不会静默这么久;一旦又有新文本到达,窗口会立即重新计算。
         */
        const val NOISE_ONLY_IDLE_GRACE_MS = 10_000L
    }

    /**
     * 一轮正文本轮结算的内部候选:已清洗的 [body]、人可读的 [detail]、以及它是否来自 tts 句级文本。
     */
    private data class Candidate(val body: String, val detail: String, val fromTts: Boolean)
}

/**
 * [XiaozhiReplyText] 交给调用方的一轮正文结果。
 *
 * @param body **已清洗**的正文;空串 = 本轮确实没有可上屏正文(只有表情/空文本/工具模板)。
 * @param detail 人可读的说明:正常时是「取自哪一层」,为空时是「哪一层为空」(tts / llm 两层各自的
 *   条数与清洗后字数),供日志与失败原因(如 `XiaozhiGateway.NO_BODY`)使用 —— **不**参与上屏正文。
 */
data class XiaozhiReplyOutcome(val body: String, val detail: String)
