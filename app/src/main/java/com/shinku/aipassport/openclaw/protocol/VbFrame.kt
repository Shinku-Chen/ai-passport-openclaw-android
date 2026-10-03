package com.shinku.aipassport.openclaw.protocol

/**
 * 语音对讲桥帧协议 —— 与固件 main/voice_bridge_frame.h 完全一致。
 *
 * 每帧 = [MAGIC0][MAGIC1][TYPE:1B][FLAGS:1B][LEN:uint16 大端] + payload(LEN 字节),共 6+LEN 字节。
 * 接收端跨 BLE notify 重组:先找 magic(0xA5 0x5A)对齐帧边界,再读 LEN,凑齐 6+LEN 才吐帧。
 *
 * 为什么加 magic:旧协议只有 1B type(0x01-0x04),而 PCM 采样字节大量等于这些值。
 * 一旦任何单字节失步(丢 notify / 分片错位),接收端会把 PCM 里恰好等于 0x01-0x04 的字节
 * 当成帧头,永久锁死(表现为 flags=201/172/255 等伪帧)。magic 0xA5 0x5A 在随机数据中
 * 出现的概率约 1/65536,几乎不会与音频/JSON 混淆,接收端凭它可靠重同步;type 仅作辅助校验。
 * 同时每种类型有载荷长度上限(v1 音频载荷含 1 字节 SEQ),长度不符的帧一律丢弃,
 * 确保脏数据不进 STT。
 */
object VbFrame {
    const val MAGIC0 = 0xA5
    const val MAGIC1 = 0x5A
    const val HEADER_SIZE = 6

    // ---- 帧类型 ----
    const val TYPE_AUDIO = 0x01      // 设备→App 原始 PCM int16 mono 16k
    const val TYPE_TEXT = 0x02       // App→设备 UTF-8 文本,渲染到屏幕
    const val TYPE_CONTROL = 0x03    // App→设备 JSON 命令
    const val TYPE_EVENT = 0x04      // 设备→App JSON 事件
    const val TYPE_OPUS = 0x05       // 设备→App Opus 帧(固件已编码),供小智识别
    const val TYPE_TTS_OPUS = 0x06   // App→设备 下行 TTS Opus 帧(手机合成,设备播放回复)

    // ---- FLAGS ----
    const val FLAG_MORE = 0x01       // 续帧
    const val FLAG_FIRST = 0x02      // 一段语音/消息开头
    const val FLAG_LAST = 0x04       // 一段语音/消息结尾

    // ---- 常量 ----
    const val AUDIO_CHUNK_SAMPLES = 512
    const val AUDIO_CHUNK_BYTES = AUDIO_CHUNK_SAMPLES * 2   // 1024B

    /** Opus 单包上限(与固件 OC_OPUS_PAYLOAD_MAX 一致)。 */
    const val OPUS_PAYLOAD_MAX = 512

    /**
     * 下行 TTS 帧头长度:`[SEQ:1B][rate_khz:1B][frame_ms:1B]`(单位字节)。
     * 见 [VbTtsOpusPayload]。
     */
    const val TTS_HEADER_SIZE = 3

    /** 下行 TTS 单帧 Opus 包上限(与固件 OC_TTS_OPUS_PAYLOAD_MAX 一致);不含 3B 帧头。 */
    const val TTS_OPUS_PAYLOAD_MAX = 512

    /** 文本载荷上限(与固件 OC_TEXT_PAYLOAD_MAX 一致)。 */
    const val TEXT_PAYLOAD_MAX = 2048

    /** 控制/事件 JSON 载荷上限(与固件 OC_JSON_PAYLOAD_MAX 一致)。 */
    const val JSON_PAYLOAD_MAX = 512

    /**
     * 各帧类型的载荷上限。
     *
     * 音频类载荷首字节是 [SEQ:1B](v1 协议;SEQ 只占 1 字节,用于丢帧统计),
     * 所以 PCM 兜底帧是 1024+1、Opus 帧是 ≤512+1,不是恰好 1024/512。
     * 超限一律按错位处理(见 [VbFrameReassembler])。
     */
    fun payloadLimit(type: Int): Int = when (type) {
        TYPE_AUDIO -> AUDIO_CHUNK_BYTES + 1     // [SEQ] + int16 PCM
        TYPE_OPUS -> OPUS_PAYLOAD_MAX + 1       // [SEQ] + 一个 Opus 包
        TYPE_TTS_OPUS -> TTS_HEADER_SIZE + TTS_OPUS_PAYLOAD_MAX   // [SEQ][rate_khz][frame_ms] + Opus 包
        TYPE_TEXT -> TEXT_PAYLOAD_MAX
        TYPE_CONTROL, TYPE_EVENT -> JSON_PAYLOAD_MAX
        else -> 0
    }

