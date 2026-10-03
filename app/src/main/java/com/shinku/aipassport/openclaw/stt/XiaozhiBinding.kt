package com.shinku.aipassport.openclaw.stt

import android.content.Context

/**
 * 小智设备绑定的持久化 + 保存前的闸门决策。
 *
 * 为什么需要它:网关类型是「小智 AI」时,小智云按 Device-Id(= 已连接设备的蓝牙 MAC)登记设备,
 * 只有绑定过的设备才能用识别/对话链路。因此「保存网关类型 = 小智 AI」不能像 Echo 那样直接落盘,
 * 必须先把**当前设备**绑定好(见 `SettingsFragment.saveSettings` 的闸门),否则用户会得到一个
 * 「看着已保存、实际用不了」的网关。
 *
 * 注意:其它网关类型**不需要**绑定(它们只把小智当识别引擎,Device-Id 是匿名标识)——
 * 闸门会直接放行,保存不受「设备是否在线」影响。
 *
 * 决策逻辑抽成不依赖 Android 的 [XiaozhiBindGate],便于 JVM 单测直接覆盖
 * 「非小智类型 → 不绑定直接放行 / 未绑定 → 先绑定 / 已绑定 → 直接保存 / 换了设备 → 重新绑定 /
 * 没设备 → 拦截 / 绑定失败 → 不落盘」。
 */
class XiaozhiBinding(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 已成功绑定的设备 MAC(小智 Device-Id 格式);null = 还没绑定过任何设备。 */
    var boundMac: String?
        get() = prefs.getString(KEY_BOUND_MAC, null)
        set(value) = prefs.edit().putString(KEY_BOUND_MAC, value?.trim()).apply()

    private companion object {
        const val PREFS = "xiaozhi_binding"
        const val KEY_BOUND_MAC = "bound_mac"
    }
}

/**
 * 「保存小智 AI」这道闸门的**纯决策**(不碰 Android)。
 *
 * 分两段:[beforeSave] 决定保存动作是直接落盘还是先走绑定;[afterBind] 决定绑定跑完之后
 * 允不允许落盘。两段都只做判断、不产生副作用,调用方(设置页)据此落盘或保持原配置不变。
 *
 * **只对「小智 AI」生效**:其余网关拿 [BeforeSave.NotXiaozhi] 直接放行,既不触发小智绑定,
 * 也不会因为「设备未连接」被拦下(它们只把小智当识别引擎,见 [XiaozhiIdentity])。
 * 「该用哪个标识」不在这里判断 —— 统一调 [XiaozhiIdentity.resolve]。
 */
object XiaozhiBindGate {

    /** 保存网关设置之前的走向。 */
    sealed interface BeforeSave {
        /**
         * 当前网关类型不是「小智 AI」:小智不做绑定 —— 保存流程**原样继续**,与设备是否在线无关。
         */
        data object NotXiaozhi : BeforeSave

        /** 小智模式但设备未连接/取不到真实 MAC:拦截保存(绝不回退手机侧标识,也绝不猜)。 */
        data object NoDevice : BeforeSave

        /** 已绑定当前设备:直接保存生效,不重复绑定。 */
        data class AlreadyBound(val mac: String) : BeforeSave

        /** 从未绑定,或换了设备(存的是别的 MAC):先走绑定流程,成功才落盘。 */
        data class NeedBind(val mac: String) : BeforeSave
    }

    /** 绑定流程结束后的落盘决策。 */
    enum class AfterBind { Persist, KeepOldConfig }

    /**
     * @param gatewayType 保存后生效的网关类型(见 [XiaozhiIdentity.GATEWAY_XIAOZHI]);非小智 → [BeforeSave.NotXiaozhi]
     * @param deviceAddress 已连接设备的蓝牙地址(原始值);空/null = 没连接设备
     * @param boundMac 已绑定设备的 MAC(来自 [XiaozhiBinding.boundMac]);空/null = 从未绑定
     */
    fun beforeSave(gatewayType: String?, deviceAddress: String?, boundMac: String?): BeforeSave {
        // 标识只有一处实现([XiaozhiIdentity.resolve]):这里按它的结果分流,不再自己判断类型/MAC 格式。
        return when (val id = XiaozhiIdentity.resolve(gatewayType, deviceAddress)) {
            // 非小智模式:匿名标识只用于识别,不构成「绑定」→ 直接放行(不拦保存)。
            XiaozhiIdentity.Resolution.Anonymous -> BeforeSave.NotXiaozhi
            is XiaozhiIdentity.Resolution.Unavailable -> BeforeSave.NoDevice
            is XiaozhiIdentity.Resolution.DeviceMac -> {
                val bound = boundMac?.trim().orEmpty()
                if (bound.isNotEmpty() && bound.equals(id.deviceId, ignoreCase = true)) {
                    BeforeSave.AlreadyBound(id.deviceId)
                } else {
                    BeforeSave.NeedBind(id.deviceId)
                }
            }
        }
    }

    /**
     * 绑定结果的唯一落盘条件:成功才落盘;失败/超时**保持原配置不变**。
     *
     * 显式成函数(而不是调用处直接 `if (ok)`)的理由:这条不变量与 [beforeSave] 一样是这个闸门的
     * 契约,单测可以在没有真机、没有网络的情况下把它钉住。
     */
    fun afterBind(activated: Boolean): AfterBind =
        if (activated) AfterBind.Persist else AfterBind.KeepOldConfig
}
