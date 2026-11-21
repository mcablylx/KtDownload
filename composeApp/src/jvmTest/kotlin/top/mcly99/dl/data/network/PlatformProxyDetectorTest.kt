package top.mcly99.dl.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.PlatformProxyDetector.Inspect
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 系统代理探测的纯解析契约（P1）。
 *
 * 全部测试只喂字符串，不读注册表 / 不跑 `scutil`，因此在任何平台上结果都一致。
 */
class PlatformProxyDetectorTest {

    private fun proxy(host: String, port: Int, socks: Boolean = false) = Inspect.Proxy(host, port, socks)

    // ---- macOS: scutil --proxy ----

    @Test
    fun scutil_http_proxy_is_parsed_before_socks() {
        val output = """
            <dictionary> {
              HTTPEnable : 1
              HTTPPort : 7890
              HTTPProxy : 127.0.0.1
              SOCKSEnable : 1
              SOCKSPort : 7891
              SOCKSProxy : 10.0.0.1
            }
        """.trimIndent()
        assertEquals(proxy("127.0.0.1", 7890), PlatformProxyDetector.parseScutil(output))
    }

    @Test
    fun scutil_falls_back_to_https_then_socks() {
        val https = """
            <dictionary> {
              HTTPEnable : 0
              HTTPSEnable : 1
              HTTPSPort : 8443
              HTTPSProxy : secure.local
            }
        """.trimIndent()
        assertEquals(proxy("secure.local", 8443), PlatformProxyDetector.parseScutil(https))

        val socks = """
            <dictionary> {
              HTTPEnable : 0
              SOCKSEnable : 1
              SOCKSPort : 1080
              SOCKSProxy : 127.0.0.1
            }
        """.trimIndent()
        assertEquals(proxy("127.0.0.1", 1080, socks = true), PlatformProxyDetector.parseScutil(socks))
    }

    @Test
    fun scutil_uses_default_ports_when_missing() {
        val output = """
            <dictionary> {
              HTTPEnable : 1
              HTTPProxy : proxy.local
            }
        """.trimIndent()
        assertEquals(proxy("proxy.local", 80), PlatformProxyDetector.parseScutil(output))
    }

    @Test
    fun scutil_rejects_invalid_port_and_disabled_proxy() {
        val output = """
            <dictionary> {
              HTTPEnable : 1
              HTTPPort : 70000
              HTTPProxy : proxy.local
            }
        """.trimIndent()
        assertEquals(Inspect.Direct, PlatformProxyDetector.parseScutil(output))
        assertEquals(Inspect.Direct, PlatformProxyDetector.parseScutil("<dictionary> {\n}\n"))
    }

    @Test
    fun scutil_pac_takes_priority_over_fixed_proxy() {
        val output = """
            <dictionary> {
              ProxyAutoConfigEnable : 1
              ProxyAutoConfigURLString : http://example.com/proxy.pac
              HTTPEnable : 1
              HTTPProxy : 127.0.0.1
            }
        """.trimIndent()
        assertEquals(Inspect.PacUnsupported, PlatformProxyDetector.parseScutil(output))
    }

    // ---- Windows registry ----

    @Test
    fun windows_single_server_value_is_parsed() {
        assertEquals(
            proxy("127.0.0.1", 8080),
            PlatformProxyDetector.parseWindowsRegistry(1, "127.0.0.1:8080", null),
        )
    }

    @Test
    fun windows_per_protocol_value_prefers_http() {
        val server = "http=127.0.0.1:8080;https=10.0.0.1:8443;socks=192.168.1.1:1080"
        assertEquals(proxy("127.0.0.1", 8080), PlatformProxyDetector.parseWindowsRegistry(1, server, null))
    }

    @Test
    fun windows_per_protocol_value_falls_back_to_socks() {
        val server = "socks=192.168.1.1:1080"
        assertEquals(
            proxy("192.168.1.1", 1080, socks = true),
            PlatformProxyDetector.parseWindowsRegistry(1, server, null),
        )
    }

