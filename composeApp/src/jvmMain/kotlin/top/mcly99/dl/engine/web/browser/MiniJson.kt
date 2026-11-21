package top.mcly99.dl.engine.web.browser

/**
 * 极简 JSON 解析 / 序列化（只覆盖 CDP 消息需要的那点语法）。
 *
 * 为什么不引 kotlinx-serialization 或 Gson：整个项目只拿它读写 Chrome DevTools Protocol
 * 的消息，为此加一个序列化框架（还带编译器插件）不划算，也会让配置缓存多一层不确定性。
 * 这里 200 行不到，还能单独写单测。
 *
 * 映射关系：`object` → `Map<String, Any?>`（保留插入顺序）、`array` → `List<Any?>`、
 * 数字统一 `Double`、其余原样。
 */
internal object MiniJson {

    /** 解析失败抛 [IllegalArgumentException]，调用方按「消息格式不认识」处理 */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "JSON 结尾有多余内容（位置 ${parser.pos}）" }
        return value
    }

    /** 序列化：只支持 Map / List / String / Number / Boolean / null */
    fun stringify(value: Any?): String = buildString { write(value, this) }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(value)
            is Number -> out.append(if (value.toDouble() % 1.0 == 0.0 && value.toDouble() < 1e15) value.toLong() else value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(pos < text.length) { "JSON 意外结束" }
            return when (val ch = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(ch == '-' || ch.isDigit()) { "JSON 非法字符 '$ch'（位置 $pos）" }
                    readNumber()
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("JSON 对象缺少 ',' 或 '}'（位置 $pos）")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("JSON 数组缺少 ',' 或 ']'（位置 $pos）")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < text.length) { "JSON 字符串未闭合" }
                when (val ch = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < text.length) { "JSON 转义未完成" }
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= text.length) { "JSON \\u 转义长度不足" }
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("JSON 未知转义 '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            return text.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("JSON 数字格式错误（位置 $start）")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, pos)) { "JSON 期望 '$literal'（位置 $pos）" }
            pos += literal.length
            return value
        }

        private fun peek(): Char {
            require(pos < text.length) { "JSON 意外结束" }
            return text[pos]
        }

        private fun expect(ch: Char) {
            require(pos < text.length && text[pos] == ch) { "JSON 期望 '$ch'（位置 $pos）" }
            pos++
        }
    }
}

// ---------------------------------------------------------------- 取值助手

internal fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asArray(): List<Any?>? = this as? List<Any?>

internal fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key].asObject()

internal fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key].asArray()

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()

internal fun Map<String, Any?>.int(key: String): Int? = num(key)?.toInt()