    /** 重组缓冲容量:容纳合法最大帧(TEXT 2048,协议规范要求的 6 + 2048)。 */
    const val MAX_FRAME = HEADER_SIZE + TEXT_PAYLOAD_MAX   // 6 + 2048 = 2054B
}

/**
 * 解析出的完整帧。
 */
data class VbFrameData(
    val type: Int,
    val flags: Int,
    val payload: ByteArray,
)

/**
 * 流式重组器:把 BLE notify 进来的字节攒起来,凑足整帧时吐出。
 * 镜像固件 vb_frame_reassembler_push 的逻辑。
 */
class VbFrameReassembler(
    private val onFrame: (VbFrameData) -> Unit,
) {
    private val buf = ByteArray(VbFrame.MAX_FRAME)
    private var len = 0
    private var need = 0          // 还差多少字节;0=等帧头
    private var frameLen = 0      // 当前帧总长(4+payload)
    private var inFrame = false

    fun push(bytes: ByteArray, count: Int = bytes.size) {
        var n = count
        var idx = 0
        while (n > 0) {
            if (!inFrame) {
                // 等帧头(magic + type/flags/len 共 6 字节)。未凑齐前逐字节扫描 magic 重同步:
                // 只要 buf 头两字节不是 magic,就丢弃 1 字节继续找,不信任 PCM/JSON 里的伪帧头。
                val want = VbFrame.HEADER_SIZE - len
                val take = if (n < want) n else want
                System.arraycopy(bytes, idx, buf, len, take)
                len += take; idx += take; n -= take
                if (len < 2) continue

                // 找 magic 前缀
                while (true) {
                    if ((buf[0].toInt() and 0xFF) == VbFrame.MAGIC0 &&
                        (buf[1].toInt() and 0xFF) == VbFrame.MAGIC1) break
                    if (len <= 1) { len = 0; break }
                    System.arraycopy(buf, 1, buf, 0, len - 1)
                    len -= 1
                }
                if (len == 0) continue          // 还在找 magic,原有剩余不足
                if (len < VbFrame.HEADER_SIZE) continue   // 帧头还没凑齐

                val payloadLen = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                val type = buf[2].toInt() and 0xFF
                val limit = VbFrame.payloadLimit(type)
                frameLen = VbFrame.HEADER_SIZE + payloadLen
                if (frameLen > buf.size || limit == 0 || payloadLen > limit) {
                    // 异常帧长/未知类型:即便 magic 命中,超界仍说明错位 → 丢弃头字节继续找下一个 magic
                    System.arraycopy(buf, 1, buf, 0, len - 1)
                    len -= 1
                    continue
                }
                inFrame = true
                need = frameLen - len
            }

            // 等 payload —— 关键:即使正攒一帧,也要监测 payload 里是否【嵌入了新帧头】。
            // 固件在 AUDIO 帧发送中途可能插入 EVENT 帧(turn_end),此时 EVENT 的 6B 头
            // 会紧跟 AUDIO 帧的某个 notify。若这里不切帧,EVENT 会被当成 AUDIO 的 payload
            // 余量吞掉(App 收不到 turn_end → STT 永不 endTurn → 卡"发送中")。
            // 这里扫描未处理字节中是否出现合法新帧头(magic + 合法type + 合理 len),
            // 命中即丢弃当前半截帧、切到新帧重同步。AUDIO 里恰好出现 a5 5a + 合法type
            // 概率 ~1/65536,且还需 len 合理,误切风险可忽略。
            var cut = -1
            var i = 0
            while (i + 5 < n) {
                val b0 = bytes[idx + i].toInt() and 0xFF
                val b1 = bytes[idx + i + 1].toInt() and 0xFF
                if (b0 == VbFrame.MAGIC0 && b1 == VbFrame.MAGIC1) {
                    val t = bytes[idx + i + 2].toInt() and 0xFF
                    // 只在检测到【非音频】的插入帧(EVENT/TEXT/CONTROL)时才切帧。
                    // 固件不会在 AUDIO 帧里再嵌一个 AUDIO 帧,故仅当 type∈{2,3,4}
                    // 且 len 合理才视为边界;避免 AUDIO payload 里偶发的 a5 5a+type=1
                    // 被误切。turn_end 正是 EVENT(type=4),正是要救的场景。
                    val okType = t == VbFrame.TYPE_TEXT || t == VbFrame.TYPE_CONTROL ||
                        t == VbFrame.TYPE_EVENT
                    val pl = ((bytes[idx + i + 4].toInt() and 0xFF) shl 8) or
                        (bytes[idx + i + 5].toInt() and 0xFF)
                    // EVENT 帧都很短(turn_start ~19B / turn_end ~34B);限制 ≤256 更安全,
                    // 也避免把 AUDIO payload 中偶发的 a5 5a + type=2/3/4 + 大 len 误判。
                    val okLen = pl <= 256
                    if (okType && okLen) { cut = i; break }
                }
                i++
            }
            if (cut != -1) {
                // 半截 frame(如果已有内容)先丢弃;跳到新帧头切帧
                len = 0; need = 0; frameLen = 0; inFrame = false
                idx += cut; n -= cut
                continue
            }

            val take = if (n < need) n else need
            System.arraycopy(bytes, idx, buf, len, take)
            len += take; idx += take; n -= take; need -= take

            if (need == 0) {
                // 完整帧:type/flags 在 buf 的 magic 之后(offset 2/3)
                val type = buf[2].toInt() and 0xFF
                val flags = buf[3].toInt() and 0xFF
                val p = ByteArray(frameLen - VbFrame.HEADER_SIZE)
                System.arraycopy(buf, VbFrame.HEADER_SIZE, p, 0, p.size)
                onFrame(VbFrameData(type, flags, p))
                len = 0; need = 0; frameLen = 0; inFrame = false
            }
        }
    }

    fun reset() {
        len = 0; need = 0; frameLen = 0; inFrame = false
    }
}

