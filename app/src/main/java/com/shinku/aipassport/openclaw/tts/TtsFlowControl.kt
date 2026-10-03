package com.shinku.aipassport.openclaw.tts

/**
 * 下行 TTS 的**流控**(纯逻辑,不依赖 Android/coroutines,可 JVM 单测)。
 *
 * 设备收到 `tts_start` 后按 60ms/帧实时播放,所以「App 已经推了多少音频」减去「自 `tts_start`
 * 起过了多少实时时间」就是 App **领先设备**的音频量。协议规定领先量不得超过约 **2s**
 * (固件 `intercom-wire-protocol.md`:Phone must not run more than about 2 s of audio ahead of
 * the device),即最多 [MAX_LEAD_FRAMES] = 33 帧未播完就得等。
 *
 * 为什么实际等待得比上限更早([TARGET_LEAD_MS] = 1200ms):设备解码队列只有 24 包 ≈1.44s,
 * 贴着 2s 上限推会把队列灌满,设备就按「丢最旧」规则丢掉用户还没听到的语音;1200ms 足够吸收
 * BLE 写重试与手机调度抖动,又给队列留 240ms 余量。上限仍是硬约束([atLeadCap] /
 * [MAX_LEAD_FRAMES]),见 `TtsFlowControlTest` 的「按 [waitMs] 节流后领先量恒 ≤2s」用例。
 *
 * 用法(调用方按帧循环):
 * ```kotlin
 * var sent = 0
 * val startedAt = System.currentTimeMillis()
 * frames.forEach { packet ->
 *     val wait = TtsFlowControl.pushWaitMs(sent, System.currentTimeMillis() - startedAt)
 *     if (wait > 0) delay(wait)
 *     send(packet); sent++
 * }
 * ```
 *
 * **小智直通的推送节奏([pushWaitMs],2026-10 真机修正)**:只按领先量节流会有一个坑 ——
 * 「领先量」是**已推音频 − 已过实时**,它只在 App 真的比实时快时才涨,而小智直通实测
 * 带响应写约 18.5 帧/秒(54ms/帧,仅比实时的 60ms/帧快 ~10%),于是开播后**十几秒内**设备侧
 * 缓冲都接近空转,任何一次写回调/调度抖动都会变成设备日志里的 `欠载`(实测一段 215 帧的音频
 * 回报 `欠载=32`)。修正两条:**开播瞬时预充** [PRECHARGE_FRAMES](前几帧不受帧间下限约束,
 * 让设备侧一开始就有垫底音频)+ **与领先量无关的帧间下限** [MIN_SEND_INTERVAL_MS](见 [pushWaitMs])。
 */
object TtsFlowControl {

    /** 每帧时长(ms),与下行帧头 `frame_ms` 一致。 */
    const val FRAME_MS = TtsFraming.FRAME_MS

    /** 领先上限:2000ms(协议规定不超过约 2s)。 */
    const val MAX_LEAD_MS = 2_000

    /** 领先上限折合帧数:2000 / 60 = 33(33 × 60ms = 1980ms ≤ 2000ms)。 */
    const val MAX_LEAD_FRAMES = MAX_LEAD_MS / FRAME_MS

    /**
     * 目标领先量:1200ms。
     *
     * 真机调过一轮:800ms 时听感“一卡一卡”(设备统计 `欠载=57/83`) —— 因为“领先量”是按
     * **已交给 BLE 队列的帧数**算的,而 BLE 写队列当时积了 20+ 帧,实际送到设备的音频远不到
     * 800ms,设备解码队列经常见底。现配合 [MAX_INFLIGHT_FRAMES] 把“在途”卡死,
     * 于是设备侧真实缓冲 ≈ 1200ms − 8×60ms ≈ 720ms,再往上就会碰设备 24 包 ≈1.44s 的解码队列上限。
     */
    const val TARGET_LEAD_MS = 1_200

