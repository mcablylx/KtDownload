package top.mcly99.dl.engine.clouddrive.browser

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JCEF Cookie 收集等待策略回归测试（P0-3）。
 *
 * 旧实现用固定 Thread.sleep(400) 赌异步回调写完，Cookie 多/IO 忙时会漏掉
 * 「最后一次」的有效值，恰好让 last-wins 去重保住旧 STOKEN → 复现 errno=-6。
 * 这里通过注入 startVisit 验证：收齐 count/total 才返回、last-wins 生效、
 * 回调不来时靠超时兜底而不卡死。
 */
class JcefCookieCollectTest {

    @Test
    fun waits_until_last_callback_and_keeps_last_value() {
        val pairs = collectCookiePairsBlocking(listOf("baidu.com"), startVisit = { emit ->
            // 模拟异步回调：STOKEN 两份，后一份才是有效值
            emit("passport.baidu.com", "STOKEN", "old", 0, 3)
            emit("pan.baidu.com", "OTHER", "v", 1, 3)
            emit("pan.baidu.com", "STOKEN", "fresh", 2, 3)
        })

        assertEquals(listOf("STOKEN" to "fresh", "OTHER" to "v"), pairs)
    }

    @Test
    fun emits_from_other_domain_are_filtered() {
        val pairs = collectCookiePairsBlocking(listOf("baidu.com"), startVisit = { emit ->
            emit("baidu.com", "A", "1", 0, 2)
            emit("evil.com", "A", "2", 1, 2)
        })

        assertEquals(listOf("A" to "1"), pairs)
    }

    @Test
    fun async_callbacks_complete_before_return() {
        // 回调从另一个线程迟到：必须在收齐后才返回，不能靠固定 sleep 抢跑
        val pairs = collectCookiePairsBlocking(listOf("baidu.com"), timeoutMs = 2000) { emit ->
            val t = Thread {
                emit("baidu.com", "A", "1", 0, 2)
                Thread.sleep(60)
                emit("baidu.com", "B", "2", 1, 2)
            }
            t.start()
            // startVisit 返回时回调尚未跑完；阻塞 await 要等到最后一项
            t.join(10)
        }

        assertEquals(listOf("A" to "1", "B" to "2"), pairs)
    }

    @Test
    fun timeout_fallback_returns_without_hanging() {
        val started = System.nanoTime()
        // 只来了一个回调且 total 声称还有更多，永远不齐：必须超时返回而不是卡死
        val pairs = collectCookiePairsBlocking(listOf("baidu.com"), timeoutMs = 120) { emit ->
            emit("baidu.com", "A", "1", 0, 5)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("A" to "1"), pairs)
        assertTrue(elapsedMs < 1000, "应在超时后及时返回，实际耗时 ${elapsedMs}ms")
    }

    @Test
    fun no_cookies_returns_empty_quickly() {
        // total=0 不会触发 countDown，靠超时兜底；但也不能无限等
        val pairs = collectCookiePairsBlocking(listOf("baidu.com"), timeoutMs = 80) { _ -> }
        assertTrue(pairs.isEmpty())
    }
}
