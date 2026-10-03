package com.shinku.aipassport.openclaw.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下行音频**写模式**契约的单测(纯 JVM):钉住 2026-10 真机 A/B 的结论,
 * 避免有人再把小智直通改回无响应写。
 */
class TtsWriteModeTest {

    /**
     * 小智直通 **固定带响应写**:无响应写在本机/本固件组合上会静默丢帧
     * (App 报「写入成功 N 帧」而设备 `TTS` 计数恒为 0、`RX 缓冲满` 也为 0 → 不是 ring 溢出)。
     */
    @Test
    fun xiaozhi_direct_is_pinned_to_the_responded_write() {
        assertEquals(TtsWriteMode.WITH_RESPONSE, TtsWriteMode.XIAOZHI_DIRECT)
        assertFalse("小智直通不得使用无响应写", TtsWriteMode.XIAOZHI_DIRECT.bulkWrite)
    }

    /**
     * 本机合成那条路**本次未改**(仍是历史行为):标记为待真机复核 ——
     * 同一手机/固件组合上它很可能同样静默丢帧。
     */
    @Test
    fun local_synthesis_still_uses_the_no_response_write_pending_device_review() {
        assertEquals(TtsWriteMode.NO_RESPONSE, TtsWriteMode.LOCAL_SYNTHESIS)
        assertTrue(TtsWriteMode.LOCAL_SYNTHESIS.bulkWrite)
    }

    /** `bulkWrite` 是传给 `BleCentral.setBulkWrite` 的值:true = 无响应写,false = 带响应写。 */
    @Test
    fun bulk_write_flag_matches_the_gatt_write_type() {
        assertTrue(TtsWriteMode.NO_RESPONSE.bulkWrite)
        assertFalse(TtsWriteMode.WITH_RESPONSE.bulkWrite)
        // 两条链路的写模式**必须不同**:它们共用同一个下游开关,一边改了另一边就跟着变
        assertTrue(TtsWriteMode.XIAOZHI_DIRECT != TtsWriteMode.LOCAL_SYNTHESIS)
    }
}
