package com.shinku.aipassport.openclaw.stt

/**
 * 小智 Device-Id 的**纯**归一化工具:把「已连接对讲设备的蓝牙地址」整理成小智侧既有的 mac 字段格式
 * (6 组大写十六进制、冒号分隔,如 `4C:11:AE:30:B9:7A`)。
 *
 * 为什么单独成类:小智云按 Device-Id 登记/绑定设备(OTA 与识别 WS 用的是同一个值),
 * 所以「取哪个地址、用什么格式」必须只有一处实现 —— 不允许出现第二种写法(小写/无分隔/短横线),
 * 也不允许在取不到设备地址时拿手机侧标识(全零匿名 MAC、机型名等)顶上:
 * 那会把两台设备登记成同一台,绑定结果对当前设备无效。
 *
 * 本类不依赖 Android,可在 JVM 单测里直接覆盖。
 */
object XiaozhiDeviceId {

    /** 带分隔符的 BLE 地址:`XX:XX:XX:XX:XX:XX`。 */
    private val COLON_MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** 无分隔符的 12 位十六进制地址(某些来源会给成这种形式)。 */
    private val BARE_MAC = Regex("^[0-9A-Fa-f]{12}$")

    /**
     * 归一化成小智 Device-Id(`XX:XX:XX:XX:XX:XX`,大写)。
     *
     * @param raw 原始地址,如 `4C:11:AE:30:B9:7A` / `4c11ae30b97a`
     * @return 归一化后的地址;不是完整的 6 字节 MAC(2 种合法写法之外的一律视为非法)时返回 **null**
     */
    fun formatAddress(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (!COLON_MAC.matches(s) && !BARE_MAC.matches(s)) return null
        return s.filter { it != ':' }.uppercase().chunked(2).joinToString(":")
    }
}
