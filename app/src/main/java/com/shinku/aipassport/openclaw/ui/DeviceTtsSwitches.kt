package com.shinku.aipassport.openclaw.ui

import com.shinku.aipassport.openclaw.stt.XiaozhiIdentity

/**
 * 两处「朗读」开关的**同源显示状态**(纯逻辑,JVM 单测钉住)。
 *
 * 背景:「设备朗读」与「播放小智语音」是**同一个设置** —— `GatewaySettings.ttsEnabled` —— 的两个入口。
 * 小智模式下的音源是小智随会话下发的 opus(这条路上**没有**本地合成),所以「设备朗读」在小智模式下
 * 就是「用小智的声音在设备上朗读」。做成两个独立开关会互相打架(同一件事两处不一致),因此:
 *  - **一个状态**:两处开关的勾选状态都只取同一个 `tts_enabled`,任一处置位后另一处立刻一致
 *    (渲染与回写见 `SettingsFragment.renderTtsSwitches` / `onTtsSwitchToggled`,这里给出两处该显示成什么);
 *  - **一个门控**:本机系统 TTS 不可用时两处一起置灰,不出现一灰一亮的分叉 —— 但**值仍然是开**
 *    (勾选状态只由 `tts_enabled` 决定,本机能力不参与);
 *  - **小智 AI 下一定可点**:小智的音源是它自己下发的音频,**不经过本机合成**,所以「本机合不成语音」
 *    不该限制这个开关(真机 bug:探测到本机不支持合成就把 `tts_enabled` 写成 false 并置灰,
 *    小智音色因此一句都没下发);
 *  - **可见性只由网关类型决定**:那行开关只在「小智 AI」下出现 —— 其余四种网关的「设备朗读」
 *    念的是手机本地合成的音频,与「用小智的声音播」不是同一件事。
 *
 * 不依赖 Android,可在 JVM 单测里直接覆盖(见 `DeviceTtsSwitchesTest`)。
 */
object DeviceTtsSwitches {

    /** 开关标题(作者原话:网关设置里选「小智 AI」时显示的那一项)。 */
    const val XIAOZHI_TITLE = "播放小智语音"

    /**
     * 开关下方的小字说明:讲清这是谁的声音、它与「设备朗读」是同一个开关(免得用户在两处各拨一次)、
     * 以及**这条通路不需要本机合成**(本机 TTS 不可用时这里照样能开)。
     */
    const val XIAOZHI_HINT =
        "用小智的声音在设备上朗读；与「对话设置 → 设备朗读」是同一个开关；" +
            "音频由小智下发，不需要本机合成"

    /** 入口名(只进日志):用户拨的是哪一处,日志里要能对照「两处是同一个设置」。 */
    const val ENTRY_CHAT = "设备朗读"
    const val ENTRY_XIAOZHI = "播放小智语音"

    /** 两处开关此刻该显示的样子。 */
    data class View(
        /** 两处**共同**的勾选状态:唯一来源 `tts_enabled`,两处永远同值。 */
        val checked: Boolean,
        /** 「网关设置 → 小智 AI」那一行是否出现。 */
        val xiaozhiRowVisible: Boolean,
        /**
         * 两处开关是否可点(两处同值)。**只由「本机合成能不能用」与「是否小智 AI」决定**,
         * 与勾选状态完全解耦:本机合成不可用 → 置灰;当前网关是小智 AI → 一定可点
         * (小智的音频由云端下发,不需要本机合成)。
         */
        val enabled: Boolean,
    )

    /**
     * 「网关设置 → 小智 AI」那一行是否出现。
     *
     * 「是不是小智 AI」由**唯一**判定来源 [XiaozhiIdentity.isXiaozhi] 给出(与识别通道/绑定/状态行同源,
     * 含大小写与空白归一化),不在这里另写一份字符串比较;类型按**下拉框当前选中**的那个 ——
     * 选中小智 AI 就立刻能看到这一行,不必先保存。
     */
    fun xiaozhiRowVisible(gatewayType: String?): Boolean = XiaozhiIdentity.isXiaozhi(gatewayType)

    /**
     * 由**唯一来源**算出两处开关该显示成什么样。
     *
     * @param gatewayType 当前选中的网关类型(决定那一行是否出现)
     * @param ttsEnabled `GatewaySettings.ttsEnabled`:两处共用的唯一状态(**只影响 checked**)
     * @param ttsSupported 本机系统 TTS 探测结论(true = 可用;见 `SettingsFragment.applyTtsAvailability`)。
     *   **只影响 enabled**,绝不参与 checked —— 本机合不成语音不等于用户关掉了朗读开关。
     */
    fun view(
        gatewayType: String?,
        ttsEnabled: Boolean,
        ttsSupported: Boolean = true,
    ): View = View(
        checked = ttsEnabled,
        xiaozhiRowVisible = xiaozhiRowVisible(gatewayType),
        enabled = switchEnabled(gatewayType, ttsSupported),
    )

    /**
     * 开关能不能点:本机合成可用 → 可点;否则**只有小智 AI 可点**(它的音频来自小智,不经本机合成)。
     *
     * 与 [View.checked] 完全解耦:置灰只是「这里点了也白点」的信息性提示,不改用户/默认的设置值。
     */
    fun switchEnabled(gatewayType: String?, ttsSupported: Boolean): Boolean =
        ttsSupported || xiaozhiRowVisible(gatewayType)

    /**
     * 本机合成不可用时开关下方的提示文案(放在「对话设置 → 设备朗读」那一行下)。
     *
     * 两条信息缺一不可：① 为什么置灰(带可读原因);② **不只是没救** —— 小智 AI 下仍可开启,
     * 由小智的声音朗读,不需要本机合成(真机 bug 的根因就是这里只说了「开关已关闭且不可打开」,
     * 用户以为整条朗读通路都废了)。
     */
    fun unsupportedHint(reason: String): String =
        "\u26a0\ufe0f 本机不支持合成语音：$reason。本机朗读（手机合成 → 下发）已置灰；" +
            "用「小智 AI」时这个开关仍可开启——由小智的声音朗读，不需要本机合成。"

    /**
     * 「网关设置 → 小智 AI」那一行下方的提示文案。
     *
     * 本机合成不可用时**也要把原因写出来**（否则用户在小智分组里看到「与设备朗读是同一个开关」,
     * 会以为那边置灰了这里也开不了）:明说这一行走的是小智自己的声音,不受本机能力影响。
     */
    fun xiaozhiHint(unsupportedReason: String?): String =
        if (unsupportedReason == null) XIAOZHI_HINT
        else "$XIAOZHI_HINT（本机不支持合成语音：$unsupportedReason；这一行仍可开启）"
}
