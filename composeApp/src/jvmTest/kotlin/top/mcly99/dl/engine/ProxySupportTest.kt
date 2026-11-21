package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * D-36：下载引擎与「抓网页」共用的代理判定。
 *
 * 两处共用一处规则，任何一侧写歪都会变成「下载走代理、抓页面直连」这种难查的问题。
 */
class ProxySupportTest {

    @Test
    fun disabled_or_blank_host_means_direct_connection() {
        assertNull(proxyOrNull(false, "127.0.0.1", 8080))
        assertNull(proxyOrNull(true, "", 8080))
        assertNull(proxyOrNull(true, "   ", 8080))
    }

    @Test
    fun out_of_range_ports_are_rejected() {
        listOf(-1, 0, 65_536, Int.MAX_VALUE).forEach { port ->
            assertNull(proxyOrNull(true, "127.0.0.1", port), "port=$port 应视为直连")
        }
    }

    @Test
    fun boundary_ports_are_accepted() {
        assertNotNull(proxyOrNull(true, "127.0.0.1", 1))
        assertNotNull(proxyOrNull(true, "127.0.0.1", 65_535))
    }

    @Test
    fun valid_config_builds_an_http_proxy_with_trimmed_host() {
        // 用 localhost：字面量解析，不触发外部 DNS
        val proxy = proxyOrNull(true, "  localhost  ", 8080)
        assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("localhost", address.hostString)
        assertEquals(8080, address.port)
    }
}
