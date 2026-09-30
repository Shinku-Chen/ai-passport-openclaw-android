package com.shinku.aipassport.openclaw.tts

import com.shinku.aipassport.openclaw.protocol.VbFrame

/**
 * 下行 TTS 的 PCM 分帧规则(纯逻辑,不依赖 Android,可 JVM 单测)。
 *
 * 每帧恰好一个 Opus 包、固定 60ms(见 `docs/wire-protocol.md` 的 TTS 下行):
 *
 * | 采样率 | 每帧采样数 | 每帧 PCM 字节数 |
 * | --- | --- | --- |
 * | 16000 Hz | 960 | 1920 |
 * | 24000 Hz | 1440 | 2880 |
 *
 * 规则:
 *  - 只有 16k / 24k 能编成设备认的下行帧([supportedRateKhz]);其余采样率 **M1 不重采样**,
 *    调用方据此放弃本轮下发并在日志里说明原因;
 *  - **尾帧不足一帧时补零(静音)成整帧**:libopus 编码器要固定帧长(960 / 1440 采样),
 *    补零既保证最后一个字不被截断,也不给设备引入「短帧」这一协议外状态;
 *  - 完全空的 PCM → 空列表(不发任何帧);
 *  - int16 落单的尾字节(奇数长度)直接丢掉:它不是完整采样点。
 */
object TtsFraming {

    /** 每帧时长(ms)。协议里 `frame_ms` 目前恒为 60。 */
    const val FRAME_MS = 60

    /** 单声道 16bit 小端:每个采样 2 字节。 */
    const val SAMPLE_BYTES = 2

    /** 支持的采样率(Hz):16k(960 samples/帧) 与 24k(1440 samples/帧)。 */
    val SUPPORTED_RATES_HZ = listOf(16_000, 24_000)

    /**
     * 向合成引擎**请求**的采样率:24k(音质更好)。
     * 引擎可能忽略请求(系统 TTS 的输出采样率由引擎决定),实际值以 [DeviceTtsEngine.lastSampleRateHz] 为准。
     */
    const val PREFERRED_RATE_HZ = 24_000

    /** 每帧采样数(16k→960,24k→1440)。 */
    fun frameSamples(sampleRateHz: Int, frameMs: Int = FRAME_MS): Int =
        sampleRateHz * frameMs / 1000

    /** 每帧 PCM 字节数(16k→1920,24k→2880)。 */
    fun framePcmBytes(sampleRateHz: Int, frameMs: Int = FRAME_MS): Int =
        frameSamples(sampleRateHz, frameMs) * SAMPLE_BYTES

    /**
     * 采样率 → 下行帧头的 `rate_khz`(16 / 24)。
     * 其它采样率返回 `0`(协议只认这两个;调用方不要发帧)。
     */
    fun supportedRateKhz(sampleRateHz: Int): Int = when (sampleRateHz) {
        16_000 -> 16
        24_000 -> 24
        else -> 0
    }

    /**
     * 把 PCM 按 60ms 切帧。末片不足一帧时**补零**成整帧(见类注释);空输入返回空列表。
     */
    fun splitPcmFrames(
        pcm: ByteArray,
        sampleRateHz: Int,
        frameMs: Int = FRAME_MS,
    ): List<ByteArray> {
        val frameBytes = framePcmBytes(sampleRateHz, frameMs)
        require(frameBytes > 0) { "unsupported sample rate: $sampleRateHz" }
        if (pcm.isEmpty()) return emptyList()
        val frames = ArrayList<ByteArray>((pcm.size + frameBytes - 1) / frameBytes)
        var off = 0
        while (off < pcm.size) {
            val frame = ByteArray(frameBytes)
            val take = minOf(frameBytes, pcm.size - off)
            System.arraycopy(pcm, off, frame, 0, take)
            // 尾部剩余字节保持 0(静音):统一帧长,末字不会被截断
            frames.add(frame)
            off += take
        }
        return frames
    }

