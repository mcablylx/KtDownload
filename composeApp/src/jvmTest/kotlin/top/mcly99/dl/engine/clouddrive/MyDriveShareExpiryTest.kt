package top.mcly99.dl.engine.clouddrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareExpire
import top.mcly99.dl.theme.shareExpiryLabel

/**
 * 分享有效期档位：按平台真实能力声明，而不是全局写死「永久 / 7 天」。
 *
 * 之前的写法让 1 天 / 30 天 / 115 的 3 天与 15 天全都选不到 —— 接口本来就支持，
 * 只是 UI 没给入口。这里守住三件事：档位不重复、每个档位都有文案、
 * 不能建分享的平台不许留档位（否则会出现「有选择器但按钮是灰的」）。
 */
class MyDriveShareExpiryTest {

    private val adapters = defaultMyDriveAdapters()

    private fun caps(platform: SharePlatform) = adapters.getValue(platform).capabilities

    @Test
    fun general_platforms_offer_the_four_common_tiers() {
        listOf(
            SharePlatform.QUARK,
            SharePlatform.UC,
            SharePlatform.BAIDU,
            SharePlatform.C139,
            SharePlatform.PAN123,
            SharePlatform.XUNLEI,
            SharePlatform.GUANGYA,
        ).forEach { platform ->
            assertEquals(DEFAULT_SHARE_TIERS, caps(platform).shareExpiryTiers, "platform=$platform")
            assertTrue(caps(platform).sharePasscode, "platform=$platform 支持提取码")
        }
    }

    @Test
    fun pan115_uses_its_own_tier_codes() {
        val tiers = caps(SharePlatform.PAN115).shareExpiryTiers
        assertEquals(
            listOf(
                ShareExpire.PAN115_FOREVER,
                ShareExpire.PAN115_ONE_DAY,
                ShareExpire.PAN115_THREE_DAYS,
                ShareExpire.PAN115_SEVEN_DAYS,
                ShareExpire.PAN115_FIFTEEN_DAYS,
            ),
            tiers,
        )
        // 115 没有 30 天档，也不能把中性码混进来（3 会被当成「7 天」的档位码）
        assertFalse(tiers.contains(ShareExpire.THIRTY_DAYS))
        assertFalse(tiers.contains(ShareExpire.SEVEN_DAYS))
    }

    @Test
    fun lanzou_decides_expiry_and_passcode_server_side() {
        val caps = caps(SharePlatform.LANZOU)
        assertTrue(caps.createShare, "蓝奏能建分享")
        assertTrue(caps.shareExpiryTiers.isEmpty(), "有效期由服务端决定，不该给选择器")
        assertFalse(caps.sharePasscode, "提取码由服务端决定，填了不生效")
    }

    @Test
    fun a_platform_that_cannot_share_has_no_tiers_at_all() {
        val caps = caps(SharePlatform.ILANZOU)
        assertFalse(caps.createShare)
        assertTrue(caps.shareExpiryTiers.isEmpty())
    }

    @Test
    fun every_declared_tier_is_unique_and_has_a_label_in_both_languages() {
        adapters.values.forEach { adapter ->
            val tiers = adapter.capabilities.shareExpiryTiers
            assertEquals(tiers.size, tiers.distinct().size, "重复档位: ${adapter.platform}")
            tiers.forEach { code ->
                assertTrue(shareExpiryLabel(code, english = false).isNotBlank(), "zh 缺文案: $code")
                assertTrue(shareExpiryLabel(code, english = true).isNotBlank(), "en 缺文案: $code")
            }
        }
    }
}
