package top.mcly99.dl.utils

/**
 * 极简 QR 码编码器（字节模式，纠错等级 M，版本 1~10）。
 *
 * 只依赖 JDK，不引入第三方库；用于把局域网上传地址展示给手机扫描。
 *
 * 用法：
 * ```kotlin
 * val matrix: Array<BooleanArray>? = QrCode.encode("http://192.168.1.10:8080/?token=xxx")
 * ```
 * 已实现：Reed-Solomon 纠错、掩码选择、格式/版本信息。
 * 不支持：中文等非字节模式优化、版本 > 10、结构化追加。
 */
object QrCode {

    /** 纠错等级 M 下各版本的分块参数：版本 -> (每块数据码字, 数据块数, 纠错块数) */
    private data class BlockSpec(val totalCodewords: Int, val dataCodewords: Int, val blocks: Int, val eccCodewords: Int)

    /** 版本 1~10 的基本参数（纠错等级 M） */
    private val SPECS = arrayOf(
        BlockSpec(26, 16, 1, 10),
        BlockSpec(44, 28, 1, 16),
        BlockSpec(70, 44, 1, 26),
        BlockSpec(100, 64, 2, 18),
        BlockSpec(134, 86, 2, 24),
        BlockSpec(172, 108, 4, 16),
        BlockSpec(196, 124, 4, 18),
        BlockSpec(242, 154, 4, 22),
        BlockSpec(292, 182, 5, 22),
        BlockSpec(346, 216, 5, 26),
    )

