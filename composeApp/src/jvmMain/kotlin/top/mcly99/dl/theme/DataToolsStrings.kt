package top.mcly99.dl.theme

/**
 * 「数据与诊断」文案（自研，双语）。
 *
 * 与 [NetworkStrings] / [MultiAccountStrings] / [MyDriveStrings] 同一条理由：
 * **不进 [UiStrings]** —— 它的构造参数已逼近 JVM 方法签名 255 槽上限，
 * 再加字段会「编译通过、运行时 ClassFormatError」。
 */
data class DataToolsStrings(
    val title: String,
    val desc: String,
    // ---- 三行动作 ----
    val exportBackup: String,
    val exportBackupDesc: String,
    val importBackup: String,
    val importBackupDesc: String,
    val diagnostics: String,
    val diagnosticsDesc: String,
    // ---- 口令弹窗 ----
    val passphraseTitle: String,
    val passphraseLabel: String,
    val passphraseHint: String,
    val passphraseNote: String,
    val passphraseTooShort: String,
    val cancel: String,
    val confirm: String,
    // ---- 状态 ----
    val working: String,
    /** 参数：导出账号数 */
    val backupDone: (Int) -> String,
    val backupEmpty: String,
    /** 参数：新增数、已存在而跳过数 */
    val importDone: (Int, Int) -> String,
    /** 参数：错误信息 */
    val importFailed: (String) -> String,
    /** 参数：诊断包路径 */
    val diagnosticsDone: (String) -> String,
    /** 参数：错误信息 */
    val diagnosticsFailed: (String) -> String,
    /** 账号池尚未初始化（理论上不会出现，防御性提示） */
    val noStore: String,
)

fun dataToolsStrings(language: String): DataToolsStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSDataTools else zhCNDataTools

private val zhCNDataTools = DataToolsStrings(
    title = "数据与诊断",
    desc = "账号备份、问题排查材料",
    exportBackup = "导出账号备份",
    exportBackupDesc = "用自己设的口令加密，换机后可原样导入；忘记口令无法恢复",
    importBackup = "导入账号备份",
    importBackupDesc = "按平台 + 凭证去重合并，不会覆盖或删除本机已有账号",
    diagnostics = "导出诊断包",
    diagnosticsDesc = "环境快照、脱敏设置、最近日志与崩溃留痕打成一个 zip，便于排查",
    passphraseTitle = "设置备份口令",
    passphraseLabel = "口令",
    passphraseHint = "至少 6 个字符",
    passphraseNote = "口令只用于本次加密，不会写进任何文件；丢失后备份无法恢复。",
    passphraseTooShort = "口令至少 6 个字符",
    cancel = "取消",
    confirm = "确定",
    working = "正在处理…",
    backupDone = { "已导出 $it 个账号" },
    backupEmpty = "当前没有可备份的账号",
    importDone = { added, skipped -> "新增 $added 个账号，跳过 $skipped 个已存在" },
    importFailed = { "导入失败：$it" },
    diagnosticsDone = { "诊断包已保存到 $it" },
    diagnosticsFailed = { "导出失败：$it" },
    noStore = "账号池不可用，请重启应用后重试",
)

private val enUSDataTools = DataToolsStrings(
    title = "Data & diagnostics",
    desc = "Account backup and troubleshooting material",
    exportBackup = "Export account backup",
    exportBackupDesc = "Encrypted with your own passphrase; import it on another machine. Lost passphrase cannot be recovered",
    importBackup = "Import account backup",
    importBackupDesc = "Merged by drive + credential; existing accounts are never overwritten or removed",
    diagnostics = "Export diagnostics",
    diagnosticsDesc = "Environment snapshot, redacted settings, recent logs and crash traces in one zip",
    passphraseTitle = "Set backup passphrase",
    passphraseLabel = "Passphrase",
    passphraseHint = "At least 6 characters",
    passphraseNote = "The passphrase is only used for this file and is never written to disk; losing it makes the backup unrecoverable.",
    passphraseTooShort = "Passphrase must be at least 6 characters",
    cancel = "Cancel",
    confirm = "OK",
    working = "Working…",
    backupDone = { "$it account(s) exported" },
    backupEmpty = "No accounts to back up yet",
    importDone = { added, skipped -> "$added added, $skipped already present" },
    importFailed = { "Import failed: $it" },
    diagnosticsDone = { "Diagnostics saved to $it" },
    diagnosticsFailed = { "Export failed: $it" },
    noStore = "Account store unavailable, restart the app and retry",
)
