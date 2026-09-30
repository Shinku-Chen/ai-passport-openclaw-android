package com.shinku.aipassport.openclaw.gateway

/**
 * 「自定义 OpenAI 兼容」请求路径的纯逻辑解析（无 Android 依赖，可 JVM 单测）。
 *
 * 设置页允许用户**直接填完整请求路径**，也兼容旧的「只填基址前缀」用法：
 *  - 填 `/v1`（旧默认）→ 自动补成 `/v1/chat/completions`，行为与历史版本一致；
 *  - 填 `/openai/v1/chat/completions` → 原样使用，适配任意布局的服务；
 *  - 填 `openai/v1`（缺前导斜杠）→ 归一化为 `/openai/v1/chat/completions`。
 */
object OpenAiPath {

    /** 空/全空白输入时的默认完整 chat 路径（即 OpenAI 兼容的 `/v1/chat/completions`）。 */
    const val DEFAULT_CHAT_PATH = "/v1/chat/completions"

    /** chat 端点后缀：用户填「前缀」时自动补上它。 */
    const val CHAT_SUFFIX = "/chat/completions"

    /** 探活用的模型列表端点后缀。 */
    private const val MODELS_SUFFIX = "/models"

    private val SLASHES = Regex("/+")

    /**
     * 把用户填的值解析成完整 chat 路径。
     *
     * 规则：
     *  1. 空/全空白 → [DEFAULT_CHAT_PATH]。
     *  2. 原文（查询串之前的部分）已以 [CHAT_SUFFIX] 结尾（允许结尾多余斜杠）→ 原样，只做斜杠归一化。
     *  3. 否则视为「前缀」：归一化后追加 [CHAT_SUFFIX]。
     *     例：`/v1` → `/v1/chat/completions`；`openai/v1` → `/openai/v1/chat/completions`；
     *     `/` → `/chat/completions`。
     *  4. 带查询串（`?...`）时，后缀判定与拼接只看 `?` 之前的部分，查询串原样保留在末尾。
     *     例：`/v1?key=1` → `/v1/chat/completions?key=1`。
     *  5. 归一化：去首尾空白、合并连续 `/`、确保以 `/` 开头、结尾不留 `/`（查询串除外）。
     */
    fun resolveChatPath(raw: String): String {
        if (raw.isBlank()) return DEFAULT_CHAT_PATH
        val (path, query) = splitQuery(raw)
        val normalized = normalizePath(path)
        val chatPath = if (normalized.endsWith(CHAT_SUFFIX)) normalized else normalized + CHAT_SUFFIX
        return chatPath + query
    }

    /**
     * 由 chat 路径推导 `GET …/models` 探活路径。
     *
     * 规则：
     *  1. 结尾（查询串之前的部分）是 [CHAT_SUFFIX] → 换成 `/models`。
     *     例：`/v1/chat/completions` → `/v1/models`。
     *  2. 结尾不是它 → 用「其所在目录」+ `/models`。
     *     例：`/openai/v1/chat` → `/openai/v1/models`。
     *  3. 查询串原样保留在末尾：`/v1/chat/completions?key=1` → `/v1/models?key=1`。
     */
    fun modelsPath(chatPath: String): String {
        val (rawPath, query) = splitQuery(chatPath)
        val path = normalizePath(rawPath)
        val base = if (path.endsWith(CHAT_SUFFIX)) {
            path.removeSuffix(CHAT_SUFFIX)
        } else {
            val idx = path.lastIndexOf('/')
            if (idx <= 0) "" else path.substring(0, idx)
        }
        return base + MODELS_SUFFIX + query
    }

    /** 以第一个 `?` 为界拆成 (路径, 查询串)；没有查询串时 query 为空串。 */
    private fun splitQuery(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf('?')
        return if (idx < 0) trimmed to "" else trimmed.substring(0, idx) to trimmed.substring(idx)
    }

    /** 去空白、合并连续 `/`、补前导 `/`、去结尾 `/`；根路径（`/`、空串）归一化为空串。 */
    private fun normalizePath(path: String): String {
        var p = path.trim().replace(SLASHES, "/")
        if (!p.startsWith("/")) p = "/$p"
        return p.trimEnd('/')
    }
}
