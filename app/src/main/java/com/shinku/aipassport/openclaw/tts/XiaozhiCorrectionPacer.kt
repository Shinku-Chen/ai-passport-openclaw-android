package com.shinku.aipassport.openclaw.tts

import kotlin.math.roundToInt

/**
 * 某一刻「音频侧是不是紧」的快照(纯数据,由服务侧从 BLE 推送现场取)。
 *
 * 存在的理由:一次**正文补正**要不要现在上屏,只取决于**同一刻**的音频状态,而这个状态只存在于
 * 服务侧的推送现场(已推帧数、自 `tts_start` 起的实时时长、GATT 写回调计数、待发队列长度)。
 * 把它抽成一个值对象后,**判定**([XiaozhiCorrectionPacer])与**日志**([XiaozhiPacingLog])
 * 用同一份数据 —— 真机上「文字动的那一刻垫底是否见底」可以一眼对上,判定也能被 JVM 单测直接喂假数据覆盖。
 *
 * @param active 本段音频是否正在推(已开段 + 已真写出过至少一帧)。false = 没有音频可让路,没必要暂缓。
 * @param leadMs **音频垫底**(ms):已推帧的音频时长 − 自 `tts_start` 起的实时时长;可为负 = 已经欠了。
 * @param leadFrames [leadMs] 折合帧数(只用于日志)。
 * @param inflightFrames **在途**帧数:已交给 BLE、还没拿到 GATT 写回调的帧。
 * @param msSinceLastFrameWrite 距上一帧音频写进 BLE 写队列的毫秒数(-1 = 本段还没写出过帧)。
 * @param queueFrames 服务侧待发队列的长度(帧)。
 */
data class XiaozhiAudioPacing(
    val active: Boolean,
    val leadMs: Long,
    val leadFrames: Double,
    val inflightFrames: Int,
    val msSinceLastFrameWrite: Long,
    val queueFrames: Int,
)

/**
 * **正文补正让路**的纯逻辑(不依赖 Android/coroutines,可 JVM 单测)。
 *
 * ## 为什么需要它
 * 真机现象:**屏幕文字变动(上屏/补正)时语音会卡顿**。两条已定性的机制:
 *  1. **手机侧** —— 正文帧与音频帧**共用一条 BLE 串行写队列**(带响应写下一个音频帧 ≈54ms):
 *     正文帧插队会让音频多等,而正文帧到达设备那一刻还会触发一次渲染;
 *  2. **设备侧(只读事实)** —— BLE RX drain 与 LVGL 文本渲染在**同一个应用任务**(固件 `oc_app.c`):
 *     渲染多行正文那几十毫秒里 RX 排不空 → 解码队列见底 → `欠载`。
 *
 * 设备渲染是**固件侧**的事(本轮不改固件),所以手机侧能做的只有一件事:**别在音频紧的时候去动文字**。
 * 本类就是这个取舍:**首句上屏不受任何影响**(它是播放开始前的信号,必须立刻上屏并开播),
 * 之后的每一次**补正**在音频紧时**暂缓**、攒起来,等垫底恢复或本段音频推完时**一次性补上最终完整正文**。
 *
 * ## 明确取舍(作者已接受)
 * 极端情况下(整段音频都紧 / 句子一直在来)**屏幕文字会晚于声音结束**才补全 —— 用「文字稍晚」
 * 换「声音不断」。收尾那一次补正是**无条件**的([flush]),所以文字最终一定是完整的。
 *
 * ## 状态机
 * ```
 * onTurnStart()            清空(上一轮的待补正文作废)
 * offer(正文, now, pacing) 补正到达:SEND_NOW(立刻上屏) / HOLD(攒起来,覆盖上一条)
 * tick(now, pacing)        音频推进时调用:攒够条件 / 超过兜底时限就吐出待补正文
 * flush(now)               本段音频推完/收尾:无条件吐出待补正文(最终完整正文)
 * ```
 *
 * @param leadHoldMs 「音频垫底」低于它就算**紧**(见 [LEAD_HOLD_MS])
 * @param inflightHoldFrames 在途达到它就算**写队列在积压**(见 [INFLIGHT_HOLD_FRAMES])
 * @param minGapMs 两次补正上屏之间的最小间隔(见 [MIN_CORRECTION_GAP_MS])
 * @param maxCorrections 一轮里最多**主动**补正几次,之后一律攒到收尾(见 [MAX_CORRECTIONS_PER_TURN])
 * @param maxHoldMs 兜底:攒这么久还没等到机会也要补上(见 [MAX_HOLD_MS])
 */
