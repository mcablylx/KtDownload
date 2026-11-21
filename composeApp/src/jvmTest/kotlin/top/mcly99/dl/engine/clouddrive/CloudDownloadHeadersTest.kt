package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.BaiduConstants
import top.mcly99.dl.data.network.C139Constants
import top.mcly99.dl.data.network.GuangYaConstants
import top.mcly99.dl.data.network.ILanzouConstants
import top.mcly99.dl.data.network.LanzouConstants
import top.mcly99.dl.data.network.Pan115Constants
import top.mcly99.dl.data.network.Pan123Constants
import top.mcly99.dl.data.network.QuarkConstants
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.UCConstants
import top.mcly99.dl.data.network.XunleiConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0：下载阶段请求头的逐平台口径。
 *
 * 这些断言看起来琐碎，但每一条都对应一个真实故障：115 少一份 Cookie 就 `403 no cookie value`；
 * 迅雷/百度用浏览器 UA 会被 CDN 降级；UC 缺 Referer 被限速到 ~100 KB/s；
 * 139 / 123 / 光鸭的直链是 CDN 签名地址，带账号 Cookie 只是把凭证白白外发。
 */
class CloudDownloadHeadersTest {

    private val credential = "BDUSS=account-cookie"

    @Test
    fun quark_uses_api_user_agent_and_referer() {
        val h = CloudDownloadHeaders.of(SharePlatform.QUARK, credential)

        assertEquals(QuarkConstants.API_USER_AGENT, h.userAgent)
        assertEquals(QuarkConstants.DOWNLOAD_REFERER, h.referer)
        assertEquals(credential, h.cookie)
        assertNull(h.origin, "夸克 CDN 不校验 Origin")
    }

    @Test
    fun guest_link_prefers_the_server_issued_session() {
        // 游客直链只有服务端下发的 __pugs；用账号凭证反而与直链身份不匹配
        val h = CloudDownloadHeaders.of(SharePlatform.QUARK, credential, guestCookie = "__pugs=guest")

        assertEquals("__pugs=guest", h.cookie)
        assertEquals(QuarkConstants.API_USER_AGENT, h.userAgent)
    }

    @Test
    fun uc_needs_referer_and_origin_and_switches_ua_for_guests() {
        val logged = CloudDownloadHeaders.of(SharePlatform.UC, credential)
        assertEquals(UCConstants.USER_AGENT, logged.userAgent)
        assertEquals(UCConstants.DOWNLOAD_REFERER, logged.referer)
        assertEquals(UCConstants.WEB_ORIGIN, logged.origin)
        assertNull(logged.secChUa)

        val guest = CloudDownloadHeaders.of(SharePlatform.UC, credential, guestCookie = "__puus=guest")
        assertEquals(UCConstants.GUEST_UA, guest.userAgent)
        assertEquals(UCConstants.GUEST_SEC_CH_UA, guest.secChUa)
        assertEquals("__puus=guest", guest.cookie)
    }

    @Test
    fun baidu_uses_netdisk_client_user_agent() {
        val h = CloudDownloadHeaders.of(SharePlatform.BAIDU, credential)

        assertEquals(BaiduConstants.UA_NETDISK, h.userAgent)
        assertEquals(credential, h.cookie)
        assertTrue(h.userAgent!!.startsWith("netdisk;"), "百度大文件直链只认 netdisk 客户端 UA")
    }

    @Test
    fun signed_cdn_platforms_do_not_leak_the_account_cookie() {
        // 139 / 123 / 光鸭 / 蓝奏优享的直链都是 CDN 签名地址，账号不参与校验。
        // 带上 Cookie 只会把凭证发给第三方节点，属于无谓外发。
        listOf(
            SharePlatform.C139,
            SharePlatform.PAN123,
            SharePlatform.GUANGYA,
            SharePlatform.ILANZOU,
        ).forEach { platform ->
            val h = CloudDownloadHeaders.of(platform, credential)
            assertNull(h.cookie, "$platform 不应携带账号 Cookie")
            assertNotNull(h.userAgent, "$platform 需要专用 UA")
            assertFalse(h.referer.isNullOrBlank(), "$platform 需要 Referer")
        }
        assertEquals(C139Constants.PC_UA, CloudDownloadHeaders.of(SharePlatform.C139, credential).userAgent)
        assertEquals(Pan123Constants.WEB_UA, CloudDownloadHeaders.of(SharePlatform.PAN123, credential).userAgent)
        assertEquals(GuangYaConstants.WEB_UA, CloudDownloadHeaders.of(SharePlatform.GUANGYA, credential).userAgent)
        assertEquals(
            ILanzouConstants.WEB_UA,
            CloudDownloadHeaders.of(SharePlatform.ILANZOU, credential).userAgent,
        )
    }

