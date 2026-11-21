package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.BaiduApi
import top.mcly99.dl.data.network.QuarkApi
import top.mcly99.dl.data.network.UCApi
import top.mcly99.dl.data.network.XunleiWebCredential
import top.mcly99.dl.data.repository.BaiduResolveRepository
import top.mcly99.dl.data.repository.QuarkResolveRepository
import top.mcly99.dl.data.repository.UCResolveRepository
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 覆盖「回落范围由 Repository 自己声明」与「迅雷分片拼接只有一个实现」这两处收敛，
 * 以及三平台配额所需的多参数凭证解析。全部不发网络请求。
 */
class CloudDriveResolverContractTest {

    @Test
    fun no_save_fallback_is_declared_only_by_repositories_that_override_it() {
        // 只有真正覆写了 getShareDownloadLinkWithoutSave 的平台，失败后才有第二次机会
        assertTrue(QuarkResolveRepository(QuarkApi()).supportsSeparateNoSaveLink)
        assertTrue(BaiduResolveRepository(BaiduApi()).supportsSeparateNoSaveLink)
        assertFalse(UCResolveRepository(UCApi()).supportsSeparateNoSaveLink)
    }

    @Test
    fun xunlei_bridge_cookie_shares_are_reassembled_with_both_tokens() {
        val raw = """{"access_token":"access_token_1234567890","refresh_token":"refresh_token_1234567890"}"""
        val encoded = URLEncoder.encode(raw, Charsets.UTF_8.name())
        val half = encoded.length / 2
        val pairs = mapOf(
            XunleiWebCredential.CRED_COOKIE_COUNT to "2",
            "${XunleiWebCredential.CRED_COOKIE_PREFIX}0" to encoded.take(half),
            "${XunleiWebCredential.CRED_COOKIE_PREFIX}1" to encoded.substring(half),
            "deviceid" to "prefix.ABCDEF0123456789ABCDEF0123456789",
        )

        val cred = XunleiDriveCredential.fromCookiePairs(pairs)
            ?: error("分片 Cookie 应能还原出完整凭证")
        assertEquals("access_token_1234567890", cred.accessToken)
        assertEquals("refresh_token_1234567890", cred.refreshToken)
        assertEquals("ABCDEF0123456789ABCDEF0123456789".take(32), cred.deviceId)
        assertTrue(cred.isUsable)
    }

    @Test
    fun xunlei_bare_access_token_in_cookie_still_works_without_the_old_entry_point() {
        val pairs = mapOf(
            "access_token" to "access_token_1234567890",
            "refresh_token" to "refresh_token_1234567890",
        )
        val cred = XunleiDriveCredential.fromCookiePairs(pairs)
            ?: error("裸 token 兜底不应丢失")
        assertEquals("access_token_1234567890", cred.accessToken)
        assertEquals("refresh_token_1234567890", cred.refreshToken)
        assertNull(XunleiDriveCredential.fromCookiePairs(emptyMap()))
    }

    @Test
    fun credential_fields_parse_json_pipe_and_plain_token_forms() {
        val json = CloudCredentialFields.parse(
            """{"access_token":"tok","device_id":"dev","device_sign":"sig","app_token":"app","uuid":"uid"}""",
        )
        assertEquals("tok", json.accessToken)
        assertEquals("dev", json.deviceId)
        assertEquals("sig", json.deviceSign)
        assertEquals("app", json.appToken)
        assertEquals("uid", json.uuid)

        val guangya = CloudCredentialFields.parse("token123|device-1|sign-1")
        assertEquals("token123", guangya.accessToken)
        assertEquals("device-1", guangya.deviceId)
        assertEquals("sign-1", guangya.deviceSign)

        val ilanzou = CloudCredentialFields.parse("app-1|uuid-1")
        assertEquals("app-1", ilanzou.appToken)
        assertEquals("uuid-1", ilanzou.uuid)

        // 既有纯令牌凭证：只当令牌用，设备信息缺失 → 配额分支会安静跳过
        val plain = CloudCredentialFields.parse("plain-token-123")
        assertEquals("plain-token-123", plain.accessToken)
        assertTrue(plain.deviceId.isEmpty())
        assertTrue(plain.uuid.isEmpty())
    }
}