/**
 * 把一帧编码成字节(发送方向)。返回 HEADER+payload。
 */
fun vbEncodeFrame(type: Int, flags: Int, payload: ByteArray): ByteArray {
    val total = VbFrame.HEADER_SIZE + payload.size
    val out = ByteArray(total)
    out[0] = VbFrame.MAGIC0.toByte()
    out[1] = VbFrame.MAGIC1.toByte()
    out[2] = type.toByte()
    out[3] = flags.toByte()
    out[4] = ((payload.size shr 8) and 0xFF).toByte()
    out[5] = (payload.size and 0xFF).toByte()
    System.arraycopy(payload, 0, out, VbFrame.HEADER_SIZE, payload.size)
    return out
}

/**
 * 下行 TTS Opus 帧的载荷(payload,不含 6B 帧头):
 *
 * ```
 * [SEQ:1B][rate_khz:1B][frame_ms:1B][Opus 包 ≤512B]
 * ```
 *
 * 与固件 `intercom-wire-protocol.md` 的 `TTS_OPUS`(0x06)一致:
 *  - [seq] 是 1 字节计数器,每帧 +1、到 256 回绕(见 [VbTtsOpusPayload.nextSeq]);只用于设备侧
 *    统计 SEQ 缺口,丢了不重传(丢一个包 = 60ms 音频);
 *  - [rateKhz] 只允许 `16` / `24`(帧头自带采样率,流中换采样率也合法:设备按新值重配解码器);
 *  - [frameMs] 目前恒为 `60`(每帧恰好一个 Opus 包);
 *  - [opus] 是一个完整 Opus 包,上限 [VbFrame.TTS_OPUS_PAYLOAD_MAX](512)。
 *
 * 整个 payload 长度因此是 `4..515`(`3 + 包长`),超出即按错位处理(同 [VbFrameReassembler])。
 */
data class VbTtsOpusPayload(
    val seq: Int,
    val rateKhz: Int,
    val frameMs: Int,
    val opus: ByteArray,
) {
    companion object {
        /** SEQ 是 1 字节计数器:每帧 +1,到 256 回绕到 0。 */
        fun nextSeq(seq: Int): Int = (seq + 1) and 0xFF
    }
}

/**
 * 组装一个下行 TTS 载荷([VbTtsOpusPayload])。
 *
 * @throws IllegalArgumentException [opus] 超过 [VbFrame.TTS_OPUS_PAYLOAD_MAX](512B)
 */
fun encodeTtsOpusPayload(seq: Int, rateKhz: Int, frameMs: Int, opus: ByteArray): ByteArray {
    require(opus.size <= VbFrame.TTS_OPUS_PAYLOAD_MAX) {
        "opus packet ${opus.size}B exceeds ${VbFrame.TTS_OPUS_PAYLOAD_MAX}B"
    }
    val out = ByteArray(VbFrame.TTS_HEADER_SIZE + opus.size)
    out[0] = (seq and 0xFF).toByte()
    out[1] = (rateKhz and 0xFF).toByte()
    out[2] = (frameMs and 0xFF).toByte()
    System.arraycopy(opus, 0, out, VbFrame.TTS_HEADER_SIZE, opus.size)
    return out
}

/**
 * 把一帧 JSON 载荷裁剪到固件该类型的载荷上限 [VbFrame.JSON_PAYLOAD_MAX](512B,
 * 对应固件 `oc_proto.h` 的 `OC_JSON_PAYLOAD_MAX`),裁剪点回退到 UTF-8 字符边界。
 *
 * 为什么必须裁:固件 `header_valid()` 把「LEN 超过该类型上限」的帧头当**错位字节**逐字节丢弃
 * (既不计类型计数,也不计「废半截」),所以超长的 CONTROL/EVENT 会被静默丢掉 —— 还会连着吃掉
 * 后面那一帧的帧头字节。控制帧本身很短,但 `gateway.detail` 这种携带错误文案的字段没有上限。
 *
 * 纯函数:超限时返回裁剪后的新数组,未超限时原样返回入参(不复制)。
 */
