package top.mcly99.dl.utils

/**
 * 版本号比较的纯逻辑。
 *
 * 版本号来自两处：本地是 gradle.properties 的 `appVersion`（如 "1.0.0"），
 * 远端是 Release 的 tag_name（如 "v1.0.1"）。两者格式不完全一致，
 * 且字符串比较会在 "1.0.10" vs "1.0.9" 上出错，因此统一解析成数字段逐段比较。
 *
 * 解析规则（宽容，宁可当作「不比当前新」也不要误报更新）：
 * - 去掉开头的 `v` / `V`；
 * - 只取第一个非数字点分部分（忽略 "-beta" / "+build" 等后缀）；
 * - 每段必须是纯十进制数字，任何一段非法则整段视为 0；
 * - 段数不同的按补 0 对齐（1.0 与 1.0.0 相等）。
 */
object Version {

    /** 把版本字符串解析为数字段列表；无法解析时返回空列表。 */
    fun parse(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        // 只保留点分数字主体：1.2.3-beta.4 -> 1.2.3；去掉构建元数据
        val core = trimmed.substringBefore("-").substringBefore("+")
        if (core.isEmpty()) return emptyList()
        val parts = core.split(".")
        if (parts.isEmpty() || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) {
            return emptyList()
        }
        return parts.map { it.toIntOrNull() ?: 0 }
    }

    /**
     * 比较 l 与 r：l 更新返回正数，相同返回 0，l 更旧返回负数。
     * 任一版本无法解析时按「不比对方新」处理，返回 0（即不提示更新）。
     */
    fun compare(left: String?, right: String?): Int {
        val a = parse(left)
        val b = parse(right)
        if (a.isEmpty() || b.isEmpty()) return 0
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return if (av > bv) 1 else -1
        }
        return 0
    }

    /** remote 是否比 local 新。 */
    fun isNewer(remote: String?, local: String?): Boolean = compare(remote, local) > 0
}
