package com.shinku.aipassport.openclaw.stt

/**
 * 「保存网关设置」按钮下方那行**绑定流程状态**的纯逻辑:可见性 / 文案 / 保存按钮复位。
 *
 * 真机问题(本对象钉住的回归点):点「保存网关设置」后按钮一直停在「查询小智云端…」且点不动 ——
 * 「查云端」没有硬超时,而复位按钮的代码只写在正常路径的最后一行(异常/超时直接跳过)。
 * 把「阶段 → 界面」抽成纯函数之后,可以单测钉住两条不变量:
 *  1. **任何终局**(已激活 / 绑定成功 / 失败 / 查询超时)保存按钮都必须回到 [IDLE_BUTTON_TEXT] + 可点
 *     (见 [button]);只有「正在跑流程」的两个阶段才置灰防重复点击,且它们**必有出口**;
 *  2. 这一行只在**小智 AI 模式**且**有流程状态**时显示:空闲/非小智(不查云端、不绑定)→ 整行隐藏。
 *
 * 不依赖 Android,可在 JVM 单测里直接覆盖(见 `XiaozhiSaveStatusTest`)。
 */
object XiaozhiSaveStatus {

    /** 保存按钮的空闲文案:任何路径结束时都必须回到它。 */
    const val IDLE_BUTTON_TEXT = "保存网关设置"

    /** 查询云端硬超时的可读文案:明确「没保存」,并告诉用户下一步是重试(不是静默)。 */
    const val QUERY_TIMEOUT_REASON = "查询小智云端超时，请重试（未保存）"

    /** 失败原因缺失时的兜底文案:绝不出现「绑定失败:」后面什么都没有的行。 */
    const val FALLBACK_FAILED_REASON = "未取得失败原因，请重试"

    /** 流程阶段(界面只由它驱动,不再各写一处文案)。 */
    enum class Stage {
        /** 空闲:没在跑流程(这一行整行隐藏)。 */
        Idle,

        /** 正在查云端(按钮置灰显示「查询小智云端…」)。 */
        QueryingCloud,

        /** 云端未激活:已给出 6 位绑定码,在等用户在 xiaozhi.me 授权。 */
        WaitingBind,

        /** 云端已激活:闸门放行并已落盘。 */
        Activated,

        /** 云端未激活但走完绑定码 + 授权轮询,成功落盘。 */
        Bound,

        /** 失败(查云端失败 / 绑定失败或超时 / 其它异常):未落盘,原配置继续生效。 */
        Failed,

        /** 查云端硬超时:未落盘(与 [Failed] 分开,用户要做的下一步不同 —— 直接重试即可)。 */
        QueryTimeout,
    }

    /**
     * 一次保存流程的终局。只有 [Saved] / [Bound] 代表**已落盘**,其余都代表**未落盘**。
     */
    sealed interface Outcome {
        /** 云端已激活:闸门允许保存,已落盘。 */
        data class Saved(val mac: String) : Outcome

        /** 绑定成功(用户已在 xiaozhi.me 输入绑定码并授权):已落盘。 */
        data class Bound(val mac: String) : Outcome

        /** 失败:未落盘,[reason] 必须是可读原因。 */
        data class Failed(val reason: String) : Outcome

        /** 查云端硬超时:未落盘。 */
        data object QueryTimeout : Outcome
    }

    /** 这一行此刻的样子:visible=false 表示整行隐藏。 */
    data class Line(val visible: Boolean, val text: String = "")

    /** 保存按钮此刻的样子。 */
    data class Button(val text: String, val enabled: Boolean)

    /** 这一行只属于「小智 AI」这个网关类型(其余网关不查云端、不绑定,也没有绑定可谈)。 */
    fun isXiaozhiType(gatewayType: String?): Boolean =
        gatewayType == XiaozhiIdentity.GATEWAY_XIAOZHI

    /** 终局 → 阶段。 */
    fun stageOf(outcome: Outcome): Stage = when (outcome) {
        is Outcome.Saved -> Stage.Activated
        is Outcome.Bound -> Stage.Bound
        is Outcome.Failed -> Stage.Failed
        Outcome.QueryTimeout -> Stage.QueryTimeout
    }

    /**
     * 这一行该显示什么。
     *
     * 整行隐藏的三种情况:非小智模式、空闲(还没点过保存)、算出来的文案为空。
     *
     * @param gatewayType **本次保存要生效的网关类型**(调用方传下拉框里选中的那个,不是已落盘的类型)
     * @param mac 已连接设备的真 MAC(小智 Device-Id);用于「已激活/绑定成功」的结论行
     * @param code 云端下发的 6 位绑定码
     * @param reason 失败原因;只取首行(完整原因仍由弹窗展示)
     */
    fun line(
        gatewayType: String?,
        stage: Stage,
        mac: String? = null,
        code: String? = null,
        reason: String? = null,
    ): Line {
        if (!isXiaozhiType(gatewayType) || stage == Stage.Idle) return Line(false)
        val text = when (stage) {
            Stage.Idle -> ""
            Stage.QueryingCloud -> "正在查询小智云端…"
            // 弹窗可能被用户关掉:这行必须留下绑定码与「正在等授权」,用户据此继续
            Stage.WaitingBind ->
                "需要绑定：请在 xiaozhi.me 输入绑定码 ${code?.trim().orEmpty()}（等待授权…）"

            Stage.Activated -> "已激活：${mac?.trim().orEmpty()}（已保存）"
            Stage.Bound -> "绑定成功：${mac?.trim().orEmpty()}（已保存）"
            Stage.Failed ->
                "绑定失败：${firstLine(reason) ?: FALLBACK_FAILED_REASON}（未保存，原配置继续生效）"

            Stage.QueryTimeout -> QUERY_TIMEOUT_REASON
        }
        return if (text.isBlank()) Line(false) else Line(true, text)
    }

    /**
     * 保存按钮该显示什么。
     *
     * 只有「正在查云端 / 等待绑定」两个**进行中**阶段置灰防重复点击;其余阶段(含全部终局)
     * 一律 [IDLE_BUTTON_TEXT] + 可点 —— 流程必须始终有出口,否则就是真机上的「点了不返回、永远卡住」。
     */
    fun button(stage: Stage): Button = when (stage) {
        Stage.QueryingCloud -> Button("查询小智云端…", false)
        Stage.WaitingBind -> Button("等待小智绑定…", false)
        else -> Button(IDLE_BUTTON_TEXT, true)
    }

    /**
     * 该阶段是否「正在跑流程」(= 按钮置灰防重复点击)。
     *
     * 除了 [button] 自己,切网关类型时也要用它判断「能不能把那行状态清掉」:
     * 流程还在跑就只清显示会跟随后到来的终态打架。
     */
    fun isBusy(stage: Stage): Boolean = !button(stage).enabled

    /** 可读原因只取首行:这一行是单行提示;完整原因(可能多行)仍由弹窗展示。 */
    private fun firstLine(reason: String?): String? =
        reason?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
