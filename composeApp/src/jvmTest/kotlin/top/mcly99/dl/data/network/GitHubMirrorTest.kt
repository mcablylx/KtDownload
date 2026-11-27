package top.mcly99.dl.data.network

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.mcly99.dl.data.ShimPrefs
import top.mcly99.dl.engine.clouddrive.CloudDriveService
import top.mcly99.dl.engine.clouddrive.GitHubMirrorChoice
import java.nio.file.Files
import java.nio.file.Path

/**
 * GitHub 下载镜像（P2）：默认 AUTO、只有实测成功才走镜像、测速失败回落直连、显式直连优先。
 *
 * 注入临时 prefs + 纯函数 prober + 可替换时钟，全程零真实网络，绝不读写 ~/.mclydownload。
 * `resetForTest()` 清空单例的测速快照，避免用例之间互相污染。
 */
class GitHubMirrorTest {

    private val dirs = mutableListOf<Path>()
    private val originalPrefs = GitHubMirror.prefs
    private val originalProber = GitHubMirror.prober
    private val originalClock = GitHubMirror.clock
    private val t0 = 1_700_000_000_000L

    private fun newPrefs(): ShimPrefs {
        val dir = Files.createTempDirectory("ktdown-mirror-test").also { dirs.add(it) }
        return ShimPrefs(dir.resolve("misc.properties").toFile())
    }

    private fun probe(prefix: String, bps: Long?) = GitHubMirror.Probe(prefix, 12L, bps)

    private fun allFailed() = GitHubMirror.PRESETS.map { probe(it, null) }

    /** 等一轮异步 refresh 落库；refresh 会同步把 testing 置 true，所以不会误等 */
    private fun awaitRefresh() = runBlocking {
        withTimeout(5_000L) { GitHubMirror.snapshot.first { !it.testing } }
    }

    @BeforeTest
    fun setUp() {
        GitHubMirror.prefs = newPrefs()
        GitHubMirror.clock = { t0 }
        GitHubMirror.resetForTest()
        // 默认注入「全部测速失败」，任何意外触发的 refresh 都不会发真实网络
        GitHubMirror.prober = { prefix -> probe(prefix, null) }
    }

    @AfterTest
    fun tearDown() {
        GitHubMirror.prefs = originalPrefs
        GitHubMirror.prober = originalProber
        GitHubMirror.clock = originalClock
        GitHubMirror.resetForTest()
        dirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        dirs.clear()
    }

    @Test
    fun default_is_auto_but_direct_until_a_mirror_passes_the_probe() {
        assertTrue(GitHubMirror.isEnabled(), "新用户默认 AUTO")
        assertEquals("", GitHubMirror.activePrefix(), "未测速不得插入未验证的第三方前缀")
        assertTrue(GitHubMirror.shouldRefresh(), "从未测过，启动应触发一次测速")
        assertTrue(GitHubMirror.PRESETS.size == 5)
    }

    @Test
    fun explicit_direct_choice_always_wins_even_after_a_successful_probe() {
        GitHubMirror.setEnabled(false)
        assertFalse(GitHubMirror.isEnabled())
        assertFalse(GitHubMirror.shouldRefresh(), "显式直连不应向外发探测请求")

        GitHubMirror.applyProbeResults(
            listOf(probe(GitHubMirror.PRESETS[0], 9_000_000L)),
        )
        assertEquals("", GitHubMirror.activePrefix())
    }

    @Test
    fun successful_probe_enables_only_the_fastest_mirror() {
        GitHubMirror.applyProbeResults(
            listOf(
                probe(GitHubMirror.PRESETS[0], 100_000L),
                probe(GitHubMirror.PRESETS[1], 900_000L),
                probe(GitHubMirror.PRESETS[2], 300_000L),
            ),
        )
        assertEquals(GitHubMirror.PRESETS[1], GitHubMirror.activePrefix())
        assertFalse(GitHubMirror.lastProbeFailed())
    }

    @Test
    fun all_probes_failed_falls_back_to_github_direct() {
        GitHubMirror.applyProbeResults(allFailed())
        assertEquals("", GitHubMirror.activePrefix())
        assertTrue(GitHubMirror.lastProbeFailed())
    }

