package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P2-10 回归：云盘凭证不再作为服务级共享可变字段。
 *
 * 旧实现用 `useCredential()` 在每次请求开头覆盖 `@Volatile credential`，而 Repository
 * 构造时的 tokenProvider / accountProvider 闭包都读它：并发「浏览 + 批量取链」会串号；
 * 刷新 token 写回时若定位失败还会落到主账号，把 B 的新 token 写进 A。
 *
 * 行为断言覆盖「按凭证隔离实例」；接线用源码契约锁住，避免以后又加回共享字段。
 */
class CloudDriveCredentialIsolationTest {

    @Test
    fun different_credentials_never_share_a_repository() {
        val service = CloudDriveService()
        val a = service.repository(SharePlatform.QUARK, "cookie-A")
        val b = service.repository(SharePlatform.QUARK, "cookie-B")
        assertNotSame(a, b, "不同账号的并发请求必须各自持有 Repository")
        assertSame(a, service.repository(SharePlatform.QUARK, "cookie-A"), "同凭证应复用缓存")
    }

    @Test
    fun token_provider_platforms_are_isolated_too() {
        val service = CloudDriveService()
        assertNotSame(
            service.repository(SharePlatform.PAN123, "A"),
            service.repository(SharePlatform.PAN123, "B"),
        )
        assertNotSame(
            service.repository(SharePlatform.XUNLEI, "A"),
            service.repository(SharePlatform.XUNLEI, "B"),
        )
    }

    @Test
    fun guest_and_logged_in_paths_are_separate_instances() {
        val service = CloudDriveService()
        assertNotSame(
            service.repository(SharePlatform.QUARK, ""),
            service.repository(SharePlatform.QUARK, "cookie-A"),
        )
    }

    @Test
    fun no_shared_credential_field_or_guessed_account_remains() {
        val service = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/clouddrive/CloudDriveService.kt")
        assertFalse(service.contains("private var credential: String"), "服务级共享凭证字段又回来了")
        assertFalse(service.contains("fun useCredential("), "不应再有 useCredential 中转")
        assertTrue(service.contains("data class RepositoryKey(val platform: SharePlatform, val credential: String)"))
        assertTrue(service.contains("repository(platform, credential)"), "凭证必须按请求显式传参")
        assertTrue(
            service.contains("credentialSink?.invoke(SharePlatform.XUNLEI, requestCredential, json)"),
            "刷新结果必须带旧凭证，账号池才能精确定位",
        )

        val vm = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/viewmodel/CloudDriveViewModel.kt")
        assertFalse(vm.contains("activeCredential ="), "ViewModel 不该再用共享字段猜当前账号")
        assertTrue(vm.contains("api.bindCredentialSink { platform, expected, value ->"))

        val myDrive = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/clouddrive/MyDriveApi.kt")
        assertTrue(myDrive.contains("onCredentialRefreshed(platform, expected, value)"))
        assertFalse(myDrive.contains("service.useCredential("))

        val accounts = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/clouddrive/CloudDriveAccounts.kt")
        val updateBody = accounts.substringAfter("fun updateCredential(").substringBefore("private fun replace")
        assertTrue(updateBody.contains("已丢弃该结果"), "定位失败必须明确丢弃")
        assertFalse(updateBody.contains("isPrimary"), "定位失败不得回落主账号")

        val appVm = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/viewmodel/AppViewModel.kt")
        assertTrue(appVm.contains("cloudAccounts.updateCredential(platform, expected, value)"))
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}
