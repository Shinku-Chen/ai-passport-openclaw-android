package com.shinku.aipassport.openclaw.ble

/**
 * 单次 ATT 写的**分片长度**(纯逻辑,不依赖 Android/BLE,可直接 JVM 单测)。
 *
 * 一次 ATT 写最多携带 `MTU − 3` 字节(3 = ATT opcode + handle 两个字节),所以片长必须跟着
 * **协商到的 MTU** 走,而不是写死一个值:
 *  - 写死 240 时,一个 249B 的 `TTS_OPUS` 帧要拆成 2 次写(240 + 9);片长 ≥ 249(即 MTU ≥ 252)
 *    时本可 1 次写完 —— 下行音频流的吞吐直接受写在途次数限制(带响应写每片都要等一次 GATT 回调);
 *  - 反过来,MTU 协商失败/还没协商到(Android 默认 23)时若照发 240B,只能靠协议栈的 Long Write
 *    兜底,分片层根本不知道自己切片切大了。
 *
 * 注意(真机尚未核对的一环):本仓库请求的 MTU 是 247(`BleNus.REQUEST_MTU`),片长因此是 244
 * —— 249B 的帧**仍然要 2 次写**。要拿到「1 次写完」得把请求的 MTU 提到 ≥252,那属于另一项改动
 * (先看设备/协议栈实际能协商到多少:新的 `MTU=… → 单次 ATT 写片长=…` 日志行就是取证点)。
 *
 * 上限 [MAX_CHUNK] = 253 = 256 − 3:把分片压在 256B 这个档位以内 —— 这是本链路(固件 RX ring
 * 只有 4KB)实测验证过的写尺寸,再大的 MTU(如 517)也不会让单次写变大、把对端缓冲一次灌满。
 */
object WriteChunking {

    /** ATT 写载荷之外的开销(opcode + handle)。 */
    const val ATT_WRITE_OVERHEAD = 3

    /** 分片长度上限:253 字节(= 256 − 3),见类注释。 */
    const val MAX_CHUNK = 253

    /** MTU 未协商到(还没回调 / 协商失败)时的回退片长:与改动前的硬编码值一致。 */
    const val FALLBACK_CHUNK = 240

    /**
     * 按协商到的 MTU 取本次连接的分片长度。
     *
     * @param mtu 协商结果;null / ≤0 = 未协商到(回退 [FALLBACK_CHUNK])
     */
    fun chunkSize(mtu: Int?): Int {
        if (mtu == null || mtu <= 0) return FALLBACK_CHUNK
        return (mtu - ATT_WRITE_OVERHEAD).coerceAtMost(MAX_CHUNK).coerceAtLeast(1)
    }
}
