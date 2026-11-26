/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.network

import top.mcly99.dl.data.AppContext
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 迅雷设备指纹管理器（动态生成 + 持久化）：
 * - 每台设备首次启动生成唯一 deviceId/peerId/devicesign，此后永久复用（进程重启不变）；
 * - devicesign 按 §8 公式：div101.{deviceId}{md5(sha1(deviceId + package + appid + appkey))}；
 * - 未初始化（异常路径）时回退到 XunleiConstants 官方抓包指纹，保证行为不崩。
 *
 * 目的：开源分发后每台设备独立指纹，避免所有用户共享一个官方指纹被迅雷风控识别/连带封禁。
 */
object XunleiDeviceFingerprint {

    private const val PREFS = "xunlei_device_fp"
    private const val KEY_ID = "device_id"
    private const val KEY_PEER = "peer_id"
    private const val KEY_SIGN = "device_sign"

    // devicesign 计算常量（与文档 §8 / alist 一致）
    private const val PACKAGE_NAME = "com.xunlei.downloadprovider"
    private const val APPID = "40"
    private const val APP_KEY = "34a062aaa22f906fca4fefe9fb3a3021"
    private const val HEX = "0123456789abcdef"

    @Volatile
    private var initialized = false

    // 未初始化时的 fallback：官方抓包真实设备（保持旧行为，绝不崩）
    @Volatile
    private var deviceId: String = XunleiConstants.DEVICE_ID
    @Volatile
    private var peerId: String = XunleiConstants.PEER_ID
    @Volatile
    private var deviceSign: String = XunleiConstants.DEVICE_SIGN

    /** 进程启动时调用一次；幂等，可重复调用 */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = AppContext.xunleiFpPrefs
            val savedId = prefs.get(KEY_ID, null)
            if (savedId != null) {
                deviceId = savedId
                // 旧版本/配置被改坏时 KEY_PEER/KEY_SIGN 可能缺失：用常量回退而非 !! NPE（P2-5）
                peerId = prefs.get(KEY_PEER, XunleiConstants.PEER_ID) ?: XunleiConstants.PEER_ID
                deviceSign = prefs.get(KEY_SIGN, XunleiConstants.DEVICE_SIGN) ?: XunleiConstants.DEVICE_SIGN
            } else {
                // 首次启动：生成唯一设备指纹并持久化
                val newId = randomHex(32)
                val newPeer = randomHex(32)
                val newSign = buildDeviceSign(newId)
                prefs.put(KEY_ID, newId)
                prefs.put(KEY_PEER, newPeer)
                prefs.put(KEY_SIGN, newSign)
                deviceId = newId
                peerId = newPeer
                deviceSign = newSign
            }
            initialized = true
        }
    }

    /**
     * 取设备号（首次调用时自动 [init]，保证是持久化的随机指纹而非官方常量）。
     *
     * 三个 getter 都先 init：设备号是**请求头 X-Device-Id 的唯一来源**之一，
     * 空值会被服务端判 `invalid_argument / device_id is empty`（就是「请求参数错误」）。
     * 依赖调用方记得在启动时 init 太脆，这里收口成「谁取谁保证就绪」。
     */
    fun deviceId(): String {
        ensure()
        return deviceId
    }

    fun peerId(): String {
        ensure()
        return peerId
    }

    fun deviceSign(): String {
        ensure()
        return deviceSign
    }

    /** 幂等初始化；未初始化时取值会拿到官方抓包常量（多用户共享，风控不友好） */
    private fun ensure(): Boolean {
        if (!initialized) init()
        return initialized
    }

    /** devicesign：div101.{deviceId}{md5(sha1(deviceId + package_name + appid + app_key))} */
    private fun buildDeviceSign(id: String): String {
        val base = id + PACKAGE_NAME + APPID + APP_KEY
        val sha1 = sha1Hex(base)
        val md5 = md5Hex(sha1)
        return "div101.$id$md5"
    }

    private fun randomHex(len: Int): String = buildString {
        repeat(len) { append(HEX[Random.nextInt(16)]) }
    }

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
