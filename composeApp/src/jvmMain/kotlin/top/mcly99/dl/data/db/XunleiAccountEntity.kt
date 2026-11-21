/*
 * Compatibility shim for the ported YunX-Desktop cloud-drive resolver.
 * Only the fields touched by XunleiResolveRepository are modelled; KtDownload
 * does not ship the upstream account database.
 */
package top.mcly99.dl.data.db

data class XunleiAccountEntity(
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    val userId: String = "",
)
