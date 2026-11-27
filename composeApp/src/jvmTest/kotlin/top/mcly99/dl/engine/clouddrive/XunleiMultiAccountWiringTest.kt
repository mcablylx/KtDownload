package top.mcly99.dl.engine.clouddrive

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import top.mcly99.dl.data.db.XunleiAccountEntity
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.XunleiApi
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import top.mcly99.dl.data.repository.XunleiResolveRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P1-03：迅雷多账号链路三处未打通的回归测试。
 *
 * 修复前：
 * - `accountByTokenProvider` 声明后从未接线，B 账号一律回退主账号身份；
 * - `cacheUserId` 用默认「空才写」，切到 B 账号后 captcha/init 仍带 A 的 user_id；
 * - `transferFile` / `ensureBatchSpace` 的配额恒查主账号（转存却落在目标账号）。
 *
 * 这里用假 OkHttp 服务端记录每次 pan 请求的 Authorization / X-Device-Id / X-Captcha-Token，
 * 断言「传目标账号凭证 → 整条链路都用目标账号的三件套」，并锁定空凭证回退不变量。
 */
class XunleiMultiAccountWiringTest {

    /** 目标账号 B 的凭证原文（结构化 JSON，正是 CloudDriveViewModel 轮换池传下来的形态） */
    private val credentialB =
        """{"accessToken":"token-B","refreshToken":"rt-B","deviceId":"dev-B","captchaToken":"captcha-B","userId":"user-B"}"""

    @Test
    fun batch_space_check_uses_the_target_account_identity() = runBlocking {
        val fake = RecordingXunleiServer()
        val repo = repository(fake)

        repo.ensureBatchSpace(listOf(1024L), credentialB)

        val about = fake.requests.lastOrNull { it.url.encodedPath.endsWith("/drive/v1/about") }
        assertNotNull(about, "整批预算应查询一次配额")
        assertEquals("Bearer token-B", about.header("Authorization"), "配额不得查主账号 A")
        assertEquals("dev-B", about.header("X-Device-Id"))
        assertEquals("captcha-B", about.header("X-Captcha-Token"))
    }

    @Test
    fun transfer_file_space_check_and_restore_use_the_target_account() = runBlocking {
        val fake = RecordingXunleiServer()
        val repo = repository(fake)

        repo.transferFile(
            session = ShareSession("share-1", "stoken-1", "title"),
            file = ShareFile("fid-1", "a.mp4", 1024L, false, "0", "ft"),
            toDirFid = "dir-1",
            cookie = credentialB,
            skipSpaceCheck = false,
        )

        val authorized = fake.requests.filter { it.header("Authorization") != null }
        assertTrue(authorized.isNotEmpty(), "转存链路应发出带身份的请求")
        authorized.forEach { req ->
            assertEquals("Bearer token-B", req.header("Authorization"), "转存链路不得回退主账号 A")
            assertEquals("dev-B", req.header("X-Device-Id"))
            assertEquals("captcha-B", req.header("X-Captcha-Token"))
        }
        assertTrue(
            authorized.any { it.url.encodedPath.endsWith("/drive/v1/about") },
            "未跳过单文件校验时，配额请求必须存在且属于目标账号",
        )
    }

    @Test
    fun download_link_uses_the_target_account() = runBlocking {
        val fake = RecordingXunleiServer()
        val repo = repository(fake)

        repo.getDownloadLink("fid-1", credentialB)

        val req = fake.requests.last { it.header("Authorization") != null }
        assertEquals("Bearer token-B", req.header("Authorization"))
        assertEquals("dev-B", req.header("X-Device-Id"))
        assertEquals("captcha-B", req.header("X-Captcha-Token"))
    }

    @Test
    fun empty_credential_still_falls_back_to_the_primary_account() = runBlocking {
        val fake = RecordingXunleiServer()
        val repo = repository(fake)

        repo.getDownloadLink("fid-1", "")

        val req = fake.requests.last { it.header("Authorization") != null }
        assertEquals("Bearer token-PRIMARY", req.header("Authorization"), "空凭证必须维持主账号语义")
        assertEquals("dev-PRIMARY", req.header("X-Device-Id"))
        assertEquals("captcha-PRIMARY", req.header("X-Captcha-Token"))
    }

    @Test
    fun cloud_drive_service_wires_the_account_provider() = runBlocking {
        val repo = CloudDriveService().repository(SharePlatform.XUNLEI) as XunleiResolveRepository
        val provider = repo.accountByTokenProvider
        assertNotNull(provider, "createRepository 必须接线 accountByTokenProvider（P1-03）")

        val account = provider(credentialB)
        assertNotNull(account, "凭证原文应能被解析成目标账号")
        assertEquals("token-B", account.accessToken)
        assertEquals("dev-B", account.deviceId)
        assertEquals("captcha-B", account.captchaToken)
    }

    private fun repository(fake: RecordingXunleiServer): XunleiResolveRepository =
        XunleiResolveRepository(
            api = XunleiApi(clientProvider = { fake.client }),
            accountProvider = { "token-PRIMARY" },
            deviceIdProvider = { "dev-PRIMARY" },
            captchaProvider = { "captcha-PRIMARY" },
        ).apply {
            // 与 CloudDriveService.createRepository 的接线保持同一形态
            accountByTokenProvider = { raw ->
                XunleiDriveCredential.parse(raw)?.let { c ->
                    XunleiAccountEntity(
                        accessToken = c.accessToken,
                        refreshToken = c.refreshToken,
                        deviceId = c.deviceId,
                        captchaToken = c.captchaToken,
                        userId = c.userId,
                    )
                }
            }
        }

    /** 记录所有请求，按路径返回最小可用 JSON；不发真实网络。 */
    private class RecordingXunleiServer {
        val requests = mutableListOf<Request>()

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requests += req
                val path = req.url.encodedPath
                val body = when {
                    path.endsWith("/drive/v1/about") ->
                        """{"data":{"quota":{"limit":1000000000,"usage":0,"usage_in_trash":0}}}"""
                    path.endsWith("captcha/init") ->
                        """{"captcha_token":"ct","expires_in":300}"""
                    else ->
                        """{"data":{"id":"new-id","name":"n","size":1,"links":{"application/octet-stream":{"url":"https://dl.example/f"}}}}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
