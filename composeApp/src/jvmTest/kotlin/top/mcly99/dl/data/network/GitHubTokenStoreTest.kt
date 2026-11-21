package top.mcly99.dl.data.network

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitHub Token 的注入式读写；不碰加密文件，也不把明文写进日志。 */
class GitHubTokenStoreTest {

    private var stored: String? = null
    private var writes = 0
    private val originalReader = GitHubTokenStore.tokenReader
    private val originalWriter = GitHubTokenStore.tokenWriter

    @BeforeTest
    fun setUp() {
        stored = null
        writes = 0
        GitHubTokenStore.tokenReader = { stored }
        GitHubTokenStore.tokenWriter = { value ->
            writes++
            stored = value
            true
        }
    }

    @AfterTest
    fun tearDown() {
        GitHubTokenStore.tokenReader = originalReader
        GitHubTokenStore.tokenWriter = originalWriter
    }

    @Test
    fun round_trip_and_has_token() {
        assertFalse(GitHubTokenStore.hasToken())
        assertTrue(GitHubTokenStore.setToken("  ghp_example  "))
        assertEquals("ghp_example", GitHubTokenStore.getToken())
        assertTrue(GitHubTokenStore.hasToken())
    }

    @Test
    fun blank_token_clears_the_value() {
        GitHubTokenStore.setToken("ghp_example")
        GitHubTokenStore.setToken("   ")
        assertNull(GitHubTokenStore.getToken())
        assertFalse(GitHubTokenStore.hasToken())
    }

    @Test
    fun a_failed_read_is_treated_as_not_configured_and_does_not_delete() {
        stored = "ghp_example"
        GitHubTokenStore.tokenReader = { error("key temporarily unavailable") }
        assertNull(GitHubTokenStore.getToken())
        // 上游 #179：解密失败绝不删原值，否则密钥恢复后用户的 Token 已永久丢失
        assertEquals(0, writes)
        assertEquals("ghp_example", stored)
        GitHubTokenStore.tokenReader = { stored }
        assertEquals("ghp_example", GitHubTokenStore.getToken())
    }

    @Test
    fun a_failed_write_is_reported_without_throwing() {
        GitHubTokenStore.tokenWriter = { false }
        assertFalse(GitHubTokenStore.setToken("ghp_example"))
    }
}
