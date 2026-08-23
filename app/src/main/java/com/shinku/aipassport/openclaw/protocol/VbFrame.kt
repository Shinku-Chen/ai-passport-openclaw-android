package com.shinku.aipassport.openclaw.protocol

/**
 * 语音对讲桥帧协议 —— 与固件 main/voice_bridge_frame.h 完全一致。
 *
 * 每帧 = [TYPE:1B][FLAGS:1B][LEN:uint16 大端] + payload(LEN 字节),共 4+LEN 字节。
 * 接收端跨 BLE notify 重组:攒到 >=4 读 LEN,再攒到 4+LEN 才吐帧;无分隔符,
 * 音频字节永不被误当控制。
 */
object VbFrame {
    const val HEADER_SIZE = 4

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
    const val MAX_FRAME = HEADER_SIZE + AUDIO_CHUNK_BYTES   // 1028B
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
                // 等帧头(4 字节)
                val want = VbFrame.HEADER_SIZE - len
                val take = if (n < want) n else want
                System.arraycopy(bytes, idx, buf, len, take)
                len += take; idx += take; n -= take
                if (len < VbFrame.HEADER_SIZE) break

                val payloadLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                frameLen = VbFrame.HEADER_SIZE + payloadLen
                inFrame = true
                need = frameLen - len
                if (frameLen > buf.size) {
                    // 异常帧:丢弃
                    len = 0; inFrame = false; need = 0; frameLen = 0
                    continue
                }
            }

            // 等 payload
            val take = if (n < need) n else need
            System.arraycopy(bytes, idx, buf, len, take)
            len += take; idx += take; n -= take; need -= take

            if (need == 0) {
                val type = buf[0].toInt() and 0xFF
                val flags = buf[1].toInt() and 0xFF
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
    out[0] = type.toByte()
    out[1] = flags.toByte()
    out[2] = ((payload.size shr 8) and 0xFF).toByte()
    out[3] = (payload.size and 0xFF).toByte()
    System.arraycopy(payload, 0, out, VbFrame.HEADER_SIZE, payload.size)
    return out
}
