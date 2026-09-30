package com.shinku.aipassport.openclaw.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下行 TTS 帧(`TYPE_TTS_OPUS = 0x06`,手机→设备)的编解码往返与边界单测。
 *
 * 契约来源:固件 `docs/development/engineering/intercom-wire-protocol.md` 的 TTS 下行一节 ——
 * payload = `[SEQ:1B][rate_khz:1B][frame_ms:1B][Opus 包 ≤512B]`,整段 payload 长度 `4..515`。
 */
class TtsFrameTest {

    @Test
    fun type_and_limit_constants_match_firmware_contract() {
        assertEquals(0x06, VbFrame.TYPE_TTS_OPUS)
        assertEquals(3, VbFrame.TTS_HEADER_SIZE)
        assertEquals(512, VbFrame.TTS_OPUS_PAYLOAD_MAX)
        // 载荷上限 = 3B 头 + 512B Opus(固件 OC_TTS_OPUS_PAYLOAD_MAX 只约束 Opus 包)
        assertEquals(515, VbFrame.payloadLimit(VbFrame.TYPE_TTS_OPUS))
    }

    @Test
    fun payload_roundtrip_keeps_header_and_packet() {
        val opus = ByteArray(100) { (it * 7 and 0xFF).toByte() }
        val payload = encodeTtsOpusPayload(seq = 7, rateKhz = 24, frameMs = 60, opus = opus)

        assertEquals(103, payload.size)
        val decoded = decodeTtsOpusPayload(payload)!!
        assertEquals(7, decoded.seq)
        assertEquals(24, decoded.rateKhz)
        assertEquals(60, decoded.frameMs)
        assertArrayEquals(opus, decoded.opus)
    }

    @Test
    fun max_size_packet_is_accepted_and_larger_is_rejected() {
        val maxPacket = ByteArray(VbFrame.TTS_OPUS_PAYLOAD_MAX) { 0x5A }
        val payload = encodeTtsOpusPayload(1, 16, 60, maxPacket)
        assertEquals(515, payload.size)
        assertEquals(512, decodeTtsOpusPayload(payload)!!.opus.size)

        // 超上限:编码端直接拒(不会发出超界帧),解码端按错位处理返回 null
        val tooBig = runCatching { encodeTtsOpusPayload(1, 16, 60, ByteArray(513)) }
        assertTrue("超过 512B 的 Opus 包必须被拒绝", tooBig.isFailure)
        assertNull(decodeTtsOpusPayload(ByteArray(516)))
    }

    @Test
    fun payload_without_opus_packet_is_rejected() {
        // 只有 3B 头、没有 Opus 包 → 不是合法载荷
        assertNull(decodeTtsOpusPayload(ByteArray(VbFrame.TTS_HEADER_SIZE)))
        assertNull(decodeTtsOpusPayload(ByteArray(2)))
        assertNull(decodeTtsOpusPayload(ByteArray(0)))
    }

    @Test
    fun seq_wraps_around_at_256() {
        assertEquals(1, VbTtsOpusPayload.nextSeq(0))
        assertEquals(255, VbTtsOpusPayload.nextSeq(254))
        assertEquals(0, VbTtsOpusPayload.nextSeq(255))
        // 回绕后再回到 1:1 字节计数器语义(固件统计 SEQ 缺口用)
        assertEquals(1, VbTtsOpusPayload.nextSeq(VbTtsOpusPayload.nextSeq(255)))
        // 编码端只取低 8 位
        assertEquals(0, decodeTtsOpusPayload(encodeTtsOpusPayload(256, 16, 60, byteArrayOf(1)))!!.seq)
    }

    @Test
    fun encoded_frame_header_is_magic_tts_type_with_zero_flags() {
        val opus = ByteArray(10) { it.toByte() }
        val frame = vbEncodeTtsOpusFrame(seq = 3, rateKhz = 24, frameMs = 60, opus = opus)

        assertEquals(VbFrame.MAGIC0, frame[0].toInt() and 0xFF)
        assertEquals(VbFrame.MAGIC1, frame[1].toInt() and 0xFF)
        assertEquals(VbFrame.TYPE_TTS_OPUS, frame[2].toInt() and 0xFF)
        // 音频帧各自独立可解:FLAGS 固定 0,分段由 tts_start/tts_stop 界定
        assertEquals(0, frame[3].toInt() and 0xFF)
        assertEquals(VbFrame.HEADER_SIZE + 13, frame.size)
        assertEquals(13, ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF))
    }

    /**
     * 整帧经 [VbFrameReassembler] 还原:证明「写出去的那一帧」在接收方向上也能被解析
     * (帧头/长度/载荷上限三者一致),而不只是编解码函数自洽。
     */
    @Test
    fun encoded_frame_decodes_through_reassembler() {
        val opus = ByteArray(64) { (it + 1).toByte() }
        val frame = vbEncodeTtsOpusFrame(seq = 9, rateKhz = 16, frameMs = 60, opus = opus)
        val got = ArrayList<VbFrameData>()
        val reassembler = VbFrameReassembler { got.add(it) }
        // 模拟 BLE 分片:按 7 字节切片喂入,验证跨分片重组
        var off = 0
        while (off < frame.size) {
            val take = minOf(7, frame.size - off)
            reassembler.push(frame.copyOfRange(off, off + take))
            off += take
        }
        assertEquals(1, got.size)
        assertEquals(VbFrame.TYPE_TTS_OPUS, got[0].type)
        val decoded = decodeTtsOpusPayload(got[0].payload)!!
        assertEquals(9, decoded.seq)
        assertEquals(16, decoded.rateKhz)
        assertArrayEquals(opus, decoded.opus)
    }
}