fun clampJsonPayload(bytes: ByteArray, maxBytes: Int = VbFrame.JSON_PAYLOAD_MAX): ByteArray {
    require(maxBytes > 0) { "maxBytes must be > 0" }
    if (bytes.size <= maxBytes) return bytes
    // 回退到 UTF-8 字符边界:cut 落在续字节(10xxxxxx)上就往前退
    var cut = maxBytes
    while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
    if (cut == 0) cut = maxBytes   // 极端:整段都是续字节(非法 UTF-8),至少保证有进展
    return bytes.copyOfRange(0, cut)
}

/**
 * 解析下行 TTS 载荷。[encodeTtsOpusPayload] 的逆操作。
 *
 * 长度不足(没有 Opus 包)或超出上限([VbFrame.TTS_HEADER_SIZE] + [VbFrame.TTS_OPUS_PAYLOAD_MAX])时返回 null:
 * 这两种情况都说明帧边界已错位,调用方按错位处理(不交给解码器)。
 */
fun decodeTtsOpusPayload(payload: ByteArray): VbTtsOpusPayload? {
    if (payload.size <= VbFrame.TTS_HEADER_SIZE) return null
    if (payload.size > VbFrame.TTS_HEADER_SIZE + VbFrame.TTS_OPUS_PAYLOAD_MAX) return null
    return VbTtsOpusPayload(
        seq = payload[0].toInt() and 0xFF,
        rateKhz = payload[1].toInt() and 0xFF,
        frameMs = payload[2].toInt() and 0xFF,
        opus = payload.copyOfRange(VbFrame.TTS_HEADER_SIZE, payload.size),
    )
}

/**
 * 编码一整帧下行 TTS 帧(发送方向):`[A5][5A][0x06][FLAGS=0][LEN]` + [encodeTtsOpusPayload]。
 *
 * FLAGS 固定 0:音频帧各自独立可解,分段语义由 `tts_start` / `tts_stop` 界定,不用 MORE/FIRST/LAST。
 */
fun vbEncodeTtsOpusFrame(seq: Int, rateKhz: Int, frameMs: Int, opus: ByteArray): ByteArray =
    vbEncodeFrame(VbFrame.TYPE_TTS_OPUS, 0, encodeTtsOpusPayload(seq, rateKhz, frameMs, opus))

/**
 * 把 UTF-8 文本字节按 [maxBytes] 切成多片,每片都回退到 UTF-8 字符边界,
 * 保证不会把一个多字节字符(如汉字 3 字节、emoji 4 字节)切到两片中间。
 *
 * 上行 TEXT 帧的 payload 上限是 [VbFrame.TEXT_PAYLOAD_MAX](2048),还要减去 1 字节 role,
 * 因此长语音/带附加提示的长文本必须分片。纯函数、无 Android 依赖,便于 JVM 单测
 * 验证「含中文多字节时仍不切坏汉字」(见 `TextChunkingTest`)。
 *
 * 边界判定:分片点若落在 UTF-8 续字节(0b10xxxxxx)上,说明正切在字符中间,
 * 向前退到该字符的首字节(`cut` 指向下一片的首字节,即一个字符边界)。
 * 注:这是对提取前服务内联循环的修正 —— 旧写法检查的是 `cut-1` 恒字节,
 * 会切在字符中间、并可能把末尾几个续字节留在 `cut == off` 分支丢掉。
 *
 * 语义:
 *  - 空输入返回空列表(调用方自行发一条空帧);
 *  - 每片 ≤ [maxBytes];末片可能更短;
 *  - 各片按顺序拼接后与输入字节完全相同(不丢字节)。
 */
fun splitTextPayload(body: ByteArray, maxBytes: Int): List<ByteArray> {
    require(maxBytes > 0) { "maxBytes must be > 0" }
    if (body.isEmpty()) return emptyList()
    val chunks = ArrayList<ByteArray>()
    var off = 0
    while (off < body.size) {
        val end = minOf(off + maxBytes, body.size)
        // 回退到 UTF-8 字符边界:cut 落在续字节上就往前退
        var cut = end
        while (cut > off && cut < body.size && (body[cut].toInt() and 0xC0) == 0x80) cut--
        // 非法 UTF-8(单个字符比 maxBytes 还长)兜底:不前进会死循环,至少保证推进
        if (cut == off) cut = end
        chunks.add(body.copyOfRange(off, cut))
        off = cut
    }
    return chunks
}
