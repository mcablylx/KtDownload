package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「换端口失败」的三种结局。
 *
 * 核心回归是第二条：`WebUploadServer.start` 先在新端口起、成功了才关旧的，
 * 所以换端口失败时旧服务仍在原端口工作。早先 ViewModel 直接把 `webServerRunning`
 * 置成 `ok`，界面会显示「未运行」并把地址清空，用户在外面的设备就再也连不上（P1-20）。
 */
class WebPortOutcomeTest {

    @Test
    fun started_on_requested_port_is_plain_success() {
        val outcome = WebPortOutcome.resolve(
            requestedPort = 8080,
            started = true,
            activePort = 8080,
            running = true,
        )

        assertTrue(outcome.available)
        assertFalse(outcome.failed)
        assertFalse(outcome.keptOldPort)
        assertEquals(8080, outcome.activePort)
        assertEquals("Web 服务已启动，端口 8080", outcome.describe())
    }

    @Test
    fun failed_switch_but_old_port_alive_is_available_with_error() {
        val outcome = WebPortOutcome.resolve(
            requestedPort = 9091,
            started = false,
            activePort = 9090,
            running = true,
        )

        // 既要报错（新端口没成），又要显示旧地址（服务其实还在）
        assertTrue(outcome.failed)
        assertTrue(outcome.available)
        assertTrue(outcome.keptOldPort)
        assertEquals(9090, outcome.activePort)
        assertEquals(
            "端口 9091 启动失败，服务继续在旧端口 9090 运行",
            outcome.describe(),
        )
    }

    @Test
    fun failed_and_stopped_is_not_available() {
        val outcome = WebPortOutcome.resolve(
            requestedPort = 9091,
            started = false,
            activePort = 9091,
            running = false,
        )

        assertTrue(outcome.failed)
        assertFalse(outcome.available)
        assertFalse(outcome.keptOldPort)
        assertEquals("Web 服务启动失败，端口 9091", outcome.describe())
    }
}