class XiaozhiCorrectionPacer(
    private val leadHoldMs: Long = LEAD_HOLD_MS,
    private val inflightHoldFrames: Int = INFLIGHT_HOLD_FRAMES,
    private val minGapMs: Long = MIN_CORRECTION_GAP_MS,
    private val maxCorrections: Int = MAX_CORRECTIONS_PER_TURN,
    private val maxHoldMs: Long = MAX_HOLD_MS,
) {

    /** 一次补正的处置结果。 */
    enum class Decision {
        /** 现在就上屏。 */
        SEND_NOW,

        /** 暂缓:已攒起来(覆盖上一条),等 [tick] / [flush] 吐出。 */
        HOLD,
    }

    /** 攒着的**最新**正文(多次补正合并成这样一条);null = 没有待补。 */
    private var pending: String? = null

    /** [pending] 第一次被攒起来的时刻(算兜底时限用)。 */
    private var pendingSinceMs = 0L

    /** 上一次**真的上屏**的补正时刻(算最小间隔用);[NO_SEND_YET] = 本轮还没发过。 */
    private var lastSentAtMs = NO_SEND_YET

    /** 本轮已**主动**补正的次数(不含收尾那次)[flush]。 */
    private var sent = 0

    /** 当前攒着的正文(只读;服务侧用它决定要不要跑一次 [tick])。 */
    val pendingBody: String? get() = pending

    /** 本轮已主动补正的次数(只读,日志用)。 */
    val sentCount: Int get() = sent

    /**
     * 新一轮 / 本轮被打断:**丢弃**上一轮攒着的正文并复位记账(幂等)。
     *
     * 为什么是丢弃而不是补发:待补正文属于**上一轮**,而新一轮马上会下发自己的 `'U'`/`'A'`;
     * 此时插一条上一轮的正文只会让设备屏先显示旧内容。作者已接受「极端下文字晚于声音」,
     * 用户按了 OK 打断就是明确地不再关心上一轮的文案。
     */
    fun onTurnStart() {
        pending = null
        pendingSinceMs = 0L
        lastSentAtMs = NO_SEND_YET
        sent = 0
    }

    /**
     * 一条**补正**正文到达(首句**不**走这里,它在服务侧就被放行了)。
     *
     * 同一轮会多次调用:每次都用新正文**覆盖**待补的那条(合并 —— 屏幕要的是「最新最完整」,
     * 中间那些短版本没有单独显示的价值)。
     *
     * @return [Decision.SEND_NOW] = 立刻上屏([canSendNow] 全过);[Decision.HOLD] = 攒起来。
     */
    fun offer(body: String, nowMs: Long, pacing: XiaozhiAudioPacing?): Decision {
        if (body.isBlank()) return Decision.SEND_NOW
        if (pending == null) pendingSinceMs = nowMs
        pending = body
        if (!canSendNow(nowMs, pacing)) return Decision.HOLD
        take(nowMs)
        return Decision.SEND_NOW
    }

    /**
     * 音频侧推进时的例行检查(服务侧在推送循环里调用,代价极低)。
     *
     * @return 该补上屏的正文(null = 继续攒:还没到该发的时机)
     */
    fun tick(nowMs: Long, pacing: XiaozhiAudioPacing?): String? {
        if (pending == null) return null
        // 兜底时限优先:音频那一路可能压根没起来(链路异常/开关中途关掉),
        // 不能让文案无限期地消失 —— 到时无条件补上,哪怕此刻音频还紧。
        if (nowMs - pendingSinceMs >= maxHoldMs) return take(nowMs)
        if (!canSendNow(nowMs, pacing)) return null
        return take(nowMs)
    }

    /**
     * 本段音频推完 / 收尾:**无条件**吐出待补正文(「文字最终一定完整」这条保证落在这里)。
     *
     * @return 该补上屏的正文(null = 没有待补)
     */
    fun flush(nowMs: Long): String? = take(nowMs)

    /**
     * 现在能不能发。
     *
     * - 没有在播的音频([XiaozhiAudioPacing.active] 为假 / 快照为 null)→ **可以**:
     *   没有声音要被保护,再拦就只是让文字白白变晚(含「设备朗读开关关着」这一整类);
     * - 本轮主动补正已达 [maxCorrections] → **不发**(攒到收尾一次补全文);
     * - 距上一次补正不足 [minGapMs] → **不发**(两次渲染停顿不许挤在一起);
     * - 在途 ≥ [inflightHoldFrames] → **不发**(写队列在积压,现在插一条正文帧要排在它们后面);
     * - 垫底 < [leadHoldMs] → **不发**(设备侧缓冲太薄,吃不下一次渲染停顿)。
     */
    private fun canSendNow(nowMs: Long, pacing: XiaozhiAudioPacing?): Boolean {
        if (pacing == null || !pacing.active) return true
        if (sent >= maxCorrections) return false
        if (lastSentAtMs != NO_SEND_YET && nowMs - lastSentAtMs < minGapMs) return false
        if (pacing.inflightFrames >= inflightHoldFrames) return false
        if (pacing.leadMs < leadHoldMs) return false
        return true
    }

    /** 记账并取出待补正文(不变量:取出来的那一刻就等于「它上屏了」)。 */
    private fun take(nowMs: Long): String? {
        val body = pending ?: return null
        pending = null
        pendingSinceMs = 0L
        markSent(nowMs)
        return body
    }

    private fun markSent(nowMs: Long) {
        lastSentAtMs = nowMs
        sent++
    }

    companion object {
        /** 「本轮还没发过补正」的哨兵值(用 `Long.MIN_VALUE`,不与任何真实时刻冲突)。 */
        private const val NO_SEND_YET = Long.MIN_VALUE

        /**
         * **音频垫底**的下限:低于它就算「音频紧」,补正让路。取 **900ms**。
         *
         * 理由(**为什么不是别的值**):
         *  - 设备解码队列 24 包 ≈1.44s,流控目标领先量 1200ms、硬上限 2000ms,在途上限 8 帧(480ms)。
         *    垫底 900ms 再扣掉最坏的在途(≤480ms),设备侧仍有 ≈420ms 缓冲 ——
         *    而设备渲染一段 125–201 字节多行正文的停顿是**几十毫秒**量级,留了 5–10 倍余量;
         *  - 取满 1200ms(等于目标领先量)更安全,但垫底是在开播后以 **+200ms/s** 涨上去的,
         *    要 ~5–6s 才饱和:那样每次补正都会白等到饱和,文案比必要的更晚,
         *    而 900ms 只比目标低 25%,安全性几乎不变;
         *  - 取太小(≤300ms)等于不拦:一次渲染停顿 + 一次写抖动就够把设备侧见底(真机 `欠载=23`)。
         */
        const val LEAD_HOLD_MS = 900L

        /**
         * **在途**的下限:达到它就算「写队列在积压」,补正让路。取 **4 帧**。
         *
         * 理由:在途上限是 8 帧,取它的一半。此刻插一条正文帧(200B 正文 = 2 次 ATT 写)要排在
         * `在途 × ≈54ms` 的音频写之后 —— 4 帧 ≈216ms,一半 ≈108ms,与「一次渲染停顿」同量级;
         * 再往上(比如 6–8 帧)插队的等待就明显长过渲染本身,那次让路就得不偿失。
         */
        const val INFLIGHT_HOLD_FRAMES = 4

        /**
         * 两次补正上屏之间的**最小间隔**:**600ms**(10 帧音频)。
         *
         * 理由:一次正文渲染会把设备 RX 排空几十毫秒,而一帧音频就是 60ms 的播放量 ——
         * 两次补正之间至少留 10 帧的播放量,设备才来得及把 RX 排空、把解码队列重新垫起来。
         * 更短(如 200ms)会把两次渲染停顿挤进同一小段音频里,等于把「一次卡顿」变成「一段卡顿」。
         */
        const val MIN_CORRECTION_GAP_MS = 600L

        /**
         * 一轮里最多**主动**补正几次:取 **3**。
         *
         * 理由:每多一次补正就多一次渲染停顿,而收益递减(屏幕已经从「首句」长到接近完整)。
         * 3 次之后一律攒到收尾的 [flush] 一次补全文 —— 文字最终仍然完整(那条硬约束不受影响)。
         */
        const val MAX_CORRECTIONS_PER_TURN = 3

        /**
         * 兜底时限:**攒了 10s 还没等到机会就无条件补上**。
         *
         * 理由:正常让路最多等一整段音频(真机一段 5–17s),等待期间音频循环一直在跑、
         * [tick] 一直有机会;这个时限只为**音频那一路压根没起来**的病态情况兜底
         * (没有在播音频时 [canSendNow] 本来就放行,所以它实际几乎不会生效)。
         */
        const val MAX_HOLD_MS = 10_000L
    }
}