    /** 各版本对齐图形中心坐标 */
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
    )

    /**
     * 编码文本为模块矩阵。
     * @return `BooleanArray[y][x]`,true 表示深色模块;超出版本范围时返回 null
     */
    fun encode(text: String): Array<BooleanArray>? {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = chooseVersion(data.size) ?: return null
        return encodeWithVersion(data, version)
    }

    private fun chooseVersion(byteCount: Int): Int? {
        for (v in 1..SPECS.size) {
            val capacityBits = SPECS[v - 1].dataCodewords * 8
            // 模式指示符 4bit + 长度位 + 数据 + 终止符
            val lengthBits = if (v <= 9) 8 else 16
            if (4 + lengthBits + byteCount * 8 <= capacityBits) return v
        }
        return null
    }

    private fun encodeWithVersion(data: ByteArray, version: Int): Array<BooleanArray> {
        val spec = SPECS[version - 1]
        val size = 17 + version * 4

        // ---- 1. 位流 ----
        val bits = BitBuffer()
        bits.append(0b0100, 4)                                   // 字节模式
        bits.append(data.size, if (version <= 9) 8 else 16)      // 字符计数
        data.forEach { bits.append(it.toInt() and 0xFF, 8) }

        val capacityBits = spec.dataCodewords * 8
        // 终止符
        bits.append(0, minOf(4, capacityBits - bits.length))
        // 补齐到字节
        if (bits.length % 8 != 0) bits.append(0, 8 - bits.length % 8)
        // 填充码字 0xEC / 0x11 交替
        var pad = true
        while (bits.length < capacityBits) {
            bits.append(if (pad) 0xEC else 0x11, 8)
            pad = !pad
        }

        val dataCodewords = bits.toByteArray()

        // ---- 2. 分块 + 纠错 ----
        val blocks = splitIntoBlocks(dataCodewords, spec)
        val ecc = blocks.map { reedSolomon(it, spec.eccCodewords) }
        val finalCodewords = interleave(blocks, ecc)

        // ---- 3. 放置模块 ----
        val matrix = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(matrix, reserved, version)

        var bitIndex = 0
        val codewords = finalCodewords
        var right = size - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right--  // 跳过计时列
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (col in 0 until 2) {
                    val x = right - col
                    if (reserved[y][x]) continue
                    var dark = false
                    if (bitIndex < codewords.size * 8) {
                        val byte = codewords[bitIndex ushr 3].toInt() and 0xFF
                        dark = ((byte ushr (7 - (bitIndex and 7))) and 1) == 1
                        bitIndex++
                    }
                    matrix[y][x] = dark
                }
            }
            upward = !upward
            right -= 2
        }

        // ---- 4. 掩码：选罚分最低的一种 ----
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val candidate = applyMask(matrix, reserved, mask)
            val score = penalty(candidate)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
            }
        }
        val masked = applyMask(matrix, reserved, bestMask)
        drawFormatInfo(masked, reserved, bestMask)
        if (version >= 7) drawVersionInfo(masked, reserved, version)
        return masked
    }

    // ------------------------------------------------------------------ 纠错

    private fun splitIntoBlocks(data: ByteArray, spec: BlockSpec): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val shortLen = spec.dataCodewords / spec.blocks
        val longBlocks = spec.dataCodewords % spec.blocks
        // QR 规范：短块（Group 1）在前，长块（Group 2）在后；
        // 交错写入时解码器按同样顺序还原，顺序反了会导致 v8+ 无法识别。
        val shortBlocks = spec.blocks - longBlocks
        var offset = 0
        for (i in 0 until spec.blocks) {
            val len = shortLen + if (i >= shortBlocks) 1 else 0
            result.add(data.copyOfRange(offset, offset + len))
            offset += len
        }
        return result
    }

    private fun interleave(data: List<ByteArray>, ecc: List<ByteArray>): ByteArray {
        val out = mutableListOf<Int>()
        val maxData = data.maxOf { it.size }
        for (i in 0 until maxData) {
            data.forEach { if (i < it.size) out.add(it[i].toInt() and 0xFF) }
        }
        val maxEcc = ecc.maxOf { it.size }
        for (i in 0 until maxEcc) {
            ecc.forEach { if (i < it.size) out.add(it[i].toInt() and 0xFF) }
        }
        return ByteArray(out.size) { out[it].toByte() }
    }

    /** GF(256) 上的 Reed-Solomon 纠错码 */
    private fun reedSolomon(data: ByteArray, eccLength: Int): ByteArray {
        val gen = generatorPolynomial(eccLength)
        val result = IntArray(eccLength)
        data.forEach { b ->
            val factor = (b.toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, eccLength - 1)
            result[eccLength - 1] = 0
            for (i in 0 until eccLength) {
                result[i] = result[i] xor gfMul(gen[i + 1], factor)
            }
        }
        return ByteArray(eccLength) { result[it].toByte() }
    }

    private fun generatorPolynomial(degree: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor gfMul(g[j], GF_EXP[i])
            }
            g = next
        }
        return g
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[(GF_LOG[a] + GF_LOG[b]) % 255]
    }

    private val GF_EXP: IntArray = IntArray(512).also { exp ->
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private val GF_LOG: IntArray = IntArray(256).also { log ->
        for (i in 0 until 255) log[GF_EXP[i]] = i
    }

    // ------------------------------------------------------------------ 功能图形

    private fun drawFunctionPatterns(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, version: Int) {
        val size = matrix.size

        // 定位图形（三个角）
        drawFinder(matrix, reserved, 3, 3)
        drawFinder(matrix, reserved, size - 4, 3)
        drawFinder(matrix, reserved, 3, size - 4)

        // 对齐图形
        val centers = ALIGNMENT[version - 1]
        for (cy in centers) {
            for (cx in centers) {
                // 与定位图形重叠时跳过
                if ((cy == 6 && cx == 6) || (cy == 6 && cx == size - 7) || (cy == size - 7 && cx == 6)) continue
                drawAlignment(matrix, reserved, cx, cy)
            }
        }

        // 计时图形
        for (i in 0 until size) {
            if (!reserved[6][i]) {
                matrix[6][i] = i % 2 == 0
                reserved[6][i] = true
            }
            if (!reserved[i][6]) {
                matrix[i][6] = i % 2 == 0
                reserved[i][6] = true
            }
        }

        // 格式信息区域先占位
        reserveFormatArea(reserved, size)
        // 版本信息区域
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    reserved[size - 11 + j][i] = true
                    reserved[i][size - 11 + j] = true
                }
            }
        }
        // 固定深色模块
        matrix[size - 8][8] = true
        reserved[size - 8][8] = true
    }

    private fun drawFinder(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, cx: Int, cy: Int) {
        val size = matrix.size
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                matrix[y][x] = dist != 2 && dist != 4
                reserved[y][x] = true
            }
        }
    }

    private fun drawAlignment(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                matrix[cy + dy][cx + dx] = dist != 1
                reserved[cy + dy][cx + dx] = true
            }
        }
    }

    private fun reserveFormatArea(reserved: Array<BooleanArray>, size: Int) {
        for (i in 0 until 9) {
            reserved[8][i] = true
            reserved[i][8] = true
        }
        for (i in 0 until 8) {
            reserved[8][size - 1 - i] = true
            reserved[size - 1 - i][8] = true
        }
    }

    private fun drawFormatInfo(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, mask: Int) {
        val size = matrix.size
        // 纠错等级 M = 00
        val data = (0b00 shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor (((rem ushr 9) and 1) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun bit(i: Int) = ((bits ushr i) and 1) == 1

        // 左上
        for (i in 0..5) matrix[8][i] = bit(i)
        matrix[8][7] = bit(6)
        matrix[8][8] = bit(7)
        matrix[7][8] = bit(8)
        for (i in 9..14) matrix[14 - i][8] = bit(i)

        // 右上（行 8）/ 左下（列 8）
        for (i in 0..7) matrix[8][size - 1 - i] = bit(i)
        for (i in 8..14) matrix[size - 15 + i][8] = bit(i)
        matrix[size - 8][8] = true
    }

    private fun drawVersionInfo(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, version: Int) {
        var rem = version
        for (i in 0 until 12) {
            rem = (rem shl 1) xor (((rem ushr 11) and 1) * 0x1F25)
        }
        val bits = (version shl 12) or rem
        val size = matrix.size
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) == 1
            val a = size - 11 + i % 3
            val b = i / 3
            matrix[b][a] = dark
            matrix[a][b] = dark
        }
    }

    // ------------------------------------------------------------------ 掩码与罚分

    private fun applyMask(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, mask: Int): Array<BooleanArray> {
        val size = matrix.size
        val out = Array(size) { y -> BooleanArray(size) { x -> matrix[y][x] } }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (reserved[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (y / 2 + x / 3) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                }
                if (invert) out[y][x] = !out[y][x]
            }
        }
        return out
    }

    /** 简化罚分：连续同色 + 2×2 方块 + 深色比例，用于挑选掩码 */
    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0
        for (y in 0 until n) {
            var run = 1
            for (x in 1 until n) {
                if (m[y][x] == m[y][x - 1]) run++ else { if (run >= 5) score += run - 2; run = 1 }
            }
            if (run >= 5) score += run - 2
        }
        for (x in 0 until n) {
            var run = 1
            for (y in 1 until n) {
                if (m[y][x] == m[y - 1][x]) run++ else { if (run >= 5) score += run - 2; run = 1 }
            }
            if (run >= 5) score += run - 2
        }
        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3
            }
        }
        val dark = m.sumOf { row -> row.count { it } }
        val ratio = dark * 100 / (n * n)
        score += (kotlin.math.abs(ratio - 50) / 5) * 10
        return score
    }

    // ------------------------------------------------------------------ 位缓冲

    private class BitBuffer {
        private val bytes = mutableListOf<Int>()
        var length: Int = 0
            private set

        fun append(value: Int, bitCount: Int) {
            for (i in bitCount - 1 downTo 0) {
                if (length % 8 == 0) bytes.add(0)
                if (((value ushr i) and 1) == 1) {
                    bytes[bytes.size - 1] = bytes[bytes.size - 1] or (0x80 ushr (length % 8))
                }
                length++
            }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
    }
}
