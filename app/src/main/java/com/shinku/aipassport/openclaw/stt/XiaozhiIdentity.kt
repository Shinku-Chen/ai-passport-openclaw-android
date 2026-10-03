package com.shinku.aipassport.openclaw.stt

/**
 * 「小智侧标识(Device-Id)取哪个值」的**唯一**决策点:入参 = 当前网关类型 + 已连接设备地址,
 * 出参 = 具体标识 + 是否可建链(或失败原因)。
 *
 * 为什么必须只有一处:小智会话的 `Device-Id` 同时被三条路径使用 —— 识别/对话 WS 握手
 * ([SttFactory] → [XiaozhiStt] → [XiaozhiSession])、OTA/绑定 ([XiaozhiActivator])、
 * 以及「高级 → 小智识别」的状态行([com.shinku.aipassport.openclaw.ui.SettingsFragment])。
 * 三处各写一份判断必然分叉(上一轮就是如此:识别通道被改成永远用真 MAC,非小智网关的识别一起被带崩)。
 *
 * 规则(**按当前网关类型**决定,而不是「永远是设备 MAC」):
 *  - 网关类型 == `xiaozhi`(「小智 AI」)→ 用**已连接对讲设备的真实蓝牙 MAC**,格式见 [XiaozhiDeviceId]
 *    (小智云按这个值登记/绑定设备,所以它必须与手机侧标识区分开);取不到 → 失败并给可读原因,
 *    **不回落匿名**(那会把两台设备登记成同一台,绑定结果对当前设备无效)。
 *  - 网关类型 != `xiaozhi`(OpenClaw / Hermes / 自定义 OpenAI 兼容 / 回显)→ 用**全零匿名 MAC**:
 *    这些模式只把小智通道当**识别(STT)引擎**(回复来自各自的网关),匿名通道是实测能跑通的形态,
 *    与「设备在线与否」无关,因此必须继续可用。
 *
 * 本对象不依赖 Android,可在 JVM 单测里直接覆盖(见 `XiaozhiIdentityTest`)。
 */
object XiaozhiIdentity {

    /** 需要真实设备 MAC 的唯一网关类型(与 `GatewaySettings.TYPE_XIAOZHI` 同一个字面量)。 */
    const val GATEWAY_XIAOZHI = "xiaozhi"

    /** 非小智网关使用的全零匿名标识(识别通道的既有形态)。 */
    const val ANONYMOUS_DEVICE_ID = "00:00:00:00:00:00"

    /** 「高级 → 小智识别」里匿名标识的展示形式。 */
    const val ANONYMOUS_LABEL = "00:00:00:00:00:00（匿名）"

    /** 非小智模式下说明为什么这里不是设备 MAC(保存同一份文案,避免两处各写一句)。 */
    const val DEVICE_MAC_HINT = "用小智 AI 作为网关时会改用设备 MAC"

    /** 小智模式但取不到设备地址时的可读原因(调用方据此停手,不猜、不回落)。 */
    const val NO_DEVICE_REASON =
        "请先连接设备:小智 AI 用【已连接设备的蓝牙 MAC】作为设备 ID,现在没取到设备地址。"

    /**
     * 一次解析的结果:标识本体 + 能否用它建链(+ 不能时的可读原因)。
     *
     * 三态就是全部可能:小智模式的真 MAC / 非小智模式的匿名 / 小智模式但没设备(失败)。
     */
    sealed interface Resolution {

        /** 实际使用的 Device-Id;不可建链时为空串(绝不拿别的值顶上)。 */
        val deviceId: String

        /** 能否用 [deviceId] 建链(WS 握手 / OTA 绑定共用这一判据)。 */
        val canLink: Boolean

        /** 不可建链时的可读原因;可建链时为 null。 */
        val reason: String?

        /** 网关类型 == 小智 AI:用已连接设备的真实蓝牙 MAC(已归一化)。 */
        data class DeviceMac(override val deviceId: String) : Resolution {
            override val canLink: Boolean get() = true
            override val reason: String? get() = null
        }

        /** 网关类型 != 小智 AI:全零匿名标识(识别可用;不做设备绑定)。 */
        data object Anonymous : Resolution {
            override val deviceId: String get() = ANONYMOUS_DEVICE_ID
            override val canLink: Boolean get() = true
            override val reason: String? get() = null
        }

        /** 网关类型 == 小智 AI 但取不到设备地址:停手报错(不回退匿名标识)。 */
        data class Unavailable(override val reason: String) : Resolution {
            override val deviceId: String get() = ""
            override val canLink: Boolean get() = false
        }
    }

    /**
     * 按**当前**网关类型解析小智侧标识。
     *
     * 调用方必须每次建链(WS 握手 / OTA 绑定 / 渲染状态行)时调用,不要缓存结果 ——
     * 用户可能中途改网关类型,标识会随之在小智真 MAC 与匿名标识之间切换
     * (服务侧类型变化会走既有的重载路径并丢弃旧热连接,见 `VoiceBridgeService` / [XiaozhiSession.resetDeviceId])。
     *
     * @param gatewayType 当前生效的网关类型(见 [GATEWAY_XIAOZHI]);空/未知一律按非小智处理。
     * @param deviceAddress 已连接对讲设备的蓝牙地址(原始值,可为 null);非小智模式下不参与判断。
     */
    fun resolve(gatewayType: String?, deviceAddress: String?): Resolution {
        if (!isXiaozhi(gatewayType)) return Resolution.Anonymous
        val mac = XiaozhiDeviceId.formatAddress(deviceAddress)
            ?: return Resolution.Unavailable(NO_DEVICE_REASON)
        return Resolution.DeviceMac(mac)
    }

    /** 是否是「需要真实设备 MAC」的网关类型(小智 AI)。 */
    fun isXiaozhi(gatewayType: String?): Boolean =
        gatewayType?.trim()?.lowercase() == GATEWAY_XIAOZHI

    /**
     * 非小智模式下「不做设备绑定」的可读说明(给 [XiaozhiActivator] 与设置页直接用)。
     *
     * 绑定只对「小智 AI」有意义:别的网关把小智当识别引擎,Device-Id 是匿名标识,
     * 拿匿名标识去 xiaozhi.me 绑定既绑不到当前设备,也会把绑定关系弄错。
     */
    fun bindingNotApplicableReason(gatewayType: String?): String =
        "不用绑定设备:只有网关类型「小智 AI」才用小智做设备绑定。" +
            "当前类型「${gatewayType?.trim().orEmpty()}」只把小智当识别引擎" +
            "(Device-Id 用匿名标识 $ANONYMOUS_DEVICE_ID)。"
}
