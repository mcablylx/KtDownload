package top.mcly99.dl.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mcly99.dl.engine.clouddrive.CloudAccountBackup
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.theme.DataToolsStrings
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.utils.DiagnosticBundle
import top.mcly99.dl.utils.NativeFileDialog
import java.io.File

/**
 * 设置页「数据与诊断」卡片（自研）。
 *
 * 三件事都做成**明确的按钮 + 结果回显**，不做静默成功：
 * - 导出账号备份：口令加密的 `.ktab`，换机可导入；
 * - 导入账号备份：按平台 + 凭证去重合并，绝不覆盖本机已有账号；
 * - 导出诊断包：环境快照 + 脱敏设置 + 最近日志 + 崩溃留痕。
 *
 * 备份口令只在内存里活一次，不落盘、不进日志；文件对话框与加密/打包都在 IO 线程外等待，
 * 避免设置页看起来「点了没反应」。
 */
@Composable
fun DataToolsSection(
    strings: DataToolsStrings,
    settings: AppSettings,
    accountStore: CloudDriveAccountStore?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 口令弹窗：null = 关闭；Export = 导出；Import = 导入已选文件 */
    var passphraseFor by remember { mutableStateOf<PassphrasePurpose?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FlowDownColors.Card),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = FlowDownColors.Primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(strings.title, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
                    Text(
                        strings.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            ActionRow(
                title = strings.exportBackup,
                description = strings.exportBackupDesc,
                action = strings.exportBackup,
                enabled = !busy && accountStore != null,
            ) {
                if (accountStore == null) {
                    notice = strings.noStore
                } else {
                    passphraseFor = PassphrasePurpose.Export
                }
            }
            ActionRow(
                title = strings.importBackup,
                description = strings.importBackupDesc,
                action = strings.importBackup,
                enabled = !busy && accountStore != null,
            ) {
                if (accountStore == null) {
                    notice = strings.noStore
                    return@ActionRow
                }
                // 先选文件再问口令：用户取消选文件时不该先被要求输口令
                val file = NativeFileDialog.chooseFile(strings.importBackup, CloudAccountBackup.FILE_EXTENSION)
                if (file != null) passphraseFor = PassphrasePurpose.Import(file)
            }
            ActionRow(
                title = strings.diagnostics,
                description = strings.diagnosticsDesc,
                action = strings.diagnostics,
                enabled = !busy,
            ) {
                val target = NativeFileDialog.saveFile(
                    title = strings.diagnostics,
                    defaultName = DiagnosticBundle.defaultFileName(),
                    extension = "zip",
                ) ?: return@ActionRow
                busy = true
                notice = strings.working
                scope.launch {
                    val counts = accountStore?.snapshot()
                        ?.groupingBy { it.platform.name }
                        ?.eachCount()
                        .orEmpty()
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            DiagnosticBundle.export(target.toPath(), settings, counts)
                        }
                    }
                    notice = result.fold(
                        onSuccess = { strings.diagnosticsDone(it.toString()) },
                        onFailure = { strings.diagnosticsFailed(it.message ?: it.javaClass.simpleName) },
                    )
                    busy = false
                }
            }

            if (notice != null) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { notice = null }) { Text(strings.confirm) }
                }
            }
        }
    }

    val purpose = passphraseFor
    if (purpose != null) {
        PassphraseDialog(
            strings = strings,
            onDismiss = { passphraseFor = null },
            onConfirm = { passphrase ->
                passphraseFor = null
                when (purpose) {
                    PassphrasePurpose.Export -> {
                        val target = NativeFileDialog.saveFile(
                            title = strings.exportBackup,
                            defaultName = "kt-accounts.${CloudAccountBackup.FILE_EXTENSION}",
                            extension = CloudAccountBackup.FILE_EXTENSION,
                        )
                        val store = accountStore
                        if (target != null && store != null) {
                            busy = true
                            notice = strings.working
                            scope.launch {
                                val rows = store.snapshot()
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        CloudAccountBackup.export(rows, passphrase, target.toPath(), top.mcly99.dl.APP_VERSION)
                                    }
                                }
                                notice = result.fold(
                                    onSuccess = { if (it == 0) strings.backupEmpty else strings.backupDone(it) },
                                    onFailure = { strings.importFailed(it.message ?: it.javaClass.simpleName) },
                                )
                                busy = false
                            }
                        }
                    }

                    is PassphrasePurpose.Import -> {
                        // 局部 val：后面在协程里用，可空参数无法智能转换
                        val store = accountStore ?: return@PassphraseDialog
                        busy = true
                        notice = strings.working
                        scope.launch {
                            val result = runCatching {
                                val backup = withContext(Dispatchers.IO) {
                                    CloudAccountBackup.import(purpose.file.toPath(), passphrase)
                                }
                                val added = store.mergeAll(backup.accounts)
                                added to (backup.accounts.size - added)
                            }
                            notice = result.fold(
                                onSuccess = { (added, skipped) -> strings.importDone(added, skipped) },
                                onFailure = { strings.importFailed(it.message ?: it.javaClass.simpleName) },
                            )
                            busy = false
                        }
                    }
                }
            },
        )
    }
}

private sealed interface PassphrasePurpose {
    data object Export : PassphrasePurpose
    data class Import(val file: File) : PassphrasePurpose
}

@Composable
private fun ActionRow(
    title: String,
    description: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) FlowDownColors.Foreground else FlowDownColors.MutedForeground,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
        }
        CompactButton(text = action, onClick = onClick, enabled = enabled)
    }
}

/**
 * 口令输入。
 *
 * 最小长度 6 只挡「手滑按了回车」，不是安全强度承诺；真正防暴力破解的是
 * PBKDF2 的 21 万次迭代（见 [CloudAccountBackup]）。
 */
@Composable
private fun PassphraseDialog(
    strings: DataToolsStrings,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val tooShort = text.length in 1..5
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.passphraseTitle) },
        text = {
            Column {
                Text(
                    strings.passphraseLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
                CompactTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = strings.passphraseHint,
                    masked = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tooShort) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        strings.passphraseTooShort,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.Destructive,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.passphraseNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.toCharArray()) },
                enabled = text.length >= 6,
            ) { Text(strings.confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}
