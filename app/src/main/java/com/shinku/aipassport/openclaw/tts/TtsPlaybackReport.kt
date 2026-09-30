package com.shinku.aipassport.openclaw.tts

import com.google.gson.JsonObject

/**
 * 设备回报的 TTS 播放结果(设备→App 的 `EVENT` 帧;见 `docs/wire-protocol.md` 的 TTS 下行)。
 *
 * 设备是唯一知道「实际听到多少」的一方:它可能因队列溢出丢掉最旧的包、也可能因解码失败中止,
 * 所以 App 只把这两个事件当**日志与对账**用(「已发送」与「已播放」分开看),不当作逐帧信用
 * (per-frame credit)—— 流控只靠实时节奏(见 [TtsFlowControl])。
 *
 * 字段为 `-1` 表示设备没带这个字段(例:`tts_playback_aborted` 只有 `ev`)。
 */
data class TtsPlaybackReport(
    /** 事件名:`tts_playback_done` / `tts_playback_aborted`。 */
    val ev: String,
    /** 设备接受的包数。 */
    val frames: Int = UNKNOWN,
    /** 设备成功解码的包数。 */
    val decoded: Int = UNKNOWN,
    /** 设备丢弃的包数(队列溢出 + SEQ 缺口)。 */
    val dropped: Int = UNKNOWN,
    /** 播放中途没音频可播的次数。 */
    val underruns: Int = UNKNOWN,
    /** 单包解码最长耗时(µs)。 */
    val decodeUsMax: Int = UNKNOWN,
) {
    companion object {
        /** 设备没带该字段。 */
        const val UNKNOWN = -1

        const val DONE = "tts_playback_done"
        const val ABORTED = "tts_playback_aborted"

        /** 这两个事件都是「一段 TTS 结束」的信号。 */
        val ALL = listOf(DONE, ABORTED)
    }
}

/**
 * 把设备 `EVENT` 的 JSON 解析成 [TtsPlaybackReport];不是 TTS 播放回报时返回 null。
 */
fun parseTtsPlaybackReport(obj: JsonObject): TtsPlaybackReport? {
    val ev = obj.get("ev")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    if (ev !in TtsPlaybackReport.ALL) return null
    return TtsPlaybackReport(
        ev = ev,
        frames = intOrUnknown(obj, "frames"),
        decoded = intOrUnknown(obj, "decoded"),
        dropped = intOrUnknown(obj, "dropped"),
        underruns = intOrUnknown(obj, "underruns"),
        decodeUsMax = intOrUnknown(obj, "decode_us_max"),
    )
}

/**
 * 播放回报的日志行:
 * `TTS 播放完成: frames=12 decoded=12 dropped=0 underruns=0 decode_us_max=812`
 * `TTS 播放中止: frames=? decoded=? dropped=? underruns=? decode_us_max=?`
 *
 * 设备没带的字段显示 `?`(而不是假装 0):「未知」与「0 次丢包」是两件事。
 */
fun ttsPlaybackLogLine(report: TtsPlaybackReport): String {
    val head = if (report.ev == TtsPlaybackReport.ABORTED) "TTS 播放中止" else "TTS 播放完成"
    return "$head: frames=${num(report.frames)} decoded=${num(report.decoded)} " +
        "dropped=${num(report.dropped)} underruns=${num(report.underruns)} " +
        "decode_us_max=${num(report.decodeUsMax)}"
}

private fun num(v: Int): String = if (v == TtsPlaybackReport.UNKNOWN) "?" else v.toString()

private fun intOrUnknown(obj: JsonObject, key: String): Int =
    obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
        runCatching { p.asInt }.getOrNull() ?: TtsPlaybackReport.UNKNOWN
    } ?: TtsPlaybackReport.UNKNOWN
