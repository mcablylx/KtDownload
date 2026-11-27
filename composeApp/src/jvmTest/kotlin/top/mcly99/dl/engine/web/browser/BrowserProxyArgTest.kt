package top.mcly99.dl.engine.web.browser

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2-11 回归：深度抓取的浏览器代理参数必须按 [Proxy.type] 选 scheme。
 *
 * 旧实现无条件拼 `--proxy-server=http://host:port`；「跟随系统」探测到 SOCKS 时，
 * OkHttp 主链路按 SOCKS 正常走，只有 Chromium 把 SOCKS 服务当 HTTP 代理握手，
 * 于是同一份设置下「只有抓网页失败」。
 */
class BrowserProxyArgTest {

    private fun http(host: String = "127.0.0.1", port: Int = 7890) =
        Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))

    private fun socks(host: String = "127.0.0.1", port: Int = 1080) =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))

    @Test
    fun http_proxy_keeps_http_scheme() {
        assertEquals("http://127.0.0.1:7890", browserProxyArg(http()))
    }

    @Test
    fun socks_proxy_uses_socks5_scheme() {
        assertEquals("socks5://127.0.0.1:1080", browserProxyArg(socks()))
    }

    @Test
    fun direct_and_null_proxy_add_no_argument() {
        assertNull(browserProxyArg(null))
        assertNull(browserProxyArg(Proxy.NO_PROXY))
    }

    @Test
    fun non_inet_socket_address_is_rejected() {
        // java.net.Proxy 的构造器本身就要求 HTTP/SOCKS 的地址必须是 InetSocketAddress，
        // 所以其它 SocketAddress 子类型根本进不到 browserProxyArg；这里锁定该 JDK 契约。
        // 生产代码仍保留 `as? InetSocketAddress` 作为防御：万一构造器语义变化，
        // 也不会把无法解析的地址拼进 --proxy-server。
        val other = object : java.net.SocketAddress() {}
        assertFailsWith<IllegalArgumentException> { Proxy(Proxy.Type.SOCKS, other) }
    }

    @Test
    fun direct_proxy_has_no_inet_address_to_format() {
        // NO_PROXY 是 JDK 私有构造器产生的 DIRECT 代理，address() 为 null；
        // browserProxyArg 的 `as? InetSocketAddress` 会在此提前返回，不能拼出坏参数。
        assertEquals(Proxy.Type.DIRECT, Proxy.NO_PROXY.type())
        assertNull(Proxy.NO_PROXY.address())
        assertNull(browserProxyArg(Proxy.NO_PROXY))
    }

    @Test
    fun blank_host_is_rejected() {
        assertNull(browserProxyArg(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("", 1080))))
    }

    @Test
    fun ipv6_literal_is_bracketed() {
        val address = InetSocketAddress(InetAddress.getByName("::1"), 1080)
        val arg = requireNotNull(browserProxyArg(Proxy(Proxy.Type.SOCKS, address))) {
            "IPv6 SOCKS 代理应能生成参数"
        }
        assertTrue(arg.startsWith("socks5://["), "IPv6 字面量必须加方括号：$arg")
        assertTrue(arg.endsWith("]:1080"), "IPv6 字面量必须闭合方括号：$arg")
    }

    @Test
    fun launch_uses_the_typed_argument_instead_of_hardcoded_http() {
        val source = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/web/browser/BrowserCapture.kt")
        assertTrue(source.contains("browserProxyArg(proxyProvider())"), "launch 必须走类型分派的纯函数")
        assertTrue(
            !source.contains("--proxy-server=http://\${address.hostString}"),
            "不能再无条件把代理拼成 http://",
        )
        assertTrue(source.contains("Proxy.Type.SOCKS -> \"socks5://"), "SOCKS 必须映射到 socks5://")
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}
