package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform

/**
 * 「网盘列表」用到的平台目录与凭证状态。
 *
 * 对齐 YunX-Desktop 的「网盘」标签页（`DriveScreen.kt`）：那个页面本质就是
 * 「每个网盘一张账号卡片」，卡片显示品牌文字头像、名称、登录徽标、空间配额，
 * 点击卡片进登录页 / 个人文件页，三点菜单管理账号。
 *
 * ktdown 没有照搬上游的 sqlite 账号库（10 张 `*_account` 表），P3 改用自研的
 * 加密账号池 [CloudAccount]：同一平台可存多个账号、其中一条是主账号，
 * 卡片状态由「是否至少有一个账号」决定，解析默认用主账号，C1 开启后批量下载
 * 按文件轮换取链。凭证经 AES-256-GCM 加密后落在 `~/.mclydownload/`，重启后仍有效。
 */

/** 空间配额的轻量投影，避免 UI 直接依赖 AGPL 命名空间的 `QuotaInfo` */
data class CloudQuota(val used: Long, val total: Long) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

data class CloudDrivePlatformInfo(
    val platform: SharePlatform,
    /** 卡片显示名，与上游 DriveScreen 的平台清单一致 */
    val name: String,
    /** 圆形文字头像；上游也没有品牌 PNG，用的是首字/缩写 */
    val avatar: String,
    /** 分享链接域名，卡片副标题用 */
    val shareHost: String,
    /** 「打开官网」跳转地址 */
    val siteUrl: String,
    /** 该平台凭证形态，用于编辑框提示 */
    val credentialLabel: String,
    /** 未配置凭证时能否浏览分享目录 */
    val guestBrowse: Boolean,
    /** 未配置凭证时能否直接取直链下载（上游 ResolveViewModel.supportsGuestDownload） */
    val guestDownload: Boolean,
    /**
     * 移植层是否提供该平台的空间配额接口。
     * 迅雷 / 光鸭 / 蓝奏优享版的接口需要多参数，凭证必须是结构化 JSON
     * （见 [CloudCredentialFields]）；信息不足时 [CloudDriveService.quota] 会安静跳过。
     */
    val supportsQuota: Boolean,
)

object CloudDriveCatalog {
    val platforms: List<CloudDrivePlatformInfo> = listOf(
        CloudDrivePlatformInfo(
            SharePlatform.QUARK, "夸克网盘", "夸", "pan.quark.cn",
            "https://pan.quark.cn/", "Cookie", true, true, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.UC, "UC网盘", "UC", "drive.uc.cn",
            "https://drive.uc.cn/", "Cookie", true, true, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.XUNLEI, "迅雷网盘", "迅", "pan.xunlei.com",
            "https://pan.xunlei.com/", "access token", true, false, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.BAIDU, "百度网盘", "度", "pan.baidu.com",
            "https://pan.baidu.com/", "Cookie", true, false, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.C139, "139网盘", "139", "yun.139.com",
            "https://yun.139.com/", "Cookie / Authorization", true, false, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.PAN123, "123云盘", "123", "www.123pan.com",
            "https://www.123pan.com/", "access token", true, false, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.PAN115, "115网盘", "115", "115.com",
            "https://115.com/", "Cookie", true, false, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.GUANGYA, "光鸭云盘", "光", "guangyapan.com",
            "https://www.guangyapan.com/", "access token", true, true, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.ILANZOU, "蓝奏云优享版", "优", "ilanzou.com",
            "https://www.ilanzou.com/", "appToken / uuid", true, true, true,
        ),
        CloudDrivePlatformInfo(
            SharePlatform.LANZOU, "蓝奏云", "蓝", "lanzou.com",
            "https://www.lanzou.com/", "Cookie（可选）", true, true, false,
        ),
        // GitHub 不是网盘分享平台，但上游也把它列在「网盘」标签页的账号 sheet 里。
        // D-18 落地后这张卡不再占位：点击可填可选的 Personal Access Token
        // （仅用于把 API 限额从 60 次/小时提到 5000 次/小时），匿名也能正常浏览下载。
        CloudDrivePlatformInfo(
            SharePlatform.GITHUB, "GitHub", "GH", "github.com",
            "https://github.com/", "Personal access token（可选）", true, true, false,
        ),
    )

    private val byPlatform = platforms.associateBy { it.platform }

    fun of(platform: SharePlatform): CloudDrivePlatformInfo? = byPlatform[platform]

