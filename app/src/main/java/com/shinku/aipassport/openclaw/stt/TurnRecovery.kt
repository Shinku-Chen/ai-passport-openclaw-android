package com.shinku.aipassport.openclaw.stt

/**
 * 「本轮没识别出结果」的补救判定(纯逻辑,不依赖 Android / OkHttp,可在 JVM 单测里覆盖)。
 *
 * 背景(真机现象):小智服务端会在**闲置约 60s** 后静默废弃连接上的识别会话 ——
 * WebSocket 传输层还活着(ping/pong 正常、服务端不报 error),但 `listen.start` 与音频没有响应。
 * 此时 App 认为「热连接可用」,于是用户的整轮语音被发进一个死会话:松手后等满 6s 也拿不到 `stt`,
 * 最后只能回一句「无语音」—— 用户说的话就**丢了**。
 *
 * 修法分两层:
 *  1. 预防:[WarmLink.IDLE_TIMEOUT_MS] 压到服务端会话寿命之内,不让这种死会话留到下一轮;
 *  2. 兜底(本文件):本轮音频**留一份缓存**,一旦「已上送音频但没等到任何 stt」,
 *     就重连一次并把这一轮重放上去 —— 用户的语音仍能被识别,代价只是晚 1–2s,
 *     而不是变成「无语音」。
 *
 * 本文件只回答三个纯问题,[XiaozhiStt] 负责真正的重连与重放:
 *  1. 该不该重试([shouldRetry]);
 *  2. 缓存是否到上限([bufferFull]);
 *  3. 各段等待时限([FIRST_WAIT_MS] / [RECONNECT_WAIT_MS] / [REPLAY_WAIT_MS])。
 */
object TurnRecovery {

    /**
     * 本轮音频上送完毕后,等 `stt` 的第一段时限。
     *
     * 正常一轮实测 ~0.2s 就回 `stt`(真机日志 `识别中…` → `收到小智文本: stt`),1.2s 足够宽松;
     * 它只影响「失败时多晚才补救」,成功路径一收到 stt 就返回,不会白等。
     */
    const val FIRST_WAIT_MS: Long = 1_200L

    /** 补救时重连(建链 + hello)允许的最长耗时:超时就放弃补救,按「无识别」返回。实测 0.3–0.7s。 */
    const val RECONNECT_WAIT_MS: Long = 5_000L

    /** 重放完成后等 `stt` 的时限(与重连预算合计仍远短于「松手 → 设备显示回复」的用户耐心)。 */
    const val REPLAY_WAIT_MS: Long = 5_000L

    /**
     * 单轮最多缓存多少帧:60ms/帧 → 400 帧 ≈ 24s 语音(约 50KB),够覆盖最长一次按住说话,
     * 又不会因为异常长按把内存撑大。
     */
    const val MAX_BUFFER_FRAMES: Int = 400

    /**
     * 是否该重连重放补救。
     *
     * 三个条件同时成立才补救:
     *  - [bufferedFrames] > 0:本轮确实有音频可重放(空轮重试没有意义,只会白连一次);
     *  - [alreadyRetried] == false:每轮最多补救一次(避免死循环式重连);
     *  - [sawAnyStt] == false:本轮一条 `stt` 都没收到(收到了就说明链路是好的,不打断它)。
     */
    fun shouldRetry(
        bufferedFrames: Int,
        alreadyRetried: Boolean,
        sawAnyStt: Boolean,
    ): Boolean = bufferedFrames > 0 && !alreadyRetried && !sawAnyStt

    /** 帧缓存是否已达上限(到上限后不再缓存,只上送,避免异常长按吃内存)。 */
    fun bufferFull(bufferedFrames: Int): Boolean = bufferedFrames >= MAX_BUFFER_FRAMES

    /**
     * 重连重放的时间预算:默认就是上面三个实机常量,JVM 单测降下来跑得快(不改生产行为)。
     */
    data class Budget(
        val firstWaitMs: Long = FIRST_WAIT_MS,
        val reconnectWaitMs: Long = RECONNECT_WAIT_MS,
        val replayWaitMs: Long = REPLAY_WAIT_MS,
    )
}
