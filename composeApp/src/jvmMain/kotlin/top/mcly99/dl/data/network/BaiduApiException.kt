/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.network

/**
 * 百度网盘 API 业务异常：携带服务端 errno/err_msg，
 * 用于把具体错误原因（如「提取码错误」「分享已失效」）透传给 UI。
 *
 * @property errno 服务端返回的错误码（本地校验失败时为 null）。百度把多种原因都映射到 errno=2，
 *   上层需要按 errno 分支做重试（刷新 bdstoken / 补 BDCLND / 删同名残留）。
 */
class BaiduApiException(message: String, val errno: Int? = null) : Exception(message)