    /**
     * 允许同时“在途”(已交给 BLE、但还没拿到 GATT 写回调)的帧数上限。
     *
     * 超过它就先等 —— 否则所谓领先量只存在于**手机**队列里,设备那边是空的,
     * 播放只能靠补静音维持,听感就是一卡一卡。8 帧 ≈ 480ms,足够吸收 BLE 写入的抖动。
     */
    const val MAX_INFLIGHT_FRAMES = 8

    /**
     * 在途帧是否已经超过上限。
     *
     * @param sentFrames 已交给 BLE 队列的帧数(本轮累计)
     * @param deliveredFrames 已拿到 GATT 写回调的帧数(本轮累计)
     */
    fun inFlightExceeds(
        sentFrames: Int,
        deliveredFrames: Int,
        maxInFlight: Int = MAX_INFLIGHT_FRAMES,
    ): Boolean = sentFrames - deliveredFrames > maxInFlight

    /**
     * **开播瞬时预充的帧数**:前 [PRECHARGE_FRAMES] 帧不再受帧间下限([MIN_SEND_INTERVAL_MS])约束。
     *
     * 取值 6 帧 ≈ 360ms 音频:
     *  - **够垫底**:设备解码队列是 24 包 ≈1.44s,6 帧占其中 1/4,足够吸收开播瞬间的几次写抖动;
     *  - **不拖开播**:首帧照发,预充只影响第 2–6 帧的发射时刻 —— 「文字上屏 → 立刻出声」不受影响
     *    (声音在第 1 帧就到设备了);
     *  - **远低于上限**:预充最多把领先量抬到 360ms,离 [TARGET_LEAD_MS] = 1200ms 与协议硬上限
     *    [MAX_LEAD_MS] = 2000ms 都还很远,不会把设备解码队列灌满。
     */
    const val PRECHARGE_FRAMES = 6

    /**
     * **帧间最小间隔(实时下限)**:预充之后,每帧之间至少隔这么久 —— 它给推送速率封顶(≤ 1000/间隔 帧/秒),
     * 与「领先量」无关(领先量只封上限,不封速率)。
     *
     * 取值 50ms(20 帧/秒),理由:
     *  - **比实时快**:实时是 60ms/帧,50ms 快 20%,所以领先量(设备侧垫底音频)在整段里持续增长
     *    (每秒 +200ms)—— 这段「垫底还没攒起来」的窗口正是欠载发生的地方,所以取用户给的
     *    50–55ms 区间的**下限**(55ms 时增长只有 91ms/s,攒满要十几秒);
     *  - **不比实测吞吐更慢**:带响应写实测 ≈54ms/帧,50ms 快于实测值 → 在本机/本固件上它**不生效**
     *    (真正限速的是 BLE 往返),不会成为段落中段的新瓶颈;
     *  - **又能防止突发灌爆对端**:MTU 吃满后(一帧一次 ATT 写即可发完)写往返可能远快于 54ms,
     *    这时由它把速率压在 20 帧/秒以内,对端 4KB RX ring 与 24 包解码队列不会被一次突发灌满
     *    (领先量上限仍然硬封着「最多领先 2s」)。
     */
    const val MIN_SEND_INTERVAL_MS = 50L

    /**
     * 在途积压等待的**上限**(ms):超过它必须放行,否则链路半死时整段卡死在这里。
     *
     * 1s 的理由:在途积压意味着「已交给 BLE、还没等到写回调」—— 写回调本身有
     * `WRITE_CALLBACK_TIMEOUT_MS` = 2s 的超时兜底,等满 1s 仍然没有回调,基本可以判定这一跳堵住了;
     * 继续无限等只会让设备停在半途(欠载/无音),而放行 + 明显日志至少能让整段走完并留下证据。
     */
    const val MAX_INFLIGHT_WAIT_MS = 1_000L

    /** 在途积压的轮询间隔(ms):等待期间每这么久重查一次送达计数。 */
    const val INFLIGHT_POLL_MS = 5L

