package com.shinku.aipassport.openclaw.tts

import com.shinku.aipassport.openclaw.gateway.XiaozhiGateway
import com.shinku.aipassport.openclaw.protocol.decodeTtsOpusPayload
import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小智 TTS 直通(`XiaozhiTtsRelay`)的 JVM 单测:无网络、无 Android/BLE 依赖(时钟注入)。
 *
 * 被测模型(**按段播放**,2026-10 作者最终口径,见 `docs/design/xiaozhi-ai-gateway.md` §5):
 * **小智能区分段落,按段落播放对应语音** —— 一轮回复有多个段(段 = 小智自己的一句),
 * 每段:`tts_start` → 该段音频帧 → `tts_stop`;设备回报「这一段播完」(或兜底超时)之后才进下一段。
 *
 * 覆盖的契约:
 *  1. **多段切分与逐段括号**:一句一对 `tts_start`/`tts_stop`,段的顺序 = 小智的句序;段尾由
 *     「下一句文本到达」(或段尾静默兜底)决定,`tts.state=stop` **不**收段(尾帧还要收);
 *  2. **段间等设备回报**:回报到达 / 兜底超时两条路都能进下一段,且不在设备还在播上一段时抢跑;
 *  3. **字幕与同段音频的先后**:第 N 段的 `tts_start` 只在它的字幕已写进 BLE 串行写队列之后;
 *     **字幕随段推进**（2026-10 真机修正):第 N 段的字幕只在**第 N 段真正开始**的那一刻才写
 *     —— 文本可以早到,但**只缓存**（首段仍立即上屏）。
 *  4. **不丢音频、不串段**:段内迟到的帧照收(一段 `tts_stop` 之后绝不再有帧插进那一段);
 *     缺帧的段跳过;换轮/打断清全部段,上一轮在途的残帧丢弃;
 *  5. **既有不变量**:首段(首句)文本+音频齐了就**立即**开播;直通门(网关类型/开关/设备能力)
 *     任一不满足时整段零下发;一轮开始清空;缓冲有上限且绝不提前开播;
 *  6. **帧头**:`[SEQ][rate_khz][frame_ms] + opus`,24 kHz/60 ms,SEQ 每帧 +1、到 256 回绕;
 *  7. **抢跑路径**:非本轮正文(识别原文 / 版本提示 / 超时原因 / 上一轮迟到信号)一律不开播。
 */
class XiaozhiTtsRelayTest {

    /** 记录下发调用的假通路(顺序也记下来 —— 「什么时候发 tts_start/tts_stop」是本类的核心契约)。 */
    private class FakeDownlink : XiaozhiTtsDownlink {
        val events = ArrayList<String>()
        val frames = ArrayList<ByteArray>()
        val frameMeta = ArrayList<Pair<Int, Int>>()

        /** relay 在段界回调「把本段字幕写进 BLE 队列」写下的那些字幕(顺序与 [events] 里的 `screen` 同源)。 */
        val subtitles = ArrayList<String>()

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

        /**
         * 服务侧的**段界上屏**出口([com.shinku.aipassport.openclaw.tts.XiaozhiTtsRelay] 的
         * `writeSubtitle` 回调):真实链路里它由 `VoiceBridgeService.writeSegmentSubtitle` 写帧,
         * 与音频帧**同一条 BLE 串行写队列** —— 所以这里只记一条 `screen`。
         */
        fun writeSubtitle(body: String) {
            events.add("screen")
            subtitles.add(body)
        }

        /** 假通路上已下发的 SEQ 序列(校验「按序、不丢、不重」)。 */
        fun seqs(): List<Int> = frames.map { decodeTtsOpusPayload(it)!!.seq }

        /** 假通路上已下发的 opus 原始内容(校验顺序与内容都不是乱序的)。 */
        fun opusBodies(): List<List<Byte>> = frames.map { decodeTtsOpusPayload(it)!!.opus.toList() }

        /** 各事件的下标(顺序断言用)。 */
        fun firstIndex(event: String): Int = events.indexOf(event)
    }

    /** 可推进的假时钟(只用于「等设备回报超时」那条路,其余逻辑不依赖时间)。 */
    private class FakeClock {
        var now = 0L

        fun advance(ms: Long) {
            now += ms
        }
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
        clock: FakeClock = FakeClock(),
        gate: () -> XiaozhiTtsGate = { openGate() },
    ) = XiaozhiTtsRelay(
        gate = gate,
        downlink = downlink,
        // 段界上屏:relay 在推进到本段时回调它写帧(真实链路 = VoiceBridgeService.writeSegmentSubtitle)。
        writeSubtitle = { body -> downlink.writeSubtitle(body) },
        // 段内更新:真实链路 = VoiceBridgeService.rewriteDisplayedSubtitle(带让路),单测里同样记一条 `screen`。
        rewriteSubtitle = { body -> downlink.writeSubtitle(body) },
        maxBufferFrames = maxBufferFrames,
        nowMs = { clock.now },
    )

    /**
     * 会话层交付本轮正文([XiaozhiTtsRelay.onReplyBody])。
     *
     * 真实链路(见 `XiaozhiSession.handleServerMessage`):`tts` 报文先喂正文装配器([XiaozhiReplyText]),
     * 它一交付就把正文告诉直通侧,然后同一个报文才轮到直通侧的 [XiaozhiTtsRelay.onTtsState] ——
     * 所以单测里这个调用总是排在 [state] 之前。
     */
    private fun XiaozhiTtsRelay.deliver(body: String) = onReplyBody(body)

    /** 会话层把 `tts` 状态报文转给直通侧([XiaozhiTtsRelay.onTtsState])。 */
    private fun XiaozhiTtsRelay.state(state: String, text: String = "") = onTtsState(state, text)

    /**
     * 服务侧要把这条本段字幕写给设备([VoiceBridgeService.sendTextFrame] →
     * [XiaozhiTtsRelay.onReplySubtitleReady]):**写帧时刻由 relay 按段界决定** —— 首段立即,
     * 后续段要等上一段播完/兜底超时推进。真正写下的那一下由假通路的 `writeSubtitle` 记一条 `screen`。
     */
    private fun subtitle(r: XiaozhiTtsRelay, body: String): Boolean =
        r.onReplySubtitleReady(XiaozhiScreenSignal.REPLY_ROLE, body)

