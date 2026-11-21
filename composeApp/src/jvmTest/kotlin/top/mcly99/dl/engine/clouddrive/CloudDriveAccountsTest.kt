package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 「网盘列表」平台目录与凭证状态：对齐 YunX 网盘标签页的账号卡片 */
class CloudDriveAccountsTest {

    @Test
    fun catalog_covers_every_share_platform_including_github() {
        val catalog = CloudDriveCatalog.platforms.map { it.platform }.toSet()
        // D-18 落地后 GitHub 卡片不再占位：它是「可选 Token」配置入口，
        // 链接解析也已接入，因此目录覆盖全部 SharePlatform 枚举。
        val expected = SharePlatform.values().toSet()
        assertEquals(expected, catalog)
        assertEquals(expected.size, CloudDriveCatalog.platforms.size)
        assertTrue(SharePlatform.GITHUB in catalog)
        assertTrue(CloudDriveCatalog.platforms.all { it.name.isNotBlank() && it.avatar.isNotBlank() })
        assertEquals(
            CloudDriveCatalog.platforms.size,
            CloudDriveCatalog.platforms.map { it.name }.toSet().size,
        )
        assertNotNull(CloudDriveCatalog.of(SharePlatform.XUNLEI))
    }

    @Test
    fun github_card_supports_an_optional_token_but_no_quota() {
        val info = assertNotNull(CloudDriveCatalog.of(SharePlatform.GITHUB))
        assertTrue(CloudDriveCatalog.supportsCredential(SharePlatform.GITHUB))
        assertFalse(info.supportsQuota)
        assertTrue(info.guestDownload)
        assertTrue(CloudDriveCatalog.supportsCredential(SharePlatform.QUARK))
    }

    @Test
    fun quota_capability_covers_nine_platforms() {
        val supported = CloudDriveCatalog.platforms.filter { it.supportsQuota }.map { it.platform }.toSet()
        assertEquals(9, supported.size)
        assertTrue(SharePlatform.XUNLEI in supported)
        assertTrue(SharePlatform.GUANGYA in supported)
        assertTrue(SharePlatform.ILANZOU in supported)
        // 蓝奏云官方没有配额接口
        assertFalse(SharePlatform.LANZOU in supported)
    }

    @Test
    fun guest_download_matches_upstream_capability() {
        val guestDownload = CloudDriveCatalog.platforms
            .filter { it.guestDownload }
            .map { it.platform }
            .toSet()
        assertEquals(
            setOf(
                SharePlatform.QUARK, SharePlatform.UC, SharePlatform.GUANGYA,
                SharePlatform.ILANZOU, SharePlatform.LANZOU, SharePlatform.GITHUB,
            ),
            guestDownload,
        )
    }

    @Test
    fun store_is_per_platform_and_can_hold_a_github_token() {
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        store.set(SharePlatform.QUARK, "  quark-cookie  ")
        assertEquals("quark-cookie", store.credentialFor(SharePlatform.QUARK))
        assertTrue(store.isConfigured(SharePlatform.QUARK))
        assertFalse(store.isConfigured(SharePlatform.UC))
        assertEquals(listOf(SharePlatform.QUARK), store.configuredPlatforms())

        store.set(SharePlatform.UC, "uc-token")
        assertEquals(2, store.configuredPlatforms().size)

        store.set(SharePlatform.QUARK, "   ")
        assertFalse(store.isConfigured(SharePlatform.QUARK))

        // D-18：GitHub Token 与网盘 Cookie 走同一份加密存储，只是不参与网盘取链
        store.set(SharePlatform.GITHUB, " ghp_example ")
        assertEquals("ghp_example", store.credentialFor(SharePlatform.GITHUB))
        assertTrue(store.isConfigured(SharePlatform.GITHUB))

        store.clear(SharePlatform.UC)
        store.clear(SharePlatform.GITHUB)
        assertTrue(store.configuredPlatforms().isEmpty())
    }

    @Test
    fun mask_only_exposes_head_and_tail() {
        assertEquals("", CloudDriveAccountStore.mask("  "))
        assertEquals("ab••••", CloudDriveAccountStore.mask("abcdef"))
        val masked = CloudDriveAccountStore.mask("whole-cookie-value-1234")
        assertTrue(masked.startsWith("who"))
        assertTrue(masked.endsWith("34"))
        assertTrue(masked.contains("••••"))
        assertFalse(masked.contains("cookie"))
    }

    @Test
    fun quota_fraction_is_clamped() {
        assertEquals(0.25f, CloudQuota(25L, 100L).fraction)
        assertEquals(0f, CloudQuota(1L, 0L).fraction)
        assertEquals(1f, CloudQuota(200L, 100L).fraction)
    }

    @Test
    fun service_quota_returns_null_without_a_credential_or_for_unsupported_platform() =
        kotlinx.coroutines.test.runTest {
            val service = CloudDriveService()
            // 不发网络请求：空凭证与不支持配额的平台都应直接返回 null
            assertEquals(null, service.quota(SharePlatform.QUARK, ""))
            assertEquals(null, service.quota(SharePlatform.LANZOU, "cookie"))
            assertEquals(null, service.quota(SharePlatform.GITHUB, "token"))
        }
}
