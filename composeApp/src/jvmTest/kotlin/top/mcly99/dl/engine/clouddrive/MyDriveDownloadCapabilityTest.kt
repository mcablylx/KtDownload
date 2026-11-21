package top.mcly99.dl.engine.clouddrive

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform

/**
 * 「我的网盘」的下载能力位必须与实现一致（P1-15）。
 *
 * 原来 `fullCapabilities()` 把 `download` 写死成 `true`，而 139 / 123 / 115 / 光鸭 /
 * 蓝奏 / 蓝奏优享 的仓库 `getDownloadLink` 恒抛 `UnsupportedOperationException`
 * （那套接口是**分享**链路的）。结果是「下载」按钮一定失败，UI 却提示「已加入下载」，
 * 用户去任务列表里找一个不存在的任务。
 *
 * 这里守两件事：
 * 1. 谁支持、谁不支持，逐个平台钉死；
 * 2. 能力位为 false 时 `downloadLink` 必须直接返回 null 而不发请求（UI 的灰按钮不是装饰）。
 */
class MyDriveDownloadCapabilityTest {

    private val adapters = defaultMyDriveAdapters()

    private fun caps(platform: SharePlatform) = adapters.getValue(platform).capabilities

    @Test
    fun platforms_with_a_real_download_link_declare_the_capability() {
        listOf(
            SharePlatform.QUARK,
            SharePlatform.UC,
            SharePlatform.BAIDU,
            SharePlatform.XUNLEI,
        ).forEach { platform ->
            assertTrue(caps(platform).download, "platform=$platform 的仓库已实现 getDownloadLink")
        }
    }

    @Test
    fun platforms_whose_repository_throws_do_not_declare_it() {
        listOf(
            SharePlatform.C139,
            SharePlatform.PAN123,
            SharePlatform.PAN115,
            SharePlatform.GUANGYA,
            SharePlatform.LANZOU,
            SharePlatform.ILANZOU,
        ).forEach { platform ->
            assertFalse(
                caps(platform).download,
                "platform=$platform 的 getDownloadLink 恒抛 UnsupportedOperationException，" +
                    "声明成可下载只会得到一个必然失败的按钮",
            )
        }
    }

    @Test
    fun a_platform_without_the_capability_returns_null_without_hitting_the_network() = runBlocking {
        // 没有网络也应该立刻返回 null：能力位判断在取链之前
        val api = DefaultMyDriveApi()
        val ctx = DriveContext(platform = SharePlatform.C139, credential = "any")
        val file = MyDriveFile("f-1", "a.mp4", size = 1L, parentId = "0")
        assertNull(api.downloadLink(ctx, file))
    }

    @Test
    fun a_directory_is_never_downloaded_even_on_a_capable_platform() = runBlocking {
        val api = DefaultMyDriveApi()
        val ctx = DriveContext(platform = SharePlatform.QUARK, credential = "any")
        val dir = MyDriveFile("d-1", "目录", isDir = true, parentId = "0")
        assertNull(api.downloadLink(ctx, dir))
    }

    @Test
    fun every_platform_in_the_catalog_has_an_adapter() {
        // 能力位查不到适配器会抛异常；这里保证每个非 GitHub 平台都在表里
        val platforms = CloudDriveCatalog.platforms.map { it.platform }.filter { it != SharePlatform.GITHUB }
        assertEquals(platforms.toSet(), adapters.keys.filter { it != SharePlatform.GITHUB }.toSet())
    }
}
