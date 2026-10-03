package com.shinku.aipassport.openclaw.stt

import android.content.Context

/**
 * 小智设备绑定的持久化 + 保存前的闸门决策。
 *
 * 为什么需要它:小智云按 Device-Id(= 已连接设备的蓝牙 MAC)登记设备,只有绑定过的设备才能用
 * 识别/对话链路。因此「保存网关类型 = 小智 AI」不能像 Echo 那样直接落盘,必须先把**当前设备**
 * 绑定好(见 `SettingsFragment.saveXiaozhi`),否则用户会得到一个「看着已保存、实际用不了」的网关。
 *
 * 决策逻辑抽成不依赖 Android 的 [XiaozhiBindGate],便于 JVM 单测直接覆盖
 * 「未绑定 → 先绑定 / 已绑定 → 直接保存 / 换了设备 → 重新绑定 / 没设备 → 拦截 / 绑定失败 → 不落盘」。
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
 */
object XiaozhiBindGate {

    /** 保存「小智 AI」之前的走向。 */
    sealed interface BeforeSave {
        /** 设备未连接/取不到真实 MAC:拦截保存(绝不回退手机侧标识,也绝不猜)。 */
        data object NoDevice : BeforeSave

        /** 已绑定当前设备:直接保存生效,不重复绑定。 */
        data object AlreadyBound : BeforeSave

        /** 从未绑定,或换了设备(存的是别的 MAC):先走绑定流程,成功才落盘。 */
        data class NeedBind(val mac: String) : BeforeSave
    }

    /** 绑定流程结束后的落盘决策。 */
    enum class AfterBind { Persist, KeepOldConfig }

    /**
     * @param deviceMac 已连接设备的蓝牙 MAC(已归一化或原始值均可);空/null = 没连接设备
     * @param boundMac 已绑定设备的 MAC(来自 [XiaozhiBinding.boundMac]);空/null = 从未绑定
     */
    fun beforeSave(deviceMac: String?, boundMac: String?): BeforeSave {
        val mac = deviceMac?.trim().orEmpty()
        if (mac.isEmpty()) return BeforeSave.NoDevice
        val bound = boundMac?.trim().orEmpty()
        return if (bound.isNotEmpty() && bound.equals(mac, ignoreCase = true)) {
            BeforeSave.AlreadyBound
        } else {
            BeforeSave.NeedBind(mac)
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
