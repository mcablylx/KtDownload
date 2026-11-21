package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.mcly99.dl.model.ProxyMode
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * P1：三态代理是下载、抓网页、播放、网盘 API、OkHttp 客户端唯一共用的口径。
 * 系统代理用可注入的 lambda 隔离本机环境，保证测试在 macOS / Windows / Linux 上结果一致。
 */
class NetworkProxyResolverTest {

    private val systemProxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress("127.0.0.1", 1080),
    )

    @Test
    fun direct_ignores_whatever_is_typed_in_manual_fields() {
        assertNull(resolveProxy(ProxyMode.DIRECT, "127.0.0.1", 8080) { systemProxy })
    }

    @Test
    fun manual_builds_an_http_proxy_from_host_and_port() {
        val proxy = resolveProxy(ProxyMode.MANUAL, "  127.0.0.1  ", 8080) { systemProxy }
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        val address = proxy?.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(8080, address.port)
    }

    @Test
    fun manual_rejects_invalid_host_or_port_instead_of_leaking_to_direct_proxy() {
        assertNull(resolveProxy(ProxyMode.MANUAL, "", 8080) { systemProxy })
        assertNull(resolveProxy(ProxyMode.MANUAL, "   ", 8080) { systemProxy })
        listOf(-1, 0, 65_536).forEach { port ->
            assertNull(resolveProxy(ProxyMode.MANUAL, "127.0.0.1", port) { systemProxy }, "port=$port")
        }
    }

    @Test
    fun system_ignores_manual_fields_and_keeps_the_detected_proxy_type() {
        val proxy = resolveProxy(ProxyMode.SYSTEM, "should-not-be-used", 9999) { systemProxy }
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        assertEquals(1080, (proxy?.address() as InetSocketAddress).port)
    }

    @Test
    fun system_without_a_detected_proxy_falls_back_to_direct() {
        assertNull(resolveProxy(ProxyMode.SYSTEM, "127.0.0.1", 8080) { null })
    }

    @Test
    fun detector_failure_is_treated_as_direct_not_a_crash() {
        assertNull(resolveProxy(ProxyMode.SYSTEM, "", 0) { error("scutil unavailable") })
    }

    @Test
    fun legacy_proxyOrNull_contract_is_unchanged() {
        assertNull(proxyOrNull(false, "127.0.0.1", 8080))
        assertEquals(8080, ((proxyOrNull(true, "127.0.0.1", 8080))?.address() as InetSocketAddress).port)
    }
}