    /**
     * 解析 WAV(PCM 16bit)成「采样率 + 裸 PCM」。
     *
     * 系统 TTS 的 `synthesizeToFile` 产出 44 字节头(可能带 `LIST` 等额外 chunk)的 WAV,
     * 采样率由引擎决定,所以必须读头而不是假设。不支持的格式(非 PCM / 非 16bit / 多声道 /
     * 缺 `data`)返回 null,由调用方记日志放弃本轮。
     *
     * 兼容 `WAVE_FORMAT_EXTENSIBLE`(0xFFFE):很多设备上系统 TTS 用可扩展头写单声道 16bit,
     * 这里按普通 PCM 处理(只校验声道数/位宽)。
     */
    fun pcmFromWav(wav: ByteArray): WavPcm? {
        if (wav.size < 44) return null
        if (tagOf(wav, 0) != "RIFF" || tagOf(wav, 8) != "WAVE") return null
        var rateHz = 0
        var channels = 0
        var bits = 0
        var format = -1
        var dataStart = -1
        var dataLen = 0
        var off = 12
        while (off + 8 <= wav.size) {
            val id = tagOf(wav, off)
            val size = le32(wav, off + 4).toInt()
            if (size < 0) break
            val body = off + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= wav.size) {
                        format = le16(wav, body)
                        channels = le16(wav, body + 2)
                        rateHz = le32(wav, body + 4).toInt()
                        bits = le16(wav, body + 14)
                    }
                }
                "data" -> {
                    dataStart = body
                    dataLen = minOf(size, wav.size - body)
                }
            }
            // chunk 按偶数字节对齐(RIFF 规定)
            off = body + size + (size and 1)
        }
        val pcmFormatOk = format == 1 || format == 0xFFFE
        if (!pcmFormatOk || channels != 1 || bits != 16 || rateHz <= 0) return null
        if (dataStart < 0 || dataLen <= 0) return null
        return WavPcm(
            sampleRateHz = rateHz,
            channels = channels,
            bitsPerSample = bits,
            pcm = wav.copyOfRange(dataStart, dataStart + dataLen),
        )
    }

    /** 读 4 字节 ASCII chunk id。 */
    private fun tagOf(bytes: ByteArray, at: Int): String {
        if (at + 4 > bytes.size) return ""
        return String(bytes, at, 4, Charsets.US_ASCII)
    }

    /** 小端 uint16(读越界返回 0)。 */
    private fun le16(b: ByteArray, at: Int): Int {
        if (at + 2 > b.size) return 0
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }

    /** 小端 uint32(读越界返回 0)。 */
    private fun le32(b: ByteArray, at: Int): Long {
        if (at + 4 > b.size) return 0L
        return ((b[at].toLong() and 0xFF)) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}

/** 从 WAV 里取出的裸 PCM 与其格式信息。 */
data class WavPcm(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    /** 单声道 16bit 小端裸 PCM(已剥掉 WAV 头)。 */
    val pcm: ByteArray,
)

/**
 * 一轮设备朗读的音频计划:分帧 + 逐帧编码后的 Opus 包。纯数据,便于 JVM 单测。
 *
 * @param rateKhz 下行帧头里的 `rate_khz`(16 / 24),整轮固定
 * @param frameMs 下行帧头里的 `frame_ms`(恒 60)
 * @param packets 顺序即播放顺序;每个元素一个 Opus 包(≤ [VbFrame.TTS_OPUS_PAYLOAD_MAX] 字节)
 * @param dropped 因超过载荷上限被丢弃的帧数(只记日志,不重编码)
 */
data class TtsPushPlan(
    val rateKhz: Int,
    val frameMs: Int,
    val packets: List<ByteArray>,
    val dropped: Int,
)

/**
 * 把合成出来的 PCM 编成 60ms 一帧的下行 Opus 包(纯逻辑,编码器由 [encode] 注入以配合单测)。
 *
 * 规则:
 *  - 采样率只支持 16k / 24k([TtsFraming.supportedRateKhz]);其它采样率返回 null
 *    (**M1 不重采样**,调用方记日志说明并放弃本轮);
 *  - 分帧与尾帧补零规则见 [TtsFraming.splitPcmFrames];
 *  - [encode] 返回 null / 空包 → 该帧被丢弃(计入 [TtsPushPlan.dropped]);
 *  - 编码结果超过 [maxPayloadBytes](512) → 丢弃该帧并计数:超限包会让设备按错位处理整帧,
 *    丢掉一个 60ms 帧比打乱后续帧边界划算。
 *
 * @param maxPayloadBytes 单帧 Opus 包上限(默认 [VbFrame.TTS_OPUS_PAYLOAD_MAX])
 * @param encode (整帧 PCM, 每帧采样数) -> Opus 包
 */
fun buildTtsPushPlan(
    pcm: ByteArray,
    sampleRateHz: Int,
    maxPayloadBytes: Int = VbFrame.TTS_OPUS_PAYLOAD_MAX,
    frameMs: Int = TtsFraming.FRAME_MS,
    encode: (frame: ByteArray, samplesPerFrame: Int) -> ByteArray?,
): TtsPushPlan? {
    val rateKhz = TtsFraming.supportedRateKhz(sampleRateHz)
    if (rateKhz == 0) return null
    val samplesPerFrame = TtsFraming.frameSamples(sampleRateHz, frameMs)
    val packets = ArrayList<ByteArray>()
    var dropped = 0
    TtsFraming.splitPcmFrames(pcm, sampleRateHz, frameMs).forEach { frame ->
        val packet = encode(frame, samplesPerFrame)
        when {
            packet == null || packet.isEmpty() -> dropped++
            packet.size > maxPayloadBytes -> dropped++
            else -> packets.add(packet)
        }
    }
    return TtsPushPlan(rateKhz = rateKhz, frameMs = frameMs, packets = packets, dropped = dropped)
}
