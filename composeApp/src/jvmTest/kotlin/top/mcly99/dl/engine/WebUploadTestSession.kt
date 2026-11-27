package top.mcly99.dl.engine

import java.net.HttpURLConnection
import java.net.URL

/**
 * P2-18：测试侧的一次性引导客户端。
 *
 * 生产代码把令牌限制在 `GET /session?token=...`，换到 HttpOnly Cookie 后 302 到 `/`；
 * HttpURLConnection 默认不开 CookieManager，所以测试显式取出 Set-Cookie 再手工带上。
 * 这样用例验证的是「会话 Cookie 真的够用」，而不是绕过新协议直接戳内部字段。
 */
internal object WebUploadTestSession {

    data class Result(val code: Int, val headers: Map<String, String>, val cookie: String?)

    fun bootstrap(port: Int, token: String?): Result {
        val query = if (token == null) "" else "?token=$token"
        val conn = (URL("http://127.0.0.1:$port/session$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 10_000
        }
        return try {
            val code = conn.responseCode
            val headers = conn.headerFields.mapNotNull { (k, v) ->
                k?.let { it to v.joinToString(",") }
            }.toMap()
            val cookie = headers.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value?.substringBefore(';')
            Result(code, headers, cookie)
        } finally {
            conn.disconnect()
        }
    }
}
