package com.shinku.aipassport.openclaw.tts

import com.shinku.aipassport.openclaw.protocol.decodeTtsOpusPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小智 TTS 直通(`XiaozhiTtsRelay`)的 JVM 单测:无网络、无 Android/BLE 依赖。
 *
 * 覆盖的契约(见 `docs/design/xiaozhi-ai-gateway.md` §4.3 与 `docs/wire-protocol.md` 的 TTS 下行):
 *  1. **帧头**:`[SEQ][rate_khz][frame_ms] + opus`,rate/frame 用小智 hello 自报的 24 kHz/60 ms,
 *     SEQ 每帧 +1、到 256 回绕;opus 包原样透传(不解码/不重编码);
 *  2. **状态映射**:`start` / `sentence_start` 开一段(句级流式,只发一次 `tts_start`)、
 *     `sentence_end` 不是一段结束、`stop` 才发 `tts_stop`;
 *  3. **打断幂等**:新一轮 `turn_start` 反复调用不产生任何下发,打断后到达的旧音频被丢弃,
 *     下一轮重新声明 `tts_start`(不是直接甩音频);
 *  4. **设备朗读关掉就不下发音频**(且中途关掉时仍把已开始的一段收尾,不让设备停在播放态);
 *  5. 非 16/24 kHz、帧长非正、空包、超过 512B 的包一律丢弃,不破坏后续合法帧。
 */
class XiaozhiTtsRelayTest {

    /** 记录下发调用的假通路(顺序也记下来 —— 「什么时候发 tts_start/tts_stop」是本类的核心契约)。 */
    private class FakeDownlink : XiaozhiTtsDownlink {
        val events = ArrayList<String>()
        val frames = ArrayList<ByteArray>()
        val frameMeta = ArrayList<Pair<Int, Int>>()

        override fun start() {
            events.add("start")
        }

        override fun pushFrame(rateKhz: Int, frameMs: Int, payload: ByteArray) {
            events.add("frame")
            frames.add(payload)
            frameMeta.add(rateKhz to frameMs)
        }

        override fun stop() {
            events.add("stop")
        }
    }

    private fun relay(downlink: FakeDownlink, enabled: () -> Boolean = { true }) =
        XiaozhiTtsRelay(enabled = enabled, downlink = downlink)

    @Test
    fun audio_frames_carry_xiaozhi_header_and_seq_wraps_at_256() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onTtsState("start", "")
        val opus = ByteArray(10) { (it * 3 and 0xFF).toByte() }

        // 257 帧:SEQ 0..255 然后回绕到 0(1 字节计数器)
        repeat(257) { r.onTtsAudio(opus, 24, 60) }

        assertEquals(257, link.frames.size)
        val first = decodeTtsOpusPayload(link.frames.first())!!
        assertEquals(0, first.seq)
        assertEquals(24, first.rateKhz)
        assertEquals(60, first.frameMs)
        assertArrayEquals("opus 包必须原样透传", opus, first.opus)
        assertEquals(255, decodeTtsOpusPayload(link.frames[255])!!.seq)
        assertEquals("SEQ 到 256 回绕到 0", 0, decodeTtsOpusPayload(link.frames[256])!!.seq)
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
    }

    @Test
    fun states_map_to_start_and_stop_once_per_utterance() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("start", "")
        r.onTtsState("sentence_start", "第一句")
        r.onTtsAudio(ByteArray(6), 24, 60)
        r.onTtsState("sentence_end", "第一句")

        assertEquals(listOf("start", "frame"), link.events)
        assertTrue("sentence_end 不是一段结束", link.events.none { it == "stop" })

        r.onTtsState("sentence_start", "第二句")
        r.onTtsAudio(ByteArray(6), 24, 60)
        assertEquals("逐句不重发 tts_start", listOf("start", "frame", "frame"), link.events)

        r.onTtsState("stop", "")
        assertEquals(listOf("start", "frame", "frame", "stop"), link.events)
    }

    @Test
    fun sentence_start_without_start_still_opens_the_segment() {
        val link = FakeDownlink()
        val r = relay(link)
        // 个别服务端实现可能不发 state=start:首个 sentence_start 必须能兜底开一段
        r.onTtsState("sentence_start", "你好")
        assertEquals(listOf("start"), link.events)
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertEquals(listOf("start", "frame"), link.events)
    }

    @Test
    fun turn_start_resets_state_and_drops_stale_audio_idempotently() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)

        // 打断:流水线每轮 turn_start 都会调一次,重复调用必须是幂等的(不产生额外下发)
        r.onTurnStart()
        r.onTurnStart()
        assertEquals(listOf("start", "frame"), link.events)

        // 打断后仍在飞行中的旧轮音频必须被丢弃(否则会给设备一段没有 tts_start 的错位音频)
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertEquals(listOf("start", "frame"), link.events)

        // 下一轮:重新声明一段再推音频
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTtsState("stop", "")
        assertEquals(listOf("start", "frame", "start", "frame", "stop"), link.events)
    }

    @Test
    fun disabled_relay_pushes_no_audio_frames() {
        val link = FakeDownlink()
        var on = false
        val r = relay(link) { on }

        // 关闭(默认关)时:整个生命周期一个字节不下发
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTtsState("stop", "")
        assertTrue("设备朗读关掉时不能下发任何音频/控制帧", link.events.isEmpty())

        // 中途关掉开关:已开始的一段仍要收尾(否则设备停在播放态),但不再推帧
        on = true
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)
        on = false
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTtsState("stop", "")
        assertEquals(listOf("start", "frame", "stop"), link.events)
    }

    @Test
    fun invalid_rate_frame_length_and_oversize_packet_are_dropped() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onTtsState("sentence_start", "")

        r.onTtsAudio(ByteArray(4), 22, 60)            // 22050Hz:固件只收 16/24,M1 不重采样
        r.onTtsAudio(ByteArray(4), 0, 60)             // hello 还没上报速率
        r.onTtsAudio(ByteArray(4), 24, 0)             // 帧长非法
        r.onTtsAudio(ByteArray(0), 24, 60)            // 空包
        r.onTtsAudio(ByteArray(513), 24, 60)          // 超过 512B 上限

        assertEquals("非法帧一帧都不能下发", listOf("start"), link.events)

        // 后续合法帧不受影响(丢弃只针对那一帧)
        r.onTtsAudio(ByteArray(4), 16, 60)
        assertEquals(1, link.frames.size)
        assertEquals(16, decodeTtsOpusPayload(link.frames[0])!!.rateKhz)
        assertEquals(listOf(16 to 60), link.frameMeta)
    }
}
