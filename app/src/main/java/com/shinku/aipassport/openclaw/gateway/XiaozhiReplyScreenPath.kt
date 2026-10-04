package com.shinku.aipassport.openclaw.gateway

/**
 * 小智的**正文 / 字幕**通道是否生效:只有「小智 AI 网关」才走这条路。
 *
 * **为什么必须按网关类型设闸(2026-10-04 作者真机反馈)**:小智会话是**双用途**的 ——
 * 非小智网关下它只用来做语音转文字([com.shinku.aipassport.openclaw.stt.XiaozhiStt]),
 * 但小智服务端并不知道 App 后面挂的是哪个后端,**任何类型下它都会推自己的 `llm`/`tts`/下行音频**。
 *
 * 音频那一路早就由 [XiaozhiTtsGate] 按类型挡住了(否则会和本地合成的朗读叠着出声);而**正文**
 * 这一路原来没挡:小智的回复会被直通 relay 接管、按段写进设备屏 —— 于是设备屏多出 App 对话列表里
 * 根本没有的内容,两处对不上。
 *
 * 判定做成纯函数:它只依赖网关类型,可以被 JVM 单测直接钉住(见 `XiaozhiReplyScreenPathTest`)。
 */
object XiaozhiReplyScreenPath {

    /**
     * @param gatewayType 当前生效的网关类型(见 [GatewaySettings.type]);允许 `null`(设置尚未加载)
     *   —— 那种情况一律返回 false:宁可不接管,也不要把别家后端的对话搞乱。
     */
    fun active(gatewayType: String?): Boolean = gatewayType == GatewaySettings.TYPE_XIAOZHI
}
