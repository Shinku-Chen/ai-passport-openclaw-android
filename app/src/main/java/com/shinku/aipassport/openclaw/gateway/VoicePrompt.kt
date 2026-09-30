package com.shinku.aipassport.openclaw.gateway

/**
 * 语音输入附加提示词(「回复约束提示词」)。
 *
 * 用户在设置页配置一段文本,通话时【自动附加到语音识别结果末尾】再发给网关
 * (例如「请用不超过 200 字回复,不要使用 emoji 表情,也不要使用 Markdown 表格。」)。
 * 语义约束:
 *  - 只作用于【语音输入】;对话框打字输入一律原样发送,绝不追加;
 *  - 追加用换行分隔(`input + "\n" + suffix`),让网关把提示词与用户内容区分开;
 *  - 提示词为空/全空白时不追加(用户清空即可关闭该行为);
 *  - 输入为空时只发提示词(避免发出一条以换行开头的消息);
 *  - 绝不截断网关回复(本类只处理上行文本)。
 *
 * 抽成纯函数(Kotlin/JVM 无 Android 依赖)便于单测,见 `VoicePromptTest`。
 */
object VoicePrompt {

    /**
     * 组合真正发给网关的文本。
     *
     * @param input STT 识别原文(或用户输入)
     * @param suffix 设置页配置的附加提示词
     * @param isVoice true = 语音输入(可追加);false = 文字输入(原样返回)
     */
    fun compose(input: String, suffix: String, isVoice: Boolean): String {
        if (!isVoice) return input
        val extra = suffix.trim()
        if (extra.isEmpty()) return input
        return if (input.isBlank()) extra else "$input\n$extra"
    }
}

/**
 * 「语音附加提示」的默认值:约束回复长度并禁用 emoji 表情。
 *
 * 设置页可改;用户清空后即不再追加任何内容。
 */
const val DEFAULT_VOICE_PROMPT_SUFFIX =
    "请用不超过 200 字回复，不要使用 emoji 表情，也不要使用 Markdown 表格。"
