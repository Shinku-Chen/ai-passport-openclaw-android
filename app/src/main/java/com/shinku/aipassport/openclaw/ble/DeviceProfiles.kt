package com.shinku.aipassport.openclaw.ble

/**
 * 已知设备的档案目录。
 *
 * 目前只有一个档案：[PASSPORT]（AI Passport 对讲机固件）。它的 UUID / 广播名前缀 / MTU
 * 直接引用 [BleNus] 里的常量，所以"字面值"仍然只有一处，避免档案与固件头文件各写一份而漂移。
 *
 * 新增品牌时的做法：
 *  1. 在 [all] 里加一个 [DeviceProfile]（UUID、前缀、配对方式、帧格式、音频参数、能力）；
 *  2. 若帧格式不是 [Framing.PASSPORT_A5]，App 侧再补一个对应的编解码器；
 *  3. 其余流程（扫描过滤、连接、服务发现、订阅、音频发送、UI）不需要改动。
 *
 * 查找入口：[detect] 按广播名选档案（未来"扫描结果里混着多个品牌"时用），[byId] 按稳定标识取。
 */
object DeviceProfiles {

    /** AI Passport 对讲机固件（当前唯一已实现档案）。 */
    val PASSPORT: DeviceProfile = DeviceProfile(
        id = "passport",
        displayName = "AI Passport",
        namePrefixes = listOf(BleNus.DEVICE_NAME_PREFIX),
        serviceUuid = BleNus.SERVICE_UUID,
        rxUuid = BleNus.RX_UUID,
        txUuid = BleNus.TX_UUID,
        cccdUuid = BleNus.CCCD_UUID,
        requestMtu = BleNus.REQUEST_MTU,
        // 固件要求 LE Secure Connections + MITM，6 位随机码显示在设备屏上，用户输入后完成配对。
        pairing = PairingMode.PASSPORT_SCREEN_CODE,
        // 与固件 voice_bridge_frame.h 的帧格式逐字对应（VbFrame.kt 负责编解码）。
        framing = Framing.PASSPORT_A5,
        // 音频：上行 Opus 16 kHz、60 ms/帧（与固件 oc_audio 一致）。
        audio = AudioSpec(codec = AudioSpec.Codec.OPUS, sampleRateHz = 16_000, frameMs = 60),
        // 三键 + 240×320 屏；单条文本上限与固件协议一致（2047 字节 UTF-8）。
        capabilities = Capabilities(hasDisplay = true, buttonCount = 3, maxTextBytes = 2047),
    )

    /** 全部已知档案。无匹配档案的设备按"未支持"处理。 */
    val all: List<DeviceProfile> = listOf(PASSPORT)

    /**
     * 默认档案：当前扫描/连接流程使用的档案。
     *
     * 目前只有一台设备的场景，等价于"唯一档案"。以后要支持多设备同时在线时，
     * 把这里换成"用户选中的档案"即可，调用方（BleCentral）不需要改结构。
     */
    val default: DeviceProfile get() = PASSPORT

    /** 按稳定标识查档案；不存在返回 null。 */
    fun byId(id: String): DeviceProfile? = all.firstOrNull { it.id == id }

    /**
     * 按广播名推断档案。
     *
     * 多个档案命中时返回 [all] 中靠前的一个（档案顺序即优先级），都不命中返回 null，
     * 由调用方决定是提示"设备不受支持"还是按默认档案尝试连接。
     */
    fun detect(name: String?): DeviceProfile? = all.firstOrNull { it.matchesName(name) }
}
