package com.shinku.aipassport.openclaw.tts

import com.shinku.aipassport.openclaw.gateway.XiaozhiGateway
import com.shinku.aipassport.openclaw.protocol.decodeTtsOpusPayload
import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *  4. **设备朗读关掉就不下发音频**（且中途关掉时仍把已开始的一段收尾，不让设备停在播放态）；
 *  5. 非 16/24 kHz、帧长非正、空包、超过 512B 的包一律丢弃，不破坏后续合法帧；
 *  6. **服务端只推二进制音频、一条 `tts` JSON 都没有时，首帧兜底开段**
 *     （真机 bug 的回归测试：不会发状态的实现下，旧代码把整段音频都挡在「未开窗口」外，
 *     表现为 App 收到几十帧、设备侧 `TTS=0`）；
 *  7. **直通门**（网关类型 / `tts_enabled` / 设备 `caps:["tts_opus"]`）三项组合的放行与拦截;
 *  8. **小智模式下关掉开关 = 安静,不回退成本地合成**:小智自带音频
 *     ([XiaozhiGateway.providesDeviceTtsAudio] = true,流水线因此不做本地合成/手机朗读),
 *     关闭只是把这条唯一的音频通路拦下(拦截原因是开关而不是设备能力),没有任何替代通道。
 */
class XiaozhiTtsRelayTest {

    /** 记录下发调用的假通路（顺序也记下来 —— 「什么时候发 tts_start/tts_stop」是本类的核心契约）。 */
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

    /** 默认直通门 = 三项全真（小智 AI + 开关打开 + 设备已报能力）。 */
    private fun openGate(
        gatewayType: String = XiaozhiIdentity.GATEWAY_XIAOZHI,
        ttsEnabled: Boolean = true,
        deviceTtsCapable: Boolean = true,
    ) = XiaozhiTtsGate(gatewayType, ttsEnabled, deviceTtsCapable)

    private fun relay(
        downlink: FakeDownlink,
        gate: () -> XiaozhiTtsGate = { openGate() },
    ) = XiaozhiTtsRelay(gate = gate, downlink = downlink)

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
        // 直通门里的设备朗读开关（`tts_enabled`）实时切换
        val r = relay(link) { openGate(ttsEnabled = on) }

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

