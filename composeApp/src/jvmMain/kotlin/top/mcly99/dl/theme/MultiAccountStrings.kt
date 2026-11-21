package top.mcly99.dl.theme

/**
 * 「多账号 / C1 轮换」文案（P3）。
 *
 * 与 [NetworkStrings] 同理：**刻意不并入 [UiStrings]**。它的构造参数已逼近 JVM 方法
 * 签名 255 槽上限，再加字段会「编译通过、运行时 ClassFormatError」（P1 踩过）。
 * 设置页与云盘页各自按 `AppSettings.language` 选表，不给 UiStrings 增加槽位。
 */
data class MultiAccountStrings(
    // ---- 设置页：实验性功能卡片 ----
    val experimentalTitle: String,
    val experimentalDesc: String,
    val multiAccountTitle: String,
    val multiAccountDesc: String,
    val multiAccountRisk: String,
    // ---- 设置页：C2 单文件多源分片（依赖 C1） ----
    val multiSourceTitle: String,
    val multiSourceDesc: String,
    val multiSourceRisk: String,
    // ---- 云盘页：账号管理 ----
    val accountSectionTitle: String,
    val primaryBadge: String,
    /** 参数：备注或默认名、凭证掩码 */
    val accountLabel: (String, String) -> String,
    val unnamedAccount: String,
    val addAccount: String,
    val addAccountHint: String,
    val setPrimary: String,
    val rename: String,
    val remove: String,
    val accountAdded: (String) -> String,
    val accountRemoved: (String) -> String,
    val primaryChanged: (String) -> String,
    /** 卡片副标题里的账号数：参数为账号数量 */
    val accountCount: (Int) -> String,
)

/** 与 [stringsFor] / [networkStrings] 的回落规则一致：除 `en-US` 外一律中文 */
fun multiAccountStrings(language: String): MultiAccountStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSMultiAccount else zhCNMultiAccount

private val zhCNMultiAccount = MultiAccountStrings(
    experimentalTitle = "实验性下载",
    experimentalDesc = "多账号能力默认关闭，开启前请确认已了解风险",
    multiAccountTitle = "多账号轮换下载（C1）",
    multiAccountDesc = "同一网盘登录多个账号时，批量下载按文件轮换账号取链，绕开单账号的并发与限速；单文件仍只用一个账号",
    multiAccountRisk = "实验功能：只有该平台配置了 2 个以上账号才会生效。每个文件取链失败不会自动换账号重试。",
    multiSourceTitle = "单文件多源分片（C2）",
    multiSourceDesc = "夸克 / UC / 迅雷 / 百度的大文件用多个账号各取一条直链，分片轮流下载；某个源失败自动换下一个",
    multiSourceRisk = "需要先开启 C1。只对大于 10 MB 且支持 Range 的分片下载生效；每个账号都要能打开同一个分享，取链失败的那个源会被跳过。多源任务不支持重启后续传。",
    accountSectionTitle = "该网盘的账号",
    primaryBadge = "主账号",
    accountLabel = { name, masked -> "$name · $masked" },
    unnamedAccount = "未命名账号",
    addAccount = "添加账号",
    addAccountHint = "添加前请在登录页点「清除登录态」，否则内嵌浏览器会自动登回当前账号",
    setPrimary = "设为主账号",
    rename = "改备注",
    remove = "退出",
    accountAdded = { "已添加账号：$it" },
    accountRemoved = { "已退出账号：$it" },
    primaryChanged = { "已切换主账号：$it" },
    accountCount = { "$it 个账号" },
)

private val enUSMultiAccount = MultiAccountStrings(
    experimentalTitle = "Experimental downloads",
    experimentalDesc = "Multi-account features are off by default — read the risk note first",
    multiAccountTitle = "Rotate accounts per file (C1)",
    multiAccountDesc = "When a drive has several accounts, batch downloads rotate them per file to work around single-account concurrency and throttling; each file still uses one account",
    multiAccountRisk = "Experimental: only takes effect with 2+ accounts on the platform. A failed link fetch is not retried on another account.",
    multiSourceTitle = "Multi-source chunking per file (C2)",
    multiSourceDesc = "For large files on Quark / UC / Xunlei / Baidu, fetch one direct link per account and rotate sources across chunks; a failed source falls back to the next",
    multiSourceRisk = "Requires C1. Applies only to chunked downloads over 10 MB that support Range; each account must be able to open the same share, and accounts that fail to fetch a link are skipped. Multi-source tasks cannot resume after a restart.",
    accountSectionTitle = "Accounts on this drive",
    primaryBadge = "Primary",
    accountLabel = { name, masked -> "$name · $masked" },
    unnamedAccount = "Unnamed account",
    addAccount = "Add account",
    addAccountHint = "Clear the login state on the login page first, or the embedded browser will sign straight back into the current account",
    setPrimary = "Make primary",
    rename = "Edit remark",
    remove = "Sign out",
    accountAdded = { "Account added: $it" },
    accountRemoved = { "Account removed: $it" },
    primaryChanged = { "Primary account changed: $it" },
    accountCount = { "$it accounts" },
)
