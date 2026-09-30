package com.shinku.aipassport.openclaw.tts

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备朗读(下行 TTS)编排与播放回报的单测(纯 JVM)。
 *
 * 覆盖两条协议硬要求:
 *  - **用户按下 OK 开始新一轮(`turn_start`)必须立刻发 `{"ev":"tts_abort"}`**:设备可能仍在播
 *    上一轮回复的 ≤2s 缓冲,只有 abort 能让它停手(且与设置开关无关,abort 是幂等操作);
 *  - 网关回复上屏**之后**才下发朗读,且受设置项 `tts_enabled`(默认关)控制、空文本不下发。
 */
class DeviceTtsSessionTest {

    /** 记录调用的假通路。 */
    private class FakeDownlink : DeviceTtsDownlink {
        val spoken = ArrayList<String>()
        var aborts = 0
        val reports = ArrayList<TtsPlaybackReport>()

        override fun speak(text: String) { spoken.add(text) }
        override fun abort() { aborts++ }
        override fun onPlaybackReport(report: TtsPlaybackReport) { reports.add(report) }
    }

    private fun session(enabled: Boolean, downlink: FakeDownlink) =
        DeviceTtsSession(enabled = { enabled }, downlink = downlink)

    @Test
    fun tts_abort_is_always_sent_on_new_turn() {
        val link = FakeDownlink()
        // 即使设置关着、也从来没下发过音频:新一轮照样发 abort
        // (设备可能还在播上一轮 tts_stop 之后的缓冲;abort 幂等)
        val s = session(enabled = false, downlink = link)
        s.onTurnStart()
        s.onTurnStart()
        assertEquals(2, link.aborts)
        assertEquals(0, link.spoken.size)
    }

    @Test
    fun tts_abort_is_sent_on_new_turn_after_a_reply_was_pushed() {
        val link = FakeDownlink()
        val s = session(enabled = true, downlink = link)
        s.onReply("回复")
        s.onTurnStart()
        assertEquals(listOf("回复"), link.spoken)
        assertEquals("新一轮开始必须掐掉在途朗读", 1, link.aborts)
    }

    @Test
    fun cancel_sends_abort() {
        val link = FakeDownlink()
        val s = session(enabled = true, downlink = link)
        s.onCancel()
        assertEquals(1, link.aborts)
    }

    @Test
    fun reply_is_pushed_only_when_enabled() {
        val off = FakeDownlink()
        session(enabled = false, downlink = off).onReply("你好")
        assertTrue("设置关闭(默认)时不下发设备朗读", off.spoken.isEmpty())

        val on = FakeDownlink()
        session(enabled = true, downlink = on).onReply("你好")
        assertEquals(listOf("你好"), on.spoken)
    }

    @Test
    fun blank_reply_is_not_pushed() {
        val link = FakeDownlink()
        val s = session(enabled = true, downlink = link)
        s.onReply("")
        s.onReply("   \n ")
        assertTrue(link.spoken.isEmpty())
    }

    @Test
    fun device_playback_report_is_forwarded() {
        val link = FakeDownlink()
        val s = session(enabled = true, downlink = link)
        val report = TtsPlaybackReport(
            ev = TtsPlaybackReport.DONE,
            frames = 12,
            decoded = 12,
            dropped = 1,
            underruns = 0,
            decodeUsMax = 900,
        )
        s.onDeviceEvent(report)
        assertEquals(listOf(report), link.reports)
    }

    // ---- CONTROL 事件与播放回报的协议契约 ----

    @Test
    fun control_event_json_matches_firmware_contract() {
        assertEquals("{\"ev\":\"tts_start\"}", TtsControl.START_JSON)
        assertEquals("{\"ev\":\"tts_stop\"}", TtsControl.STOP_JSON)
        assertEquals("{\"ev\":\"tts_abort\"}", TtsControl.ABORT_JSON)
    }

    @Test
    fun playback_done_parses_device_fields() {
        val obj = JsonParser.parseString(
            """{"ev":"tts_playback_done","frames":12,"decoded":12,"dropped":1,"underruns":0,"decode_us_max":812}""",
        ).asJsonObject
        val report = parseTtsPlaybackReport(obj)!!
        assertEquals(TtsPlaybackReport.DONE, report.ev)
        assertEquals(12, report.frames)
        assertEquals(12, report.decoded)
        assertEquals(1, report.dropped)
        assertEquals(0, report.underruns)
        assertEquals(812, report.decodeUsMax)
        assertEquals(
            "TTS 播放完成: frames=12 decoded=12 dropped=1 underruns=0 decode_us_max=812",
            ttsPlaybackLogLine(report),
        )
    }

    @Test
    fun playback_aborted_without_fields_logs_unknown_placeholders() {
        val obj = JsonParser.parseString("""{"ev":"tts_playback_aborted"}""").asJsonObject
        val report = parseTtsPlaybackReport(obj)!!
        assertEquals(TtsPlaybackReport.ABORTED, report.ev)
        assertEquals(TtsPlaybackReport.UNKNOWN, report.frames)
        assertEquals(
            "TTS 播放中止: frames=? decoded=? dropped=? underruns=? decode_us_max=?",
            ttsPlaybackLogLine(report),
        )
    }

    @Test
    fun unrelated_events_are_not_playback_reports() {
        assertNull(parseTtsPlaybackReport(JsonParser.parseString("""{"ev":"turn_end","frames":3}""").asJsonObject))
        assertNull(parseTtsPlaybackReport(JsonParser.parseString("""{"cmd":"status"}""").asJsonObject))
    }
}
