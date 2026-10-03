package com.shinku.aipassport.openclaw.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **下行 TTS 帧的逐字节对照测试(自查用)** —— 把 App 组出来的字节与**固件解析器期望的字节**
 * 并排列出来,任何一次漂移都在这里先炸,而不是等到真机上看出 `TTS=0`。
 *
 * 固件侧权威定义(只读,不在本仓):
 *  - `main/oc_proto.h`:`[A5][5A][TYPE:1B][FLAGS:1B][LEN:2B **大端**] + payload`,
 *    `OC_FRAME_TTS_OPUS = 0x06`,`OC_TTS_OPUS_HEADER = 3`,`OC_TTS_OPUS_PAYLOAD_MAX = 512`,
 *    `OC_TTS_OPUS_FRAME_MAX = 3 + 512 = 515`(`oc_payload_limit(0x06)` 用的就是这个整帧载荷上限);
 *  - `main/oc_proto.c` 的 `header_valid()`:magic 对 + type 已知 + `LEN ≤ 该类型上限`
 *    才算合法帧头 —— LEN 超限或 type 未知都会被**逐字节丢弃并静默重同步**(既不计类型计数,
 *    也不计「废半截」),这正是真机上「CTRL 涨、TTS 恒为 0」的形态;
 *  - `main/oc_tts.c` 的 `oc_tts_parse()`:载荷 = `[SEQ:1B][rate_khz:1B][frame_ms:1B] + Opus 包`,
 *    长度合法范围 `4..515`,rate 只认 `16/24`,frame_ms 只认 `60`;
 *  - `main/oc_app.c` 的「帧到达」汇总:`重组=N(CTRL=… TTS=… EVT=… TXT=… 废半截=…)` ——
 *    计数点(重组器 → `stat_by_type[type & 0x0F]`)在 `oc_tts_parse` **之前**,
 *    所以「设备侧 TTS 恒为 0」只可能意味着**一个合法 type=0x06 帧头都没被重组出来**,
 *    与 opus 内容、rate/帧长是否合法无关。
 *
 * 本文件里每个用例都把「App 产出」与「固件期望」写成**同一组字面量**,逐字节断言:
 * ```
 * App      : A5 5A 06 00 00 0E 00 18 3C 01 02 …            (seq=0x00, 24kHz, 60ms, 11B opus)
 * 固件期望  : A5 5A 06 00 00 0E 00 18 3C 01 02 …            (完全一致;LEN=0x000E=14=3+11)
 * ```
 */
class TtsWireCompatTest {