/**
 * 上屏/补正的**取证日志行**(纯函数,单测钉住格式):真机上一眼看出「文字动的那一刻垫底是否见底」。
 *
 * 形如:`第 2 次上屏/补正 | 音频垫底≈10 帧(≈600ms) | 在途=1 | 距上一音频帧写入 54ms | 队列=37`
 */
object XiaozhiPacingLog {

    /**
     * @param ordinal 本轮第几次**正文上屏**(1 = 首句;≥2 = 补正)
     * @param pacing 音频侧快照;null = 直通未接线(非小智路径)
     */
    fun line(ordinal: Int, pacing: XiaozhiAudioPacing?): String {
        if (pacing == null) return "第 $ordinal 次上屏/补正 | 音频侧无状态(小智直通未接线)"
        val frames = pacing.leadFrames.roundToInt()
        val since = if (pacing.msSinceLastFrameWrite < 0L) {
            "无(本段还没写出过音频帧)"
        } else {
            "${pacing.msSinceLastFrameWrite}ms"
        }
        return "第 $ordinal 次上屏/补正 | 音频垫底≈$frames 帧(≈${pacing.leadMs}ms) | " +
            "在途=${pacing.inflightFrames} | 距上一音频帧写入 $since | 队列=${pacing.queueFrames}"
    }
}
