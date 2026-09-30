package com.shinku.aipassport.openclaw.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `turn_ready`(设备侧「按下即红、就绪变绿」)的去重判定单测。
 *
 * 核心约束:
 *  - 同一轮**只放行一次**(识别引擎的就绪回调可能重连/重试后重复);
 *  - barge/断开后的**旧轮迟到回调一律丢弃**(不能把上一轮的绿灯发到新一轮);
 *  - 新一轮重新放行。
 */
class TurnReadyTest {

    @Test
    fun first_ready_of_a_turn_is_allowed() {
        assertTrue(TurnReadyGate().arm(1))
    }

    @Test
    fun repeated_ready_of_the_same_turn_is_dropped() {
        val gate = TurnReadyGate()
        assertTrue(gate.arm(1))
        assertFalse("同一轮只发一次", gate.arm(1))
        assertFalse(gate.arm(1))
    }

    @Test
    fun stale_turn_after_barge_is_dropped() {
        val gate = TurnReadyGate()
        assertTrue(gate.arm(2))
        // barge / 断开后 turnId 已自增:旧轮(1、2)的迟到就绪回调必须丢弃
        assertFalse(gate.arm(2))
        assertFalse(gate.arm(1))
        assertTrue("新一轮照常放行", gate.arm(3))
    }

    @Test
    fun event_json_matches_device_contract() {
        assertEquals("{\"ev\":\"turn_ready\"}", TURN_READY_EVENT_JSON)
    }
}
