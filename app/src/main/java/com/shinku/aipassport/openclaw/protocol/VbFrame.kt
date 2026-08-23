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
 * 同时 AUDIO 帧 payload 固定为 1024B,长度不符的帧一律丢弃,确保脏数据不进 STT。
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

    // ---- FLAGS ----
    const val FLAG_MORE = 0x01       // 续帧
    const val FLAG_FIRST = 0x02      // 一段语音/消息开头
    const val FLAG_LAST = 0x04       // 一段语音/消息结尾

    // ---- 常量 ----
    const val AUDIO_CHUNK_SAMPLES = 512
    const val AUDIO_CHUNK_BYTES = AUDIO_CHUNK_SAMPLES * 2   // 1024B
    const val MAX_FRAME = HEADER_SIZE + AUDIO_CHUNK_BYTES   // 6 + 1024 = 1030B
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
                frameLen = VbFrame.HEADER_SIZE + payloadLen
                if (frameLen > buf.size || payloadLen > VbFrame.AUDIO_CHUNK_BYTES) {
                    // 异常帧长:即便 magic 命中,超界仍说明错位 → 丢弃头字节继续找下一个 magic
                    System.arraycopy(buf, 1, buf, 0, len - 1)
                    len -= 1
                    continue
                }
                inFrame = true
                need = frameLen - len
            }

            // 等 payload
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
