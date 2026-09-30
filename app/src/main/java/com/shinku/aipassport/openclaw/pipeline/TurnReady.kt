package com.shinku.aipassport.openclaw.pipeline

/**
 * 设备侧「可以说话了」事件帧的 JSON(见 `docs/wire-protocol.md` 第 4 节)。
 *
 * 与设备→App 的事件帧**同格式**(`TYPE_EVENT = 0x04`,payload 是 JSON),只是方向反过来:
 * 设备收到它把「按下即红(准备中)」变绿(可以说话)。
 */
const val TURN_READY_EVENT_JSON = "{\"ev\":\"turn_ready\"}"

/**
 * 「按下即红、就绪变绿」里的**就绪**去重判定(纯逻辑,不依赖 Android,可 JVM 单测)。
 *
 * 语义:
 *  - 设备按下 OK 的瞬间整屏变红(准备中),收到 `{"ev":"turn_ready"}` 才变绿 —— 绿色 = 用户此时
 *    说话一定能被识别,所以这条事件只能由「录音/识别真的开始」触发(见 [SttEngine] 的
 *    `startTurn(onReady)`),既不能按下就发(那时还录不到音),也不能等识别结果才发。
 *  - **同一轮只发一次**:识别引擎的就绪回调可能重复(重连、握手重试)或被 barge 后的旧回调
 *    迟到触发;`turn` 编号单调递增,因此「不大于已发过的轮次」一律不发。
 *  - 旧轮(barge / 断开后的迟到回调)也靠同一条规则丢弃,不会把上一轮的绿光误发到新一轮。
 *
 * 用法:每轮 `turn_start` 用**当前轮号**调 [arm];返回 true 才真的下发事件帧。
 */
class TurnReadyGate {

    /** 已成功放行过的最大轮号;[Int.MIN_VALUE] = 还没有任何一轮发过。 */
    @Volatile
    private var sentTurn: Int = Int.MIN_VALUE

    /**
     * @param turn 触发就绪的轮号(即 `VoicePipeline.turnId`)
     * @return true = 该轮第一次就绪、可以下发 `turn_ready`;false = 同轮已发过或已是旧轮
     */
    @Synchronized
    fun arm(turn: Int): Boolean {
        if (turn <= sentTurn) return false
        sentTurn = turn
        return true
    }
}
