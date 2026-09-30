package com.shinku.aipassport.openclaw.gateway

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OpenClaw 网关实例注册表:全进程复用同一个 [OpenClawGateway](同一份配置)。
 *
 * 为什么要复用(修复「每 ~6s 一次 `WS 失败`」):
 * 改造前顶部「刷新」探针、概览页、对话页、语音桥服务各自 `new OpenClawGateway(...)`,
 * 每建一个就新开一条 WS;网关侧看到同一设备身份/同一 sessionKey 的多条连接并互相顶掉,
 * 于是被顶掉的那条不断重连、不断失败,而正在收集的回复被 `onSocketDown` 提前判空。
 * 复用同一个实例后,全进程只有一条对话 WS,探针/监控只是读它的 `isReady()/lastError`。
 *
 * 引用计数语义:每次 [acquire] 加一,调用方用完 `adapter.close()`(或 [release])减一,
 * 计数归零才真正断开。因此 UI 页面销毁不会关掉前台服务正在用的健康 socket。
 *
 * 配置变化(host/端口/scheme/token/路径/自签开关/等待上限)会换 key:注册表真正关闭旧实例,
 * 新配置重新建链——例如设置页保存后重启服务时。
 */
object OpenClawGatewayRegistry {

    private val lock = Any()

    /** 当前共享实例的配置指纹。 */
    private var key: String? = null

    /** 当前共享实例。 */
    private var shared: OpenClawGateway? = null

    /**
     * 状态文案订阅者。
     * 共享实例只能有一个 `onStatus`,这里多播给所有订阅者:语音桥服务要播到顶部状态卡,
     * 而 UI 探针(先于服务构造共享实例)用的是空回调,不能因此丢掉服务的状态播报。
     */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 默认空回调,用于判断调用方是否真的关心状态。 */
    private val noop: (String) -> Unit = {}

    /**
     * 取(必要时新建)共享实例并加一次引用。
     *
     * @param context 仅用于读取设备身份/设置;内部只持有 applicationContext
     * @param config 显式配置(设置页草稿校验请改用 `GatewayFactory.createFromDraft`,不要污染共享实例)
     * @param onStatus 状态文案回调;可多次注册,用 [removeListener] 注销
     */
    fun acquire(
        context: Context,
        config: OpenClawConfig,
        onStatus: (String) -> Unit = noop,
    ): OpenClawGateway {
        if (onStatus !== noop) listeners.addIfAbsent(onStatus)
        synchronized(lock) {
            val fresh = keyOf(config)
            val current = shared
            if (current != null && fresh == key) {
                current.retain()
                return current
            }
            // 配置变了:旧实例必须真正断开(不能只减引用计数,它连的还是旧地址/旧 token)
            current?.shutdown()
            val created = OpenClawGateway(context.applicationContext, config) { message ->
                listeners.forEach { listener -> listener(message) }
            }
            key = fresh
            shared = created
            return created
        }
    }

    /** 释放一次引用(等价于 `gateway.close()`)。 */
    fun release(gateway: OpenClawGateway) = gateway.close()

    /** 服务销毁时注销自己的状态订阅,避免回调继续持有已停止的服务实例。 */
    fun removeListener(onStatus: (String) -> Unit) {
        listeners.remove(onStatus)
    }

    /** 配置指纹:任一字段变化都算另一条连接。 */
    fun keyOf(config: OpenClawConfig): String = listOf(
        config.host,
        config.port,
        config.useTls.toString(),
        config.token,
        config.wsPath,
        config.allowInsecureTls.toString(),
        config.replyTimeoutSeconds.toString(),
        // 会话名也影响连接语义(sessionKey);漏掉它会导致改会话名后仍复用旧实例
        config.normalizedSessionName(),
    ).joinToString("|")
}
