package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主机连接上限学习的判据（E 项）。
 *
 * 这层逻辑全是「什么时候该相信自己看到的」，没有 IO，所以可以逐条钉死：
 * 观测不足不生效、偶发一次不生效、单连接也失败就不是并发问题、十分钟后作废。
 */
class HostCapRegistryTest {

    private var clock = 1_000_000L

    private fun registry() = HostCapRegistry(now = { clock })

    @Test
    fun a_single_observation_is_not_enough() {
        val registry = registry()

        assertNull(registry.observeRejection("cdn:443", 2), "第一次观测不该立即生效")
        assertEquals(2, registry.observeRejection("cdn:443", 2), "第二次观测才定下上限")
        assertEquals(2, registry.capFor("cdn:443"))
    }

    @Test
    fun cap_takes_the_minimum_because_it_is_what_actually_worked() {
        val registry = registry()

        registry.observeRejection("cdn:443", 4)
        // 后一次被拒时只有 2 条在收数据：4 条那轮里必然有 2 条是被拒的
        assertEquals(2, registry.observeRejection("cdn:443", 2))
        assertEquals(2, registry.capFor("cdn:443"))
    }

    @Test
    fun a_later_smaller_observation_can_lower_the_cap() {
        val registry = registry()
        registry.observeRejection("cdn:443", 4)
        registry.observeRejection("cdn:443", 4)
        assertEquals(4, registry.capFor("cdn:443"))

        registry.observeRejection("cdn:443", 3)
        assertEquals(3, registry.capFor("cdn:443"))
    }

    @Test
    fun a_single_connection_failure_means_the_problem_is_not_concurrency() {
        val registry = registry()
        registry.noteSingleConnectionFailure("expired.example:443")

        assertNull(registry.observeRejection("expired.example:443", 3), "直链过期的 403 不能当成并发限制")
        assertNull(registry.capFor("expired.example:443"))
        assertTrue(registry.isNotConcurrency("expired.example:443"))
    }

    @Test
    fun cap_is_forgotten_after_ten_minutes_without_new_rejections() {
        val registry = registry()
        registry.observeRejection("cdn:443", 2)
        registry.observeRejection("cdn:443", 2)
        assertEquals(2, registry.capFor("cdn:443"))

        clock += 9 * 60 * 1000L
        assertEquals(2, registry.capFor("cdn:443"), "九分钟还没到遗忘时间")

        clock += 2 * 60 * 1000L
        assertNull(registry.capFor("cdn:443"), "超过十分钟应作废，否则用户永远只能跑 2 条连接")
    }

    @Test
    fun hosts_are_tracked_independently() {
        val registry = registry()
        registry.observeRejection("a.example:443", 2)
        registry.observeRejection("a.example:443", 2)

        assertNull(registry.capFor("b.example:443"), "另一个主机不应继承别人的上限")
        assertEquals(2, registry.capFor("a.example:443"))
    }

    @Test
    fun blank_host_and_zero_observation_are_ignored() {
        val registry = registry()

        assertNull(registry.observeRejection("", 2))
        assertNull(registry.observeRejection("cdn:443", 0))
        assertNull(registry.capFor(""))
    }

    @Test
    fun host_key_keeps_the_port_so_two_services_do_not_share_a_cap() {
        assertEquals("127.0.0.1:8080", hostKeyOf("http://127.0.0.1:8080/path/file.bin"))
        assertEquals("cdn.example.com", hostKeyOf("https://cdn.example.com/a/b"))
        assertEquals("", hostKeyOf("not a url"))
    }
}
