/*
 * Adapted from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Original: data/network/GitHubTokenStore.kt
 * Downstream changes (N-07):
 *   - upstream FileCredentialCipher (data/security, not ported here) is replaced by
 *     ktdown's existing AES-256-GCM CloudCredentialStorage, keyed on SharePlatform.GITHUB;
 *   - reads/writes can be injected so CloudDriveAccountStore stays the single source of
 *     truth and two storage instances never overwrite each other's full snapshot;
 *   - Log shimmed to top.mcly99.dl.utils.Log.
 */
package top.mcly99.dl.data.network

import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.utils.Log

/**
 * GitHub Token 加密存储。
 *
 * 安全说明：
 * - Token 仅用于提升 GitHub API 限额（未认证 60 次/小时/IP，认证后 5000 次/小时）；
 * - **严禁明文存储**：统一经 `CloudCredentialStorage` 的 AES-GCM 加密后落盘
 *   （`~/.mclydownload/cloud-credentials.properties`，0600/0700）；
 * - **严禁输出到日志**，**严禁在 UI 中回显完整 Token**（编辑器只显示掩码）。
 *
 * 上游以 `AppContext.miscPrefs` + `FileCredentialCipher` 存一份独立密文；ktdown 把
 * GitHub 作为网盘列表里的一张凭证卡片，Token 直接落在 [CloudDriveAccountStore] 的
 * GITHUB 槽位，避免两套加密存储各存一份、互相覆盖。
 */
object GitHubTokenStore {

    private const val TAG = "MclyDownload-GitHub"

    /** 上游键名保留在注释里便于对照：`github_token_encrypted` */

    /**
     * 下游注入点：由 AppViewModel 接到 [CloudDriveAccountStore]，使内存态与落盘态同源。
     * 未注入时退回共享的加密存储（返回 null 表示「未配置」）。
     */
    @Volatile
    var tokenReader: (() -> String?)? = null

    /**
     * 下游注入点：写入/清除 Token。返回是否写入成功。
     * 未注入时退回共享的加密存储。
     */
    @Volatile
    var tokenWriter: ((String?) -> Boolean)? = null

    private val shared get() = CloudDriveAccountStore.sharedStorage

    /** 读取已加密 Token 并解密；未配置或**当前解不开**返回 null */
    fun getToken(): String? {
        val value = runCatching {
            tokenReader?.invoke() ?: shared.load()[SharePlatform.GITHUB]
        }.getOrNull()
        // ★ 解密失败**不要删**（对齐上游 #179「凭证加固」）：密钥一时读不到（文件被占用等）就删掉，
        //   等于把用户配置的 Token 永久丢掉。本次返回 null（界面按「未配置」处理），
        //   下次读取能解开时自动恢复。
        return value?.takeIf { it.isNotBlank() }
    }

    /** 设置/更新 Token；传 null 或空串则清除。返回是否确实写入成功 */
    fun setToken(token: String?): Boolean {
        val trimmed = token?.trim().orEmpty()
        val writer = tokenWriter
        if (writer != null) return runCatching { writer(trimmed.ifBlank { null }) }.getOrDefault(false)
        return runCatching {
            val all = shared.load().toMutableMap()
            if (trimmed.isEmpty()) all.remove(SharePlatform.GITHUB) else all[SharePlatform.GITHUB] = trimmed
            shared.save(all)
            true
        }.getOrElse {
            // 加密失败（密钥暂时不可用）：保留原值，不要覆盖成空
            Log.w(TAG, "Token 加密失败，保留原值：${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 是否「已配置**且当前能读出来**」。
     *
     * ★ 刻意解密一次：密文读不出来时若仍报「已配置」，界面会显示已登录 GitHub，
     *   实际每个请求都在静默走匿名限额（60 次/小时）。调用方应 remember 结果、别每帧调。
     */
    fun hasToken(): Boolean = getToken() != null
}
