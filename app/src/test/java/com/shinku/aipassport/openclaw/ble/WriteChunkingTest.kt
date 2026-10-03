package com.shinku.aipassport.openclaw.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写分片长度([WriteChunking])的单测:按协商到的 MTU 动态取,协商失败回退。
 *
 * 为什么值得钉:下行 TTS 的一帧(≤521B)要切成若干次 ATT 写,片长直接决定**写在途次数**
 * (带响应写每片都要等一次 GATT 回调)—— 249B 的帧在片长 240 时要写 240 + 9 两次,
 * 而实际协商到 256 时可以一次写完。
 */
class WriteChunkingTest {

    /** 协商失败/还没协商到 → 回退到改动前的硬编码值 240。 */
    @Test
    fun falls_back_to_240_when_mtu_is_unknown() {
        assertEquals(240, WriteChunking.chunkSize(null))
        assertEquals(WriteChunking.FALLBACK_CHUNK, WriteChunking.chunkSize(null))
        assertEquals(240, WriteChunking.chunkSize(0))
        assertEquals(240, WriteChunking.chunkSize(-1))
    }

    /** 协商到有效 MTU → 片长 = MTU − 3(ATT opcode + handle)。 */
    @Test
    fun chunk_is_mtu_minus_the_att_overhead() {
        assertEquals(20, WriteChunking.chunkSize(23))    // 未协商的默认 MTU
        assertEquals(244, WriteChunking.chunkSize(247))  // DeviceProfiles 请求的 MTU
        assertEquals(247, WriteChunking.chunkSize(250))
    }

    /** 253(= 256 − 3)是片长上限;更大的 MTU 不会让单次写更大。 */
    @Test
    fun chunk_is_capped_at_253() {
        assertEquals(250, WriteChunking.chunkSize(253))
        assertEquals(253, WriteChunking.chunkSize(256))
        assertEquals(253, WriteChunking.chunkSize(517))
        assertEquals(WriteChunking.MAX_CHUNK, WriteChunking.chunkSize(1_000))
    }

    /** 边界:249B 的 TTS_OPUS 帧从 2 次 ATT 写降到 1 次的临界点(片长 ≥ 249 ⇒ MTU ≥ 252)。 */
    @Test
    fun a_249_byte_frame_needs_one_write_from_mtu_252_on() {
        val frameBytes = 249
        fun writes(chunk: Int) = (frameBytes + chunk - 1) / chunk

        assertEquals("回退片长 240 时要写 240 + 9", 2, writes(WriteChunking.FALLBACK_CHUNK))
        assertEquals("请求 MTU 247(片长 244)时仍要 2 次", 2, writes(WriteChunking.chunkSize(247)))
        assertEquals("临界点前一档 MTU 251(片长 248,差 1 字节)要 2 次", 2, writes(WriteChunking.chunkSize(251)))
        assertEquals("临界点 MTU 252(片长 249,刚好一帧)降到 1 次", 1, writes(WriteChunking.chunkSize(252)))
        assertEquals("MTU 253(片长 250)也是 1 次", 1, writes(WriteChunking.chunkSize(253)))
        assertEquals("MTU 256(片长 253)也是 1 次", 1, writes(WriteChunking.chunkSize(256)))
    }

    /** 片长必须是正数:极端小的 MTU 也只能退到 1 字节,不能返回 0/负数(会让写泵空转)。 */
    @Test
    fun chunk_is_never_non_positive() {
        assertEquals(1, WriteChunking.chunkSize(3))
        assertEquals(1, WriteChunking.chunkSize(1))
        assertTrue(WriteChunking.chunkSize(23) > 0)
    }
}
