package top.mcly99.dl.data.network

/**
 * 夸克 / UC 共用的 Cookie 工具（P3-1）。
 *
 * 两个平台的登录态都靠 `__puus`（约 3 小时过期，取链签名校验必需）与 `__pus`，
 * 游客态靠 `__pugs`，协议完全一致（对应 AList 的 `pkg/cookie` 与 `requestWithCookie`）。
 * 旧实现在 `QuarkCookieUtil` 与 `UCCookieUtil` 里逐字复制了同一份实现，改一处漏一处的
 * 风险很高，这里收敛成唯一实现，两边都指向它。
 */
internal object CookieUtil {

    /** 需要跟随响应回写的会话字段 */
    private val TRACKED = setOf("__puus", "__pus")

    /** 游客态令牌名 */
    private const val GUEST_PUGS = "__pugs"

    /**
     * 把响应 `Set-Cookie` 列表里的最新 `__puus` / `__pus` 合并回原 Cookie 串。
     *
     * 只取每条的 `name=value` 部分，忽略 `Path` / `Domain` / `expires` 等属性；
     * 同名已存在则替换、不存在则追加，其余字段顺序保持不变。
     */
    fun mergeFromSetCookies(original: String, setCookies: List<String>): String {
        var cookie = original
        for (sc in setCookies) {
            val kv = sc.substringBefore(';').trim()
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val name = kv.substring(0, eq)
            if (name in TRACKED) cookie = setOrReplace(cookie, name, kv.substring(eq + 1))
        }
        return cookie
    }

    /** 去掉 `__puus`，用于触发服务端重新下发（AList 的 refreshPuus） */
    fun withoutPuus(cookie: String): String =
        cookie.split(";").map { it.trim() }
            .filter { !it.startsWith("__puus=") }
            .joinToString("; ")

    /** 设置或替换单个 Cookie 字段，保持其它字段顺序 */
    fun setOrReplace(cookie: String, name: String, value: String): String {
        val parts = cookie.split(";").map { it.trim() }.toMutableList()
        val idx = parts.indexOfFirst { it.startsWith("$name=") }
        val kv = "$name=$value"
        if (idx >= 0) parts[idx] = kv else parts.add(kv)
        return parts.joinToString("; ")
    }

    /**
     * 从响应 `Set-Cookie` 里取出游客态令牌，返回可直接当 Cookie 头用的 `__pugs=值`；
     * 没有（或值为空）时返回 null —— 空值不能当成有效令牌发出去。
     */
    fun pugsFromSetCookies(setCookies: List<String>): String? =
        setCookies.asSequence()
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("$GUEST_PUGS=") && it.length > GUEST_PUGS.length + 1 }
}