        // 后续合法帧不受影响（丢弃只针对那一帧）
        r.onTtsAudio(ByteArray(4), 16, 60)
        assertEquals(1, link.frames.size)
        assertEquals(16, decodeTtsOpusPayload(link.frames[0])!!.rateKhz)
        assertEquals(listOf(16 to 60), link.frameMeta)
    }

    /** 真机 bug 的回归测试:服务端只推二进制音频、一条 `tts` JSON 都没有时,首帧必须兜底开段。 */
    @Test
    fun audio_without_any_tts_state_report_opens_segment_and_pushes() {
        val link = FakeDownlink()
        val r = relay(link)

        // 没有 start/sentence_start/sentence_end/stop,只有音频帧
        r.onTtsAudio(ByteArray(10), 24, 60)
        r.onTtsAudio(ByteArray(10), 24, 60)
        r.onTtsAudio(ByteArray(10), 24, 60)

        assertEquals("首帧就要补上 tts_start(bracket 不能缺)", listOf("start", "frame", "frame", "frame"), link.events)
        assertEquals("一段只发一次 tts_start", 1, link.events.count { it == "start" })
        assertEquals(24, decodeTtsOpusPayload(link.frames.first())!!.rateKhz)
        assertEquals(60, decodeTtsOpusPayload(link.frames.first())!!.frameMs)

        // 跨轮同样成立:下一轮即使上一轮的 stop 没来,音频也要继续走到设备
        r.onTurnStart()
        r.onTtsAudio(ByteArray(10), 24, 60)
        assertEquals(4, link.frames.size)
    }

    /**
     * 服务端**会**发状态报文时,窗口仍然是有意义的:窗口外的音频是上一段/上一轮的残留,
     * 绝不能给它补 `tts_start` 推给设备(那会给设备一段错位的音频)。
     */
    @Test
    fun late_audio_is_dropped_once_the_server_reports_states() {
        val link = FakeDownlink()
        val r = relay(link)
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTtsState("stop", "")
        assertEquals(listOf("start", "frame", "stop"), link.events)

        // stop 之后还在飞的帧:丢弃(不重开一段)
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertEquals(listOf("start", "frame", "stop"), link.events)

        // 新一轮打断后的旧帧:同样丢弃(旧实现的不变量,保持不动)
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTurnStart()
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertEquals(listOf("start", "frame", "stop", "start", "frame"), link.events)
    }

    /** 直通门三项:非小智网关 / 开关关闭 / 设备未报能力 —— 一个音频帧都不下发。 */
    @Test
    fun gate_blocks_when_any_of_three_conditions_fails() {
        // ① 网关类型不是小智 AI(其它四种网关下小智只当识别引擎,它的 TTS 音频不能直通)
        val notXiaozhi = FakeDownlink()
        val r1 = relay(notXiaozhi) { openGate(gatewayType = "openclaw") }
        r1.onTtsState("start", "")
        r1.onTtsAudio(ByteArray(4), 24, 60)
        r1.onTtsState("stop", "")
        assertTrue("非小智网关下不能下发任何帧", notXiaozhi.events.isEmpty())

        // ② 设置项关闭(默认值语义由 GatewaySettings 保证为 true,这里只验「关掉就不发」)
        val disabled = FakeDownlink()
        val r2 = relay(disabled) { openGate(ttsEnabled = false) }
        r2.onTtsState("start", "")
        r2.onTtsAudio(ByteArray(4), 24, 60)
        assertTrue("开关关闭时不能下发任何帧", disabled.events.isEmpty())

        // ③ 设备没在 hello 里报 caps:["tts_opus"]
        val noCaps = FakeDownlink()
        val r3 = relay(noCaps) { openGate(deviceTtsCapable = false) }
        r3.onTtsState("start", "")
        r3.onTtsAudio(ByteArray(4), 24, 60)
        assertTrue("设备未报能力时不能下发任何帧", noCaps.events.isEmpty())
    }

    /**
     * 「关闭播放小智语音」= 拦下小智音频,而**不是**回退成本地合成。
     *
     * 小智模式的音频来源只有一条:它自己随会话下发的 opus
     * ([XiaozhiGateway.providesDeviceTtsAudio] = true —— 流水线 `speakReply` 对这种网关直接返回,
     * 既不做本地合成也不回退手机朗读)。
     * 因此关闭开关的结果就是「设备安静」:直通门拦下每一帧,且拦下的原因是开关本身。
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
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(ByteArray(4), 24, 60)
        r.onTtsState("stop", "")
        assertTrue("关闭时设备必须是安静的(不下发、也不本地合成)", link.events.isEmpty())
    }

    /** 门从拦截变为放行(设备重连后补报 caps / 用户打开开关):后面的帧立刻能走。 */
    @Test
    fun gate_opens_mid_flight_when_capability_arrives() {
        var capable = false
        val link = FakeDownlink()
        val r = relay(link) { openGate(deviceTtsCapable = capable) }

        r.onTtsAudio(ByteArray(4), 24, 60)
        assertTrue("能力未到时报的帧一律不下发", link.events.isEmpty())

        capable = true
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertEquals(listOf("start", "frame"), link.events)
    }

    /**
     * 真机 bug 的回归:开关被（错误地）存成 false 时直通门全拦；用户在小智 AI 下手动打开后必须立刻放行。
     *
     * 真机日志形态：`直通门: type=xiaozhi enabled=false caps=true → 拦截(设备朗读开关(tts_enabled)关闭)`
     * → 设备侧 `TTS=0`。直通门实时读设置（不需要重启服务），所以打开开关后下一帧就要能走。
     */
    @Test
    fun gate_allows_xiaozhi_audio_once_device_tts_is_turned_on() {
        var enabled = false
        val link = FakeDownlink()
        val r = relay(link) { openGate(ttsEnabled = enabled) }

        // 开关关着:整段拦下，原因就是开关本身（不是设备能力/网关类型）
        r.onTtsState("start", "")
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertTrue("开关关闭时不能下发任何帧", link.events.isEmpty())
        assertFalse(openGate(ttsEnabled = false).allowed)

        // 用户在小智 AI 下打开开关 → 立刻放行（同一轮内实时生效）
        enabled = true
        assertTrue("ttsEnabled=true 时直通门必须放行", openGate(ttsEnabled = true).allowed)
        assertEquals(null, openGate(ttsEnabled = true).blockedReason)
        r.onTtsState("sentence_start", "你好")
        r.onTtsAudio(ByteArray(4), 24, 60)
        assertEquals(listOf("start", "frame"), link.events)
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
}
