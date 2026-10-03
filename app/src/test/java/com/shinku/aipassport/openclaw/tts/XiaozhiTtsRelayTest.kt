package com.shinku.aipassport.openclaw.tts

import com.shinku.aipassport.openclaw.gateway.XiaozhiGateway
import com.shinku.aipassport.openclaw.protocol.decodeTtsOpusPayload
import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity
import com.shinku.aipassport.openclaw.stt.XiaozhiReplyText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小智 TTS 直通(`XiaozhiTtsRelay`)的 JVM 单测:无网络、无 Android/BLE 依赖。
 *
 * 被测行为(作者 2026-10-04 定的操作流程,见 `docs/design/xiaozhi-ai-gateway.md` §4.4):
 * **正文先整段上屏,紧接着开始播 TTS;TTS 不需要保证整段 —— 一边播放一边缓冲也可以。**
 *
 * 覆盖的契约:
 *  1. **触发点**:`{"type":"tts","state":"stop"}`(整段正文齐)之前/之后都不自动开播 —— 开播由
 *     **「正文已上屏」信号**([XiaozhiTtsRelay.onReplyTextDisplayed])触发,已缓冲的帧按到达顺序下发、
 *     `tts_start` 必在首帧前;
 *  2. **顺序**:正文上屏必须早于第一个音频帧(信号之前一个音频字节都不下发);
 *  3. **窗口在 `stop` 之后保持打开**:迟到帧照常下发(一边播一边缓冲),SEQ 连续;
 *  4. **窗口收尾**:新一轮 `turn_start` / barge / 设备 `turn_cancel`(都走 [XiaozhiTtsRelay.onTurnStart])
 *     与设备回报本段播放结束([XiaozhiTtsRelay.onDevicePlaybackFinished])→ 丢弃剩余缓冲 + `tts_stop`;
 *  5. **一轮开始/打断清空缓冲**:上一轮的残帧绝不进下一轮;
 *  6. **缓冲上限**:正文上屏之前超限时「先推已收 + 之后即时下发」,不丢整段;
 *  7. **帧头**:`[SEQ][rate_khz][frame_ms] + opus`,rate/frame 用小智 hello 自报的 24 kHz/60 ms,
 *     SEQ 每帧 +1、到 256 回绕;opus 包原样透传(不解码/不重编码);非法帧丢弃;
 *  8. **设备朗读关掉就不下发音频**(直通门),小智模式**不回退**本地合成;
 *  9. **服务端只推二进制音频、一条 `tts` JSON 都没有时,首帧兜底开段并即时下发**
 *     (这种服务端没有「整段正文齐」的信号,缓冲等待会让整段音频永远发不出去)。
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

        /** 假通路上已下发的 SEQ 序列(校验「按序、不丢、不重」)。 */
        fun seqs(): List<Int> = frames.map { decodeTtsOpusPayload(it)!!.seq }

        /** 假通路上已下发的 opus 原始内容(校验顺序与内容都不是乱序的)。 */
        fun opusBodies(): List<List<Byte>> = frames.map { decodeTtsOpusPayload(it)!!.opus.toList() }
    }

    /** 内容随 [i] 变化的假 opus 包:顺序错了/丢帧了都能从内容上看出来。 */
    private fun opus(i: Int, size: Int = 10): ByteArray = ByteArray(size) { ((i * 13 + it) and 0xFF).toByte() }

    /** 默认直通门 = 三项全真(小智 AI + 开关打开 + 设备已报能力)。 */
    private fun openGate(
        gatewayType: String = XiaozhiIdentity.GATEWAY_XIAOZHI,
        ttsEnabled: Boolean = true,
        deviceTtsCapable: Boolean = true,
    ) = XiaozhiTtsGate(gatewayType, ttsEnabled, deviceTtsCapable)

    private fun relay(
        downlink: FakeDownlink,
        maxBufferFrames: Int = XiaozhiTtsRelay.MAX_BUFFER_FRAMES,
        gate: () -> XiaozhiTtsGate = { openGate() },
    ) = XiaozhiTtsRelay(gate = gate, downlink = downlink, maxBufferFrames = maxBufferFrames)

    /**
     * 走完「正文上屏」这一步:真实实现是服务侧 `sendTextFrame('A')` 把分片写进 BLE 串行写队列**之后**
     * 调 [XiaozhiTtsRelay.onReplyTextDisplayed](见 `VoiceBridgeService.notifyXiaozhiReplyOnScreen`)。
     * 单测里直接调它,并在需要顺序断言时往下发通路的 events 里插一条 `screen` 标记。
     */
    private fun FakeDownlink.replyOnScreen(relay: XiaozhiTtsRelay) {
        events.add("screen")
        relay.onReplyTextDisplayed()
    }

    // ---- ① 开播时机与顺序(本次返工的核心) ----

    /**
     * 验收点 ①(音频侧):两句文本 + 音频帧**交错**到达 —— `stop` 之前与 `stop` 之后(正文还没上屏)
     * 都一个字节都不下发;「正文已上屏」之后才 `tts_start` → 按到达顺序推完已缓冲的帧,
     * 且 SEQ 连续、opus 原样、窗口保持打开(不在这里 `tts_stop`)。
     */
    @Test
    fun audio_is_buffered_until_the_reply_is_on_screen_then_pushed_in_order() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("sentence_end", "第一句。")
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsState("sentence_start", "第二句。")
        r.onTtsAudio(opus(3), 24, 60)
        assertTrue("stop 之前一个字节都不下发", link.events.isEmpty())

        r.onTtsState("stop", "")
        assertTrue("stop 只是「整段正文齐了、可以开播」:正文还没上屏,仍然不下发", link.events.isEmpty())

        link.replyOnScreen(r)
        assertEquals(
            "开播:下发 tts_start → 按到达顺序连续推已缓冲的帧",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals("窗口在 stop 之后保持打开:这一批推完还没有 tts_stop", 0, link.events.count { it == "stop" })
        assertEquals("SEQ 按下发顺序递增", listOf(0, 1, 2), link.seqs())
        assertEquals(
            "帧顺序 = 到达顺序",
            listOf(opus(1).toList(), opus(2).toList(), opus(3).toList()),
            link.opusBodies(),
        )
        assertEquals(listOf(24 to 60, 24 to 60, 24 to 60), link.frameMeta)
        val first = decodeTtsOpusPayload(link.frames.first())!!
        assertEquals(24, first.rateKhz)
        assertEquals(60, first.frameMs)
        assertArrayEquals("opus 包必须原样透传", opus(1), first.opus)
    }

    /**
     * 验收点 ②(**顺序断言**):同一条交错事件流同时喂正文装配器([XiaozhiReplyText],会话层的喂法)
     * 与直通 relay —— 整段正文只在 `stop` 时上屏一次,而**第一个音频帧必须晚于正文上屏**。
     */
    @Test
    fun reply_text_is_on_screen_before_the_first_audio_frame() {
        val emitted = ArrayList<String>()
        val link = FakeDownlink()
        val r = relay(link)
        // 正文装配器把「上屏」记进同一条时间线(真实实现里这之后才会走 sendTextFrame 并信号 relay)
        val reply = XiaozhiReplyText(emit = { body -> link.events.add("text"); emitted += body })
        reply.onTurnStart()
        r.onTurnStart()
        val onState: (String, String) -> Unit = { state, text ->
            reply.onTtsState(state, text)
            r.onTtsState(state, text)
        }

        onState("sentence_start", "今天晴，")
        r.onTtsAudio(opus(1), 24, 60)
        onState("sentence_end", "今天晴，二十度。")
        r.onTtsAudio(opus(2), 24, 60)
        onState("sentence_start", "记得带伞。")
        r.onTtsAudio(opus(3), 24, 60)

        assertTrue("整段正文齐之前:一个字都不上屏", emitted.isEmpty())
        assertTrue("整段正文齐之前:一个音频字节都不下发", link.events.isEmpty())

        onState("stop", "")

        assertEquals("整段正文只在 stop 时一次性上屏", listOf("今天晴，二十度。记得带伞。"), emitted)
        assertEquals("stop 时只有正文上屏,还没有任何音频事件", listOf("text"), link.events)

        // 服务侧在 TEXT('A') 分片已写进 BLE 串行写队列之后发这个信号(见 VoiceBridgeService)
        link.replyOnScreen(r)

        assertEquals(
            "时间线:正文上屏 → 开播 → 首帧",
            listOf("text", "screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertTrue(
            "正文上屏必须早于第一个音频帧",
            link.events.indexOf("text") < link.events.indexOf("frame"),
        )
    }

    /**
     * 验收点 ③:`stop` **之后**到达的音频帧不再被丢弃 —— 正文上屏前它们与已缓冲的帧一起等,
     * 上屏后立即按到达顺序下发(一边播放一边缓冲),SEQ 连续。
     */
    @Test
    fun frames_arriving_after_stop_are_delivered_in_arrival_order() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsAudio(opus(3), 24, 60)
        assertTrue("正文还没上屏:迟到帧也在缓冲里,不下发", link.events.isEmpty())

        link.replyOnScreen(r)
        assertEquals("开播时把已缓冲的迟到帧一并按到达顺序推出去", listOf("screen", "start", "frame", "frame", "frame"), link.events)

        // 开播之后继续到达的帧:即时下发(窗口保持打开)
        r.onTtsAudio(opus(4), 24, 60)
        r.onTtsAudio(opus(5), 24, 60)
        assertEquals(
            listOf("screen", "start", "frame", "frame", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("SEQ 连续、不丢帧", listOf(0, 1, 2, 3, 4), link.seqs())
        assertEquals(
            listOf(1, 2, 3, 4, 5).map { opus(it).toList() },
            link.opusBodies(),
        )
        assertEquals("仍然没有 tts_stop(窗口要等收尾条件)", 0, link.events.count { it == "stop" })
    }

    /** 只有 `tts` 生命周期、没有任何音频帧:不发 `tts_start`/`tts_stop`(设备不必进播放态)。 */
    @Test
    fun stop_without_any_audio_sends_nothing() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onTtsState("start", "")
        r.onTtsState("sentence_start", "你好")
        r.onTtsState("sentence_end", "你好")
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals("没有音频就没有必要开段/收尾", listOf("screen"), link.events)

        // `stop` 之后才到的音频:正文已经上屏 → 照常开段下发
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(listOf("screen", "start", "frame"), link.events)
    }

    /**
     * 正文可能比 `stop` 早结算(空闲兜底窗口 / 纯 `llm` 兜底):信号先到、`stop` 后到 ——
     * 这种情况下 `stop` 一到就开播,绝不能等一个不会再来的信号(否则本段音频永远留在缓冲里)。
     */
    @Test
    fun text_on_screen_before_stop_starts_playback_at_stop() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(1), 24, 60)
        link.replyOnScreen(r)
        assertTrue("还没有 stop(整段正文没齐):不上屏就开播是不对的", link.events == listOf("screen"))

        r.onTtsState("stop", "")
        assertEquals("stop 到达即开播,不再等信号", listOf("screen", "start", "frame"), link.events)
    }

    /** 同一轮里第二个 `stop` 不能把后续帧重新「武装」成等待信号(否则迟到帧会被永远缓冲)。 */
    @Test
    fun second_stop_in_one_turn_does_not_stall_late_frames() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "第一段")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(listOf("screen", "start", "frame"), link.events)

        r.onTtsState("sentence_start", "第二段")
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsState("stop", "")
        r.onTtsAudio(opus(3), 24, 60)
        assertEquals(
            "已开播:后续帧继续即时下发",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals(listOf(0, 1, 2), link.seqs())
    }

    // ---- ④ 窗口收尾 & 一轮开始 / 打断 ----

    /**
     * 验收点 ④:新一轮 `turn_start`(以及 barge / 设备 `turn_cancel`,它们走会话层的同一条路径)
     * **关窗口 + 清空未播缓冲** —— 已开段的一段发 `tts_stop` 收尾,上一轮的残帧既不下发、
     * 也不许被当成下一轮的音频;重复调用幂等。
     */
    @Test
    fun turn_start_closes_window_and_stale_frames_never_reach_the_next_turn() {
        val link = FakeDownlink()
        val r = relay(link)

        // 上一轮:已经开播并在下发
        r.onTtsState("sentence_start", "旧轮")
        r.onTtsAudio(opus(0x11), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(listOf("screen", "start", "frame"), link.events)

        // 打断 / 新一轮(流水线每轮都会调;重复调幂等):关窗口 → tts_stop 收尾
        r.onTurnStart()
        r.onTurnStart()
        assertEquals(
            "打断/新一轮要收尾(否则设备停在播放态)",
            listOf("screen", "start", "frame", "stop"),
            link.events,
        )

        // 旧轮仍在飞行中的残帧:窗口已关 → 丢弃,绝不进下一轮
        r.onTtsAudio(opus(0x13), 24, 60)
        assertEquals(listOf("screen", "start", "frame", "stop"), link.events)

        // 下一轮:重新开段,并且只推这一轮的帧
        r.onTtsState("sentence_start", "新轮")
        r.onTtsAudio(opus(0x21), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(
            listOf("screen", "start", "frame", "stop", "screen", "start", "frame"),
            link.events,
        )
        assertEquals(
            "只推了各自的帧(旧轮残帧没有混进来)",
            listOf(opus(0x11).toList(), opus(0x21).toList()),
            link.opusBodies(),
        )
        assertEquals("SEQ 只在真正下发的帧上递增", listOf(0, 1), link.seqs())
    }

    /**
     * 验收点 ④(设备回报):设备回报本段播放结束 → 关窗口 + `tts_stop`,之后到达的迟到帧丢弃。
     *
     * 为什么以设备回报作为正常收尾点:窗口在 `stop` 之后是开着的,而服务端不会再给「音频发完没有」
     * 的信号 —— 只有设备知道自己把队列播完了。
     */
    @Test
    fun device_playback_finished_closes_the_window_and_drops_late_frames() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals(listOf("screen", "start", "frame", "frame"), link.events)

        r.onDevicePlaybackFinished()
        assertEquals(
            "本段收尾:已开段要 tts_stop",
            listOf("screen", "start", "frame", "frame", "stop"),
            link.events,
        )

        r.onTtsAudio(opus(3), 24, 60)
        assertEquals("窗口已关:迟到帧丢弃", listOf("screen", "start", "frame", "frame", "stop"), link.events)

        // 幂等:重复回报不产生第二条 tts_stop
        r.onDevicePlaybackFinished()
        assertEquals(5, link.events.size)
    }

    /**
     * 分段服务端:设备回报本段播完之后,同一轮里又推下一段(`start`…`stop`)—— 窗口会被那个 `stop`
     * 重新打开(正文已经上屏过,不必再等信号),不把第二段整段丢掉。
     */
    @Test
    fun new_segment_after_device_reported_finished_reopens_the_window() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "第一段")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        r.onDevicePlaybackFinished()
        assertEquals(listOf("screen", "start", "frame", "stop"), link.events)

        r.onTtsState("sentence_start", "第二段")   // 状态报文重新打开窗口
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsState("stop", "")         // 正文已上屏过 → 立即重新开播
        r.onTtsAudio(opus(3), 24, 60)
        assertEquals(
            listOf("screen", "start", "frame", "stop", "start", "frame", "frame"),
            link.events,
        )
        assertEquals(listOf(0, 1, 2), link.seqs())
    }

    /**
     * 迟到的设备播放回报(上一轮 `tts_abort` 触发的 `tts_playback_aborted`)不能把**新一轮**正在
     * 缓冲的音频清掉 —— 回报不带轮号,所以只在「本段已开段」时才收尾。
     */
    @Test
    fun stale_playback_report_before_any_segment_is_ignored() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "新轮")
        r.onTtsAudio(opus(1), 24, 60)
        r.onDevicePlaybackFinished()   // 上一轮 abort 的迟到回报:本段还没开段 → 忽略
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals("新一轮的音频不能被旧回报清掉", listOf("screen", "start", "frame"), link.events)
    }

    // ---- ⑥ 缓冲上限 ----

    /**
     * 验收点 ⑥:正文上屏之前缓冲到上限时**先推已收(此时才开段)+ 之后改为即时下发**,
     * 而不是丢整段;帧序与 SEQ 都不能乱,窗口仍由收尾条件关闭。
     *
     * 注意这是**退化路径**:它不再等「正文已上屏」信号(宁可牺牲一次「文字先于声音」也不丢音频)。
     */
    @Test
    fun buffer_overflow_flushes_received_frames_then_keeps_streaming() {
        val link = FakeDownlink()
        val r = relay(link, maxBufferFrames = 3)

        r.onTtsState("sentence_start", "长回复")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        assertTrue("没到上限就还在缓冲", link.events.isEmpty())

        r.onTtsAudio(opus(3), 24, 60)
        assertEquals(
            "到上限:开段并推出已收的 3 帧(不丢整段)",
            listOf("start", "frame", "frame", "frame"),
            link.events,
        )

        r.onTtsAudio(opus(4), 24, 60)
        r.onTtsAudio(opus(5), 24, 60)
        assertEquals(
            "之后改为即时下发(顺序不变)",
            listOf("start", "frame", "frame", "frame", "frame", "frame"),
            link.events,
        )

        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals("已经在即时下发:stop/上屏信号不改动节奏", 5, link.events.count { it == "frame" })
        assertEquals("仍然没有 tts_stop", 0, link.events.count { it == "stop" })

        r.onTurnStart()
        assertEquals("start", link.events.first())
        assertEquals("stop", link.events.last())
        assertEquals(1, link.events.count { it == "start" })
        assertEquals(1, link.events.count { it == "stop" })
        assertEquals("不丢帧、SEQ 连续", listOf(0, 1, 2, 3, 4), link.seqs())
        assertEquals(
            listOf(1, 2, 3, 4, 5).map { opus(it).toList() },
            link.opusBodies(),
        )
    }

    // ---- ⑧ 直通门 ----

    /** 验收点 ⑧:直通门任一项不满足 → 整段**零下发**(不光是帧,连同 tts_start/tts_stop 都没有)。 */
    @Test
    fun gate_blocks_when_any_of_three_conditions_fails() {
        // ① 网关类型不是小智 AI(其它四种网关下小智只当识别引擎,它的 TTS 音频不能直通)
        val notXiaozhi = FakeDownlink()
        val r1 = relay(notXiaozhi) { openGate(gatewayType = "openclaw") }
        r1.onTtsState("start", "")
        r1.onTtsAudio(opus(1), 24, 60)
        r1.onTtsState("stop", "")
        notXiaozhi.replyOnScreen(r1)
        assertTrue("非小智网关下不能下发任何帧", notXiaozhi.events == listOf("screen"))

        // ② 设置项关闭(默认值语义由 GatewaySettings 保证为 true,这里只验「关掉就不发」)
        val disabled = FakeDownlink()
        val r2 = relay(disabled) { openGate(ttsEnabled = false) }
        r2.onTtsState("start", "")
        r2.onTtsAudio(opus(1), 24, 60)
        r2.onTtsState("stop", "")
        disabled.replyOnScreen(r2)
        assertTrue("开关关闭时不能下发任何帧", disabled.events == listOf("screen"))

        // ③ 设备没在 hello 里报 caps:["tts_opus"]
        val noCaps = FakeDownlink()
        val r3 = relay(noCaps) { openGate(deviceTtsCapable = false) }
        r3.onTtsState("start", "")
        r3.onTtsAudio(opus(1), 24, 60)
        r3.onTtsState("stop", "")
        noCaps.replyOnScreen(r3)
        assertTrue("设备未报能力时不能下发任何帧", noCaps.events == listOf("screen"))
    }

    /**
     * 门在整段中途被关掉:**这段一个字节都不下发**(既不开段也不推帧)。
     *
     * 正文上屏(开播时机)时开关已经关着 → 正确行为是安静丢掉,而不是把已缓冲的帧补发出去。
     */
    @Test
    fun gate_closed_mid_segment_sends_nothing_for_that_segment() {
        val link = FakeDownlink()
        var on = true
        val r = relay(link) { openGate(ttsEnabled = on) }

        r.onTtsState("start", "")
        r.onTtsAudio(opus(1), 24, 60)   // 开关还开着 → 进缓冲
        on = false
        r.onTtsAudio(opus(2), 24, 60)   // 开关关掉 → 直接丢
        r.onTtsState("stop", "")
        link.replyOnScreen(r)              // 关着开关上屏 → 整段不播
        assertTrue("开关关掉后这段必须完全安静", link.events == listOf("screen"))

        // 用户重新打开:下一轮照常
        on = true
        r.onTurnStart()
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(3), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(listOf("screen", "screen", "start", "frame"), link.events)
        assertEquals(listOf(opus(3).toList()), link.opusBodies())
    }

    /**
     * 已开段(缓冲超限降级后才关的开关)的一段仍要在窗口收尾时 `tts_stop`,否则设备停在播放态;
     * 但**不再补推**未发的帧。
     */
    @Test
    fun gate_closed_after_degraded_start_still_sends_tts_stop_on_window_close() {
        val link = FakeDownlink()
        var on = true
        val r = relay(link, maxBufferFrames = 2) { openGate(ttsEnabled = on) }

        r.onTtsState("sentence_start", "长回复")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)   // 到上限 → 已开段并推了 2 帧
        assertEquals(listOf("start", "frame", "frame"), link.events)

        on = false
        r.onTtsAudio(opus(3), 24, 60)   // 关掉后不再推
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals("已开段不能补推未发的帧", listOf("start", "frame", "frame", "screen"), link.events)

        r.onTurnStart()
        assertEquals(
            "窗口收尾:已开段必须 tts_stop",
            listOf("start", "frame", "frame", "screen", "stop"),
            link.events,
        )
    }

    /** 门从拦截变为放行(设备重连后补报 caps / 用户打开开关):下一段立刻能走(实时读设置)。 */
    @Test
    fun gate_opens_mid_flight_when_capability_arrives() {
        var capable = false
        val link = FakeDownlink()
        val r = relay(link) { openGate(deviceTtsCapable = capable) }

        r.onTtsAudio(opus(1), 24, 60)
        assertTrue("能力未到时报的帧一律不下发", link.events.isEmpty())

        capable = true
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals("放行后仍要等正文上屏", emptyList<String>(), link.events)
        r.onTtsState("stop", "")
        assertEquals("stop 不开播", emptyList<String>(), link.events)
        link.replyOnScreen(r)
        assertEquals(listOf("screen", "start", "frame"), link.events)
    }

    /**
     * 真机 bug 的回归:开关被(错误地)存成 false 时直通门全拦;用户在小智 AI 下手动打开后必须立刻放行。
     *
     * 真机日志形态:`直通门: type=xiaozhi enabled=false caps=true → 拦截(设备朗读开关(tts_enabled)关闭)`
     * → 设备侧 `TTS=0`。直通门实时读设置(不需要重启服务),所以打开开关后下一段就要能走。
     */
    @Test
    fun gate_allows_xiaozhi_audio_once_device_tts_is_turned_on() {
        var enabled = false
        val link = FakeDownlink()
        val r = relay(link) { openGate(ttsEnabled = enabled) }

        r.onTtsState("start", "")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertTrue("开关关闭时不能下发任何帧", link.events == listOf("screen"))
        assertFalse(openGate(ttsEnabled = false).allowed)

        enabled = true
        assertTrue("ttsEnabled=true 时直通门必须放行", openGate(ttsEnabled = true).allowed)
        assertEquals(null, openGate(ttsEnabled = true).blockedReason)
        r.onTurnStart()
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(listOf("screen", "screen", "start", "frame"), link.events)
    }

    /** 门的判定与日志同源:放行/拦截的各组合都能给出可读原因。 */
    @Test
    fun gate_describes_each_blocked_condition() {
        assertTrue(openGate().allowed)
        assertEquals(null, openGate().blockedReason)

        assertEquals(
            "当前网关不是小智 AI(type=hermes)",
            openGate(gatewayType = "hermes").blockedReason,
        )
        assertEquals(
            "设备朗读开关(tts_enabled)关闭",
            openGate(ttsEnabled = false).blockedReason,
        )
        assertEquals(
            "设备未在 hello 报 caps:[\"tts_opus\"]",
            openGate(deviceTtsCapable = false).blockedReason,
        )
        assertTrue("非小智网关即使开关打开也不放行", !openGate(gatewayType = "echo").allowed)
    }

    /**
     * 「关闭播放小智语音」= 拦下小智音频,而**不是**回退成本地合成。
     *
     * 小智模式的音频来源只有一条:它自己随会话下发的 opus
     * ([XiaozhiGateway.providesDeviceTtsAudio] = true —— 流水线 `speakReply` 对这种网关直接返回,
     * 既不做本地合成也不回退手机朗读)。因此关闭开关的结果就是「设备安静」。
     */
    @Test
    fun xiaozhi_disabled_does_not_fall_back_to_local_synthesis() {
        // ① 小智网关自带音频 → 本地合成那条路在流水线里被 providesDeviceTtsAudio 挡住(不会补一份本地合成)
        assertTrue(
            "小智网关自带下行音频:关闭开关不能变成回退本地合成",
            XiaozhiGateway(null).providesDeviceTtsAudio,
        )

        // ② 关闭开关 → 唯一的音频通路被拦,且原因就是开关(不是设备不支持)
        val gate = openGate(ttsEnabled = false)
        assertFalse("关闭后不能放行任何小智音频", gate.allowed)
        assertEquals("设备朗读开关(tts_enabled)关闭", gate.blockedReason)

        // ③ 拦截就是全部:relay 依旧不下发任何帧/控制(没有第二条本地合成通路)
        val link = FakeDownlink()
        val r = relay(link) { gate }
        r.onTtsState("start", "")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertTrue("关闭时设备必须是安静的(不下发、也不本地合成)", link.events == listOf("screen"))
    }

    // ---- 非法帧 ----

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

        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals("非法帧一帧都不能下发(也没什么可开段/收尾)", listOf("screen"), link.events)

        // 后续合法帧不受影响(丢弃只针对那一帧)
        r.onTurnStart()
        r.onTtsState("sentence_start", "下一段")
        r.onTtsAudio(ByteArray(4), 16, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(1, link.frames.size)
        assertEquals(16, decodeTtsOpusPayload(link.frames[0])!!.rateKhz)
        assertEquals(listOf(16 to 60), link.frameMeta)
        assertEquals(listOf("screen", "screen", "start", "frame"), link.events)
    }

    // ---- SEQ 回绕 ----

    @Test
    fun audio_frames_carry_xiaozhi_header_and_seq_wraps_at_256() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onTtsState("start", "")
        val opus = ByteArray(10) { (it * 3 and 0xFF).toByte() }

        // 257 帧(< 缓冲上限 300):正文上屏前只在缓冲里
        repeat(257) { r.onTtsAudio(opus, 24, 60) }
        assertTrue("正文上屏之前:全部只在缓冲里", link.events.isEmpty())

        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(257, link.frames.size)
        val first = decodeTtsOpusPayload(link.frames.first())!!
        assertEquals(0, first.seq)
        assertEquals(24, first.rateKhz)
        assertEquals(60, first.frameMs)
        assertArrayEquals("opus 包必须原样透传", opus, first.opus)
        assertEquals(255, decodeTtsOpusPayload(link.frames[255])!!.seq)
        assertEquals("SEQ 到 256 回绕到 0", 0, decodeTtsOpusPayload(link.frames[256])!!.seq)
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals("窗口仍开:末帧不是 tts_stop", "frame", link.events.last())
    }

    // ---- 只推音频、没有状态报文的服务端(既有兜底,不能改成缓冲) ----

    /**
     * 真机 bug 的回归测试:服务端只推二进制音频、一条 `tts` JSON 都没有时,**首帧必须兜底开段**。
     *
     * 这种服务端没有「整段正文齐(`stop`)」的信号:缓冲等待会让整段音频永远发不出去,
     * 所以这里保持既有的即时下发(缓冲策略的例外,理由见 [XiaozhiTtsRelay] 类注释)。
     */
    @Test
    fun audio_without_any_tts_state_report_opens_segment_and_pushes() {
        val link = FakeDownlink()
        val r = relay(link)

        // 没有 start/sentence_start/sentence_end/stop,只有音频帧
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsAudio(opus(3), 24, 60)

        assertEquals("首帧就要补上 tts_start(bracket 不能缺)", listOf("start", "frame", "frame", "frame"), link.events)
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals(24, decodeTtsOpusPayload(link.frames.first())!!.rateKhz)
        assertEquals(60, decodeTtsOpusPayload(link.frames.first())!!.frameMs)

        // 跨轮同样成立:下一轮即使上一轮的 stop 没来,音频也要继续走到设备(并重新 bracket)
        r.onTurnStart()
        r.onTtsAudio(opus(4), 24, 60)
        assertEquals(
            listOf("start", "frame", "frame", "frame", "stop", "start", "frame"),
            link.events,
        )
        assertEquals(4, link.frames.size)
    }
}
