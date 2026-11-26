package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import top.mcly99.dl.data.ShimPrefs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02：网页签名参数的「可下发 / 可配置 / 自愈」回归。
 *
 * 1.0.3 把 client_version 与 9 盐写死在代码里，迅雷轮换后只能发版。这里验证：
 * - 内置快照与 1.0.3 逐字一致（基准签名不变）；
 * - 参数可从本地配置载入、可运行时下发并持久化、坏配置被忽略；
 * - captcha/init 的失败被精确分类并透传到错误文案；
 * - 签名被拒时会重载配置并用新参数重试一次。
 */
class XunleiWebSignConfigTest {

    private lateinit var tempDir: File
    private lateinit var originalProvider: () -> ShimPrefs

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdl-xunlei-sign-").toFile()
        originalProvider = XunleiWebSignConfig.prefsProvider
        XunleiWebSignConfig.prefsProvider = { ShimPrefs(File(tempDir, "misc.properties")) }
        XunleiWebSignConfig.reset()
    }

    @After
    fun tearDown() {
        XunleiWebSignConfig.prefsProvider = originalProvider
        XunleiWebSignConfig.load() // 恢复真实环境配置
        tempDir.deleteRecursively()
    }

    @Test
    fun builtin_snapshot_matches_the_103_baseline_signature() {
        assertEquals("1.93.10", WebSignConfig.DEFAULT_CLIENT_VERSION)
        assertEquals(9, WebSignConfig.DEFAULT_SALTS.size)
        // 与 XunleiGuestCaptchaTest 锁定的基准值一致：配置化没有改变默认签名
        assertEquals(
            "1.ab3d43c6b4a2e1116e67c88c5014fdf6",
            XunleiWebCredential.computeCaptchaSign("dev-1", 1_700_000_000_000L),
        )
        assertFalse(XunleiWebSignConfig.isOverridden())
        assertTrue(XunleiWebSignConfig.diagnostics().contains("salts=9"))
        // 诊断摘要绝不输出盐内容
        assertFalse(XunleiWebSignConfig.diagnostics().contains(WebSignConfig.DEFAULT_SALTS[0]))
    }

    @Test
    fun an_overridden_config_changes_the_sign_and_is_persisted() {
        val newSalts = List(9) { "salt-$it" }
        assertTrue(XunleiWebSignConfig.apply("2.0.0", newSalts))
        assertTrue(XunleiWebSignConfig.isOverridden())
        assertEquals("2.0.0", XunleiWebSignConfig.current().clientVersion)
        val overriddenSign = XunleiWebCredential.computeCaptchaSign(
            "dev-1", 1_700_000_000_000L, XunleiWebSignConfig.current(),
        )
        assertNotEquals(
            XunleiWebCredential.computeCaptchaSign("dev-1", 1_700_000_000_000L),
            overriddenSign,
        )
        // 重新从磁盘加载（模拟重启 / 运行时下发刚落盘），覆盖值应恢复
        XunleiWebSignConfig.load()
        assertEquals("2.0.0", XunleiWebSignConfig.current().clientVersion)
        assertEquals(
            overriddenSign,
            XunleiWebCredential.computeCaptchaSign("dev-1", 1_700_000_000_000L, XunleiWebSignConfig.current()),
        )
    }

    @Test
    fun invalid_overrides_are_ignored_and_never_break_signing() {
        assertFalse(XunleiWebSignConfig.apply("", WebSignConfig.DEFAULT_SALTS))
        assertFalse(XunleiWebSignConfig.apply("2.0.0", listOf("only-one")))
        assertFalse(XunleiWebSignConfig.apply("2.0.0", List(9) { "" }))
        assertEquals(WebSignConfig.DEFAULT, XunleiWebSignConfig.current())
        // 签名仍可用（不会因坏配置抛异常）
        assertTrue(XunleiWebCredential.computeCaptchaSign("dev-1", 1L).startsWith("1."))
    }

    @Test
    fun a_saved_override_is_reloaded_from_disk() {
        val prefs = ShimPrefs(File(tempDir, "misc.properties"))
        prefs.put("xunlei.web.clientVersion", "3.1.4")
        prefs.put("xunlei.web.salts", List(9) { "s$it" }.joinToString("|"))
        XunleiWebSignConfig.load()
        assertEquals("3.1.4", XunleiWebSignConfig.current().clientVersion)
        assertEquals(9, XunleiWebSignConfig.current().salts.size)
        assertTrue(XunleiWebSignConfig.isOverridden())

        // 坏配置（盐数量不对）被忽略，保留当前有效值
        prefs.put("xunlei.web.clientVersion", "9.9.9")
        prefs.put("xunlei.web.salts", "a|b")
        XunleiWebSignConfig.load()
        assertEquals("3.1.4", XunleiWebSignConfig.current().clientVersion)
    }

    // ------------------------------------------------------------ captcha/init 失败分类

    @Test
    fun captcha_init_classifies_failures_and_records_the_reason() = runBlocking {
        val cases = listOf(
            CaptchaServerCase(429, """{"error":"rate_limited"}""", retryAfter = "3") to WebCaptchaFailure.RATE_LIMITED,
            CaptchaServerCase(403, """{"error":"forbidden"}""") to WebCaptchaFailure.HTTP_ERROR,
            CaptchaServerCase(200, """{"error":"captcha_invalid"}""") to WebCaptchaFailure.SIGN_REJECTED,
            CaptchaServerCase(200, """{"captcha_token":""}""") to WebCaptchaFailure.SIGN_REJECTED,
            CaptchaServerCase(200, """{"error_details":[{"detail":"client_id not match"}]}""") to
                WebCaptchaFailure.CLIENT_ID_MISMATCH,
        )
        cases.forEach { (serverCase, expected) ->
            val fake = CaptchaFakeServer(mutableListOf(serverCase))
            val api = XunleiApi(clientProvider = { fake.client })
            val result = api.initWebCaptcha("dev-1", "get:/drive/v1/files")
            assertTrue(result is WebCaptchaInit.Failed || result is WebCaptchaInit.ClientIdMismatch,
                "case ${serverCase.code} should fail, got $result")
            assertEquals(expected, api.lastWebCaptchaFailure, "case ${serverCase.code}")
        }
    }

    @Test
    fun a_successful_init_clears_the_previous_failure() = runBlocking {
        val fake = CaptchaFakeServer(
            mutableListOf(
                CaptchaServerCase(200, """{"error":"captcha_invalid"}"""),
                CaptchaServerCase(200, """{"captcha_token":"token-ok","expires_in":300}"""),
            )
        )
        val api = XunleiApi(clientProvider = { fake.client })
        assertTrue(api.initWebCaptcha("dev-1", "a") is WebCaptchaInit.Failed)
        assertEquals(WebCaptchaFailure.SIGN_REJECTED, api.lastWebCaptchaFailure)
        val ok = api.initWebCaptcha("dev-1", "a")
        assertTrue(ok is WebCaptchaInit.Ok)
        assertEquals("token-ok", ok.captcha.token)
        assertNull(api.lastWebCaptchaFailure)
    }

    @Test
    fun network_throw_is_classified_as_network_not_signature_error() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw java.io.IOException("connection reset") }
            .build()
        val api = XunleiApi(clientProvider = { client })
        val result = api.initWebCaptcha("dev-1", "a")
        assertTrue(result is WebCaptchaInit.Failed)
        assertEquals(WebCaptchaFailure.NETWORK, result.reason)
        assertEquals(WebCaptchaFailure.NETWORK, api.lastWebCaptchaFailure)
    }

    @Test
    fun a_rejected_sign_reloads_config_and_retries_with_the_new_parameters() = runBlocking {
        // 内存里是旧参数；模拟「用户/远程刚把新参数写进 yunx-misc.properties 但还没重启」
        val prefs = ShimPrefs(File(tempDir, "misc.properties"))
        prefs.put("xunlei.web.clientVersion", "2.7.1")
        prefs.put("xunlei.web.salts", List(9) { "new-$it" }.joinToString("|"))
        // 第一次用 DEFAULT 签名被拒；initWebCaptcha 会 load() 出 2.7.1 并重签重试
        val fake = CaptchaFakeServer(
            mutableListOf(
                CaptchaServerCase(200, """{"error":"captcha_invalid"}"""),
                CaptchaServerCase(200, """{"captcha_token":"fresh","expires_in":300}"""),
            )
        )
        val api = XunleiApi(clientProvider = { fake.client })
        val result = api.initWebCaptcha("dev-1", "a")

        assertTrue(result is WebCaptchaInit.Ok, "自愈重试后应成功，实际 $result")
        assertEquals("fresh", result.captcha.token)
        assertEquals("1.93.10", fake.sentVersions[0])
        assertEquals("2.7.1", fake.sentVersions[1])
        assertNotEquals(fake.sentSigns[0], fake.sentSigns[1])
        assertNull(api.lastWebCaptchaFailure)
    }

    // ------------------------------------------------------------ 假服务端

    private data class CaptchaServerCase(
        val code: Int,
        val body: String,
        val retryAfter: String? = null,
    )

    /** 按序返回预设 captcha/init 响应；记录每个请求发出的 client_version 与 captcha_sign。 */
    private class CaptchaFakeServer(private val cases: MutableList<CaptchaServerCase>) {
        val sentVersions = mutableListOf<String>()
        val sentSigns = mutableListOf<String>()
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req: Request = chain.request()
                runCatching {
                    val json = org.json.JSONObject(req.body?.let {
                        val buf = okio.Buffer()
                        it.writeTo(buf)
                        buf.readUtf8()
                    }.orEmpty())
                    sentVersions += json.optJSONObject("meta")?.optString("client_version").orEmpty()
                    sentSigns += json.optJSONObject("meta")?.optString("captcha_sign").orEmpty()
                }
                val c = if (cases.size > 1) cases.removeAt(0) else cases.first()
                val builder = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(c.code)
                    .message(if (c.code == 200) "OK" else "error")
                    .body(c.body.toResponseBody("application/json".toMediaType()))
                c.retryAfter?.let { builder.header("Retry-After", it) }
                builder.build()
            }
            .build()
    }
}
