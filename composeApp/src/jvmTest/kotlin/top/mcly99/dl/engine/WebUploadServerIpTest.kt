package top.mcly99.dl.engine

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class WebUploadServerIpTest {

    private fun candidate(
        ip: String,
        name: String,
        virtual: Boolean = false,
        pointToPoint: Boolean = false,
    ) = LocalIpCandidate(
        address = InetAddress.getByName(ip) as Inet4Address,
        interfaceName = name,
        isVirtual = virtual,
        isPointToPoint = pointToPoint,
    )

    @Test
    fun prefers_physical_adapter_over_vpn() {
        val vpn = candidate("10.8.0.1", "WireGuard VPN", virtual = true)
        val ethernet = candidate("192.168.1.5", "Ethernet")

        assertEquals(ethernet.address, bestLocalIp(listOf(vpn, ethernet)))
    }

    @Test
    fun vpn_like_name_is_deprioritized_even_when_not_marked_virtual() {
        val vpn = candidate("10.8.0.1", "tap0")
        val wifi = candidate("192.168.1.6", "WLAN")

        assertEquals(wifi.address, bestLocalIp(listOf(vpn, wifi)))
    }

    @Test
    fun prefers_private_lan_address_over_public() {
        val public = candidate("203.0.113.9", "Ethernet")
        val lan = candidate("192.168.31.7", "Ethernet")

        assertEquals(lan.address, bestLocalIp(listOf(public, lan)))
    }

    @Test
    fun prefers_normal_adapter_over_point_to_point() {
        val ppp = candidate("10.0.0.2", "PPP", pointToPoint = true)
        val ethernet = candidate("10.0.0.3", "Ethernet")

        assertEquals(ethernet.address, bestLocalIp(listOf(ppp, ethernet)))
    }
}
