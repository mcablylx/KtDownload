package top.mcly99.dl.engine.clouddrive

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * 账号池必须是进程内唯一实例。
 *
 * 背景：`persist()` 写的是全量快照。云盘页和「我的网盘」各自 `CloudDriveAccountStore()`
 * 时，两个实例各持一份启动时的内存副本 —— A 加了账号并落盘，B 随后改一次，
 * 就用它那份旧快照把 A 的账号覆盖掉（静默丢数据）。所有 VM 必须取同一个 [CloudDriveAccountStore.shared]。
 */
class SharedAccountStoreTest {

    @Test
    fun the_process_wide_account_pool_is_one_instance() {
        assertSame(CloudDriveAccountStore.shared, CloudDriveAccountStore.shared)
    }
}