    /**
     * 服务侧**自己**写完一条本段字幕(段内更新 / 让路补上)之后的对账回调
     * ([VoiceBridgeService.notifyXiaozhiReplyOnScreen] → [XiaozhiTtsRelay.onReplyTextDisplayed]);
     * `screen` 事件由本方法模拟写入。
     */
    private fun screenWritten(link: FakeDownlink, r: XiaozhiTtsRelay, body: String) {
        link.events.add("screen")
        r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, body)
    }

    /**
     * 服务侧交来一条本段字幕的**完整行为**(= 真实 `VoiceBridgeService.sendTextFrame`):
     * relay 接管(首段/下一段)→ 由它在段界写(假 `writeSubtitle` 会记一条 `screen`);
     * relay 不接管(非本段正文 / 本段已在屏上的段内更新)→ 服务侧自己写 + [screenWritten] 对账。
     *
     * 顺序断言靠它往下发通路的 events 里插 `screen` 标记(真实链路里正文帧与音频帧同一条队列)。
     */
    private fun screen(link: FakeDownlink, r: XiaozhiTtsRelay, body: String) {
        if (subtitle(r, body)) return
        screenWritten(link, r, body)
    }

    /**
     * 小智发来「新的一句」:同一条报文里装配器**先**交付正文、直通侧**再**收到 `sentence_start`
     * (见 `XiaozhiSession.handleServerMessage`)。**段的边界就在这里落定**:收上一段 + 用这条正文开新段。
     *
     * @param body 本段正文(装配器**按段**交付的那串文本:**只含这一句自己**,不累计前几句)
     * @param sentenceText 这条报文里的句级文本(只是服务端侧的形状,直通侧按段不使用它)
     */
    private fun sentence(r: XiaozhiTtsRelay, body: String, sentenceText: String = body) {
        r.deliver(body)
        r.state("sentence_start", sentenceText)
    }

    // ---- ① 多段切分:每段各一对 tts_start / tts_stop ----

    /**
     * 验收点 ①:**一轮两句话 = 两段**,每段一对括号;段的顺序 = 小智的句序;
     * 段尾由「下一句文本到达」决定(这时上一句的音频已经发完),`tts.state=stop` 不收段。
     */
    @Test
    fun each_sentence_is_pushed_as_its_own_segment_with_exactly_one_start_and_one_stop() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        // ---- 第 1 句 ----
        sentence(r, "第一句。")          // 装配器交付 + 句首(首段在这里声明)
        r.onTtsAudio(opus(1), 24, 60)    // 帧先到(正文还没上屏)→ 缓冲
        screen(link, r, "第一句。")      // 字幕进 BLE 写队列 → 第 1 段开播
        r.onTtsAudio(opus(2), 24, 60)
        r.state("sentence_end", "第一句。")   // 文本收口:音频还会继续来
        r.onTtsAudio(opus(3), 24, 60)
        assertEquals(
            "第 1 段:字幕 → tts_start → 逐帧",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertTrue("字幕必须早于本段第一个音频帧", link.firstIndex("screen") < link.firstIndex("frame"))

        // ---- 第 2 句文本到达 = 第 1 段收口(音频已发完)→ tts_stop ----
        sentence(r, "第一句。第二句。", "第二句。")
        assertEquals(
            "上一段收口:推完本段 → tts_stop(段间开始等设备回报)",
            listOf("screen", "start", "frame", "frame", "frame", "stop"),
            link.events,
        )

        // ---- 第 2 段:字幕**只缓存**(文本早到也不提前上屏),要等设备回报第 1 段播完才写 + tts_start ----
        screen(link, r, "第一句。第二句。")
        r.onTtsAudio(opus(4), 24, 60)
        assertEquals(
            "第 1 段还没播完:第 2 段一个字节都不发(文本也不许提前上屏)",
            listOf("screen", "start", "frame", "frame", "frame", "stop"),
            link.events,
        )

        r.onDevicePlaybackFinished()     // 设备:第 1 段播完
        r.onTtsAudio(opus(5), 24, 60)
        assertEquals(
            "第 1 段播完 → 第 2 段 tts_start → 推帧(两段各一对括号)",
            listOf(
                "screen", "start", "frame", "frame", "frame", "stop",
                "screen", "start", "frame", "frame",
            ),
            link.events,
        )
        assertEquals("两段 → 两次 tts_start", 2, link.events.count { it == "start" })
        assertEquals("第 1 段已收尾 → 一次 tts_stop", 1, link.events.count { it == "stop" })

        // ---- 最后一段由「推空 + 静默」收口 ----
        r.state("stop")                  // 整轮文本结算:不收段(尾帧还会来)
        r.onTtsAudio(opus(6), 24, 60)
        assertEquals("stop 之后迟到帧仍归本段(不切尾音)", 6, link.frames.size)
        assertEquals("stop 不产生 tts_stop", 1, link.events.count { it == "stop" })
        r.onIdleTailStop(3)
        assertEquals(2, link.events.count { it == "stop" })

        assertEquals("SEQ 连续、不丢不重", listOf(0, 1, 2, 3, 4, 5), link.seqs())
        assertEquals(
            "帧顺序 = 到达顺序(两段之间没有被重排)",
            (1..6).map { opus(it).toList() },
            link.opusBodies(),
        )
    }

    /**
     * 验收点 ②:**段间等待设备回报**。设备不回报时按本段音频长度兜底超时推进
     * ([XiaozhiSegmentWait]),但绝不能早于设备该播完的时刻 —— 否则两段会在设备侧连成一条。
     */
    @Test
    fun the_bounded_timeout_advances_only_after_the_segment_should_have_finished_playing() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        sentence(r, "第一句。")
        screen(link, r, "第一句。")
        repeat(5) { r.onTtsAudio(opus(it + 1), 24, 60) }   // 5 帧 ≈300ms 音频
        sentence(r, "第一句。第二句。", "第二句。")          // 收第 1 段
        screen(link, r, "第一句。第二句。")
        r.onTtsAudio(opus(6), 24, 60)
        assertEquals(
            "第 1 段收口后:第 2 段只缓存字幕+帧,等设备回报",
            listOf("screen", "start", "frame", "frame", "frame", "frame", "frame", "stop"),
            link.events,
        )

        // 本段只推了 5 帧(≈300ms):估计播完 = 300 + 1500 余量 → 与最短等待 2000ms 取大 = 2000ms。
        clock.advance(1_900)
        r.pumpSegments()
        assertEquals("还没到设备该播完的时刻:不抢跑", 1, link.events.count { it == "start" })
        assertTrue(clock.now < XiaozhiSegmentWait.MIN_WAIT_MS)

        clock.advance(200)
        r.pumpSegments()
        assertEquals(
            "兜底超时到 → 第 2 段开播",
            listOf(
                "screen", "start", "frame", "frame", "frame", "frame", "frame", "stop",
                "screen", "start", "frame",
            ),
            link.events,
        )
        assertEquals(2, link.events.count { it == "start" })
    }

    /** 设备回报**不带段号**:不在「已推完、等回报」状态时的回报(迟到/早到)必须被忽略。 */
    @Test
    fun a_device_report_that_does_not_belong_to_the_awaiting_segment_is_ignored() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        sentence(r, "第一句。")
        screen(link, r, "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onDevicePlaybackFinished()     // 本段还在推:回报不是它的
        assertEquals("还在推的时候回报不关段(否则会把下一段误判成播完)", listOf("screen", "start", "frame"), link.events)
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals(listOf("screen", "start", "frame", "frame"), link.events)
    }

    // ---- ③ 字幕与它自己那一段严格同段 ----

    /**
     * 验收点 ③:第 N 段的字幕**没有**写进 BLE 写队列之前,第 N 段绝不 `tts_start`
     * (即使它的音频已经在手机里了)。
     */
    @Test
    fun a_segment_never_starts_before_its_own_subtitle_is_in_the_write_queue() {
        val link = FakeDownlink()
        val r = relay(link)

        r.deliver("第一句。")
        r.state("sentence_start", "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        assertTrue("字幕还没上屏:一个字节都不发", link.events.isEmpty())

        screen(link, r, "第一句。")
        assertEquals(listOf("screen", "start", "frame", "frame"), link.events)

        // 第 2 段同理:先收口第 1 段、再自己等字幕 + 等回报
        sentence(r, "第一句。第二句。", "第二句。")
        r.onTtsAudio(opus(3), 24, 60)
        r.onDevicePlaybackFinished()
        assertEquals(
            "第 2 段字幕还没上屏 → 不开播(哪怕设备已经回报上一段播完)",
            listOf("screen", "start", "frame", "frame", "stop"),
            link.events,
        )
        screen(link, r, "第一句。第二句。")
        assertEquals(
            "字幕落位之后才开第 2 段(缓冲里的那帧这时才推出来)",
            listOf("screen", "start", "frame", "frame", "stop", "screen", "start", "frame"),
            link.events,
        )
    }

    /**
     * 首段(首句)= **延迟优先**:文档里那句「第一句就开播」在按段模型下不变 ——
     * 首句的字幕一上屏、音频一到,立刻 `tts_start`(不等整轮、不等 `stop`、不等设备回报)。
     */
    @Test
    fun the_first_sentence_starts_immediately_once_text_and_audio_are_both_ready() {
        val link = FakeDownlink()
        val r = relay(link)

        sentence(r, "第一句。")
        assertEquals("本轮还没上屏时:上屏序号是第 1 次", 1, r.replyScreenOrdinal('A', "第一句。") ?: -1)
        screen(link, r, "第一句。")
        assertEquals("首句:字幕上屏那一刻就要开播(此刻还没帧)", listOf("screen"), link.events)
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals("首帧一到立刻开播", listOf("screen", "start", "frame"), link.events)
    }

    // ---- ④ 不丢音频 / 不串段 / 缺帧 ----

    /**
     * 验收点 ④:**段内迟到的帧照收**(一段 `tts_stop` 之后绝不再有帧插进那一段);
     * 未归属的帧**不丢**,但归到**下一段**的括号里(不在上一段的括号里发)。
     */
    @Test
    fun late_frames_are_never_pushed_after_a_segment_stop_and_are_not_lost() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        // 第 1 段:推 2 帧后由「第 2 句文本」收口
        sentence(r, "第一句。")
        screen(link, r, "第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        sentence(r, "第一句。第二句。", "第二句。")
        assertEquals(listOf("screen", "start", "frame", "frame", "stop"), link.events)

        // 第 1 段已 tts_stop:这一段绝不会再收帧 —— 迟到的帧进池(不丢,不串段)
        r.onTtsAudio(opus(3), 24, 60)
        assertEquals("上一段已收口:这一帧绝不在它的括号里发", 5, link.events.size)

        // 第 2 段:字幕 → 设备回报 → 开播;池里那帧作为第 2 段的开头推出(它不属于第 1 段了)
        screen(link, r, "第一句。第二句。")
        r.onDevicePlaybackFinished()
        r.onTtsAudio(opus(4), 24, 60)
        assertEquals(
            "迟到的帧归下一段的括号:start → 池帧 → 新帧",
            listOf("screen", "start", "frame", "frame", "stop", "screen", "start", "frame", "frame"),
            link.events,
        )
        assertEquals(
            "内容按到达顺序(改归属 ≠ 丢帧、≠ 乱序)",
            listOf(opus(1), opus(2), opus(3), opus(4)).map { it.toList() },
            link.opusBodies(),
        )
        assertEquals(listOf(0, 1, 2, 3), link.seqs())
    }

    /**
     * 验收点 ④(**缺帧**):整段一帧都没有(服务端没给音频)→ **跳过**这一段:
     * 不发空的一对 `tts_start`/`tts_stop`,下一段照常播。
     */
    @Test
    fun a_segment_without_any_audio_is_skipped_without_an_empty_bracket() {
        val link = FakeDownlink()
        val r = relay(link)

        sentence(r, "第一句。")
        screen(link, r, "第一句。")           // 第 1 段字幕上屏了,但一帧都没有
        sentence(r, "第一句。第二句。", "第二句。")   // 收口第 1 段(0 帧)
        screen(link, r, "第一句。第二句。")

        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(
            "缺帧的段直接跳过:第 2 段照常开播(没有空括号)",
            listOf("screen", "screen", "start", "frame"),
            link.events,
        )
        assertEquals("一帧都没有的段也不该抢跑", 0, link.events.count { it == "stop" })
    }

    /** 打断/换轮:关掉已开的一段(tts_stop)、清掉全部待播段;**本轮/换轮前在途的残帧绝不进下一轮**。 */
    @Test
    fun turn_start_clears_every_segment_and_never_lets_stale_frames_into_the_next_turn() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        // 上一轮:第 1 段已开播,第 2 段还在等设备回报
        sentence(r, "旧轮第一句。")
        screen(link, r, "旧轮第一句。")
        r.onTtsAudio(opus(0x11), 24, 60)
        sentence(r, "旧轮第一句。旧轮第二句。", "旧轮第二句。")
        screen(link, r, "旧轮第一句。旧轮第二句。")
        r.onTtsAudio(opus(0x12), 24, 60)
        assertEquals(
            "第 2 段已声明,但字幕只缓存、音频要等设备回报第 1 段播完(一个字节都不发)",
            listOf("screen", "start", "frame", "stop"),
            link.events,
        )

        // 打断 / 新一轮(流水线每轮都会调;重复调幂等)
        r.onTurnStart()
        r.onTurnStart()
        assertEquals(
            "换轮:第 2 段从未开段(没有 bracket),所以只有已开的那一段要 tts_stop",
            listOf("screen", "start", "frame", "stop"),
            link.events,
        )

        // 换轮前在途的残帧:本轮还什么都没声明 → 丢弃(绝不进下一轮)
        r.onTtsAudio(opus(0x13), 24, 60)
        assertEquals("残帧丢弃,不产生任何下发", 4, link.events.size)

        // 下一轮:只推这一轮的帧
        sentence(r, "新轮第一句。")
        screen(link, r, "新轮第一句。")
        r.onTtsAudio(opus(0x21), 24, 60)
        assertEquals(
            listOf("screen", "start", "frame", "stop", "screen", "start", "frame"),
            link.events,
        )
        assertEquals(
            "只推各自的帧(残帧没有混进来)",
            listOf(opus(0x11).toList(), opus(0x21).toList()),
            link.opusBodies(),
        )
        assertEquals("SEQ 只在真正下发的帧上递增", listOf(0, 1), link.seqs())
    }

    /**
     * 本轮文本已结算(`tts.stop`)之后仍有**迟到尾帧**:不收段(`stop` 不是段尾),
     * 由「推空 + 静默」收口;**收口之后**再来的帧续一段(自动再 `tts_start`),尾音不丢。
     */
    @Test
    fun trailing_frames_after_the_last_stop_are_kept_and_a_late_tail_continues_in_a_new_segment() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        sentence(r, "只有一句。")
        screen(link, r, "只有一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.state("sentence_end", "只有一句。")
        r.state("stop")                       // 整轮结算,但**不收段**
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals(
            "stop 之后的迟到帧仍归本段(不切尾音,不收段)",
            listOf("screen", "start", "frame", "frame"),
            link.events,
        )

        r.onIdleTailStop(2)                   // 推空 + 静默达上限 → 收本段
        assertEquals(listOf("screen", "start", "frame", "frame", "stop"), link.events)

        // 设备回报这一段播完 → 段的边界闭上;此时再来的帧是**新的一段**(续段),不丢
        r.onDevicePlaybackFinished()
        r.onTtsAudio(opus(3), 24, 60)
        assertEquals(
            "迟到尾帧续一段(不丢音频)",
            listOf("screen", "start", "frame", "frame", "stop", "start", "frame"),
            link.events,
        )
        assertEquals(listOf(0, 1, 2), link.seqs())

        r.onIdleTailStop(1)
        assertEquals(8, link.events.size)
    }

    /** [XiaozhiTailStop] 的纯判定:没推过帧不收段、未到阈值不收段、已收过不重收。 */
    @Test
    fun tail_stop_predicate_requires_frames_and_idle_and_not_already_stopped() {
        assertFalse("从没推过帧(本段没开播) → 不需要 tts_stop", XiaozhiTailStop.shouldStop(0, 60_000L, false))
        assertFalse(
            "推过帧但静默未到上限 → 还在等可能的后继帧",
            XiaozhiTailStop.shouldStop(30, XiaozhiTailStop.TAIL_IDLE_MS - 1, false),
        )
        assertFalse("已经收过尾 → 不重发(幂等)", XiaozhiTailStop.shouldStop(30, XiaozhiTailStop.TAIL_IDLE_MS, true))
        assertTrue(XiaozhiTailStop.shouldStop(1, XiaozhiTailStop.TAIL_IDLE_MS, false))
        assertTrue(XiaozhiTailStop.shouldStop(300, XiaozhiTailStop.TAIL_IDLE_MS * 3, false))
    }

    /** 本段从未开过段(没推过任何帧)时,收尾请求不产生任何下发。 */
    @Test
    fun tail_stop_is_a_noop_when_no_segment_started() {
        val link = FakeDownlink()
        val r = relay(link)
        r.deliver("正文")
        r.state("sentence_start", "正文")
        r.onIdleTailStop(0)
        assertTrue(link.events.isEmpty())
    }

    // ---- ⑤ 直通门(整段零下发)— 既有不变量 ----

    /** 直通门任一项不满足 → **整段零下发**(不光是帧,连同 `tts_start`/`tts_stop` 都没有)。 */
    @Test
    fun gate_blocks_when_any_of_three_conditions_fails() {
        listOf(
            openGate(gatewayType = "openclaw"),
            openGate(ttsEnabled = false),
            openGate(deviceTtsCapable = false),
        ).forEach { gate ->
            val link = FakeDownlink()
            val r = relay(link) { gate }
            r.deliver("正文")
            r.state("sentence_start", "正文")
            screen(link, r, "正文")
            r.onTtsAudio(opus(1), 24, 60)
            r.state("stop")
            r.onIdleTailStop(0)
            assertEquals("门关闭时整段零下发(${gate.blockedReason})", listOf("screen"), link.events)
        }
    }

    /** 门的判定与日志同源:放行/拦截的各组合都能给出可读原因。 */
    @Test
    fun gate_describes_each_blocked_condition() {
        assertTrue(openGate().allowed)
        assertEquals(null, openGate().blockedReason)

        assertEquals("当前网关不是小智 AI(type=hermes)", openGate(gatewayType = "hermes").blockedReason)
        assertEquals("设备朗读开关(tts_enabled)关闭", openGate(ttsEnabled = false).blockedReason)
        assertEquals("设备未在 hello 报 caps:[\"tts_opus\"]", openGate(deviceTtsCapable = false).blockedReason)
        assertTrue("非小智网关即使开关打开也不放行", !openGate(gatewayType = "echo").allowed)
    }

    /** 门从拦截变放行(设备重连后补报 caps / 用户打开开关):下一段立刻能走(实时读设置)。 */
    @Test
    fun gate_opens_mid_flight_when_capability_arrives() {
        var capable = false
        val link = FakeDownlink()
        val r = relay(link) { openGate(deviceTtsCapable = capable) }

        r.onTtsAudio(opus(1), 24, 60)
        assertTrue("能力未到时报的帧一律不下发", link.events.isEmpty())

        capable = true
        r.deliver("正文")
        r.state("sentence_start", "正文")
        screen(link, r, "正文")
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals("放行后:字幕已上屏 + 帧到了 → 开段", listOf("screen", "start", "frame"), link.events)
    }

    /**
     * 「关闭播放小智语音」= 拦下小智音频,而**不是**回退成本地合成。
     *
     * 小智模式的音频来源只有一条:它自己随会话下发的 opus([XiaozhiGateway.providesDeviceTtsAudio] = true
     * —— 流水线 `speakReply` 对这种网关直接返回,既不做本地合成也不回退手机朗读)。
     */
    @Test
    fun xiaozhi_disabled_does_not_fall_back_to_local_synthesis() {
        assertTrue("小智网关自带下行音频:关闭开关不能变成回退本地合成", XiaozhiGateway(null).providesDeviceTtsAudio)

        val gate = openGate(ttsEnabled = false)
        assertFalse("关闭后不能放行任何小智音频", gate.allowed)
        assertEquals("设备朗读开关(tts_enabled)关闭", gate.blockedReason)

        val link = FakeDownlink()
        val r = relay(link) { gate }
        r.deliver("正文")
        r.state("sentence_start", "正文")
        screen(link, r, "正文")
        r.onTtsAudio(opus(1), 24, 60)
        assertTrue("关闭时设备必须是安静的(不下发、也不本地合成)", link.events == listOf("screen"))
    }

    // ---- ⑥ 抢跑路径逐个封死 ----

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
        assertFalse("本轮正文为空(只有表情/模板) → 任何 'A' 都不算", XiaozhiScreenSignal.accepts('A', "小智这一轮没有返回可上屏的正文", ""))
        assertFalse("本轮正文为空时连空文本也不算", XiaozhiScreenSignal.accepts('A', "", "  "))
    }

    /** 非回复文本上屏不得开播(识别原文 / 版本提示 /「无语音」/ 超时与失败原因 / 空回复兜底)。 */
    @Test
    fun non_reply_texts_on_screen_never_open_the_gate() {
        val link = FakeDownlink()
        val r = relay(link)

        r.deliver("明天上海是小雨喔，白天23度。")
        r.state("sentence_start", "明天上海是小雨喔，白天23度。")
        r.onTtsAudio(opus(1), 24, 60)

        r.onReplyTextDisplayed('U', "明天上海天气")
        r.onReplyTextDisplayed('R', "明天上海是小雨喔，白天23度。")
        r.onReplyTextDisplayed('A', "版本提示:固件与 App 版本不一致")
        r.onReplyTextDisplayed('A', "无语音")
        r.onReplyTextDisplayed('A', "小智没有返回回复(等待 30 秒超时)")
        r.onReplyTextDisplayed('A', "小智这一轮没有返回可上屏的正文(只有表情/空文本或工具模板)")
        r.onReplyTextDisplayed('A', "(网关空回复)")
        assertTrue("这些文本上屏都不许把音频放出来(声音不能跑到文字前面)", link.events.isEmpty())

        assertTrue("本轮正文上屏必须被接受", r.onReplyTextDisplayed('A', "明天上海是小雨喔，白天23度。"))
        assertEquals(listOf("start", "frame"), link.events)
    }

    /** 本轮正文为空(只有表情/模板):任何 `'A'` 都不开播 —— 没有文字就没有「文字先于声音」可言。 */
    @Test
    fun empty_turn_body_keeps_the_gate_shut() {
        val link = FakeDownlink()
        val r = relay(link)
        r.deliver("")
        r.state("sentence_start", "😊")
        r.onTtsAudio(opus(1), 24, 60)
        r.state("stop")
        r.onIdleTailStop(0)

        assertFalse(r.onReplyTextDisplayed('A', ""))
        assertFalse(r.onReplyTextDisplayed('A', "小智这一轮没有返回可上屏的正文"))
        assertTrue("本轮没有可上屏正文 → 一个音频字节也不下发", link.events.isEmpty())
    }

    /**
     * 抢跑路径之二:**上一轮的迟到信号**。
     *
     * `turn_start` 清掉本轮正文记录后,旧轮那条 `TEXT('A')`(已在写队列里、晚一步才通知)即使文本完全正确
     * 也不得打开新一轮的闸门。
     */
    @Test
    fun late_screen_signal_from_the_previous_turn_cannot_open_the_new_turn() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        r.deliver("旧轮正文")
        r.state("sentence_start", "旧轮正文")
        screen(link, r, "旧轮正文")
        r.onTtsAudio(opus(0x11), 24, 60)
        assertEquals(listOf("screen", "start", "frame"), link.events)

        r.onTurnStart()
        assertEquals(listOf("screen", "start", "frame", "stop"), link.events)

        r.deliver("新轮正文")
        r.state("sentence_start", "新轮正文")
        r.onTtsAudio(opus(0x21), 24, 60)

        assertFalse("旧轮的迟到信号不得打开新一轮的闸门", r.onReplyTextDisplayed('A', "旧轮正文"))
        assertEquals("仍然只在缓冲里", listOf("screen", "start", "frame", "stop"), link.events)

        assertTrue(r.onReplyTextDisplayed('A', "新轮正文"))
        assertEquals(
            listOf("screen", "start", "frame", "stop", "start", "frame"),
            link.events,
        )
        assertEquals(listOf(opus(0x11).toList(), opus(0x21).toList()), link.opusBodies())
    }

    /** 首句的上屏信号晚于第 2 句正文到达时仍要被接受(会话层与流水线是两个线程在赛跑)。 */
    @Test
    fun first_sentence_signal_is_accepted_even_after_the_next_body_already_arrived() {
        val link = FakeDownlink()
        val r = relay(link)
        r.deliver("第一句。")
        r.deliver("第一句。第二句。")     // 第 2 句正文先到(第 1 句的 TEXT 还在写队列里)
        r.state("sentence_start", "第二句。")
        r.onTtsAudio(opus(1), 24, 60)

        assertTrue(
            "首句(第 1 段)的上屏信号即使晚到也要被接受",
            r.onReplyTextDisplayed(XiaozhiScreenSignal.REPLY_ROLE, "第一句。"),
        )
        assertEquals(listOf("start", "frame"), link.events)
        assertEquals("信号已计入:同一条再上屏就是第 2 次", 2, r.replyScreenOrdinal('A', "第一句。"))
        assertEquals("不相干的 'A' 文本仍不算信号", 0, r.replyScreenOrdinal('A', "无语音"))
    }

    /** 同一句的正文在增长(`sentence_start` 半句 → `sentence_end` 整句)时**不切段**。 */
    @Test
    fun a_growing_sentence_body_updates_the_same_segment_instead_of_splitting_it() {
        val link = FakeDownlink()
        val r = relay(link)

        r.deliver("气温大概十")
        r.state("sentence_start", "气温大概十")
        screen(link, r, "气温大概十")
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(listOf("screen", "start", "frame"), link.events)

        // sentence_end 给的是更完整的同一句:装配器会再交付一次 → 更新本段字幕,不切段
        r.deliver("气温大概十八到二十三度。")
        r.state("sentence_end", "气温大概十八到二十三度。")
        assertEquals("同一句的正文变完整:既不收段也不重新开段", listOf("screen", "start", "frame"), link.events)

        screen(link, r, "气温大概十八到二十三度。")   // 服务侧把更完整的正文再上屏一次
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals(
            "仍是同一段(只有一个 tts_start,句界不切音频)",
            listOf("screen", "start", "frame", "screen", "frame"),
            link.events,
        )
        assertEquals(1, link.events.count { it == "start" })
    }

    // ---- ⑦ 只推音频、没有状态报文的服务端 ----

    /**
     * 服务端只推二进制音频、一条 `tts` JSON 都没有时:帧**不丢**(进池),但仍只在「字幕已上屏」之后下发;
     * 没有句界信息 → 整轮按**一次交付**切成一段(交付即新段)。
     */
    @Test
    fun audio_only_stream_without_any_tts_state_waits_for_the_screen_signal_and_plays_as_one_segment() {
        val link = FakeDownlink()
        val r = relay(link)

        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsAudio(opus(3), 24, 60)
        assertTrue("信号之前一个字节都不下发", link.events.isEmpty())

        r.deliver("测试正文")
        screen(link, r, "测试正文")
        assertEquals(
            "上屏信号一到就开段并推帧",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals(listOf(0, 1, 2), link.seqs())
    }

    // ---- ⑧ 缓冲上限 / 非法帧 / SEQ ----

    /** 还没开播的段缓冲到上限时丢**最旧**的一帧(内存有界),绝不为了保音频而提前开播。 */
    @Test
    fun buffer_overflow_drops_the_oldest_frame_but_never_starts_before_the_screen_signal() {
        val link = FakeDownlink()
        val r = relay(link, maxBufferFrames = 3)

        r.deliver("长回复")
        r.state("sentence_start", "长回复")
        repeat(5) { r.onTtsAudio(opus(it + 1), 24, 60) }
        assertTrue("超限也只丢最旧的帧,一个字节都不抢跑", link.events.isEmpty())

        screen(link, r, "长回复")
        assertEquals(
            "字幕上屏后才开播,推的是仍在缓冲的三帧",
            listOf("screen", "start", "frame", "frame", "frame"),
            link.events,
        )
        assertEquals(
            "丢的是最旧的帧,剩下的按到达顺序(SEQ 从 0 起递增)",
            listOf(opus(3).toList(), opus(4).toList(), opus(5).toList()),
            link.opusBodies(),
        )
        assertEquals(listOf(0, 1, 2), link.seqs())
    }

    @Test
    fun invalid_rate_frame_length_and_oversize_packet_are_dropped() {
        val link = FakeDownlink()
        val r = relay(link)
        r.deliver("正文")
        r.state("sentence_start", "正文")

        r.onTtsAudio(ByteArray(4), 22, 60)            // 22050Hz:固件只收 16/24,不重采样
        r.onTtsAudio(ByteArray(4), 0, 60)             // hello 还没上报速率
        r.onTtsAudio(ByteArray(4), 24, 0)             // 帧长非法
        r.onTtsAudio(ByteArray(0), 24, 60)            // 空包
        r.onTtsAudio(ByteArray(513), 24, 60)          // 超过 512B 上限
        screen(link, r, "正文")
        assertEquals("非法帧一帧都不能下发(也没什么可开段)", listOf("screen"), link.events)

        // 后续合法帧不受影响(丢弃只针对那一帧)
        r.onTtsAudio(ByteArray(4), 16, 60)
        assertEquals(1, link.frames.size)
        assertEquals(16, decodeTtsOpusPayload(link.frames[0])!!.rateKhz)
        assertEquals(listOf(16 to 60), link.frameMeta)
        assertEquals(listOf("screen", "start", "frame"), link.events)
    }

    @Test
    fun audio_frames_carry_xiaozhi_header_and_seq_wraps_at_256() {
        val link = FakeDownlink()
        val r = relay(link)
        val opus = ByteArray(10) { (it * 3 and 0xFF).toByte() }

        r.deliver("长回复")
        r.state("sentence_start", "长回复")
        repeat(257) { r.onTtsAudio(opus, 24, 60) }   // < 缓冲上限 300:全部只在缓冲里
        assertTrue("字幕上屏之前:全部只在缓冲里", link.events.isEmpty())

        screen(link, r, "长回复")
        assertEquals(257, link.frames.size)
        val first = decodeTtsOpusPayload(link.frames.first())!!
        assertEquals(0, first.seq)
        assertEquals(24, first.rateKhz)
        assertEquals(60, first.frameMs)
        assertArrayEquals("opus 包必须原样透传", opus, first.opus)
        assertEquals(255, decodeTtsOpusPayload(link.frames[255])!!.seq)
        assertEquals("SEQ 到 256 回绕到 0", 0, decodeTtsOpusPayload(link.frames[256])!!.seq)
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals("本段还没收口(尾帧还会来):末帧不是 tts_stop", "frame", link.events.last())
    }

    // ---- ⑨ 句起点记账(证据/日志)+ [XiaozhiSegmentWait] 的纯判定 ----

    /**
     * 句起点 = 「交付那条正文那一刻本轮已收到的帧数」× 帧长(与真机日志里的 `该句起点≈Yms` 同一来源)。
     * 按段播放之后它只作**证据**(段的开播闸门才是字幕时机的保证),但这条记账仍要准:
     * 真机要靠它核对「第 2 句的音频起点 ≈ 第 1 句的音频总量」。
     */
    @Test
    fun sentence_start_offsets_are_cut_by_the_frames_that_arrived_before_each_body() {
        val link = FakeDownlink()
        val r = relay(link)

        r.state("start")
        r.state("sentence_start", "")               // 空句:不交付也不记边界
        r.deliver("第一句。")                        // 第 1 句交付:此刻 0 帧
        r.onTtsAudio(opus(1), 24, 60)
        r.onTtsAudio(opus(2), 24, 60)
        r.onTtsAudio(opus(3), 24, 60)
        r.deliver("第一句。")                        // 重复告知:边界不往后挪
        r.state("sentence_end", "第一句。")
        r.onTtsAudio(opus(4), 24, 60)
        r.deliver("第一句。第二句。")                 // 第 2 句交付:4 帧
        r.onTtsAudio(opus(5), 24, 60)
        r.deliver("第一句。第二句。第三句。")          // 第 3 句交付:5 帧

        assertEquals("首句起点 ≈ 0", 0L, r.sentenceStartMs("第一句。"))
        assertEquals("第 2 句起点 = 前 4 帧", 4 * 60L, r.sentenceStartMs("第一句。第二句。"))
        assertEquals(5 * 60L, r.sentenceStartMs("第一句。第二句。第三句。"))
        assertNull("没交付过的文本没有边界记录", r.sentenceStartMs("第二句。"))
    }

    /** 句起点只在真的收到过可下发音频时可用;`turn_start` 一并作废。 */
    @Test
    fun sentence_offsets_are_unavailable_without_audio_and_reset_each_turn() {
        val link = FakeDownlink()
        val r = relay(link)
        r.deliver("第一句。")
        r.deliver("第一句。第二句。")
        assertNull("一帧可下发的音频都没收到:不提供估计", r.sentenceStartMs("第一句。第二句。"))

        r.onTurnStart()
        r.deliver("第一句。")
        r.onTtsAudio(opus(1), 24, 60)
        r.deliver("第一句。第二句。")
        assertEquals(60L, r.sentenceStartMs("第一句。第二句。"))
        r.onTurnStart()
        assertNull("新一轮:上一轮的边界已作废", r.sentenceStartMs("第一句。第二句。"))
    }

    /** [XiaozhiSegmentWait] 的纯判定:按本段音频长度 + 余量估「设备该播完了」,并有最短等待下限。 */
    @Test
    fun segment_wait_predicate_scales_with_the_segment_audio_length() {
        assertFalse("刚开段:不能抢跑", XiaozhiSegmentWait.reportWaitExpired(0, 100))
        // 5 帧(300ms)+ 1500ms 余量 = 1800ms,但最短等待是 2000ms → 取 2000
        assertFalse("5 帧的短段:到 2000ms 之前都不推进", XiaozhiSegmentWait.reportWaitExpired(1_900, 5))
        assertTrue("到 2000ms → 推进", XiaozhiSegmentWait.reportWaitExpired(2_000, 5))
        // 100 帧(6s)+ 1500ms 余量 = 7500ms:长段按自己的长度估,不是固定值
        assertFalse("长段不该提前推进", XiaozhiSegmentWait.reportWaitExpired(6_000, 100))
        assertTrue(
            "长段按自己的长度 + 余量到点才推进",
            XiaozhiSegmentWait.reportWaitExpired(100 * 60L + XiaozhiSegmentWait.DEVICE_MARGIN_MS, 100),
        )
        assertFalse(
            "帧数很少也要等最短时间",
            XiaozhiSegmentWait.reportWaitExpired(XiaozhiSegmentWait.MIN_WAIT_MS - 1, 1),
        )
    }

    // ---- ⑩ 字幕随段推进(2026-10 真机修正:上一段的声音还没播完,下一段的文字不许先上屏) ----

    /**
     * 验收点 ①(本轮真机目标):**三段流** —— B 的文本早到也不得提前上屏;A 推进的那一刻
     * 才**先写 B 字幕、再 `tts_start` B**;C 同理。
     *
     * 真机反例就是本测试要封死的那个:第 2 段的 `TEXT('A')` 落在第 1 段的音频中间
     * (① 字幕比声音早 ② 文字帧插队打断音频)。
     */
    @Test
    fun three_segment_stream_writes_each_subtitle_only_at_its_own_segment_boundary() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        // ---- 第 1 段(A):字幕**立即**上屏 + 立即开播(延迟优先,不变) ----
        sentence(r, "A。")
        assertTrue("首段字幕由 relay 接管并立即上屏", subtitle(r, "A。"))
        repeat(4) { r.onTtsAudio(opus(it + 1), 24, 60) }
        assertEquals(listOf("screen", "start", "frame", "frame", "frame", "frame"), link.events)

        // ---- B 的文本与帧都早到(第 1 段还在播) ----
        sentence(r, "B。")
        assertTrue("第 2 段字幕:只缓存", subtitle(r, "B。"))
        r.onTtsAudio(opus(5), 24, 60)
        assertEquals("B 的文本早到时**没有任何** TEXT('A')(A 还在播)", 1, link.events.count { it == "screen" })
        assertEquals("B 的帧也在缓冲", 1, link.events.count { it == "start" })

        // ---- A 播完回报 → 推进到 B:先写 B 字幕、再 tts_start B ----
        r.onDevicePlaybackFinished()
        assertEquals(
            "A 推进(播完回报) → 先上屏 B 字幕 → B 的 tts_start → 推 B 的帧",
            listOf(
                "screen", "start", "frame", "frame", "frame", "frame", "stop",
                "screen", "start", "frame",
            ),
            link.events,
        )
        assertTrue(
            "字幕帧必须排在它自己那一段的 tts_start 之前",
            link.events.lastIndexOf("screen") < link.events.lastIndexOf("start"),
        )
        assertEquals("写的是 B 自己的字幕", listOf("A。", "B。"), link.subtitles)

        // ---- C 同理:文本+帧早到也只缓存,等 B 播完才轮到 ----
        sentence(r, "C。")
        subtitle(r, "C。")
        r.onTtsAudio(opus(6), 24, 60)
        assertEquals("C 的文本早到也不许提前上屏(B 还在播)", 2, link.events.count { it == "screen" })
        r.onDevicePlaybackFinished()
        assertEquals(
            "B 推进 → 先上屏 C 字幕 → C 的 tts_start → 推 C 的帧",
            listOf(
                "screen", "start", "frame", "frame", "frame", "frame", "stop",
                "screen", "start", "frame", "stop",
                "screen", "start", "frame",
            ),
            link.events,
        )
        assertEquals("三段：三条字幕、各自一对括号", listOf("A。", "B。", "C。"), link.subtitles)
        assertEquals(3, link.events.count { it == "screen" })
        assertEquals(3, link.events.count { it == "start" })
    }

    /**
     * 验收点 ②:**段内字幕更新不提前上屏** —— 第 2 段的正文被服务端分两次推(半句 → 整句),
     * 两次都在它轮到之前到达:屏幕上一字不发,段界到了写的是**最新**那一版。
     *
     * 顺序按**真实链路**:`tts` 报文先让装配器交付正文(服务侧随即请求上屏),随后直通侧才收到
     * 句界报文 —— 所以「这条是下一段的首条字幕还是已上屏那一段的更新」要到句界才能定下来。
     */
    @Test
    fun a_held_segment_subtitle_is_updated_in_place_and_still_written_only_at_its_boundary() {
        val link = FakeDownlink()
        val r = relay(link)

        // 第 1 段:A 已上屏并在播
        sentence(r, "A。")
        subtitle(r, "A。")
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(listOf("screen", "start", "frame"), link.events)

        // 第 2 段:半句 → 整句,两次都早于它的段界(且都走在句界报文之前)
        r.deliver("B 半句")
        subtitle(r, "B 半句")
        r.state("sentence_start", "B 半句")
        assertEquals("第 2 段还没轮到:一个字都不许上屏", 1, link.events.count { it == "screen" })

        r.deliver("B 整句。")
        subtitle(r, "B 整句。")
        r.state("sentence_end", "B 整句。")
        assertEquals("同一句变完整也不提前上屏(仍只缓存)", 1, link.events.count { it == "screen" })

        r.onTtsAudio(opus(2), 24, 60)
        r.onDevicePlaybackFinished()
        assertEquals("段界到了写的是**最新**那一版字幕", listOf("A。", "B 整句。"), link.subtitles)
        assertEquals(
            listOf("screen", "start", "frame", "stop", "screen", "start", "frame"),
            link.events,
        )
    }

    /**
     * 验收点 ②(另一半):**已在屏上那一段**的同一句更新仍照旧立刻上屏(不切段、不重开)。
     */
    @Test
    fun an_update_of_a_segment_already_on_screen_is_rewritten_without_splitting_the_segment() {
        val link = FakeDownlink()
        val r = relay(link)

        sentence(r, "气温大概十")
        subtitle(r, "气温大概十")
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(listOf("screen", "start", "frame"), link.events)

        // 真实链路顺序:更完整的正文先到(服务侧请求上屏)→ `sentence_end` 把它归到**本段**
        r.deliver("气温大概十八到二十三度。")
        assertTrue("已上屏那一段的更新也由 relay 接管(等句界让它落位)", subtitle(r, "气温大概十八到二十三度。"))
        assertEquals("句界还没到:不写", listOf("screen", "start", "frame"), link.events)
        r.state("sentence_end", "气温大概十八到二十三度。")
        assertEquals(
            "段内更新:立刻重写本段字幕(不切段、不重开)",
            listOf("screen", "start", "frame", "screen"),
            link.events,
        )
        r.onTtsAudio(opus(2), 24, 60)
        assertEquals("仍是同一段(只有一个 tts_start)", 1, link.events.count { it == "start" })
        assertEquals(listOf("气温大概十", "气温大概十八到二十三度。"), link.subtitles)
    }

    /**
     * 验收点 ⑤(首段时序的另一半):**首段的正文可能早于它的句界报文**到达(装配器交付 → 网关 →
     * 流水线在另一条线程/协程上)。它同样要**立即上屏**——只是上屏发生在句界报文让它落位那一刻,
     * 而不是拖到段尾。
     */
    @Test
    fun the_first_segment_is_displayed_immediately_even_when_its_body_arrives_before_its_state_report() {
        val link = FakeDownlink()
        val r = relay(link)
        // 让会话层处于「服务端会发状态报文」的常态(非空句界才声明段)
        r.state("sentence_start", "")
        val base = link.events.size

        r.deliver("A。")
        assertTrue("服务侧请求上屏(正文早于句界):relay 接管并缓存", subtitle(r, "A。"))
        assertEquals("句界还没到:一个字都不发", base, link.events.size)

        r.state("sentence_start", "A。")   // 句界到 → 声明第 1 段(字幕已就绪)
        assertEquals(
            "首段仍立即上屏(还没有帧,所以先不开播)",
            listOf("screen"),
            link.events.drop(base),
        )
        r.onTtsAudio(opus(1), 24, 60)
        assertEquals(
            "帧一到就立即开播(首帧排在字幕之后)",
            listOf("screen", "start", "frame"),
            link.events.drop(base),
        )
    }

    /**
     * 验收点 ③:**兜底超时**那条推进路径同样成立 —— 设备不回报时,字幕也是在
     * 「等满本段该播完的时刻」才写,绝不提前。
     */
    @Test
    fun the_timeout_path_also_writes_the_next_subtitle_only_after_the_wait_expires() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        sentence(r, "A。")
        subtitle(r, "A。")
        repeat(3) { r.onTtsAudio(opus(it + 1), 24, 60) }
        sentence(r, "B。")
        subtitle(r, "B。")
        r.onTtsAudio(opus(4), 24, 60)
        assertEquals(
            "B 的文本早到:只缓存(A 还在播)",
            listOf("screen", "start", "frame", "frame", "frame", "stop"),
            link.events,
        )

        clock.advance(1_900)
        r.pumpSegments()
        assertEquals("还没到设备该播完的时刻:不写 B 字幕、不开 B", 1, link.events.count { it == "screen" })

        clock.advance(200)
        r.pumpSegments()
        assertEquals(
            "兜底超时推进 → 先上屏 B 字幕 → B 的 tts_start → 推 B 的帧",
            listOf(
                "screen", "start", "frame", "frame", "frame", "stop",
                "screen", "start", "frame",
            ),
            link.events,
        )
        assertEquals("B 的字幕在超时推进那一刻才写", listOf("A。", "B。"), link.subtitles)
    }

    /**
     * 验收点 ④:**某段缺失时只补该段** —— 收尾才到齐的那一段字幕,同样先上屏它、再开它;
     * 已上屏的段（A）**不被重写**、不被重开。
     */
    @Test
    fun a_late_supplement_is_written_before_its_own_tts_start_and_earlier_subtitles_are_not_rewritten() {
        val clock = FakeClock()
        val link = FakeDownlink()
        val r = relay(link, clock = clock)

        // A 上屏并在播;A 播完后第 2 段已声明(帧也早到),但它的字幕一直没到 → 不开播
        sentence(r, "A。")
        subtitle(r, "A。")
        r.onTtsAudio(opus(1), 24, 60)
        sentence(r, "B。")
        r.onTtsAudio(opus(2), 24, 60)
        r.onDevicePlaybackFinished()
        assertEquals(
            "第 2 段字幕还没到:即使轮到了也不开播",
            listOf("screen", "start", "frame", "stop"),
            link.events,
        )

        // 收尾结算时补上缺失的那一段(装配器的「只补该段」)
        r.deliver("B。")
        screen(link, r, "B。")
        r.state("stop")
        assertEquals(
            "只补 B:先上屏 B 字幕 → B 的 tts_start → 推 B 的帧",
            listOf("screen", "start", "frame", "stop", "screen", "start", "frame"),
            link.events,
        )
        assertEquals(listOf("A。", "B。"), link.subtitles)
        assertEquals("A 的字幕没有被重写", 1, link.subtitles.count { it == "A。" })
        assertEquals("每段一对括号", 2, link.events.count { it == "start" })
    }
}