    @Test
    fun failed_probe_retries_sooner_than_a_successful_one() {
        GitHubMirror.applyProbeResults(allFailed())
        GitHubMirror.clock = { t0 + 11L * 60 * 1000 }
        assertTrue(GitHubMirror.shouldRefresh(), "全失败后回落直连，10 分钟就该重试")

        // 换成「测速成功」：同样的 11 分钟不重试，超过 6 小时才重试
        GitHubMirror.resetForTest()
        GitHubMirror.prefs = newPrefs()
        GitHubMirror.clock = { t0 }
        GitHubMirror.applyProbeResults(
            listOf(probe(GitHubMirror.PRESETS[0], 500_000L)),
        )
        GitHubMirror.clock = { t0 + 11L * 60 * 1000 }
        assertFalse(GitHubMirror.shouldRefresh())
        GitHubMirror.clock = { t0 + 7L * 60 * 60 * 1000 }
        assertTrue(GitHubMirror.shouldRefresh())
    }

    @Test
    fun fixed_prefix_is_used_immediately_and_skips_auto_probing() {
        GitHubMirror.setPrefix(GitHubMirror.PRESETS[3])
        assertEquals(GitHubMirror.PRESETS[3], GitHubMirror.activePrefix())
        assertEquals(GitHubMirror.PRESETS[3], GitHubMirror.fixedPrefix())
        assertFalse(GitHubMirror.shouldRefresh(), "用户已固定镜像，不做无谓探测")
    }

    @Test
    fun disabling_overrides_a_fixed_prefix() {
        GitHubMirror.setPrefix(GitHubMirror.PRESETS[3])
        GitHubMirror.setEnabled(false)
        assertEquals("", GitHubMirror.activePrefix())
        assertFalse(GitHubMirror.shouldRefresh())
    }

    @Test
    fun async_refresh_with_all_failures_keeps_direct() {
        GitHubMirror.refresh()
        awaitRefresh()
        assertEquals("", GitHubMirror.activePrefix())
        assertTrue(GitHubMirror.lastProbeFailed())
    }

    @Test
    fun async_refresh_persists_the_fastest_mirror() {
        GitHubMirror.prober = { prefix ->
            probe(prefix, if (prefix == GitHubMirror.PRESETS[2]) 9_000_000L else 100_000L)
        }
        GitHubMirror.refresh()
        awaitRefresh()
        assertEquals(GitHubMirror.PRESETS[2], GitHubMirror.activePrefix())
        assertFalse(GitHubMirror.lastProbeFailed())
        assertEquals(GitHubMirror.PRESETS[2], GitHubMirror.fastestPrefix())
    }

    @Test
    fun settings_round_trip_direct_auto_and_fixed_preset() {
        val service = CloudDriveService()
        assertEquals(GitHubMirrorChoice.AUTO, service.githubMirrorValue())

        service.selectGithubMirror(GitHubMirrorChoice.DIRECT)
        assertEquals(GitHubMirrorChoice.DIRECT, service.githubMirrorValue())
        assertEquals("", GitHubMirror.activePrefix())

        service.selectGithubMirror(GitHubMirrorChoice.AUTO)
        assertEquals(GitHubMirrorChoice.AUTO, service.githubMirrorValue())
        awaitRefresh() // 切回 AUTO 会立刻测一轮；注入的 prober 全失败，仍回落直连
        assertEquals("", GitHubMirror.activePrefix())

        service.selectGithubMirror(GitHubMirror.PRESETS[0])
        assertEquals(GitHubMirror.PRESETS[0], service.githubMirrorValue())
        assertEquals(GitHubMirror.PRESETS[0], GitHubMirror.activePrefix())
    }

    @Test
    fun http_and_malformed_prefixes_are_never_persisted_or_used() {
        GitHubMirror.setPrefix("http://evil.example/")
        assertEquals("", GitHubMirror.fixedPrefix(), "非 https 前缀必须被拒绝")
        assertEquals("", GitHubMirror.activePrefix())

        GitHubMirror.setPrefix("https://user:pwd@evil.example/")
        assertEquals("", GitHubMirror.fixedPrefix(), "带 userinfo 的前缀必须被拒绝")

        // 用户显式配置的自定义 https 镜像仍被允许，但规范化后保存
        GitHubMirror.setPrefix("https://mirror.example.com")
        assertEquals("https://mirror.example.com/", GitHubMirror.fixedPrefix())
        assertEquals("https://mirror.example.com/", GitHubMirror.activePrefix())
    }

    @Test
    fun tampered_fastest_prefix_falls_back_to_direct() {
        // 明文 yunx-misc.properties 被改成任意 https 主机：自动测速产物必须来自 PRESETS
        GitHubMirror.prefs.put("github_mirror_fastest", "https://evil.example/")
        GitHubMirror.resetForTest()
        assertNull(GitHubMirror.fastestPrefix())
        assertEquals("", GitHubMirror.activePrefix())
    }

    @Test
    fun all_presets_are_https() {
        assertTrue(GitHubMirror.PRESETS.all { it.startsWith("https://") })
    }
}
