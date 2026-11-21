package top.mcly99.dl.data.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * P1：HTTP/2 开关与三态代理装配到 OkHttp 客户端的契约。
 *
 * [HttpClients] 是全局单例，每个用例结束后恢复默认，避免污染其它测试。
 */
class HttpClientsTest {

    @AfterTest
    fun restoreDefaults() {
        HttpClients.setHttp2Enabled(false)
        HttpClients.setProxy(null as Proxy?)
    }

    private fun httpProxy(port: Int = 8080) =
        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

    @Test
    fun http2_off_limits_both_clients_to_http11() {
        HttpClients.setHttp2Enabled(false)
        assertEquals(listOf(Protocol.HTTP_1_1), HttpClients.apiClient().protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), HttpClients.downloadClient().protocols)
    }

    @Test
    fun http2_on_allows_h2_on_both_clients() {
        HttpClients.setHttp2Enabled(true)
        val expected = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        assertEquals(expected, HttpClients.apiClient().protocols)
        assertEquals(expected, HttpClients.downloadClient().protocols)
    }

    @Test
    fun toggling_http2_invalidates_cached_clients() {
        val before = HttpClients.apiClient()
        HttpClients.setHttp2Enabled(true)
        val after = HttpClients.apiClient()
        assertNotSame(before, after)
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), after.protocols)
    }

    @Test
    fun proxy_is_injected_into_both_clients() {
        val proxy = httpProxy()
        HttpClients.setProxy(proxy)
        assertEquals(proxy, HttpClients.apiClient().proxy)
        assertEquals(proxy, HttpClients.downloadClient().proxy)
    }

    @Test
    fun socks_proxy_type_survives_injection() {
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
        HttpClients.setProxy(socks)
        assertEquals(Proxy.Type.SOCKS, HttpClients.apiClient().proxy?.type())
        assertEquals(1080, (HttpClients.apiClient().proxy?.address() as InetSocketAddress).port)
    }

    @Test
    fun provider_changes_rebuild_clients_without_a_restart() {
        // 「跟随系统」：30 秒缓存到期或系统代理变化后，客户端必须跟着换
        var current: Proxy? = null
        HttpClients.setProxyProvider { current }
        assertNull(HttpClients.apiClient().proxy)

        val detected = httpProxy(7890)
        current = detected
        val rebuilt = HttpClients.apiClient()
        assertEquals(detected, rebuilt.proxy)
    }

    @Test
    fun legacy_host_port_overload_still_rejects_invalid_values() {
        HttpClients.setProxy(null, 8080)
        assertNull(HttpClients.apiClient().proxy)
        HttpClients.setProxy("   ", 8080)
        assertNull(HttpClients.apiClient().proxy)
        HttpClients.setProxy("127.0.0.1", 0)
        assertNull(HttpClients.apiClient().proxy)
        HttpClients.setProxy("127.0.0.1", 8080)
        assertEquals(8080, (HttpClients.apiClient().proxy?.address() as InetSocketAddress).port)
    }
}