    @Test
    fun windows_disabled_or_pac_do_not_produce_a_proxy() {
        assertEquals(Inspect.Direct, PlatformProxyDetector.parseWindowsRegistry(0, "127.0.0.1:8080", null))
        assertEquals(Inspect.Direct, PlatformProxyDetector.parseWindowsRegistry(1, null, null))
        assertEquals(
            Inspect.PacUnsupported,
            PlatformProxyDetector.parseWindowsRegistry(1, "127.0.0.1:8080", "http://example.com/proxy.pac"),
        )
    }

    // ---- Linux / generic URL ----

    @Test
    fun proxy_url_supports_scheme_missing_port_and_socks() {
        assertEquals(proxy("127.0.0.1", 8080), PlatformProxyDetector.parseProxyUrl("http://127.0.0.1:8080"))
        assertEquals(proxy("proxy.local", 7890), PlatformProxyDetector.parseProxyUrl("proxy.local:7890"))
        assertEquals(proxy("proxy.local", 80), PlatformProxyDetector.parseProxyUrl("proxy.local"))
        assertEquals(
            proxy("127.0.0.1", 1080, socks = true),
            PlatformProxyDetector.parseProxyUrl("socks5://127.0.0.1:1080"),
        )
    }

    @Test
    fun proxy_url_ignores_credentials_and_rejects_garbage() {
        assertEquals(
            proxy("127.0.0.1", 8080),
            PlatformProxyDetector.parseProxyUrl("http://user:pass@127.0.0.1:8080"),
        )
        assertNull(PlatformProxyDetector.parseProxyUrl(null))
        assertNull(PlatformProxyDetector.parseProxyUrl("   "))
        assertNull(PlatformProxyDetector.parseProxyUrl("http://"))
    }

    @Test
    fun environment_prefers_https_then_all_then_http() {
        val env = mapOf(
            "http_proxy" to "http://http.local:8080",
            "ALL_PROXY" to "socks5://all.local:1080",
            "HTTPS_PROXY" to "http://https.local:8443",
        )
        assertEquals(proxy("https.local", 8443), PlatformProxyDetector.parseEnvironment(env))
        assertEquals(
            proxy("all.local", 1080, socks = true),
            PlatformProxyDetector.parseEnvironment(env - "HTTPS_PROXY"),
        )
        assertNull(PlatformProxyDetector.parseEnvironment(emptyMap()))
    }

    // ---- conversion / description ----

    @Test
    fun to_proxy_keeps_socks_type_and_strips_ipv6_brackets() {
        val http = PlatformProxyDetector.toProxy(proxy("127.0.0.1", 8080))
        assertEquals(Proxy.Type.HTTP, http?.type())
        assertEquals(8080, (http?.address() as InetSocketAddress).port)

        // 带方括号的 IPv6 字面量必须先去掉括号才能构造 InetSocketAddress
        val socks = PlatformProxyDetector.toProxy(proxy("[::1]", 1080, socks = true))
        assertEquals(Proxy.Type.SOCKS, socks?.type())
        val address = socks?.address() as InetSocketAddress
        assertTrue(address.address is java.net.Inet6Address)
        assertTrue(!address.hostString.contains("["))
        assertEquals(1080, address.port)
    }

    @Test
    fun non_proxy_inspections_and_invalid_ports_become_direct() {
        assertNull(PlatformProxyDetector.toProxy(Inspect.Direct))
        assertNull(PlatformProxyDetector.toProxy(Inspect.PacUnsupported))
        assertNull(PlatformProxyDetector.toProxy(Inspect.Unavailable("scutil --proxy")))
        assertNull(PlatformProxyDetector.toProxy(proxy("127.0.0.1", 0)))
        assertNull(PlatformProxyDetector.toProxy(proxy("", 8080)))
    }

    @Test
    fun describe_never_exposes_credentials() {
        val text = PlatformProxyDetector.describe(proxy("127.0.0.1", 8080))
        assertTrue(text.contains("127.0.0.1:8080"))
        assertTrue(text.startsWith("HTTP"))
        assertEquals("direct", PlatformProxyDetector.describe(Inspect.Direct))
        assertEquals("PAC unsupported", PlatformProxyDetector.describe(Inspect.PacUnsupported))
    }
}