    /** 一段内容固定、易识别的假 opus 包(长度 = 11B,避开头字节与 magic 混淆的巧合)。 */
    private val opus11 = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
    )

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ---- ① 逐字节样例:App 产出 == 固件解析器期望 ----

    @Test
    fun app_frame_bytes_equal_the_firmware_expectation_byte_for_byte() {
        // App 侧组帧(唯一的发送实现):vbEncodeTtsOpusFrame = 6B 帧头 + [SEQ][rate][frame_ms] + opus
        val app = vbEncodeTtsOpusFrame(seq = 0x00, rateKhz = 24, frameMs = 60, opus = opus11)

        // 固件期望(按 oc_proto.h / oc_tts.c 手工展开,不调用 App 的任何编码函数)
        val firmware = bytes(
            0xA5, 0x5A,                       // MAGIC0 / MAGIC1
            0x06,                             // TYPE = OC_FRAME_TTS_OPUS
            0x00,                             // FLAGS = 0(音频帧不用 MORE/FIRST/LAST)
            0x00, 0x0E,                       // LEN = 14 = 3B 帧头 + 11B opus(大端)
            0x00,                             // SEQ
            0x18,                             // rate_khz = 24
            0x3C,                             // frame_ms = 60
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        )

        assertEquals(
            "App 产出的字节必须与固件解析器期望逐字节一致:\n" +
                "  App     = ${hex(app)}\n" +
                "  固件期望 = ${hex(firmware)}",
            hex(firmware),
            hex(app),
        )
        assertEquals("整帧长度 = 6 + LEN", 20, app.size)
    }

    @Test
    fun frame_header_fields_are_what_the_firmware_reads() {
        val frame = vbEncodeTtsOpusFrame(seq = 0x2A, rateKhz = 16, frameMs = 60, opus = opus11)

        assertEquals(0xA5, frame[0].toInt() and 0xFF)
        assertEquals(0x5A, frame[1].toInt() and 0xFF)
        assertEquals(0x06, frame[2].toInt() and 0xFF)                    // oc_frame_type_t
        assertEquals(0x00, frame[3].toInt() and 0xFF)                    // flags
        val len = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)  // 大端
        assertEquals("LEN = 3B 帧头 + 单个 opus 包", 3 + opus11.size, len)
        assertEquals(0x2A, frame[6].toInt() and 0xFF)                    // SEQ
        assertEquals(16, frame[7].toInt() and 0xFF)                      // rate_khz
        assertEquals(60, frame[8].toInt() and 0xFF)                      // frame_ms
        assertArrayEquals("opus 包原样透传,不重新编码", opus11, frame.copyOfRange(9, frame.size))
    }

    // ---- ② 长度边界(固件按 4..515 判定,超限静默重同步) ----

    @Test
    fun payload_length_boundaries_match_the_firmware_limits() {
        // 固件 OC_TTS_OPUS_FRAME_MAX = 3 + 512 = 515 是**整帧载荷**上限(不是 512)。
        assertEquals(515, VbFrame.payloadLimit(VbFrame.TYPE_TTS_OPUS))

        // 最小合法载荷:3B 帧头 + 1B opus = 4 → 整帧 10B
        val min = vbEncodeTtsOpusFrame(0, 16, 60, byteArrayOf(0x7F))
        assertEquals(10, min.size)
        assertEquals(4, min[5].toInt() and 0xFF)
        // 最大合法载荷:3 + 512 = 515 → 整帧 521B(固件重组缓冲放得下:6 + 2048)
        val max = vbEncodeTtsOpusFrame(0, 24, 60, ByteArray(512) { 0x55 })
        assertEquals(521, max.size)
        assertEquals(515, ((max[4].toInt() and 0xFF) shl 8) or (max[5].toInt() and 0xFF))

        // 超限包在 App 侧就丢掉:固件会把 LEN > 515 的帧头当错位字节静默跳过
        val oversize = runCatching { vbEncodeTtsOpusFrame(0, 24, 60, ByteArray(513)) }
        assertTrue("opus 包 > 512B 必须抛(绝不让它上链)", oversize.isFailure)
    }

    // ---- ③ SEQ 语义:1 字节,每帧 +1、到 256 回绕 ----

    @Test
    fun seq_is_one_byte_and_wraps_at_256() {
        assertEquals(0x00, VbFrame.TTS_HEADER_SIZE.let { vbEncodeTtsOpusFrame(0, 24, 60, opus11)[6].toInt() and 0xFF })
        assertEquals(0xFE, vbEncodeTtsOpusFrame(0xFE, 24, 60, opus11)[6].toInt() and 0xFF)
        assertEquals(0xFF, vbEncodeTtsOpusFrame(0xFF, 24, 60, opus11)[6].toInt() and 0xFF)
        assertEquals(0, VbTtsOpusPayload.nextSeq(255))
        assertEquals(1, VbTtsOpusPayload.nextSeq(0))
        // 每帧在帧头里的 SEQ 与 payload 里的 SEQ 是同一个字节(设备只看 payload[0])
        val frame = vbEncodeTtsOpusFrame(0xFF, 24, 60, opus11)
        assertEquals(0xFF, frame[6].toInt() and 0xFF)
        assertEquals(0xFF, decodeTtsOpusPayload(frame.copyOfRange(6, frame.size))!!.seq)
    }

    // ---- ④ 固件解析器视角:把 App 的字节喂进镜像重组器,必须吐出 type=0x06 ----

    /**
     * 固件 `oc_reassembler_push` 的镜像(App 侧的 [VbFrameReassembler]):按 ATT 写分片喂进去,
     * 必须重组出**一个** `TYPE_TTS_OPUS` 帧、payload 与 App 组的一模一样。
     *
     * 为什么要按 240B 分片喂:一次 ATT 写最多 `MTU − 3` 字节(`ble/WriteChunking` 现在按协商到的 MTU
     * 动态取,240 = 协商失败时的回退片长),一个 TTS 帧(≤521B)可能跨 2–3 个 ATT 写;
     * 固件侧是字节流重组,所以这种切分必须无损。
     */
    @Test
    fun the_app_frame_reassembles_as_a_type_0x06_frame_at_every_write_split() {
        val frame = vbEncodeTtsOpusFrame(seq = 0x07, rateKhz = 24, frameMs = 60, opus = ByteArray(512) { 0x33 })
        val got = ArrayList<VbFrameData>()
        val rx = VbFrameReassembler { got.add(it) }

        frame.asList().chunked(240).forEach { chunk -> rx.push(chunk.toByteArray()) }

        assertEquals("只能重组出一帧", 1, got.size)
        assertEquals(0x06, got[0].type)
        assertEquals(0x00, got[0].flags)
        assertEquals(515, got[0].payload.size)
        val payload = decodeTtsOpusPayload(got[0].payload)!!
        assertEquals(0x07, payload.seq)
        assertEquals(24, payload.rateKhz)
        assertEquals(60, payload.frameMs)
        assertEquals(512, payload.opus.size)
    }

    /**
     * 反面样例(现场证据的形态):type 未知或 LEN 超限的帧会被接收端**逐字节静默丢弃** ——
     * 既不计类型计数、也不计「废半截」。这就是「设备侧 CTRL 涨、TTS 恒为 0」的可复现形态,
     * 也是本测试存在的理由:一旦 App 侧帧头漂移,真机上只会看到计数不动。
     */
    @Test
    fun unknown_type_or_oversized_len_is_dropped_silently_by_the_reassembler() {
        // ① type 未知(例如旧固件只认 0x01..0x04)
        val got1 = ArrayList<VbFrameData>()
        val rx1 = VbFrameReassembler { got1.add(it) }
        val bogus = vbEncodeTtsOpusFrame(0, 24, 60, opus11).copyOf()
        bogus[2] = 0x07
        rx1.push(bogus)
        assertTrue("未知 type 的帧不会吐出来", got1.isEmpty())

        // ② LEN 超上限:篡改 LEN 字段为 0x0200(512 > 515 是合法的,所以用 type=TEXT 的 2048+1 超限形态)
        val got2 = ArrayList<VbFrameData>()
        val rx2 = VbFrameReassembler { got2.add(it) }
        val text = vbEncodeFrame(VbFrame.TYPE_TEXT, 0x04, ByteArray(8) { 0x41 })
        text[4] = 0x10                                  // LEN = 0x1008 > 2048(TEXT 上限)
        text[5] = 0x08
        rx2.push(text)
        assertTrue("LEN 超过该类型上限的帧不会吐出来", got2.isEmpty())
    }

    // ---- ⑤ 与固件 payload_limit 命名常量对齐 ----

    /**
     * CONTROL/EVENT 的 JSON 载荷上限(固件 `OC_JSON_PAYLOAD_MAX` = 512):超长的控制帧在固件侧会被
     * 当作**错位帧头**逐字节丢弃(不计计数、不计废半截),所以 App 必须自己先裁到上限内。
     */
    @Test
    fun json_payload_is_clamped_to_the_firmware_limit_for_control_and_event_frames() {
        assertEquals(512, VbFrame.payloadLimit(VbFrame.TYPE_CONTROL))
        assertEquals(512, VbFrame.payloadLimit(VbFrame.TYPE_EVENT))
        assertEquals(512, VbFrame.JSON_PAYLOAD_MAX)

        val short = "{\"ev\":\"tts_start\"}".toByteArray(Charsets.UTF_8)
        assertSame("未超限时不得复制/改动", short, clampJsonPayload(short))

        val exact = ByteArray(512) { 'a'.code.toByte() }
        assertSame(exact, clampJsonPayload(exact))

        val tooLong = ByteArray(600) { 'a'.code.toByte() }
        assertEquals(512, clampJsonPayload(tooLong).size)

        // 多字节字符不能切一半:519B 的中文串(173 个 3B 汉字)裁到 512 时必须退到字符边界
        // (510 = 170 × 3B),而且解码后不得出现替换字符。
        val chinese = ByteArray(173) { 0 }.joinToString("") { "\u4f60" }.toByteArray(Charsets.UTF_8)
        assertEquals(519, chinese.size)
        val clamped = clampJsonPayload(chinese)
        assertEquals(510, clamped.size)
        assertEquals(0, clamped.size % 3)
        assertTrue(
            "裁剪后不能出现被切坏的字符",
            !clamped.toString(Charsets.UTF_8).contains('\uFFFD'),
        )

        // 裁完的帧能被固件那套帧头校验接受(type 已知 + LEN ≤ 512)
        val got = ArrayList<VbFrameData>()
        VbFrameReassembler { got.add(it) }.push(
            vbEncodeFrame(VbFrame.TYPE_CONTROL, 0, clampJsonPayload(tooLong)),
        )
        assertEquals(1, got.size)
        assertEquals(VbFrame.TYPE_CONTROL, got[0].type)
        assertEquals(512, got[0].payload.size)
    }

    @Test
    fun constants_match_the_firmware_header() {
        assertEquals(0x06, VbFrame.TYPE_TTS_OPUS)
        assertEquals(3, VbFrame.TTS_HEADER_SIZE)                    // OC_TTS_OPUS_HEADER
        assertEquals(512, VbFrame.TTS_OPUS_PAYLOAD_MAX)             // OC_TTS_OPUS_PAYLOAD_MAX
        assertEquals(515, VbFrame.TTS_HEADER_SIZE + VbFrame.TTS_OPUS_PAYLOAD_MAX)
        assertEquals(6, VbFrame.HEADER_SIZE)                        // OC_HEADER_SIZE
        assertEquals(0xA5, VbFrame.MAGIC0)
        assertEquals(0x5A, VbFrame.MAGIC1)
    }
}
