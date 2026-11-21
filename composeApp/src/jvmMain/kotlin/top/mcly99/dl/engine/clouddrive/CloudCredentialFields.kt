package top.mcly99.dl.engine.clouddrive

import org.json.JSONObject

/**
 * 「多参数凭证」的轻量解析：只给空间配额用。
 *
 * 上游 SQLite 账号库里，迅雷 / 光鸭 / 蓝奏优享版的配额接口除了令牌还要设备标识或 uuid；
 * ktdown 每个平台只有一份字符串凭证，所以约定三种可粘贴的形态：
 *
 * 1. **JSON**：`{"accessToken":"…","deviceId":"…","deviceSign":"…"}`
 *    或 `{"appToken":"…","uuid":"…"}`（同时识别 snake_case 键）；迅雷网页登录产出的
 *    `XunleiDriveCredential.toJson()` 也是这一类，但配额走它自己的解析器。
 * 2. **竖线分隔**：光鸭 `accessToken|deviceId|deviceSign`，蓝奏优享 `appToken|uuid`。
 * 3. **纯令牌**：只当 [accessToken] / [appToken] 用；缺设备标识的平台会安静跳过配额查询。
 *
 * 纯函数，不碰磁盘、不抛异常；解析不出来就退化为「只有令牌」，与既有凭证完全兼容。
 */
data class CloudCredentialFields(
    val raw: String = "",
    val accessToken: String = "",
    val deviceId: String = "",
    val deviceSign: String = "",
    val appToken: String = "",
    val uuid: String = "",
) {
    companion object {
        fun parse(raw: String): CloudCredentialFields {
            val text = raw.trim()
            if (text.isEmpty()) return CloudCredentialFields()

            if (text.startsWith("{")) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json != null) {
                    return CloudCredentialFields(
                        raw = text,
                        accessToken = json.firstOf("accessToken", "access_token"),
                        deviceId = json.firstOf("deviceId", "device_id"),
                        deviceSign = json.firstOf("deviceSign", "device_sign"),
                        appToken = json.firstOf("appToken", "app_token"),
                        uuid = json.firstOf("uuid"),
                    )
                }
            }

            val parts = text.split('|').map { it.trim() }
            return when (parts.size) {
                2 -> CloudCredentialFields(
                    raw = text,
                    accessToken = parts[0],
                    appToken = parts[0],
                    uuid = parts[1],
                )
                3 -> CloudCredentialFields(
                    raw = text,
                    accessToken = parts[0],
                    deviceId = parts[1],
                    deviceSign = parts[2],
                )
                else -> CloudCredentialFields(raw = text, accessToken = text, appToken = text)
            }
        }

        private fun JSONObject.firstOf(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> optString(key, "").takeIf { it.isNotBlank() } }.orEmpty()
    }
}
