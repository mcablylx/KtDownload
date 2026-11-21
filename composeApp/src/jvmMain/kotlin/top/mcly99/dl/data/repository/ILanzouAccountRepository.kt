/*
 * Compatibility shim for the ported YunX-Desktop cloud-drive resolver.
 * KtDownload has no account database; credentials are supplied through the
 * cloud-drive tool's credential field instead.
 */
package top.mcly99.dl.data.repository

data class ILanzouAccount(
    val userId: String = "",
    val appToken: String = "",
    val uuid: String = "",
)

class ILanzouAccountRepository(
    private val account: ILanzouAccount? = null,
) {
    fun getAccount(): ILanzouAccount? = account
}