    /** 所有平台都能保存一份凭证；GitHub 那份是可提升 API 限额的 Token，不参与网盘取链。 */
    fun supportsCredential(platform: SharePlatform): Boolean = true
}

/**
 * 网盘账号池（P3）。
 *
 * 旧版本是「一个平台一份凭证」的 ConcurrentHashMap；现在同一平台可存多条
 * [CloudAccount]，其中恰好一条是主账号。**所有旧 API 保持原语义并映射到主账号**，
 * 因此 `GitHubTokenStore`、配额刷新、清理临时目录、既有测试都不需要改签名。
 *
 * 落盘分两份、只有一个真相源：
 * - `cloud-accounts.properties` 是账号池（[CloudAccountStorage]，复合键 + 独立 AAD）；
 * - `cloud-credentials.properties` 是**兼容投影**，每次变更都写回「每平台主账号」视图，
 *   让未注入读写器的 `GitHubTokenStore`、以及降级回旧版本时仍能读到凭证。
 *
 * 首次升级时若账号池文件不存在，会把旧单凭证自动迁移成该平台的主账号。
 * 明文只在内存里；测试注入 [InMemoryCloudCredentialStorage] 时账号池也自动走内存实现，
 * 绝不读写用户目录。
 */
class CloudDriveAccountStore(
    private val storage: CloudCredentialStorage = sharedStorage,
    accountStorageOverride: CloudAccountStorage? = null,
) {
    private val accountStorage: CloudAccountStorage = accountStorageOverride
        ?: if (storage is FileCloudCredentialStorage) {
            FileCloudAccountStorage()
        } else {
            InMemoryCloudAccountStorage()
        }

    private val lock = Any()
    private val accounts = mutableListOf<CloudAccount>()

    init {
        val loaded = runCatching { accountStorage.load() }
            .onFailure { e ->
                top.mcly99.dl.utils.AppLogger.warn(
                    "CloudDriveAccountStore", "恢复网盘账号池失败，按未登录处理: ${e.message}", e,
                )
            }
            .getOrDefault(emptyList())
        if (loaded.isNotEmpty()) {
            synchronized(lock) { accounts.addAll(normalize(loaded)) }
        } else {
            // 旧版单凭证 → 每个平台一条主账号；只在账号池文件确实为空时迁移，
            // 否则「用户主动清空所有账号」会在下次启动被旧投影文件复活。
            val legacy = runCatching { storage.load() }.getOrDefault(emptyMap())
            val migrated = legacy
                .filter { (_, value) -> value.isNotBlank() }
                .map { (platform, value) ->
                    CloudAccount(
                        id = LEGACY_ACCOUNT_ID,
                        platform = platform,
                        credential = value,
                        isPrimary = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            if (migrated.isNotEmpty()) {
                synchronized(lock) { accounts.addAll(normalize(migrated)) }
                persist()
            }
        }
    }

    // ------------------------------------------------------------ 旧 API（主账号视图）

    fun credentialFor(platform: SharePlatform): String = primary(platform)?.credential.orEmpty()

    fun isConfigured(platform: SharePlatform): Boolean = credentialFor(platform).isNotBlank()

    fun configuredPlatforms(): List<SharePlatform> =
        CloudDriveCatalog.platforms.map { it.platform }.filter(::isConfigured)

    /**
     * 覆盖该平台的**主账号**凭证（对齐上游 `resolveAccountSlot`：日常重新登录不新建账号）。
     * 传空串 = 清空该平台全部账号，与旧版 `values.remove(platform)` 行为一致。
     */
    fun set(platform: SharePlatform, value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank() || !CloudDriveCatalog.supportsCredential(platform)) {
            clear(platform)
            return
        }
        synchronized(lock) {
            val current = accounts.firstOrNull { it.platform == platform && it.isPrimary }
            if (current == null) {
                accounts.add(
                    CloudAccount(
                        id = LEGACY_ACCOUNT_ID,
                        platform = platform,
                        credential = trimmed,
                        isPrimary = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } else {
                replace(current, current.copy(credential = trimmed, updatedAt = System.currentTimeMillis()))
            }
            normalizeInPlace()
        }
        persist()
    }

    fun clear(platform: SharePlatform) {
        synchronized(lock) { accounts.removeAll { it.platform == platform } }
        persist()
    }

    /** 卡片副标题：只露首尾几位，参考上游 `maskAccountText` */
    fun masked(platform: SharePlatform): String =
        primary(platform)?.let(::maskCredential).orEmpty()

    /** 账号列表用：按单条账号掩码，而不是按平台取主账号 */
    fun maskCredential(account: CloudAccount): String {
        // 迅雷存的是结构化凭证，掩码整串 JSON 只会显示成 `{"a••••"}`；
        // 这里把 access_token 的前缀露出来，一眼能看出「是不是换了个账号」
        if (account.platform == SharePlatform.XUNLEI) {
            XunleiDriveCredential.parse(account.credential)
                ?.takeIf { it.isUsable }
                ?.let { return it.preview() }
        }
        return mask(account.credential)
    }

    // ------------------------------------------------------------ 多账号 API

    /** 该平台全部账号，主账号在前、其余按更新时间倒序（与上游 `ORDER BY isPrimary DESC, updatedAt DESC` 一致） */
    fun accountsFor(platform: SharePlatform): List<CloudAccount> = synchronized(lock) {
        accounts.filter { it.platform == platform }
    }

    /** C1 轮换取链用：主账号优先、去空、去重（两条相同 Cookie 轮换没有意义） */
    fun allCredentials(platform: SharePlatform): List<String> =
        accountsFor(platform).map { it.credential.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    fun accountCount(platform: SharePlatform): Int = accountsFor(platform).size

    fun primary(platform: SharePlatform): CloudAccount? = synchronized(lock) {
        accounts.firstOrNull { it.platform == platform && it.isPrimary }
            ?: accounts.firstOrNull { it.platform == platform }
    }

    fun accountById(platform: SharePlatform, id: String): CloudAccount? =
        accountsFor(platform).firstOrNull { it.id == id }

    /** 导出备份用：当前全部账号的不可变快照（含明文凭证，只在内存里传递） */
    fun snapshot(): List<CloudAccount> = synchronized(lock) { accounts.toList() }

    /**
     * 合并备份里的账号，返回**新增**条数。
     *
     * 去重口径是「平台 + 凭证原文」：同一份 Cookie 重复导入不会产生两个账号。
     * id 冲突但凭证不同时（另一台机器上随机生成的 id 撞了）换一个新 id，
     * 否则会把本机已有账号**覆盖**掉 —— 恢复备份不该有删除语义。
     */
    fun mergeAll(rows: List<CloudAccount>): Int {
        if (rows.isEmpty()) return 0
        var added = 0
        synchronized(lock) {
            for (row in rows) {
                val value = row.credential.trim()
                if (value.isBlank()) continue
                if (accounts.any { it.platform == row.platform && it.credential == value }) continue
                val id = if (accounts.any { it.id == row.id }) newCloudAccountId() else row.id
                accounts.add(row.copy(id = id, credential = value))
                added++
            }
            if (added > 0) normalizeInPlace()
        }
        if (added > 0) persist()
        return added
    }

    /** 「添加账号」：新建一条非主账号。空凭证返回 null */
    fun addAccount(
        platform: SharePlatform,
        credential: String,
        nickname: String = "",
        remark: String = "",
    ): String? {
        val value = credential.trim()
        if (value.isBlank()) return null
        val id = newCloudAccountId()
        synchronized(lock) {
            accounts.add(
                CloudAccount(
                    id = id,
                    platform = platform,
                    credential = value,
                    nickname = nickname.trim(),
                    remark = remark.trim(),
                    isPrimary = accounts.none { it.platform == platform },
                    updatedAt = System.currentTimeMillis(),
                )
            )
            normalizeInPlace()
        }
        persist()
        return id
    }

    /** 设为主账号：同平台其余账号一律取消主账号标记 */
    fun setPrimary(platform: SharePlatform, id: String) {
        synchronized(lock) {
            if (accounts.none { it.platform == platform && it.id == id }) return
            accounts.replaceAll {
                if (it.platform == platform) it.copy(isPrimary = it.id == id) else it
            }
        }
        persist()
    }

    fun rename(platform: SharePlatform, id: String, remark: String) {
        synchronized(lock) {
            val row = accounts.firstOrNull { it.platform == platform && it.id == id } ?: return
            replace(row, row.copy(remark = remark.trim()))
        }
        persist()
    }

    /** 删单个账号；若删的是主账号，[normalizeInPlace] 自动把最近更新的一条补成主账号 */
    fun remove(platform: SharePlatform, id: String) {
        synchronized(lock) {
            accounts.removeAll { it.platform == platform && it.id == id }
            normalizeInPlace()
        }
        persist()
    }

    /**
     * 解析层刷新出的新凭证（迅雷 access_token）写回**当时正在使用的那个账号**。
     *
     * P2-10：解析层把「本次请求用的旧凭证」随结果一起带出（不再靠 ViewModel 的共享
     * `activeCredential` 字段去猜），这里按凭证原文精确定位。
     * 定位失败时**丢弃**这次刷新结果，绝不回落主账号 —— 旧实现的回落正是多账号串号的
     * 来源：B 账号刷新出的 token 被静默写进 A，A 的凭证从此变成另一个账号的。
     */
    fun updateCredential(platform: SharePlatform, expected: String, newValue: String) {
        val value = newValue.trim()
        if (value.isBlank()) return
        val updated = synchronized(lock) {
            val row = accounts.firstOrNull {
                it.platform == platform && it.credential == expected && expected.isNotBlank()
            }
            if (row == null) {
                top.mcly99.dl.utils.AppLogger.warn(
                    "CloudDriveAccounts",
                    "刷新凭证写回失败：找不到 expected 对应的 ${platform.name} 账号，已丢弃该结果",
                )
                false
            } else {
                replace(row, row.copy(credential = value, updatedAt = System.currentTimeMillis()))
                true
            }
        }
        if (updated) persist()
    }

    // ------------------------------------------------------------ 内部

    private fun replace(old: CloudAccount, new: CloudAccount) {
        val index = accounts.indexOf(old)
        if (index >= 0) accounts[index] = new else accounts.add(new)
    }

    /** 保证每个平台「有账号就恰好有一个主账号」，并把主账号排到最前 */
    private fun normalizeInPlace() {
        val normalized = normalize(accounts)
        accounts.clear()
        accounts.addAll(normalized)
    }

    private fun normalize(rows: List<CloudAccount>): List<CloudAccount> =
        rows.filter { it.credential.isNotBlank() }
            .groupBy { it.platform }
            .values
            .flatMap { group ->
                val ordered = group.sortedWith(
                    compareByDescending<CloudAccount> { it.isPrimary }
                        .thenByDescending { it.updatedAt }
                )
                ordered.mapIndexed { index, row -> row.copy(isPrimary = index == 0) }
            }

    private fun primaryView(): Map<SharePlatform, String> = synchronized(lock) {
        SharePlatform.values().mapNotNull { platform ->
            val value = accounts.firstOrNull { it.platform == platform && it.isPrimary }
                ?: accounts.firstOrNull { it.platform == platform }
            value?.credential?.takeIf { it.isNotBlank() }?.let { platform to it }
        }.toMap()
    }

    private fun persist() {
        val snapshot = synchronized(lock) { accounts.toList() }
        runCatching { accountStorage.save(snapshot) }
            .onFailure { e ->
                top.mcly99.dl.utils.AppLogger.warn(
                    "CloudDriveAccountStore", "网盘账号池持久化失败: ${e.message}", e,
                )
            }
        // 兼容投影：旧版加密文件始终保留「每平台主账号」视图
        runCatching { storage.save(primaryView()) }
            .onFailure { e ->
                top.mcly99.dl.utils.AppLogger.warn(
                    "CloudDriveAccountStore", "网盘凭证兼容投影写入失败: ${e.message}", e,
                )
            }
    }

    companion object {
        const val LEGACY_ACCOUNT_ID = "legacy-primary"

        /**
         * 默认落盘实例由所有凭证消费者共用：`GitHubTokenStore` 也读同一份加密
         * properties，两个实例各自 `load()` 全量再 `save()` 会互相覆盖丢失条目。
         * 测试可给 [CloudDriveAccountStore] 注入 [InMemoryCloudCredentialStorage]。
         */
        val sharedStorage: CloudCredentialStorage = FileCloudCredentialStorage()

        /**
         * 进程内唯一的账号池实例。
         *
         * [persist] 每次写的是**全量快照**，两个实例各自持有内存副本时会互相覆盖：
         * 「云盘」页加了一个账号 → 实例 A 落盘；随后「我的网盘」再改一次 →
         * 实例 B 用它启动时读到的旧快照落盘，A 刚加的账号被静默抹掉。
         * 所有 ViewModel 一律通过这里取同一份，测试要隔离时显式注入内存实现。
         */
        val shared: CloudDriveAccountStore by lazy { CloudDriveAccountStore() }

        fun mask(value: String): String {
            val v = value.trim()
            if (v.isBlank()) return ""
            if (v.length <= 8) return "${v.take(2)}••••"
            return "${v.take(3)}••••${v.takeLast(2)}"
        }
    }
}
