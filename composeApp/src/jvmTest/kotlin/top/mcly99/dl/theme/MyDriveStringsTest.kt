package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.model.ShareExpire

/** P4 文案表：与 UiStrings 完全独立（P1 的 255 槽 ClassFormatError 教训） */
class MyDriveStringsTest {

    private val ALL_EXPIRY_CODES = listOf(
        ShareExpire.FOREVER, ShareExpire.ONE_DAY, ShareExpire.SEVEN_DAYS, ShareExpire.THIRTY_DAYS,
        ShareExpire.PAN115_FOREVER, ShareExpire.PAN115_ONE_DAY, ShareExpire.PAN115_THREE_DAYS,
        ShareExpire.PAN115_SEVEN_DAYS, ShareExpire.PAN115_FIFTEEN_DAYS,
    )

    private val zh = myDriveStrings("zh-CN")
    private val en = myDriveStrings("en-US")

    private fun plainFields(s: MyDriveStrings) = listOf(
        s.title, s.subtitle, s.platformSection, s.accountSection, s.notLoggedIn,
        s.loginHint, s.loginAction, s.primaryBadge, s.unnamedAccount,
        s.refresh, s.newFolder, s.rename, s.move, s.delete, s.share,
        s.download, s.saveShare, s.selectAll, s.clearSelection, s.up,
        s.loading, s.emptyDir, s.folderTag, s.loadMore, s.loadingMore,
        s.cancel, s.confirm, s.nameLabel,
        s.newFolderTitle, s.newFolderHint, s.renameTitle,
        s.deleteTitle, s.deleteConfirm,
        s.moveTitle, s.moveHere, s.moveUp, s.moveEmpty,
        s.shareTitle, s.passcodeLabel, s.passcodeHint, s.expireLabel,
        s.shareResultTitle, s.shareResultCode, s.copyLink,
        s.saveShareTitle, s.shareLinkLabel, s.shareLinkHint, s.saveShareAction,
        s.noSelection, s.loginRequired, s.saveShareUnsupported, s.capabilityHint,
    )

    @Test
    fun language_selection_uses_the_same_fallback_as_the_rest_of_the_app() {
        assertEquals(zh, myDriveStrings("zh-CN"))
        assertEquals(zh, myDriveStrings("fr-FR"))
        assertEquals(en, myDriveStrings("en-US"))
        assertEquals(en, myDriveStrings("EN-us"))
        assertNotEquals(zh, en)
    }

    @Test
    fun every_language_has_non_blank_labels_and_live_function_labels() {
        listOf(zh, en).forEach { s ->
            plainFields(s).forEach { assertTrue(it.isNotBlank(), "blank label: $it") }
            assertTrue(s.accountLabel("小号", "abc••••34").contains("小号"))
            assertTrue(s.accountLabel("小号", "abc••••34").contains("abc••••34"))
            assertTrue(s.accountCount(3).contains("3"))
            assertTrue(s.selectedCount(2).contains("2"))
            assertTrue(s.itemsCount(7).contains("7"))
            assertTrue(s.loadFailed("401").contains("401"))
            assertTrue(s.quotaLabel("1 GB", "10 GB").contains("10 GB"))
            assertTrue(s.deleteMessage(4).contains("4"))
            assertTrue(s.savedCount(3, 1).contains("3"))
            assertTrue(s.operationFailed("timeout").contains("timeout"))
            assertTrue(s.created("doc").contains("doc"))
            assertTrue(s.renamed("doc").contains("doc"))
            assertTrue(s.moved("dir").contains("dir"))
            assertTrue(s.deleted(2).contains("2"))
            assertTrue(s.downloaded("a.mp4").contains("a.mp4"))
            // 取链失败必须说清「没有地址」，而不是报一句笼统的「失败」：
            // 这条文案就是 P1-15 的修复面（以前这里报的是「已加入下载」）
            assertTrue(s.downloadFailed(3).contains("3"))
            assertTrue(s.downloadedPartial(2, 5).contains("2") && s.downloadedPartial(2, 5).contains("5"))
            // 有效期文案按码位现算：全档位都要有非空文案，不能只认「永久 / 7 天」
            ALL_EXPIRY_CODES.forEach { code ->
                assertTrue(s.expireOption(code).isNotBlank(), "missing expiry label for $code")
            }
            assertTrue(s.expireOption(ShareExpire.PAN115_FIFTEEN_DAYS).contains("15"))
            assertTrue(s.expireOption(ShareExpire.THIRTY_DAYS).contains("30"))
        }
    }

    @Test
    fun unknown_expiry_codes_are_shown_as_numbers_not_silently_mapped() {
        // 认不出的码位不能显示成「永久」——那会让用户以为分享永不过期
        assertTrue(zh.expireOption(999).contains("999"))
        assertTrue(en.expireOption(999).contains("999"))
    }

    @Test
    fun the_page_does_not_add_a_slot_to_ui_strings() {
        // 只要 UiStrings 还能构造出来，就说明没有把它推过 JVM 255 槽上限
        assertTrue(zhCN().cloudDriveStrings.title.isNotBlank())
        assertTrue(enUS().cloudDriveStrings.title.isNotBlank())
        assertTrue(zh.title.isNotBlank())
    }
}
