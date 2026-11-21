package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 网页登录映射表的回归测试：URL / Cookie 判定键 / 特殊平台逻辑 */
class CloudDriveLoginCatalogTest {

    @Test
    fun every_netdisk_has_an_https_in_app_login_page() {
        CloudDriveCatalog.platforms
            .filter { it.platform != SharePlatform.GITHUB }
            .forEach { info ->
                val config = CloudDriveLoginCatalog.of(info.platform)
                assertNotNull(config, "${info.name} 缺少登录配置")
                assertTrue(config.loginUrl.startsWith("https://"), "${info.name} 登录页不是 https")
                assertTrue(config.cookieDomains.isNotEmpty(), "${info.name} 没有 Cookie 域名")
            }
    }

    @Test
    fun github_has_no_netdisk_login_page() {
        assertNull(CloudDriveLoginCatalog.of(SharePlatform.GITHUB))
    }

    @Test
    fun cookie_platforms_use_the_upstream_required_keys() {
        assertEquals(
            listOf("__pus", "__puus"),
            CloudDriveLoginCatalog.of(SharePlatform.QUARK)?.requiredKeys,
        )
        assertEquals(
            listOf("__pus", "__puus"),
            CloudDriveLoginCatalog.of(SharePlatform.UC)?.requiredKeys,
        )
        // 百度必须同时有 BDUSS + STOKEN：只有 BDUSS 时浏览正常、转存恒 errno=2
        assertEquals(
            listOf("BDUSS", "STOKEN"),
            CloudDriveLoginCatalog.of(SharePlatform.BAIDU)?.requiredKeys,
        )
        assertEquals(
            listOf("Os_SSo_Sid", "RMKEY"),
            CloudDriveLoginCatalog.of(SharePlatform.C139)?.requiredKeys,
        )
        assertEquals(
            listOf("authorization"),
            CloudDriveLoginCatalog.of(SharePlatform.C139)?.anyOfKeys,
        )
    }

    @Test
    fun xunlei_uses_the_localstorage_cookie_bridge() {
        val config = CloudDriveLoginCatalog.of(SharePlatform.XUNLEI)
        assertEquals(CloudLoginMode.XUNLEI_WEB, config?.mode)
        assertEquals("https://pan.xunlei.com/", config?.loginUrl)
        assertTrue(config?.note?.contains("access_token") == true)
    }

    @Test
    fun pan115_filters_cookies_down_to_session_keys() {
        val transform = CloudDriveLoginCatalog.of(SharePlatform.PAN115)!!.transform
        val filtered = transform("UID=u; CID=c; SEID=s; PHPSESSID=p; JUNK=x; BDUSS=b")
        assertEquals("UID=u; CID=c; SEID=s; PHPSESSID=p", filtered)
        assertFalse(filtered.contains("JUNK"))
        assertFalse(filtered.contains("BDUSS"))
    }

    @Test
    fun platforms_without_detectable_cookies_are_manual() {
        listOf(
            SharePlatform.PAN123,
            SharePlatform.GUANGYA,
            SharePlatform.ILANZOU,
            SharePlatform.LANZOU,
        ).forEach { p ->
            assertEquals(CloudLoginMode.MANUAL, CloudDriveLoginCatalog.of(p)?.mode, "$p 应为手工模式")
            assertTrue(CloudDriveLoginCatalog.of(p)?.note?.isNotBlank() == true)
        }
    }
}
