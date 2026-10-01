package com.shinku.aipassport.openclaw.gateway

/**
 * 「这台网关不支持某个辅助查询」的记忆(纯逻辑,无 Android 依赖,JVM 单测直接覆盖)。
 *
 * 为什么需要:网关能力因版本/部署而异(实测一台 OpenClaw 上有 `usage`/`skills`/`channels.list`
 * 全是 `unknown method: …`,另一台上又有)。概览页每次进页都问一遍,结果就是**每次进页都看到一条报错**;
 * 而这类「能力缺失」根本不是故障(见 [isUnknownMethod] / [isBenignQueryError])。
 *
 * 规则:
 *  - 命中「不支持」的方法记下来(按**配置指纹**索引),同一台网关的同一次会话内:
 *    隐藏对应分区、**不再重复请求**;
 *  - 配置指纹变化(换网关类型/host/端口/token/路径)时整表作废 —— 新网关要重新探测,
 *    不能把上一台网关的能力结论套上去。
 *
 * 只在 UI 线程用,`synchronized` 只是廉价保险。
 */
class QuerySupport {

    private val lock = Any()

    /** 当前记忆对应的配置指纹(见 [OpenClawGatewayRegistry.keyOf])。 */
    private var configKey: String? = null

    private var unsupported: MutableSet<String> = mutableSetOf()

    /** 该配置下是否已知「网关不支持这个方法」。指纹不同或未记录过 → false(需要重新探测)。 */
    fun isUnsupported(key: String, method: String): Boolean = synchronized(lock) {
        setFor(key).contains(method)
    }

    /** 记住「该配置下网关不支持这个方法」。 */
    fun rememberUnsupported(key: String, method: String) {
        synchronized(lock) { setFor(key).add(method) }
    }

    /** 忘掉全部记忆(重新探测)。 */
    fun clear() = synchronized(lock) {
        configKey = null
        unsupported = mutableSetOf()
    }

    /** 取该指纹对应的集合;指纹变化时作废旧记忆(新网关要重新探测)。 */
    private fun setFor(key: String): MutableSet<String> {
        if (configKey != key) {
            configKey = key
            unsupported = mutableSetOf()
        }
        return unsupported
    }

    companion object {
        /**
         * 进程级共享:概览页离开再进来(Activity/Fragment 重建)仍记得「这台网关不支持这个方法」,
         * 不必再请求一次、也不再报错。App 重启即重新探测。
         */
        val shared = QuerySupport()
    }
}
