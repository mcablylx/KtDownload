package top.mcly99.dl.data.network

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** 上游 GitHubResponseCache 的 TTL / 失败哨兵 / 前缀失效 / 超大结果不缓存。 */
class GitHubResponseCacheTest {

    @BeforeTest
    fun reset() = GitHubResponseCache.clear()

    @Test
    fun successful_value_is_served_from_cache_on_second_call() = runTest {
        var calls = 0
        val fetch = suspend { calls++; "ok" }
        assertEquals("ok", GitHubResponseCache.getOrFetch("a") { fetch() })
        assertEquals("ok", GitHubResponseCache.getOrFetch("a") { fetch() })
        assertEquals(1, calls)
    }

    @Test
    fun failure_is_cached_as_a_short_lived_sentinel() = runTest {
        var calls = 0
        val fetch = suspend { calls++; null }
        assertEquals(null, GitHubResponseCache.getOrFetch("b") { fetch() })
        assertEquals(null, GitHubResponseCache.getOrFetch("b") { fetch() })
        assertEquals(1, calls)
    }

    @Test
    fun invalidate_prefix_only_drops_matching_keys() = runTest {
        var callsA = 0
        var callsB = 0
        GitHubResponseCache.getOrFetch("repo:o/r") { callsA++; "A" }
        GitHubResponseCache.getOrFetch("tree:o/r/sha") { callsB++; "B" }
        GitHubResponseCache.invalidatePrefix("repo:")
        GitHubResponseCache.getOrFetch("repo:o/r") { callsA++; "A2" }
        GitHubResponseCache.getOrFetch("tree:o/r/sha") { callsB++; "B2" }
        assertEquals(2, callsA)
        assertEquals(1, callsB)
    }

    @Test
    fun oversized_success_is_returned_but_not_cached() = runTest {
        var calls = 0
        val big = "x".repeat(200)
        val fetch = suspend { calls++; big }
        assertEquals(big, GitHubResponseCache.getOrFetch("readme:o/r/main", maxBytes = 128) { fetch() })
        assertEquals(big, GitHubResponseCache.getOrFetch("readme:o/r/main", maxBytes = 128) { fetch() })
        assertEquals(2, calls)
    }

    @Test
    fun concurrent_calls_for_the_same_key_share_one_fetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async {
            GitHubResponseCache.getOrFetch("inflight") {
                calls++
                gate.await()
                "value"
            }
        }
        // 等第一个协程确实进入 fetch 并登记 in-flight，再发第二个
        val second = async {
            while (calls == 0) kotlinx.coroutines.yield()
            GitHubResponseCache.getOrFetch("inflight") {
                calls++
                "should-not-run"
            }
        }
        gate.complete(Unit)
        assertEquals(listOf("value", "value"), listOf(first, second).awaitAll())
        assertEquals(1, calls)
    }

    @Test
    fun put_and_clear_work_without_a_fetcher() = runBlocking {
        GitHubResponseCache.put("manual", "v", success = true)
        assertEquals("v", GitHubResponseCache.getOrFetch("manual") { error("should be cached") })
        GitHubResponseCache.clear()
        assertEquals("fresh", GitHubResponseCache.getOrFetch("manual") { "fresh" })
    }
}
