package com.shinku.aipassport.openclaw.ble

import java.util.UUID

/**
 * 一台 BLE 设备的接入档案：发现方式、GATT 地址、配对要求、线协议与能力。
 *
 * 目的：把此前写死在 [BleCentral] 里的常量（服务/特征 UUID、广播名前缀、请求 MTU、配对方式、
 * 帧格式、音频参数）收进一个纯数据对象。新增品牌时只需在 [DeviceProfiles] 里加一个档案，
 * 扫描过滤、连接、服务发现、订阅、音频编码这些流程一行都不用动。
 *
 * 本类不持有 Context、不发起 IO、读的都是不可变值，因此可以直接在 JVM 单测里断言。
 */
data class DeviceProfile(
    /** 稳定标识：用于偏好键与日志（如 "passport"），不要用展示名当键。 */
    val id: String,
    /** 界面上显示的名字（如 "AI Passport"）。 */
    val displayName: String,
    /**
     * 广播名前缀白名单：命中任一前缀才认为可能是本档案的设备。
     * 空列表表示"不按名字过滤"，只依赖 [serviceUuid]。
     */
    val namePrefixes: List<String>,
    /** 主服务 UUID：Android 扫描过滤条件。 */
    val serviceUuid: UUID,
    /** App → 设备 的写特征。 */
    val rxUuid: UUID,
    /** 设备 → App 的通知特征。 */
    val txUuid: UUID,
    /** 通知开关（CCCD）描述符 UUID，通常是蓝牙标准值。 */
    val cccdUuid: UUID,
    /** 请求的 MTU。 */
    val requestMtu: Int,
    /** 配对/鉴权方式。 */
    val pairing: PairingMode,
    /** 帧格式。 */
    val framing: Framing,
    /** 上行音频参数。 */
    val audio: AudioSpec,
    val capabilities: Capabilities,
) {
    /**
     * 广播名是否属于本档案。
     *
     * 大小写敏感，与既有实现保持一致：固件广播名固定为 `Passport-A1B2` 这种形式，
     * 不区分大小写会放宽匹配范围，属于行为变化，重构不做。
     */
    fun matchesName(name: String?): Boolean {
        if (namePrefixes.isEmpty()) return true
        if (name == null) return false
        return namePrefixes.any { name.startsWith(it) }
    }
}

/** 配对/鉴权方式：决定连上之后"怎么确认对方是本人授权的设备"。 */
enum class PairingMode {
    /** 设备屏幕显示 6 位随机码，用户在 App 里输入（LE Secure Connections + MITM）。 */
    PASSPORT_SCREEN_CODE,

    /** 无码直连（Just Works），安全等级最低，仅适用于明确不谈隐私的设备。 */
    JUST_WORKS,

    /** 设备侧固定 PIN。 */
    FIXED_PIN,

    /** 配对交给应用层握手完成（例如设备侧自带 token/挑战应答）。 */
    APP_LAYER_HANDSHAKE,
}

/** 线协议帧格式：决定字节流怎么切帧。 */
enum class Framing {
    /** AI Passport 对讲机协议：`[A5 5A][TYPE][FLAGS][LEN:2B 大端][payload]`。 */
    PASSPORT_A5,

    /** 一行一个 JSON（换行分帧）。 */
    JSON_LINES,

    /** 特征上就是裸音频包，无额外帧头。 */
    RAW_OPUS,

    /** 其它自定义格式，需要在 App 侧另写编解码器。 */
    CUSTOM,
}

/** 上行音频参数（设备 → App 的方向）。 */
data class AudioSpec(
    val codec: Codec,
    /** 采样率（Hz）。 */
    val sampleRateHz: Int,
    /** 每帧时长（ms）。 */
    val frameMs: Int,
) {
    enum class Codec { OPUS, PCM }
}

/**
 * 设备能力：App 侧据此决定要不要显示某些界面、以及协议层的上限。
 * 只描述"设备本身有什么"，不描述"我们实现了什么"。
 */
data class Capabilities(
    /** 是否带屏（能显示文本）。 */
    val hasDisplay: Boolean,
    /** 实体按键数量。 */
    val buttonCount: Int,
    /** 单条文本帧允许的最大字节数（UTF-8）。 */
    val maxTextBytes: Int,
)
