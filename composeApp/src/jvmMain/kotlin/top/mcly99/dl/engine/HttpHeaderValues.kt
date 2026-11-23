package top.mcly99.dl.engine

import java.net.HttpURLConnection

/**
 * 进入 HTTP 请求头之前统一清洗头值，防止「非 ASCII 头值」把整条下载链路打断。
 *
 * 来源：上游 v1.3.4 修了浏览器插件回传的 Cookie 含中文时 OkHttp 在 `header()` 阶段抛
 * `Unexpected char 0x4e2d ... in Cookie value` 的问题（commit `1830497`）。下游虽然用
 * `HttpURLConnection`，但抓网页是通过 CDP `Network.getAllCookies` 拿 Cookie 的，值同样
 * 可能含中文 / emoji；OkHttp 侧的各平台 API 也会把登录 Cookie 原样塞进请求头。
 *
 * 这里的口径比上游更严一点：**只放行可见 ASCII（0x20..0x7E）**，其余字符（中文、emoji、
 * CR/LF、TAB、其它控制字符、0x7F）按 UTF-8 逐字节转成 `%XX`。这样同时堵住头注入，
 * 且信息不丢失 —— 服务端看到的仍是百分号编码后的原始字节，正常的 ASCII Cookie 一个字符
 * 都不会变。
 *
 * 注意：只清洗「值」，不清洗「键」；头名由代码里的常量决定，不从外部输入来。
 */
internal fun sanitizeHttpHeaderValue(value: String): String {
    if (value.isEmpty()) return value
    if (value.all { it.code in 0x20..0x7E }) return value

    val out = StringBuilder(value.length + 16)
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        if (cp in 0x20..0x7E) {
            out.append(cp.toChar())
        } else {
            // UTF-8 字节逐个百分号编码；大写十六进制，与 RFC 6265 的兜底写法一致
            String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { byte ->
                val v = byte.toInt() and 0xFF
                out.append('%').append(HEX[v ushr 4]).append(HEX[v and 0xF])
            }
        }
        i += Character.charCount(cp)
    }
    return out.toString()
}

private val HEX = "0123456789ABCDEF"

/** RFC 7230 token 允许的字符：可见 ASCII 去掉分隔符 */
private const val TOKEN_SEPARATORS = "()<>@,;:\\\"/[]?={} \t"

/**
 * 判断字符串是否是合法的 HTTP token（可用于请求头名 / Cookie 名）。
 *
 * Cookie **名**不能像值那样百分号编码——编码后服务端就认不出这个 Cookie 了，
 * 所以外部页面注入的非法名字只能整条丢弃（P2-E1）。
 */
internal fun isHttpToken(text: String): Boolean {
    if (text.isEmpty()) return false
    return text.all { it.code in 0x21..0x7E && it !in TOKEN_SEPARATORS }
}

/**
 * 登录凭证入站清洗：`k=v; k=v` 这种 Cookie 串可以放心编码，但迅雷的结构化凭证是
 * JSON（里面可能有换行 / 引号 / 非 ASCII 字段），一旦整串百分号编码就再也没法解析了。
 *
 * 所以只清洗「非结构化」的凭证；结构化凭证里的非 ASCII 值由各平台 API 自己按 JSON 转义，
 * 不会作为 HTTP 头值出现。
 */
internal fun sanitizeCredentialForHeaders(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("{") || trimmed.startsWith("[")) value
    else sanitizeHttpHeaderValue(value)
}

/**
 * [HttpURLConnection.setRequestProperty] 的安全替身：值统一走 [sanitizeHttpHeaderValue]。
 *
 * 引擎里所有 `setRequestProperty` 都换成了它，避免某条链路（主下载 / HLS / 媒体探测 /
 * 网页抓取）漏掉清洗，又各自复制一份实现。
 */
internal fun HttpURLConnection.setSafeHeader(name: String, value: String) {
    setRequestProperty(name, sanitizeHttpHeaderValue(value))
}
