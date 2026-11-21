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
 * 夸克 API 业务异常：携带服务端返回的 message 与 code 字段，
 * 用于把具体错误原因（如「提取码错误」「分享已失效」「file not found」）透传给 UI；
 * code 供上层识别特定错误（如 21001 触发兜底重转）。
 */
class QuarkApiException(message: String, val code: Int = 0) : Exception(message)