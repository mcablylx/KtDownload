package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import top.mcly99.dl.engine.clouddrive.CloudDriveService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 中文口令（迅雷 / 星海）的纯逻辑与 glue 层本地分支。
 *
 * 这里只覆盖不发真实网络请求的分支：口令服务的 HTTP 部分由 ViewModel 侧用
 * FakeApi 注入结果来验证（D-19 / D-20 / D-48）。
 */
class ShareCommandTest {

    @Test
    fun star_sea_only_accepts_short_non_url_text() {
        assertTrue(StarSeaApi.looksLikeCommand("绝对有效 咐置松君杂货铺叩苓"))
        assertFalse(StarSeaApi.looksLikeCommand(""))
        assertFalse(StarSeaApi.looksLikeCommand("   "))
        assertFalse(StarSeaApi.looksLikeCommand("x".repeat(StarSeaConstants.MAX_COMMAND_LENGTH + 1)))
        assertFalse(StarSeaApi.looksLikeCommand("https://pan.quark.cn/s/abc"))
        assertFalse(StarSeaApi.looksLikeCommand("看这个 HTTP://example.com 链接"))
    }

    @Test
    fun star_sea_maps_pwd_id_to_a_platform_share_link() {
        assertEquals("https://pan.quark.cn/s/Abc123", StarSeaApi.shareLinkFor("qk", "Abc123"))
        assertEquals("https://drive.uc.cn/s/Abc123", StarSeaApi.shareLinkFor("uc", "Abc123"))
        assertEquals("https://drive.uc.cn/s/Abc123", StarSeaApi.shareLinkFor("UC", "Abc123"))
        assertEquals("https://pan.quark.cn/s/Abc123", StarSeaApi.shareLinkFor("", "Abc123"))
    }

    @Test
    fun xunlei_kouling_accepts_only_short_alphanumeric_chinese_text() {
        assertTrue(XunleiKouling.looksLikeKouling("「张三丰资源」"))
        assertTrue(XunleiKouling.looksLikeKouling("复联4合集"))
        assertFalse(XunleiKouling.looksLikeKouling("avengers"))
        assertFalse(XunleiKouling.looksLikeKouling("123456"))
        assertFalse(XunleiKouling.looksLikeKouling("张三丰 资源"))
        assertFalse(XunleiKouling.looksLikeKouling("张".repeat(65)))
        assertEquals("张三丰资源", XunleiKouling.normalize("  「张三丰资源」。 "))
    }

    @Test
    fun xunlei_jump_url_encodes_the_command() {
        val url = XunleiKouling.buildJumpUrl("张三丰资源")
        assertTrue(url.startsWith(XunleiKouling.JUMP_API))
        assertTrue(url.contains("noredirect=1"))
        assertTrue(url.contains("wd="))
        assertFalse(url.contains("张三丰资源")) // 中文口令必须 URL 编码
    }

    @Test
    fun xunlei_location_is_reduced_to_a_clean_share_url() {
        assertEquals(
            "https://pan.xunlei.com/s/Abc123?pwd=8k2f",
            XunleiKouling.shareUrlFromLocation(
                "https://pan.xunlei.com/s/Abc123?pwd=8k2f&channel=xx&wd=%E5%BC%A0",
            ),
        )
        assertEquals(
            "https://pan.xunlei.com/s/Abc123",
            XunleiKouling.shareUrlFromLocation("https://pan.xunlei.com/s/Abc123?from=share"),
        )
        assertNull(XunleiKouling.shareUrlFromLocation("https://www.baidu.com/s?wd=abc"))
        assertNull(XunleiKouling.shareUrlFromLocation(null))
    }

    @Test
    fun glue_returns_null_for_plain_text_without_hitting_the_network() = runBlocking {
        val service = CloudDriveService()
        // 纯英文文案：下游加固后不该被发往星海
        assertNull(service.resolveCommand("just some english text"))
        // 含 URL 的文本即使带汉字也不是中文口令
        assertNull(service.resolveCommand("https://example.com/中文页面"))
    }

    @Test
    fun glue_passes_through_text_that_is_already_a_share_link() = runBlocking {
        val link = "https://pan.quark.cn/s/abc123"
        assertEquals(link, CloudDriveService().resolveCommand(link)?.getOrThrow())
    }

    @Test
    fun links_produced_by_command_services_are_recognised_by_the_existing_parser() {
        // 口令服务换回来的链接必须能被既有解析器认出来，否则整条链路会断在第二步
        assertEquals(
            SharePlatform.QUARK,
            ShareLinkParser.parse(StarSeaApi.shareLinkFor("qk", "Abc123"))?.platform,
        )
        assertEquals(
            SharePlatform.UC,
            ShareLinkParser.parse(StarSeaApi.shareLinkFor("uc", "Abc123"))?.platform,
        )
        val xunlei = ShareLinkParser.parse("https://pan.xunlei.com/s/Abc123?pwd=8k2f")
        assertEquals(SharePlatform.XUNLEI, xunlei?.platform)
        assertEquals("8k2f", xunlei?.pwd)
    }
}
