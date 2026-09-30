package com.shinku.aipassport.openclaw.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下行 TTS 的 PCM 分帧规则单测(纯 JVM,不依赖设备/Android)。
 *
 * 规则(见 [TtsFraming] / `docs/wire-protocol.md` 的 TTS 下行):
 *  - 每帧一个 Opus 包、固定 60ms:16k → 960 samples / 1920B,24k → 1440 samples / 2880B;
 *  - **尾帧不足一帧时补零成整帧**(静音),末字不被截断、也不引入协议外的短帧;
 *  - 只支持 16k/24k;空 PCM 不发帧;int16 落单尾字节丢掉。
 */
class TtsFramingTest {

    @Test
    fun frame_geometry_for_both_supported_rates() {
        assertEquals(960, TtsFraming.frameSamples(16_000))
        assertEquals(1_920, TtsFraming.framePcmBytes(16_000))
        assertEquals(16, TtsFraming.supportedRateKhz(16_000))

        assertEquals(1_440, TtsFraming.frameSamples(24_000))
        assertEquals(2_880, TtsFraming.framePcmBytes(24_000))
        assertEquals(24, TtsFraming.supportedRateKhz(24_000))

        assertEquals(listOf(16_000, 24_000), TtsFraming.SUPPORTED_RATES_HZ)
    }

    @Test
    fun unsupported_rate_has_no_rate_khz() {
        // 系统 TTS 实测可能输出 22050 / 44100:协议帧头只有 16/24,M1 不重采样 → 不下发
        assertEquals(0, TtsFraming.supportedRateKhz(22_050))
        assertEquals(0, TtsFraming.supportedRateKhz(48_000))
        assertEquals(0, TtsFraming.supportedRateKhz(8_000))
    }

    @Test
    fun exact_multiple_of_frames_splits_without_padding() {
        // 16k / 120ms = 2 帧
        val pcm = ByteArray(TtsFraming.framePcmBytes(16_000) * 2) { (it and 0xFF).toByte() }
        val frames = TtsFraming.splitPcmFrames(pcm, 16_000)

        assertEquals(2, frames.size)
        assertTrue(frames.all { it.size == 1_920 })
        assertArrayEquals(pcm.copyOfRange(0, 1_920), frames[0])
        assertArrayEquals(pcm.copyOfRange(1_920, 3_840), frames[1])
    }

    @Test
    fun tail_frame_is_zero_padded_to_a_full_frame() {
        // 16k / 100ms = 960 + 640 samples → 第 2 帧补零到 960 samples(1920B)
        val pcm = ByteArray(3_200) { (it and 0xFF).toByte() }
        val frames = TtsFraming.splitPcmFrames(pcm, 16_000)

        assertEquals(2, frames.size)
        assertEquals(1_920, frames[1].size)
        assertArrayEquals(pcm.copyOfRange(1_920, 3_200), frames[1].copyOfRange(0, 1_280))
        assertArrayEquals(ByteArray(640), frames[1].copyOfRange(1_280, 1_920))
    }

    @Test
    fun single_short_frame_is_padded_and_empty_input_yields_no_frame() {
        val frames = TtsFraming.splitPcmFrames(ByteArray(10), 24_000)
        assertEquals(1, frames.size)
        assertEquals(2_880, frames[0].size)
        assertArrayEquals(ByteArray(10), frames[0].copyOfRange(0, 10))
        assertArrayEquals(ByteArray(2_870), frames[0].copyOfRange(10, 2_880))

        assertTrue(TtsFraming.splitPcmFrames(ByteArray(0), 16_000).isEmpty())
    }

    @Test
    fun dangling_odd_byte_is_dropped_into_padding() {
        // 1921B:最后 1B 不是完整 int16 采样点,但按「补零成整帧」的规则统一进第 2 帧尾部
        val pcm = ByteArray(1_921) { 0x11 }
        val frames = TtsFraming.splitPcmFrames(pcm, 16_000)

        assertEquals(2, frames.size)
        assertEquals(1_920, frames[0].size)
        assertEquals(0x11, frames[1][0].toInt())
        assertArrayEquals(ByteArray(1_919), frames[1].copyOfRange(1, 1_920))
    }

    // ---- 计划(分帧 + 逐帧编码) ----

    /** 记录编码器收到的分帧,验证「每帧 60ms 整帧」传给 libopus。 */
    private class FakeEncoder(val packet: ByteArray = ByteArray(40)) {
        val frames = ArrayList<ByteArray>()
        val samples = ArrayList<Int>()

