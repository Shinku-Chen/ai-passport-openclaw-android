package com.shinku.aipassport.openclaw.stt

import android.content.Context

/**
 * 小智设备绑定的本地记录 + 保存前的闸门决策。
 *
 * 为什么需要它:网关类型是「小智 AI」时,小智云按 Device-Id(= 已连接设备的蓝牙 MAC)登记设备,
 * 只有绑定过的设备才能用识别/对话链路。因此「保存网关类型 = 小智 AI」不能像 Echo 那样直接落盘,
 * 必须先把**当前设备**弄成云端可用(见 `SettingsFragment.saveSettings` 的闸门),否则用户会得到
 * 一个「看着已保存、实际用不了」的网关。
 *
 * **本地 `bound_mac` 不是判据**:它只是「这台设备曾在本机绑定过」的**提示**,可能过时(换账号、
 * 在 xiaozhi.me 删过设备、换了手机)。所以保存/手动激活时**一律真的查一次云端激活状态**
 * ([XiaozhiActivator.queryCloud]),以云端为准;本地记录只在「云端已激活」的提示文案里提一句,
 * 并在成功保存后同步成当前设备(见 [XiaozhiBindGate.decide] / `SettingsFragment`)。
 *
 * 注意:其它网关类型**不需要**绑定(它们只把小智当识别引擎,Device-Id 是匿名标识)——
 * 闸门会直接放行,保存不受「设备是否在线」影响,也**不会发任何网络请求**。
 *
 * 决策逻辑全部抽成不依赖 Android 的 [XiaozhiBindGate],便于 JVM 单测覆盖
 * 「(云端 未激活/已激活/查询失败) × (本地记录 无/同设备/别的设备) × (网关 小智/非小智)」全部组合。
 */
class XiaozhiBinding(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 本机记录的「上次成功绑定/激活的设备 MAC」(小智 Device-Id 格式);null = 本机没有记录。
     *
     * **纯提示**:不参与「要不要绑定/要不要拦截保存」的判断(那只看云端)。为 null 或与当前设备不一致
     * 都不会拦住用户;成功保存后会同步成当前设备,避免界面提示与实际不符。
     */
    var boundMac: String?
        get() = prefs.getString(KEY_BOUND_MAC, null)
        set(value) = prefs.edit().putString(KEY_BOUND_MAC, value?.trim()).apply()

    private companion object {
        const val PREFS = "xiaozhi_binding"
        const val KEY_BOUND_MAC = "bound_mac"
    }
}

/**
 * 「保存小智 AI」这道闸门的**纯决策**(不碰 Android、不发请求)。
 *
 * 流程分两段:
 *  1. [beforeSave] —— 发任何网络请求**之前**的走向:非小智直接放行;小智没设备就拦;
 *     小智有设备则**必须去查一次云端**([BeforeSave.QueryCloud])。本地 `bound_mac` 不在这里参与判断。
 *  2. [decide] —— 云端查回来之后按 [CloudState] 分流:已激活 → 允许保存但**必须提示**;
 *     未激活 → 发 6 位绑定码并轮询授权;查询失败 → **不落盘** + 可读原因。
 *     绑定跑完之后的落盘条件由 [afterBind] 单独表达。
 *
 * **只对「小智 AI」生效**:其余网关拿 [BeforeSave.NotXiaozhi] / [Decision.NotXiaozhi] 直接放行,
 * 既不触发云端查询与绑定,也不会因为「设备未连接」被拦下(它们只把小智当识别引擎,见 [XiaozhiIdentity])。
 * 「该用哪个标识」不在这里判断 —— 统一调 [XiaozhiIdentity.resolve]。
 */
object XiaozhiBindGate {

    /**
     * 云端激活状态(每次保存/手动激活都**真的查一次**小智 OTA 得到的结果,见 [XiaozhiActivator.queryCloud])。
     *
     * 三态就是全部可能:已激活(设备可用)/ 未激活需绑定 / 查询失败(网络或服务器问题)。
     */
    enum class CloudState {
        /** 云端已激活:设备可用,保存允许,但必须明确告知用户(绝不静默保存)。 */
        Activated,

        /** 云端未激活:必须走 6 位绑定码 + 网页绑定 + 轮询授权,成功才落盘。 */
        NeedsBinding,

        /** 查询失败/超时/无网:不落盘,给可读原因,下次保存时重试。 */
        QueryFailed,
    }

    /** 保存网关设置之前(发任何网络请求之前)的走向。 */
    sealed interface BeforeSave {
        /**
         * 当前网关类型不是「小智 AI」:小智不做绑定 —— 保存流程**原样继续**,与设备是否在线无关,
         * 也不查云端。
         */
        data object NotXiaozhi : BeforeSave

        /** 小智模式但设备未连接/取不到真实 MAC:拦截保存(绝不回退手机侧标识,也绝不猜)。 */
        data object NoDevice : BeforeSave

        /** 小智模式且拿到了设备 MAC:**必须先查一次云端激活状态**(结果交给 [decide])。 */
        data class QueryCloud(val mac: String) : BeforeSave
    }

    /** 查完云端之后,这次保存/激活该怎么走。 */
    sealed interface Decision {
        /** 非小智:直接走通用校验-落盘闸门,不查云端、不绑定。 */
        data object NotXiaozhi : Decision

        /** 小智但取不到设备 MAC:不落盘(原因见 [XiaozhiIdentity.NO_DEVICE_REASON])。 */
        data object NoDevice : Decision

