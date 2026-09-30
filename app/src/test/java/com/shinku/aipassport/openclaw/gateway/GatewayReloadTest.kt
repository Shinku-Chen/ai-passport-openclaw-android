package com.shinku.aipassport.openclaw.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存后是否需要重载运行中的网关适配器」的判定逻辑。
 *
 * 修 bug 回归:服务只在启动时读一次设置,原来只有网关类型变化才重启,于是只改 host/端口/token
 * 时保存成功、prefs 也写了,服务却还在用旧配置(真机表现:顶部一直「网关未配置」)。
 * 这里断言:这类「只改一个连接字段」也必须判定为需要重载。
 */
class GatewayReloadTest {

    private fun openclaw(
        host: String = "192.168.1.10",
        port: String = "8035",
        token: String = "t",
    ) = GatewayConfigSnapshot.OpenClaw(
        OpenClawConfig(
            host = host,
            port = port,
            useTls = true,
            token = token,
            wsPath = "/message/messages/ws",
        ),
    )

    private fun hermes(host: String, port: Int = 8642) =
        GatewayConfigSnapshot.Hermes(HermesConfig(host = host, port = port))

    private fun openai(host: String, model: String = "gpt-4o-mini") =
        GatewayConfigSnapshot.OpenAi(OpenAiConfig(host = host, port = 8080, model = model))

    @Test
    fun null_previous_always_needs_reload() {
        assertTrue(needsGatewayReload(null, openclaw()))
        assertTrue(needsGatewayReload(null, GatewayConfigSnapshot.Echo))
    }

    @Test
    fun identical_config_does_not_need_reload() {
        assertFalse(needsGatewayReload(openclaw(), openclaw()))
        assertFalse(needsGatewayReload(hermes("a.local"), hermes("a.local")))
    }

    @Test
    fun host_only_change_needs_reload() {
        assertTrue(needsGatewayReload(openclaw(host = "a.local"), openclaw(host = "b.local")))
    }

    @Test
    fun port_only_change_needs_reload() {
        assertTrue(needsGatewayReload(openclaw(port = "8035"), openclaw(port = "9035")))
    }

    @Test
    fun token_only_change_needs_reload() {
        assertTrue(needsGatewayReload(openclaw(token = "old"), openclaw(token = "new")))
    }

    @Test
    fun hermes_host_and_port_change_need_reload() {
        assertTrue(needsGatewayReload(hermes("a.local"), hermes("b.local")))
        assertTrue(needsGatewayReload(hermes("a.local", 8642), hermes("a.local", 9999)))
    }

    @Test
    fun openai_host_or_model_change_needs_reload() {
        assertTrue(needsGatewayReload(openai("a.local"), openai("b.local")))
        assertTrue(needsGatewayReload(openai("a.local"), openai("a.local", model = "other")))
    }

    @Test
    fun switching_gateway_type_needs_reload() {
        assertTrue(needsGatewayReload(openclaw(), hermes("a.local")))
        assertTrue(needsGatewayReload(GatewayConfigSnapshot.Echo, openclaw()))
    }
}