        fun encode(frame: ByteArray, samplesPerFrame: Int): ByteArray {
            frames.add(frame)
            samples.add(samplesPerFrame)
            return packet
        }
    }

    @Test
    fun push_plan_encodes_60ms_frames_at_16k() {
        val enc = FakeEncoder()
        // 130ms @16k = 2 整帧 + 尾帧(补零)
        val pcm = ByteArray(TtsFraming.framePcmBytes(16_000) * 2 + 960) { it.toByte() }
        val plan = buildTtsPushPlan(pcm, 16_000, encode = enc::encode)!!

        assertEquals(16, plan.rateKhz)
        assertEquals(60, plan.frameMs)
        assertEquals(3, plan.packets.size)
        assertEquals(0, plan.dropped)
        assertEquals(listOf(960, 960, 960), enc.samples)
        assertTrue(enc.frames.all { it.size == 1_920 })
    }

    @Test
    fun push_plan_encodes_60ms_frames_at_24k() {
        val enc = FakeEncoder()
        // 60ms @24k = 1440 samples = 2880B
        val pcm = ByteArray(TtsFraming.framePcmBytes(24_000)) { it.toByte() }
        val plan = buildTtsPushPlan(pcm, 24_000, encode = enc::encode)!!

        assertEquals(24, plan.rateKhz)
        assertEquals(1, plan.packets.size)
        assertEquals(listOf(1_440), enc.samples)
        assertEquals(2_880, enc.frames[0].size)
    }

    @Test
    fun push_plan_drops_oversized_and_empty_packets() {
        // 第 1 帧:超上限(513B) → 丢弃;第 2 帧:空包 → 丢弃;第 3 帧:正常
        var call = 0
        val plan = buildTtsPushPlan(
            pcm = ByteArray(TtsFraming.framePcmBytes(16_000) * 3),
            sampleRateHz = 16_000,
        ) { _, _ ->
            when (++call) {
                1 -> ByteArray(513)
                2 -> ByteArray(0)
                else -> ByteArray(20)
            }
        }!!

        assertEquals(1, plan.packets.size)
        assertEquals(2, plan.dropped)
    }

    @Test
    fun push_plan_refuses_unsupported_rate() {
        assertNull(buildTtsPushPlan(ByteArray(1_000), 22_050) { _, _ -> ByteArray(10) })
    }

    // ---- WAV 解析(系统 TTS 的 synthesizeToFile 产物) ----

    /** 造一个最小 44B 头 + PCM 的 WAV。 */
    private fun wav(rateHz: Int, pcm: ByteArray, channels: Int = 1, bits: Int = 16, format: Int = 1): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun putTag(at: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(out, at) }
        fun putLe32(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
            out[at + 2] = ((v shr 16) and 0xFF).toByte()
            out[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putLe16(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putTag(0, "RIFF"); putLe32(4, 36 + pcm.size); putTag(8, "WAVE")
        putTag(12, "fmt "); putLe32(16, 16); putLe16(20, format)
        putLe16(22, channels); putLe32(24, rateHz)
        putLe32(28, rateHz * channels * bits / 8)   // byte rate
        putLe16(32, channels * bits / 8)            // block align
        putLe16(34, bits)
        putTag(36, "data"); putLe32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }

    @Test
    fun wav_parsing_reads_rate_and_strips_header() {
        val pcm = ByteArray(1_000) { it.toByte() }
        val parsed = TtsFraming.pcmFromWav(wav(24_000, pcm))!!
        assertEquals(24_000, parsed.sampleRateHz)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertArrayEquals(pcm, parsed.pcm)
    }

    @Test
    fun wav_parsing_rejects_non_pcm_or_broken_input() {
        assertNull("非 16bit", TtsFraming.pcmFromWav(wav(16_000, ByteArray(100), bits = 8)))
        assertNull("多声道", TtsFraming.pcmFromWav(wav(16_000, ByteArray(100), channels = 2)))
        assertNull("不是 WAV", TtsFraming.pcmFromWav(ByteArray(100)))
        assertNull("空 data", TtsFraming.pcmFromWav(wav(16_000, ByteArray(0))))
        assertNull(TtsFraming.pcmFromWav(ByteArray(20)))
    }

    @Test
    fun wav_parsing_accepts_extensible_header() {
        val pcm = ByteArray(20) { 1 }
        val parsed = TtsFraming.pcmFromWav(wav(16_000, pcm, format = 0xFFFE))
        assertNotNull("WAVE_FORMAT_EXTENSIBLE 的单声道 16bit 仍按 PCM 处理", parsed)
        assertArrayEquals(pcm, parsed!!.pcm)
    }
}
