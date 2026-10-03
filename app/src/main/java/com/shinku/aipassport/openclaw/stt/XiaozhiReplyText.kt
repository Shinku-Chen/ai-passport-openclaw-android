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
 *     (同一句在 `sentence_start` 与 `sentence_end` 各带一次时**只算一次**;句子在增长时取更长的那份);
 *  2. **兜底 llm.text**:整轮一条 tts 文本都没有时用它(取**最后一条**非空 `text` —— 服务端可能先推
 *     表情/首片、后推正文);
 *  3. 两者都没有 → 交出一个**空串**,表示「本轮确实没有可上屏正文」。调用方据此给可读原因,
 *     **绝不**把表情(`emotion`)或空串当正文上屏(非正文字段本类根本收不到:调用方只喂 `text`)。
 *
 * 结算(把整段一次性交出)的时机,对齐设计文档 §6 的方案 A(一条回复一个气泡,不做逐句碎气泡):
 *  - `tts.state=stop`(一段朗读结束;真机上 `sentence_end` 到 `stop` 只差毫秒级,所以不拖);
 *  - 或**最后一句之后 `sentenceIdleGraceMs` 内没有新增**([pendingIdleGraceMs]/[onIdle],兜住不发 `stop`
 *    或最后一个 `stop` 丢失的服务端);两个时机都幂等,每轮只结算一次。
 *
 * 线程语义:本类不持有线程/定时器,**由调用方**([XiaozhiSession])在 WS 回调线程喂事件、并按
 * [pendingIdleGraceMs] 安排一次延迟结算;所有状态都在内部锁里,可被多线程调用。
 *
 * @param emit 交出正文的唯一出口:非空 = 本轮正文;空串 = 本轮没有可上屏正文。可能在调用方的
 *   任意线程上被调用,实现必须线程安全且不得阻塞(出口下游是网关的等待槽位与观察者)。
 * @param sentenceIdleGraceMs 已有 tts 文本时,「最后一句之后无新增」的判定时长。
 * @param llmOnlyGraceMs 整轮**没有任何 tts 报文**(纯 `llm` 服务端)时,兜底正文的等待时长。
 *   为什么明显长于 [sentenceIdleGraceMs]:这段时间正是「表情 llm 已到、tts 首条还没到」的窗口,
 *   抢跑就会把表情当正文上屏 —— 宁可慢一点(只要任何一条 tts 报文到达就会作废这个定时器)。
 */
class XiaozhiReplyText(
    private val emit: (String) -> Unit,
    private val sentenceIdleGraceMs: Long = SENTENCE_IDLE_GRACE_MS,
    private val llmOnlyGraceMs: Long = LLM_ONLY_FALLBACK_GRACE_MS,
) {

    private val lock = Any()

    /** 本轮 tts 句级文本(按到达顺序,已去重/已取更长的那份)。 */
    private val sentences = ArrayList<String>()

    /** 最后一条非空 `llm.text`(兜底候选);null = 还没收到。 */
    private var llmText: String? = null

    /** 上一条已收下的句级文本(去重与「句子在增长」判定用)。 */
    private var lastSentence: String? = null

    /** 本轮是否收到过**任何** tts 报文(`start`/`sentence_start`/`sentence_end`/`stop`,含不带文本的)。 */
    private var ttsSeen = false

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
            ttsSeen = false
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
     * 所有状态都标记「本轮有过 tts」(即使不带文本);`stop` 触发结算,其余状态里的非空文本进拼接。
     */
    fun onTtsState(state: String, text: String) {
        synchronized(lock) {
            if (settled) return
            ttsSeen = true
            if (state == STATE_STOP) {
                settleLocked()
            } else {
                addSentenceLocked(text)
            }
        }
    }

    /**
     * 空闲结算(调用方按 [pendingIdleGraceMs] 安排定时器后调用):最后一句之后无新增 → 整段交出。
     *
     * 只在**真的有可结算候选**时才结算:有 tts 文本 → 用它;整轮没有 tts 报文但有 `llm.text` → 用兜底。
     * 其它情况保持不动(绝不因为「空闲」就交出一个表情/空串)。
     */
    fun onIdle() {
        synchronized(lock) {
            if (settled) return
            val ttsBody = ttsTextLocked()
            when {
                ttsBody != null -> settleWithLocked(ttsBody)
                !ttsSeen && llmText != null -> settleWithLocked(llmText!!)
            }
        }
    }

    /**
     * 现在该按多长空闲窗口安排结算;null = 当前没有可结算候选(不必挂定时器)。
     *
     * 为什么要交给调用方:本类不做时间/线程(纯逻辑可测),定时器由会话层挂,并且**每次事件后
     * 都重新问一次** —— 新的 tts 报文会让「纯 llm 兜底」的长窗口立刻作废,改成正常路径。
     */
    fun pendingIdleGraceMs(): Long? = synchronized(lock) {
        if (settled) return null
        when {
            ttsTextLocked() != null -> sentenceIdleGraceMs
            !ttsSeen && llmText != null -> llmOnlyGraceMs
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

    /** 按期结算(调用方已确认时机到了):有空串语义 —— 什么都没有时明确交出空串。 */
    private fun settleLocked() {
        if (settled) return
        settleWithLocked(ttsTextLocked() ?: llmText ?: "")
    }

    /** 结算的唯一出口:标记已结算后调用 [emit](空串 = 本轮没有可上屏正文)。 */
    private fun settleWithLocked(body: String) {
        settled = true
        emit(body)
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
    }
}