        /**
         * 云端已激活:保存**允许**(设备可用),但必须把 [notice] 明确展示给用户 ——
         * 不得静默直接保存,也不按住用户不放。
         */
        data class Activated(val mac: String, val notice: String) : Decision

        /** 云端未激活:发 6 位绑定码 + 网页引导 + 轮询授权,成功才落盘(见 [afterBind])。 */
        data class NeedBind(val mac: String) : Decision

        /** 查询失败/超时/无网:**不落盘** + 可读原因,下次保存重试。 */
        data class QueryFailed(val reason: String) : Decision
    }

    /** 绑定流程结束后的落盘决策。 */
    enum class AfterBind { Persist, KeepOldConfig }

    /**
     * 云端查询**之前**的走向。
     *
     * @param gatewayType 保存后生效的网关类型(见 [XiaozhiIdentity.GATEWAY_XIAOZHI]);非小智 → [BeforeSave.NotXiaozhi]
     * @param deviceAddress 已连接设备的蓝牙地址(原始值);空/null = 没连接设备
     */
    fun beforeSave(gatewayType: String?, deviceAddress: String?): BeforeSave {
        // 标识只有一处实现([XiaozhiIdentity.resolve]):这里按它的结果分流,不再自己判断类型/MAC 格式。
        return when (val id = XiaozhiIdentity.resolve(gatewayType, deviceAddress)) {
            // 非小智模式:匿名标识只用于识别,不构成「绑定」→ 直接放行(不查云端、不拦保存)。
            XiaozhiIdentity.Resolution.Anonymous -> BeforeSave.NotXiaozhi
            is XiaozhiIdentity.Resolution.Unavailable -> BeforeSave.NoDevice
            // 小智模式且拿到真 MAC:**一律**要查云端(不看本地 bound_mac —— 它可能过时)。
            is XiaozhiIdentity.Resolution.DeviceMac -> BeforeSave.QueryCloud(id.deviceId)
        }
    }

    /**
     * 云端查回来之后的决策(纯函数)。
     *
     * [boundMac] **不决定走向**,只用来在「云端已激活」的提示里说明本地记录是否过时
     * (见 [alreadyActivatedNotice]);三态 × 三种本地记录给出同一位置的决策,这条不变量由单测钉住。
     *
     * @param gatewayType 保存后生效的网关类型;非小智 → [Decision.NotXiaozhi](无论云端状态如何)
     * @param deviceAddress 已连接设备的蓝牙地址(原始值);小智模式下取不到 → [Decision.NoDevice]
     * @param cloud 云端激活状态;null(没查到/调用方漏查)一律按查询失败处理 —— 绝不放行落盘
     * @param boundMac 本机记录的「上次绑定设备」,仅用于提示文案;不参与分流
     * @param detail 查询失败时的具体原因([XiaozhiActivator.CloudQuery.detail]);空则用通用文案
     */
    fun decide(
        gatewayType: String?,
        deviceAddress: String?,
        cloud: CloudState?,
        boundMac: String? = null,
        detail: String? = null,
    ): Decision = when (val plan = beforeSave(gatewayType, deviceAddress)) {
        BeforeSave.NotXiaozhi -> Decision.NotXiaozhi
        BeforeSave.NoDevice -> Decision.NoDevice
        is BeforeSave.QueryCloud -> when (cloud) {
            CloudState.Activated -> Decision.Activated(plan.mac, alreadyActivatedNotice(plan.mac, boundMac))
            CloudState.NeedsBinding -> Decision.NeedBind(plan.mac)
            CloudState.QueryFailed, null ->
                Decision.QueryFailed(detail?.trim()?.takeIf { it.isNotEmpty() } ?: queryFailedReason(plan.mac))
        }
    }

    /**
     * 绑定结果的唯一落盘条件:成功才落盘;失败/超时**保持原配置不变**。
     *
     * 显式成函数(而不是调用处直接 `if (ok)`)的理由:这条不变量与 [beforeSave]/[decide] 一样是这个
     * 闸门的契约,单测可以在没有真机、没有网络的情况下把它钉住。
     */
    fun afterBind(activated: Boolean): AfterBind =
        if (activated) AfterBind.Persist else AfterBind.KeepOldConfig

    // ---- 文案(调用方直接展示,不再各写一份) ----

    /**
     * 「云端已激活」的提示:必须说清设备已可用、保存是允许的,并给出「要换账号怎么重来」的下一步。
     *
     * [boundMac] 与当前设备不一致时补一句「本机旧记录已按云端校正」——避免界面提示与实际不符。
     */
    fun alreadyActivatedNotice(mac: String, boundMac: String?): String {
        val stale = boundMac?.trim().orEmpty()
            .takeIf { it.isNotEmpty() && !it.equals(mac, ignoreCase = true) }
        return buildString {
            appendLine("该设备（MAC $mac）已在云端激活，无需重新绑定。")
            if (stale != null) {
                appendLine("本机记录的上次绑定设备是 $stale，已按云端与当前设备校正。")
            }
            appendLine("设置已保存，设备可以直接使用。")
            append("如果你想换一个 xiaozhi.me 账号绑定：先在 xiaozhi.me 删除该设备，再点「激活小智设备」重新激活。")
        }
    }

    /** 查询失败/超时/无网时的可读原因(不含具体异常文本,具体原因由调用方用 `detail` 优先覆盖)。 */
    fun queryFailedReason(mac: String): String =
        "查询小智云端失败：没拿到设备（MAC $mac）的激活状态（网络不可用/超时/服务器无响应）。" +
            "本次未保存，原配置继续生效；请在网络正常后再点「保存网关设置」重试。"
}
