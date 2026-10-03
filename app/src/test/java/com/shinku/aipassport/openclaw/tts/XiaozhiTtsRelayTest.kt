package com.shinku.aipassport.openclaw.tts

import com.shinku.aipassport.openclaw.gateway.XiaozhiGateway
import com.shinku.aipassport.openclaw.protocol.decodeTtsOpusPayload
import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity
import com.shinku.aipassport.openclaw.stt.XiaozhiReplyOutcome
import com.shinku.aipassport.openclaw.stt.XiaozhiReplyText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小智 TTS 直通(`XiaozhiTtsRelay`)的 JVM 单测:无网络、无 Android/BLE 依赖。
 *
 * 被测行为(作者 2026-10 定稿的「第一句就好」,见 `docs/design/xiaozhi-ai-gateway.md` §4.4、§6):
 * **本轮第一条正文（渐进交付下 = 首句）上屏就开播；屏幕文字随后由补正补齐；`tts.stop` 只做文本侧最终结算。**
 *
 * 覆盖的契约:
 *  1. **触发点**:开播由**「本轮正文已上屏」信号**([XiaozhiTtsRelay.onReplyTextDisplayed])触发,
 *     已缓冲的帧按到达顺序下发、`tts_start` 必在首帧前;**渐进交付下首句正文上屏就是第一个信号**
 *     (不必等 `stop`);后续补正上屏**不得**打断/重启已开始的音频;
 *  2. **顺序**:正文上屏必须早于第一个音频帧(信号之前一个音频字节都不下发),而且**只有「本轮正文」**
 *     上屏才算信号 —— 识别原文(`'U'`)、同为 `'A'` 的提示/兜底文本、上一轮的迟到信号都不许抢跑;
 *  3. **窗口在 `stop` 之后保持打开**:迟到帧照常下发(一边播一边缓冲),SEQ 连续;
 *  4. **窗口收尾**:新一轮 `turn_start` / barge / 设备 `turn_cancel`(都走 [XiaozhiTtsRelay.onTurnStart])
 *     与设备回报本段播放结束([XiaozhiTtsRelay.onDevicePlaybackFinished])→ 丢弃剩余缓冲 + `tts_stop`;
 *     另一条**不依赖设备回报**的确定收尾是「推空 + 静默达上限」([XiaozhiTtsRelay.onIdleTailStop],
 *     由服务侧 [com.shinku.aipassport.openclaw.tts.XiaozhiTailStop] 判定)—— 幂等,且不关窗口
 *     (迟到帧仍会续一段,不切尾音);
 *  5. **一轮开始/打断清空缓冲**:上一轮的残帧绝不进下一轮;
 *  6. **缓冲上限**:正文上屏之前超限时**丢最旧的一帧**(内存有界),绝不为保音频而提前开播 ——
 *     降级不许抢跑(作者要求「降级时宁可稍晚也别抢跑」);
 *  7. **帧头**:`[SEQ][rate_khz][frame_ms] + opus`,rate/frame 用小智 hello 自报的 24 kHz/60 ms,
 *     SEQ 每帧 +1、到 256 回绕;opus 包原样透传(不解码/不重编码);非法帧丢弃;
 *  8. **设备朗读关掉就不下发音频**(直通门),小智模式**不回退**本地合成;
 *  9. **服务端只推二进制音频、一条 `tts` JSON 都没有时**,帧不按窗口丢弃,但**仍然**等
 *     「正文已上屏」信号才下发(否则声音会跑到文字前面)。
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
     * 走完「本轮正文上屏」这一步:真实实现是服务侧 `sendTextFrame('A', 正文)` 把分片写进 BLE 串行写队列
     * **之后**调 [XiaozhiTtsRelay.onReplyTextDisplayed](见 `VoiceBridgeService.notifyXiaozhiReplyOnScreen`),
     * 而本轮正文则由会话层先经 [XiaozhiTtsRelay.onReplyBody] 告知。单测里两个调用都走一遍,
     * 并在需要顺序断言时往下发通路的 events 里插一条 `screen` 标记。
     *
     * @param body 本轮正文(默认「正文」);必须与 [XiaozhiReplyText] 交出的那个字符串一致,
     *   否则会被直通侧按「这条 `'A'` 不是本轮正文」拒掉(见下方的抢跑用例)。
     */
    private fun FakeDownlink.replyOnScreen(relay: XiaozhiTtsRelay, body: String = "正文") {
        relay.onReplyBody(body)
        events.add("screen")
        relay.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, body)
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
     * 与直通 relay —— **首句**正文上屏后音频才开播,而**第一个音频帧必须晚于正文上屏**;
     * 而且上屏信号拿的就是装配器交出的**那串文本**(与真实链路的转手一致)。
     */
    @Test
    fun reply_text_is_on_screen_before_the_first_audio_frame() {
        val emitted = ArrayList<String>()
        val link = FakeDownlink()
        val r = relay(link)
        // 正文装配器把「本轮正文」与「上屏」都记进同一条时间线:真实实现里,会话层先调 relay.onReplyBody,
        // 然后流水线把同一串文本写成 TEXT('A')(写完才信号 relay)。
        // 未变化的诊断行**不上屏也不通知观察者**(与会话层 `XiaozhiSession.emitReply` 同构)。
        val onReply: (XiaozhiReplyOutcome) -> Unit = onReply@ { outcome ->
            if (!outcome.changed) return@onReply
            link.events.add("text")
            emitted += outcome.body
            r.onReplyBody(outcome.body)
            link.events.add("screen")
            r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, outcome.body)
        }
        val reply = XiaozhiReplyText(emit = onReply)
        reply.onTurnStart()
        r.onTurnStart()
        val onState: (String, String) -> Unit = { state, text ->
            reply.onTtsState(state, text)
            r.onTtsState(state, text)
        }

        onState("sentence_start", "今天晴，")
        assertEquals("首句正文一交付就上屏(渐进交付),此时还没有音频事件", listOf("text", "screen"), link.events)
        assertTrue("正文上屏之前一个音频字节都不下发", link.events.none { it == "frame" })

        r.onTtsAudio(opus(1), 24, 60)
        assertEquals("正文上屏 → 开播 → 首帧", listOf("text", "screen", "start", "frame"), link.events)

        onState("sentence_end", "今天晴，二十度。")
        r.onTtsAudio(opus(2), 24, 60)
        onState("sentence_start", "记得带伞。")
        r.onTtsAudio(opus(3), 24, 60)

        assertEquals(
            "时间线:每次正文上屏都在音频下发之前,且只开一段(补正不重启音频)",
            listOf(
                "text", "screen", "start", "frame",
                "text", "screen", "frame",
                "text", "screen", "frame",
            ),
            link.events,
        )
        assertTrue(
            "正文上屏必须早于第一个音频帧",
            link.events.indexOf("text") < link.events.indexOf("frame"),
        )
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals(
            "每次交付的都是当前累积的完整拼接(一次比一次完整)",
            listOf("今天晴，", "今天晴，二十度。", "今天晴，二十度。记得带伞。"),
            emitted,
        )

        onState("stop", "")
        assertEquals("stop 上文本没变:不再交付、也不产生新的上屏事件", 3, emitted.size)
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
     * 信号一到就开播(作者要求:文字上屏后**立刻**开始播放,不等 `stop`、也不等整段音频),`stop`
     * 到达只是继续把窗口开着。
     */
    @Test
    fun text_on_screen_starts_playback_immediately_even_before_stop() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(opus(1), 24, 60)
        link.replyOnScreen(r)
        assertEquals("上屏信号一到就开播,不再等 stop", listOf("screen", "start", "frame"), link.events)

        r.onTtsState("stop", "")
        assertEquals("stop 到达不改节奏(窗口仍开)", listOf("screen", "start", "frame"), link.events)

        r.onTtsAudio(opus(2), 24, 60)
        assertEquals(listOf("screen", "start", "frame", "frame"), link.events)
        assertEquals(listOf(0, 1), link.seqs())
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

    /**
     * 渐进交付下同一轮会有**多次**「正文上屏」(`TEXT('A')`:首句 + 每次补正)。
     * 补正上屏**不得**重启/打断已开始的音频(只有一个 `start`、没有 `stop`),帧继续按到达顺序下发;
     * [XiaozhiTtsRelay.replyScreenOrdinal] 要如实报出「这是本轮第几次上屏」——
     * 服务侧的**补正让路**([com.shinku.aipassport.openclaw.tts.XiaozhiCorrectionPacer])就靠它
     * 分辨「首句(必须立刻上屏开播)」与「补正(音频紧时可以暂缓)」。
     */
    @Test
    fun correction_on_screen_does_not_restart_the_running_audio() {
        val link = FakeDownlink()
        val r = relay(link)

        // 首句:信号即开播
        r.onTtsState("sentence_start", "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onReplyBody("第一句。")
        assertEquals(
            "首句是本轮第 1 次上屏",
            1,
            r.replyScreenOrdinal(XiaozhiScreenSignal.REPLY_ROLE, "第一句。") ?: -1,
        )
        assertTrue(r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。"))
        assertEquals(listOf("start", "frame"), link.events)

        // 第二句 = 补正:正文变完整、音频继续(不得重发 tts_start / tts_stop)
        r.onTtsState("sentence_start", "第二句。")
        r.onTtsAudio(opus(2), 24, 60)
        r.onReplyBody("第一句。第二句。")
        assertEquals(
            "补正是本轮第 2 次上屏",
            2,
            r.replyScreenOrdinal(XiaozhiScreenSignal.REPLY_ROLE, "第一句。第二句。") ?: -1,
        )
        assertTrue(r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。第二句。"))
        assertEquals(
            "补正上屏只继续推帧,不重启也不打断",
            listOf("start", "frame", "frame"),
            link.events,
        )
        assertEquals(listOf(0, 1), link.seqs())
        // 首句那条旧文字仍然算「本轮正文」(服务侧的让路/日志判定不能被补正弄坏)
        assertTrue(r.acceptsScreenSignal(XiaozhiScreenSignal.REPLY_ROLE, "第一句。"))
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

    // ---- ⑥ 缓冲上限(降级不许抢跑) ----

    /**
     * 验收点 ⑥:正文上屏之前缓冲到上限时**丢最旧的一帧**(内存有界),并继续等「正文已上屏」信号 ——
     * **绝不**为了保住音频而提前开播:文字必须早于声音,降级时宁可稍晚。
     */
    @Test
    fun buffer_overflow_drops_the_oldest_frame_but_never_starts_before_the_screen_signal() {
        val link = FakeDownlink()
        val r = relay(link, maxBufferFrames = 3)

        r.onTtsState("sentence_start", "长回复")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        assertTrue("没到上限就还在缓冲", link.events.isEmpty())

        r.onTtsAudio(opus(3), 24, 60)
        r.onTtsAudio(opus(4), 24, 60)
        r.onTtsAudio(opus(5), 24, 60)
        assertTrue("超限也只丢最旧的一帧,一个字节都不抢跑", link.events.isEmpty())

        // 上屏信号之后:把仍在缓冲的帧(最旧的 1/2 已被丢)按到达顺序推出。
        r.onTtsState("stop", "")
        assertTrue("stop 不开播", link.events.isEmpty())
        link.replyOnScreen(r)
        assertEquals(
            "开播后才下发",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals(
            "丢的是最旧的帧,剩下的按到达顺序(SEQ 从 0 起递增)",
            listOf(opus(3).toList(), opus(4).toList(), opus(5).toList()),
            link.opusBodies(),
        )
        assertEquals(listOf(0, 1, 2), link.seqs())

        r.onTurnStart()
        assertEquals(
            "窗口收尾:已开段要 tts_stop",
            listOf("screen", "start", "frame", "frame", "frame", "stop"),
            link.events,
        )
        assertEquals(1, link.events.count { it == "start" })
        assertEquals(1, link.events.count { it == "stop" })
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
     * 已开段(上屏信号之后才关的开关)的一段仍要在窗口收尾时 `tts_stop`,否则设备停在播放态;
     * 但**不再补推**未发的帧。
     */
    @Test
    fun gate_closed_after_segment_started_still_sends_tts_stop_on_window_close() {
        val link = FakeDownlink()
        var on = true
        val r = relay(link) { openGate(ttsEnabled = on) }

        r.onTtsState("sentence_start", "长回复")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals("信号到、开关还开着 → 开段并推已缓冲的帧", listOf("screen", "start", "frame"), link.events)

        on = false
        r.onTtsAudio(opus(2), 24, 60)   // 关掉后不再推
        assertEquals("关掉后不得补推未发的帧", listOf("screen", "start", "frame"), link.events)

        r.onTurnStart()
        assertEquals(
            "窗口收尾:已开段必须 tts_stop",
            listOf("screen", "start", "frame", "stop"),
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

    // ---- 只推音频、没有状态报文的服务端(不按窗口丢弃,但也不抢跑) ----

    /**
     * 服务端只推二进制音频、一条 `tts` JSON 都没有时,帧**不按窗口丢弃**,但**仍然**只在
     * 「正文已上屏」信号之后才下发 —— 顺序优先于及时(作者要求:文字先于声音)。
     *
     * 为什么不能像上一版那样「首帧即开段、即时下发」:那条兜底会让声音跑到文字前面。信号本身是
     * 会来的 —— 正文装配器的空闲兜底窗口 / `llm` 兜底都会走 `sendText('A')`。
     */
    @Test
    fun audio_without_any_tts_state_report_waits_for_the_screen_signal() {
        val link = FakeDownlink()
        val r = relay(link)

        // 没有 start/sentence_start/sentence_end/stop,只有音频帧
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsAudio(opus(3), 24, 60)
        assertTrue("信号之前一个字节都不下发", link.events.isEmpty())

        link.replyOnScreen(r)
        assertEquals(
            "上屏信号一到就补上 tts_start 并按到达顺序推帧",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals(24, decodeTtsOpusPayload(link.frames.first())!!.rateKhz)
        assertEquals(60, decodeTtsOpusPayload(link.frames.first())!!.frameMs)
        assertEquals(listOf(0, 1, 2), link.seqs())

        // 上一轮的 stop 没来时,新一轮的帧同样要等「正文已上屏」信号才下发(并重新 bracket)
        r.onTurnStart()
        r.onTtsAudio(opus(4), 24, 60)
        r.onTtsAudio(opus(5), 24, 60)
        assertEquals(listOf("screen", "start", "frame", "frame", "frame", "stop"), link.events)
        link.replyOnScreen(r)
        assertEquals(
            listOf(
                "screen", "start", "frame", "frame", "frame", "stop",
                "screen", "start", "frame", "frame",
            ),
            link.events,
        )
        assertEquals(5, link.frames.size)
    }

    // ---- ⓬ 抢跑路径逐个封死(2026-10-05 真机修正) ----

    /**
     * 开播判定是**纯函数**([XiaozhiScreenSignal]):只有「角色 = `'A'` 且文本 = 本轮正文」才算信号。
     *
     * 这一层把服务侧所有 `sendTextFrame` 调用点一次盖全:
     *  - `'U'`(识别原文)、`'R'`(系统提示)不是回复;
     *  - 同为 `'A'` 的版本提示、「无语音」、网关超时/失败原因、空回复兜底都不是本轮正文;
     *  - 本轮正文还没装配好/本轮正文为空 → 一律不算;
     *  - 两侧首尾空白允许(网关会 trim),但正文内容必须逐字一致。
     */
    @Test
    fun screen_signal_predicate_only_accepts_the_current_turn_reply_body() {
        assertTrue(XiaozhiScreenSignal.accepts('A', "明天小雨。", "明天小雨。"))
        assertTrue("两侧空白不影响(网关/装配器会 trim)", XiaozhiScreenSignal.accepts('A', " 明天小雨。 ", "明天小雨。"))

        assertFalse("role='U'(识别原文)不是回复", XiaozhiScreenSignal.accepts('U', "明天小雨。", "明天小雨。"))
        assertFalse("role='R'(系统提示)不是回复", XiaozhiScreenSignal.accepts('R', "明天小雨。", "明天小雨。"))
        assertFalse(
            "同为 'A' 的超时/失败原因/版本提示都不算",
            XiaozhiScreenSignal.accepts('A', "小智没有返回回复(等待 30 秒超时)", "明天小雨。"),
        )
        assertFalse("本轮正文还没装配好 → 不算", XiaozhiScreenSignal.accepts('A', "明天小雨。", null))
        assertFalse(
            "本轮正文为空(只有表情/模板) → 任何 'A' 都不算",
            XiaozhiScreenSignal.accepts('A', "小智这一轮没有返回可上屏的正文", ""),
        )
        assertFalse(
            "本轮正文为空时连空文本也不算",
            XiaozhiScreenSignal.accepts('A', "", "  "),
        )
    }

    /**
     * 抢跑路径之一:**非回复文本上屏**不得开播 —— 包括 `'U'` 识别原文、同为 `'A'` 的版本提示/
     * 「无语音」/超时与失败原因/空回复兜底。帧继续缓冲,直到真正文上屏。
     */
    @Test
    fun non_reply_texts_on_screen_never_open_the_gate() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onReplyBody("明天上海是小雨喔，白天23度。")
        r.onTtsState("sentence_start", "明天上海是小雨喔，白天23度。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")

        // ① role='U' 识别原文(与本轮回复音频无关)
        r.onReplyTextDisplayed('U', "明天上海天气")
        // ② role='R' 系统提示
        r.onReplyTextDisplayed('R', "明天上海是小雨喔，白天23度。")
        // ③ 同为 role='A',但不是本轮正文:版本提示 / 「无语音」/ 超时与失败原因 / 空回复兜底
        r.onReplyTextDisplayed('A', "版本提示:固件与 App 版本不一致")
        r.onReplyTextDisplayed('A', "无语音")
        r.onReplyTextDisplayed('A', "小智没有返回回复(等待 30 秒超时)")
        r.onReplyTextDisplayed('A', "小智这一轮没有返回可上屏的正文(只有表情/空文本或工具模板)")
        r.onReplyTextDisplayed('A', "(网关空回复)")
        assertTrue("这些文本上屏都不许把音频放出来(声音不能跑到文字前面)", link.events.isEmpty())

        // ④ 真正文上屏 → 这时才开播
        val accepted = r.onReplyTextDisplayed('A', "明天上海是小雨喔，白天23度。")
        assertTrue("本轮正文上屏必须被接受", accepted)
        assertEquals(listOf("start", "frame"), link.events)
    }

    /** 本轮正文为空(只有表情/模板):任何 `'A'` 都不开播 —— 没有文字就没有「文字先于声音」可言。 */
    @Test
    fun empty_turn_body_keeps_the_gate_shut() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onReplyBody("")
        r.onTtsState("sentence_start", "😊")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsState("stop", "")

        assertFalse(r.onReplyTextDisplayed('A', ""))
        assertFalse(r.onReplyTextDisplayed('A', "小智这一轮没有返回可上屏的正文"))
        assertTrue("本轮没有可上屏正文 → 一个音频字节也不下发", link.events.isEmpty())
    }

    /**
     * 抢跑路径之二:**上一轮的迟到信号**。
     *
     * `turn_start` 清掉本轮正文记录后,旧轮那条 `TEXT('A')`(B3 已在写队列里、晚一步才通知)既使文本
     * 完全正确也不得打开新一轮的闸门 —— 否则新一轮的第一帧会抢在新一轮正文之前出声。
     */
    @Test
    fun late_screen_signal_from_the_previous_turn_cannot_open_the_new_turn() {
        val link = FakeDownlink()
        val r = relay(link)

        // 上一轮:已开播
        r.onReplyBody("旧轮正文")
        r.onTtsState("sentence_start", "旧轮正文")
        r.onTtsAudio(opus(0x11), 24, 60)
        link.replyOnScreen(r, "旧轮正文")
        assertEquals(listOf("screen", "start", "frame"), link.events)

        // 新一轮(流水线 turn_start:abort + relay.onTurnStart):旧轮的播放窗口收尾,旧轮残帧丢弃
        r.onTurnStart()
        assertEquals(listOf("screen", "start", "frame", "stop"), link.events)

        // 新一轮的帧先到:仍要等新一轮正文的上屏信号
        r.onTtsState("sentence_start", "新轮正文")
        r.onTtsAudio(opus(0x21), 24, 60)

        // 旧轮的迟到信号(文本还是旧轮的):必须被拒
        assertFalse(
            "旧轮的迟到信号不得打开新一轮的闸门",
            r.onReplyTextDisplayed('A', "旧轮正文"),
        )
        assertEquals("仍然只在缓冲里", listOf("screen", "start", "frame", "stop"), link.events)

        // 新一轮的正文上屏(会话层先告知本轮正文,服务侧再把同一串文本写进队列并发信号)
        r.onReplyBody("新轮正文")
        assertTrue(r.onReplyTextDisplayed('A', "新轮正文"))
        assertEquals(
            listOf("screen", "start", "frame", "stop", "start", "frame"),
            link.events,
        )
        assertEquals(listOf(opus(0x11).toList(), opus(0x21).toList()), link.opusBodies())
    }

    // ---- ⓮ 渐进交付:「第一句就好」(本轮主验收) ----

    /**
     * **首句正文上屏即开播**;第二句的**补正上屏不得打断/重启**已开始的音频;`stop` 之后窗口仍开、
     * 迟到帧继续按到达顺序即时下发(SEQ 连续,不多发一组 `tts_start`/`tts_stop`)。
     */
    @Test
    fun first_sentence_screen_signal_opens_playback_and_corrections_do_not_restart_it() {
        val link = FakeDownlink()
        val r = relay(link)

        // 首句:文本先到、音频紧随(真机顺序)
        r.onTtsState("sentence_start", "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals("还没交付正文 → 不算上屏序号", null, r.replyScreenOrdinal('A', "第一句。"))

        link.replyOnScreen(r, "第一句。")
        assertEquals(
            "首句正文一上屏就开播(音频是同一批帧,不必等 stop)",
            listOf("screen", "start", "frame"),
            link.events,
        )
        assertEquals("本次是首次上屏(下一次才是补正)", 2, r.replyScreenOrdinal('A', "第一句。"))

        // 第二句(补正):音频已在即时下发,补正上屏只让窗口继续开着
        r.onTtsState("sentence_start", "第二句。")
        r.onTtsAudio(opus(2), 24, 60)
        r.onReplyBody("第一句。第二句。")
        assertTrue(
            "补正正文同样算本轮的正文上屏信号",
            r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。第二句。"),
        )
        assertEquals(
            "补正上屏不打断/不重启已开始的音频",
            listOf("screen", "start", "frame", "frame"),
            link.events,
        )
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })

        // stop 只做文本侧最终结算;迟到帧照旧继续下发(窗口不关)
        r.onTtsState("stop", "")
        r.onTtsAudio(opus(3), 24, 60)
        r.onTtsAudio(opus(4), 24, 60)
        assertEquals(
            "stop 之后窗口保持打开:迟到帧继续即时下发",
            listOf("screen", "start", "frame", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("SEQ 连续、不丢不重", listOf(0, 1, 2, 3), link.seqs())
        assertEquals("这一段还没收尾:不该有 tts_stop", 0, link.events.count { it == "stop" })
    }

    /**
     * 两个**真实件**接起来的**真机那种逐句交错流**(正文装配器 → 直通 relay → 假设备):
     * 噪声句不交付/不开播;首句一到就 `tts_start` + 首帧;后续补正接着下发;`stop` 之后迟到帧继续下发。
     *
     * 这条用例把「**首句上屏 → 首帧**」在时间线上钉死,也是「不会退回正文不完整」的端到端证据:
     * 装配器每一跳交出的都是**累积完整拼接**,屏幕上不会出现模板。
     */
    @Test
    fun progressive_flow_starts_audio_at_the_first_sentence_and_keeps_late_frames() {
        val link = FakeDownlink()
        val r = relay(link)
        val emitted = ArrayList<String>()
        // 与会话层同构:装配器交出正文 → 先告知直通侧本轮正文,再把同一串文本当成 `TEXT('A')` 已入队发信号。
        // 未变化的诊断行**不上屏也不通知观察者**(与会话层 `XiaozhiSession.emitReply` 同构)。
        val onReply: (XiaozhiReplyOutcome) -> Unit = onReply@ { outcome ->
            if (!outcome.changed) return@onReply
            emitted += outcome.body
            link.events.add("text")
            r.onReplyBody(outcome.body)
            link.events.add("screen")
            r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, outcome.body)
        }
        val reply = XiaozhiReplyText(emit = onReply)
        reply.onTurnStart()
        r.onTurnStart()
        val onState: (String, String) -> Unit = { state, text ->
            reply.onTtsState(state, text)
            r.onTtsState(state, text)
        }

        onState("start", "")
        onState("sentence_start", "% get_weather(location=\"上海\")")   // 噪声:不交付、不开播
        assertEquals("只拿到模板时既不交付也不开播", emptyList<String>(), link.events)
        assertTrue(emitted.isEmpty())

        onState("sentence_start", "第一句。")                          // 首句 → 交付 + 开播
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(
            "时间线:首句正文上屏 → tts_start → 首帧",
            listOf("text", "screen", "start", "frame"),
            link.events,
        )
        assertEquals("首句是渐进交付的第 1 条", listOf("第一句。"), emitted)

        onState("sentence_start", "第二句。")                          // 补正:接下去
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals("补正只多了一屏文字,不重启音频", listOf("第一句。", "第一句。第二句。"), emitted)

        onState("stop", "")                                          // 最终结算:文本没变 → 不再交付
        r.onTtsAudio(opus(3), 24, 60)
        r.onTtsAudio(opus(4), 24, 60)
        assertEquals("stop 不产生新正文", listOf("第一句。", "第一句。第二句。"), emitted)
        assertEquals(
            "最终时间线:两次正文上屏、一次开段、四帧(含 stop 后迟到帧)",
            listOf("text", "screen", "start", "frame", "text", "screen", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("音频在第一帧之前就已开段", 1, link.events.count { it == "start" })
        assertEquals(listOf(0, 1, 2, 3), link.seqs())
        assertFalse("交付的正文绝不能含模板", emitted.any { it.contains("get_weather") })
    }

    /**
     * **首句的上屏信号晚于补正到达时仍要被接受**(渐进交付下会话层与流水线是两个线程在赛跑):
     * 只认「最后一条」会把先发出的首句信号误判成「不是本轮正文」而拒绝开播 —— 正好破坏「首句即开播」。
     */
    @Test
    fun first_sentence_signal_is_accepted_even_after_a_correction_already_arrived() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onReplyBody("第一句。")
        r.onReplyBody("第一句。第二句。")   // 补正先到(首句那条 TEXT 还在写队列里)
        r.onTtsState("sentence_start", "第一句。第二句。")
        r.onTtsAudio(opus(1), 24, 60)

        assertTrue(
            "首句的上屏信号即使晚于补正到达也要被接受",
            r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。"),
        )
        assertEquals(listOf("start", "frame"), link.events)
        assertEquals("信号已被计入:同一条再上屏就是第 2 次", 2, r.replyScreenOrdinal('A', "第一句。"))
        assertEquals("不相干的 'A' 文本仍不算信号(不可开播)", 0, r.replyScreenOrdinal('A', "无语音"))
    }

    // ---- ⓭ 确定收尾:推空 + 静默达上限(不依赖设备回报) ----

    /**
     * [XiaozhiTailStop] 的纯判定:没推过帧不收尾、未到阈值不收尾、到阈值才收、已收过不重收。
     * 这三个条件就是「不能因收尾太早把还在排队的音频切掉」与「幂等」的静态保证。
     */
    @Test
    fun tail_stop_predicate_requires_frames_and_idle_and_not_already_stopped() {
        assertFalse("从没推过帧(本段没开播) → 不需要 tts_stop", XiaozhiTailStop.shouldStop(0, 60_000L, false))
        assertFalse(
            "推过帧但静默未到上限 → 还在等可能的后继帧",
            XiaozhiTailStop.shouldStop(30, XiaozhiTailStop.TAIL_IDLE_MS - 1, false),
        )
        assertFalse(
            "已经收过尾 → 不重发(幂等)",
            XiaozhiTailStop.shouldStop(30, XiaozhiTailStop.TAIL_IDLE_MS, true),
        )
        assertTrue(XiaozhiTailStop.shouldStop(1, XiaozhiTailStop.TAIL_IDLE_MS, false))
        assertTrue(XiaozhiTailStop.shouldStop(300, XiaozhiTailStop.TAIL_IDLE_MS * 3, false))
    }

    /**
     * 「推空 + 静默」的主动收尾([XiaozhiTtsRelay.onIdleTailStop]):
     * 已开段 → `tts_stop`(设备回到空闲);幂等;**不关窗口** —— 迟到帧仍会续一段(不切尾音)。
     */
    @Test
    fun idle_tail_stop_closes_the_play_state_but_keeps_late_frames_flowing() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onReplyBody("正文")
        r.onTtsState("sentence_start", "正文")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsState("stop", "")
        link.replyOnScreen(r)
        assertEquals(listOf("screen", "start", "frame", "frame"), link.events)

        r.onIdleTailStop(2)
        assertEquals(
            "本段推空且静默:主动收尾(设备不能一直停在「接收中」)",
            listOf("screen", "start", "frame", "frame", "stop"),
            link.events,
        )

        // 幂等:再叫一次不产生第二条 tts_stop(此时本段已收尾)
        r.onIdleTailStop(2)
        assertEquals(5, link.events.size)

        // 迟到帧:**不关窗口**,所以要续一段(重新 tts_start)而不是丢弃尾音
        r.onTtsAudio(opus(3), 24, 60)
        assertEquals(
            listOf("screen", "start", "frame", "frame", "stop", "start", "frame"),
            link.events,
        )
        assertEquals(listOf(0, 1, 2), link.seqs())
    }

    /** 本段从未开过段(没推过任何帧)时,主动收尾不产生任何下发(设备未进入播放态,无需 tts_stop)。 */
    @Test
    fun idle_tail_stop_is_a_noop_when_no_segment_started() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onReplyBody("正文")
        r.onTtsState("sentence_start", "正文")
        r.onIdleTailStop(0)
        assertTrue(link.events.isEmpty())
    }

    // ---- ⑯ 句级对齐:句边界切分与「不切音频」(2026-10 作者时序) ----

    /**
     * 验收点①:音频帧本身**没有句界标记**,句界只能按「正文到达那一刻已收到的帧数」切 ——
     * 第 N 句的音频起点 ≈ 交付第 N 条正文时本段已收到的帧数 × 60ms。
     *
     * 覆盖:多句、空句、噪声句(不交付 → 不记边界)、同一句被 `sentence_start`/`sentence_end` 各带一次
     * (不产生第二条边界)、重复告知同一条正文(边界不往后挪)、缺 `sentence_start`(只靠交付记账)。
     */
    @Test
    fun sentence_start_offsets_are_cut_by_the_frames_that_arrived_before_each_body() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("start", "")
        r.onTtsState("sentence_start", "")                          // 空句:不交付也不记边界
        r.onTtsState("sentence_start", "第一句。")
        r.onReplyBody("第一句。")                                     // 第 1 句交付:此刻 0 帧
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsAudio(opus(3), 24, 60)
        r.onReplyBody("第一句。")                                     // 重复告知:边界不往后挪
        r.onTtsState("sentence_end", "第一句。")                     // 同一句的后半条:不产生新交付
        r.onTtsState("sentence_start", "% get_weather(location=\"上海\")")   // 噪声句:不交付 → 不记边界
        r.onTtsAudio(opus(4), 24, 60)
        r.onTtsState("sentence_start", "第二句。")
        r.onReplyBody("第一句。第二句。")                             // 第 2 句交付:4 帧
        r.onTtsAudio(opus(5), 24, 60)
        // 缺 `sentence_start`:服务端直接给下一句文本,交付仍然记边界
        r.onReplyBody("第一句。第二句。第三句。")                      // 第 3 句交付:5 帧

        assertEquals("首句起点 ≈ 0(交付时一帧音频还没收到)", 0L, r.sentenceStartMs("第一句。"))
        assertEquals("第 2 句起点 = 第 1 句那 3 帧 + 噪声句那 1 帧", 4 * 60L, r.sentenceStartMs("第一句。第二句。"))
        assertEquals(5 * 60L, r.sentenceStartMs("第一句。第二句。第三句。"))
        assertNull("没交付过的文本没有边界记录", r.sentenceStartMs("第二句。"))
        assertEquals("首句仍是本轮第 1 次上屏(它不走句级对齐)", 1, r.replyScreenOrdinal('A', "第一句。") ?: -1)
    }

    /**
     * 句级对齐**只在真的收到过可下发音频帧**时可用;`turn_start` 一并作废(旧轮起点不能用在新一轮,
     * 否则新一轮的第一句字幕会被押到旧轮的时间点上)。
     */
    @Test
    fun sentence_alignment_is_unavailable_without_audio_and_resets_each_turn() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onReplyBody("第一句。")
        r.onReplyBody("第一句。第二句。")
        assertNull("一帧可下发的音频都没收到:不提供句级对齐(不能把字幕拖到收尾)", r.sentenceStartMs("第一句。第二句。"))

        // 有音频的一轮:边界可用……
        r.onTurnStart()
        r.onReplyBody("第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onReplyBody("第一句。第二句。")
        assertEquals(60L, r.sentenceStartMs("第一句。第二句。"))
        // ……但 turn_start 之后立刻作废
        r.onTurnStart()
        assertNull("新一轮:上一轮的句边界已作废", r.sentenceStartMs("第一句。第二句。"))
    }

    /**
     * 验收点④:**句级对齐只改字幕落位时刻,不切音频** —— 多句的帧仍是一条连续流:
     * 整段只有一个 `tts_start`、句界处没有 `tts_stop`(不留空档),SEQ 连续、顺序 = 到达顺序。
     */
    @Test
    fun sentence_boundaries_do_not_break_the_continuous_audio_stream() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsState("sentence_start", "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onReplyBody("第一句。")
        r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。")   // 首句上屏 → 开播
        assertEquals(listOf("start", "frame", "frame"), link.events)

        // 第 2 句:字幕在它自己那句音频到点时上屏(单测直接发信号);句界处**没有** tts_stop,
        // 也没有第二个 tts_start —— 改的只是字幕落位时刻。
        r.onTtsState("sentence_start", "第二句。")
        r.onReplyBody("第一句。第二句。")
        r.onTtsAudio(opus(3), 24, 60)
        r.onTtsAudio(opus(4), 24, 60)
        r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。第二句。")

        r.onTtsState("stop", "")
        r.onTtsAudio(opus(5), 24, 60)
        r.onTtsAudio(opus(6), 24, 60)

        assertEquals(
            "一条连续流:只有一个 tts_start、句界处没有 tts_stop、也没有空档",
            listOf("start", "frame", "frame", "frame", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("SEQ 连续、不丢不重", listOf(0, 1, 2, 3, 4, 5), link.seqs())
        assertEquals(
            "帧顺序 = 到达顺序(句界没有把音频切段/重排)",
            (1..6).map { opus(it).toList() },
            link.opusBodies(),
        )
        assertEquals("第 2 句的音频起点 = 前 2 帧", 2 * 60L, r.sentenceStartMs("第一句。第二句。"))
    }
}