    @Test
    fun ilanzou_also_sends_origin() {
        val h = CloudDownloadHeaders.of(SharePlatform.ILANZOU, credential)

        assertEquals(ILanzouConstants.DOWNLOAD_ORIGIN, h.origin)
    }

    @Test
    fun pan115_merges_login_cookie_with_cdn_cookie() {
        // 115 同时校验登录 Cookie 与取链响应下发的 900 秒 CDN Cookie，缺任一直接 403
        val h = CloudDownloadHeaders.of(
            SharePlatform.PAN115,
            credential,
            guestCookie = "UID=123_A1_1; CID=cdn-token",
        )

        assertEquals(credential + "; " + "UID=123_A1_1; CID=cdn-token", h.cookie)
        assertEquals(Pan115Constants.CLIENT_UA, h.userAgent)
        assertEquals(Pan115Constants.DOWNLOAD_REFERER, h.referer)
    }

    @Test
    fun pan115_without_cdn_cookie_still_sends_the_login_cookie() {
        val h = CloudDownloadHeaders.of(SharePlatform.PAN115, credential)

        assertEquals(credential, h.cookie)
        assertTrue(h.cookie!!.isNotBlank())
    }

    @Test
    fun lanzou_referer_follows_the_download_node() {
        // 蓝奏的下载节点要求 Referer 是**该节点自己**的 origin，写死公共域名会被拒
        val h = CloudDownloadHeaders.of(
            SharePlatform.LANZOU,
            credential,
            downloadUrl = "https://down-abc.lanzoui.com/file/xyz",
        )

        assertEquals("https://down-abc.lanzoui.com/", h.referer)
        assertEquals(LanzouConstants.DOWN_IP_COOKIE, h.cookie)
        assertEquals(LanzouConstants.WEB_UA, h.userAgent)
    }

    @Test
    fun lanzou_falls_back_when_the_url_cannot_be_parsed() {
        assertEquals("https://pan.lanzoui.com/", CloudDownloadHeaders.lanzouReferer(""))
        assertEquals(
            "https://pan.lanzoui.com/",
            CloudDownloadHeaders.of(SharePlatform.LANZOU, credential, downloadUrl = "not a url").referer,
        )
    }

    @Test
    fun xunlei_never_sends_the_token_json_as_a_cookie() {
        // 迅雷的「凭证」是 access_token/refresh_token 的 JSON：塞进 Cookie 头毫无意义，
        // 直链自带签名参数。UA 必须是官方 app 串，浏览器 UA 会让 CDN 降级成整文件 200。
        val tokenJson = """{"access_token":"a","refresh_token":"r","device_id":"d"}"""
        val h = CloudDownloadHeaders.of(SharePlatform.XUNLEI, tokenJson)

        assertNull(h.cookie)
        assertEquals(XunleiConstants.APP_UA, h.userAgent)
    }

    @Test
    fun github_only_needs_a_referer() {
        val h = CloudDownloadHeaders.of(SharePlatform.GITHUB, "")

        assertNull(h.cookie)
        assertEquals("https://github.com/", h.referer)
    }

    @Test
    fun blank_credential_never_becomes_an_empty_cookie_header() {
        // 空串会被下载器当成「有这个头，但值为空」，某些 CDN 直接判会话无效
        SharePlatform.values().forEach { platform ->
            val cookie = CloudDownloadHeaders.of(platform, "").cookie
            // 蓝奏的 Cookie 是 down_ip=1（与凭证无关），其余平台空凭证必须不带 Cookie 头
            if (cookie != null) {
                assertTrue(cookie.isNotBlank(), "$platform 发了一个空 Cookie 头")
            }
            if (platform != SharePlatform.LANZOU) {
                assertNull(cookie, "$platform 的空凭证不应变成 Cookie 头")
            }
        }
    }

    @Test
    fun every_platform_has_a_referer_and_a_user_agent() {
        SharePlatform.values().forEach { platform ->
            assertFalse(
                CloudDownloadHeaders.refererFor(platform).isBlank(),
                "$platform 缺 Referer：下载会被防盗链拦下",
            )
            val h = CloudDownloadHeaders.of(platform, credential)
            // GitHub 是唯一不要求专用 UA 的平台（raw/codeload 不校验）
            if (platform != SharePlatform.GITHUB) {
                assertFalse(h.userAgent.isNullOrBlank(), "$platform 缺专用 UA")
            }
        }
    }

    @Test
    fun referer_stays_in_sync_with_the_service_contract() {
        // CloudDriveService.refererFor 现在只是转发；这条保证公开契约没有漂移
        val service = CloudDriveService()
        SharePlatform.values().forEach {
            assertEquals(CloudDownloadHeaders.refererFor(it), service.refererFor(it))
        }
    }
}
