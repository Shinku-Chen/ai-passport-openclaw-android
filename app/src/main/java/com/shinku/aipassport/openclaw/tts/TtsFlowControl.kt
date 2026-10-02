package com.shinku.aipassport.openclaw.tts

/**
 * 下行 TTS 的**流控**(纯逻辑,不依赖 Android/coroutines,可 JVM 单测)。
 *
 * 设备收到 `tts_start` 后按 60ms/帧实时播放,所以「App 已经推了多少音频」减去「自 `tts_start`
 * 起过了多少实时时间」就是 App **领先设备**的音频量。协议规定领先量不得超过约 **2s**
 * (固件 `intercom-wire-protocol.md`:Phone must not run more than about 2 s of audio ahead of
 * the device),即最多 [MAX_LEAD_FRAMES] = 33 帧未播完就得等。
 *
 * 为什么实际等待得比上限更早([TARGET_LEAD_MS] = 800ms):设备解码队列只有 24 包 ≈1.44s,
 * 贴着 2s 上限推会把队列灌满,设备就按「丢最旧」规则丢掉用户还没听到的语音;800ms 足够吸收
 * BLE 写重试与手机调度抖动,又给队列留 640ms 余量。上限仍是硬约束([atLeadCap] /
 * [MAX_LEAD_FRAMES]),见 `TtsFlowControlTest` 的「按 [waitMs] 节流后领先量恒 ≤2s」用例。
 *
 * 用法(调用方按帧循环):
 * ```kotlin
 * var sent = 0
 * val startedAt = System.currentTimeMillis()
 * frames.forEach { packet ->
 *     val wait = TtsFlowControl.waitMs(sent, System.currentTimeMillis() - startedAt)
 *     if (wait > 0) delay(wait)
 *     send(packet); sent++
 * }
 * ```
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
}