    /**
     * 在途积压还要等多久。
     *
     * @return >0 = 还要等这么多毫秒([INFLIGHT_POLL_MS]);0 = 立刻可以推下一帧
     *   (在途已回到上限内,或已等满 [MAX_INFLIGHT_WAIT_MS] 的有界上限)
     */
    fun backlogWaitMs(
        sentFrames: Int,
        deliveredFrames: Int,
        waitedMs: Long,
        maxInFlight: Int = MAX_INFLIGHT_FRAMES,
        maxWaitMs: Long = MAX_INFLIGHT_WAIT_MS,
        pollMs: Long = INFLIGHT_POLL_MS,
    ): Long =
        if (waitedMs >= maxWaitMs) 0L
        else if (inFlightExceeds(sentFrames, deliveredFrames, maxInFlight)) pollMs
        else 0L

    /** 领先量(ms):已推帧的音频时长减去自 `tts_start` 起的实时时长;可为负(还没追平播放)。 */
    fun leadMs(sentFrames: Int, elapsedMs: Long, frameMs: Int = FRAME_MS): Long =
        sentFrames.toLong() * frameMs - elapsedMs

    /** 领先量折合帧数(向上取整只用于日志)。 */
    fun leadFrames(sentFrames: Int, elapsedMs: Long, frameMs: Int = FRAME_MS): Double =
        leadMs(sentFrames, elapsedMs, frameMs).toDouble() / frameMs

    /**
     * 已推 [sentFrames] 帧、自 `tts_start` 已过 [elapsedMs] 毫秒时,还该等多久才推下一帧。
     *
     * @return 0 = 立刻可推;>0 = 等这么多毫秒(等待期间若被 barge/abort,调用方须立即停止)
     */
    fun waitMs(
        sentFrames: Int,
        elapsedMs: Long,
        frameMs: Int = FRAME_MS,
        leadTargetMs: Int = TARGET_LEAD_MS,
    ): Long = (leadMs(sentFrames, elapsedMs, frameMs) - leadTargetMs).coerceAtLeast(0L)

    /** 是否已到硬上限(领先 ≥2s / ≥33 帧未播完):到点必须等,不能再推。 */
    fun atLeadCap(
        sentFrames: Int,
        elapsedMs: Long,
        frameMs: Int = FRAME_MS,
        maxLeadMs: Int = MAX_LEAD_MS,
    ): Boolean = leadMs(sentFrames, elapsedMs, frameMs) >= maxLeadMs

    /**
     * **小智直通的推送节奏**:领先量节流([waitMs])与帧间下限([MIN_SEND_INTERVAL_MS])取较大者。
     *
     * - 领先量那一半保「不超速」的上限(领先设备 ≤ [TARGET_LEAD_MS],硬上限 [MAX_LEAD_MS]);
     * - 帧间下限那一半保**速率**有界且不慢于实时(预充之后每帧至少隔 [MIN_SEND_INTERVAL_MS]),
     *   于是设备侧垫底音频在整段里持续增长,而不是像只按领先量节流时那样十几秒都贴地;
     * - 前 [prechargeFrames] 帧**不受下限约束**(开播瞬时预充,见 [PRECHARGE_FRAMES]),
     *   但仍受领先量约束(所以硬上限不会被预冲突破)。
     *
     * @return 0 = 立刻可推;>0 = 等这么多毫秒(等待期间若被 barge/abort,调用方须立即停止)
     */
    fun pushWaitMs(
        sentFrames: Int,
        elapsedMs: Long,
        frameMs: Int = FRAME_MS,
        leadTargetMs: Int = TARGET_LEAD_MS,
        minIntervalMs: Long = MIN_SEND_INTERVAL_MS,
        prechargeFrames: Int = PRECHARGE_FRAMES,
    ): Long {
        val leadWait = waitMs(sentFrames, elapsedMs, frameMs, leadTargetMs)
        if (sentFrames < prechargeFrames) return leadWait
        val floorWait = (sentFrames * minIntervalMs - elapsedMs).coerceAtLeast(0L)
        return maxOf(leadWait, floorWait)
    }
}
