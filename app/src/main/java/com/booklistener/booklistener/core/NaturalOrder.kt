package com.booklistener.core

/**
 * 文件名/标题自然排序（Natural Sort，v1.6.0 霰弹收敛/排序能力落地）。
 *
 * 与 String 默认字典序不同：把字符串切分为「ASCII 数字段 / 文本段」交替 token——
 *  · 数字段按数值比较（去前导零后先比位数、再比字面量；"2" < "10"，且 20 位数字不溢出）
 *  · 文本段按忽略大小写的字典序比较
 *  · 数字段与文本段相遇时数字段在前（与 Windows 资源管理器一致）
 *  · 前缀完全相同则短者在前；数值相等时前导零少者在前（"2" < "02"）
 *
 * 设计取舍（对照参考实现）：
 *  - 不用 Collator：保持确定性（不随 Locale 变化），本项目排序键多为序号+标题；
 *  - 数字段限定 ASCII '0'..'9'：全角数字按文本段处理，避免混排歧义；
 *  - 提供 compare（直接）与 bySortKey（预分词，大目录性能）双入口，结果一致。
 */
object NaturalOrder {

    /** 直接比较两个字符串：负数 = a 在前，正数 = b 在前，0 = 相等 */
    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val la = a.length
        val lb = b.length
        while (i < la && j < lb) {
            val digitA = isAsciiDigit(a[i])
            val digitB = isAsciiDigit(b[j])
            when {
                digitA && digitB -> {
                    val startA = i
                    while (i < la && isAsciiDigit(a[i])) i++
                    val startB = j
                    while (j < lb && isAsciiDigit(b[j])) j++
                    val rawA = a.substring(startA, i)
                    val rawB = b.substring(startB, j)
                    var r = compareDigits(rawA, rawB)
                    if (r != 0) return r
                }
                !digitA && !digitB -> {
                    val startA = i
                    while (i < la && !isAsciiDigit(a[i])) i++
                    val startB = j
                    while (j < lb && !isAsciiDigit(b[j])) j++
                    val r = a.substring(startA, i).compareTo(b.substring(startB, j), ignoreCase = true)
                    if (r != 0) return r
                }
                else -> return if (digitA) -1 else 1 // 数字段在前
            }
        }
        return (la - i) - (lb - j) // 一方耗尽：短者在前
    }

    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    /** 数字段比较：去前导零后比位数（避免 toLong 溢出），再比字面量；数值相等前导零少者在前 */
    private fun compareDigits(rawA: String, rawB: String): Int {
        val sigA = rawA.trimStart('0').ifEmpty { "0" }
        val sigB = rawB.trimStart('0').ifEmpty { "0" }
        var r = sigA.length - sigB.length
        if (r == 0) r = sigA.compareTo(sigB)
        if (r == 0) r = rawA.length - rawB.length
        return r
    }

    /** 预分词排序键（大目录下每轮比较免重复扫描；与 [compare] 结果一致） */
    fun sortKeyFor(s: String): List<Chunk> {
        val chunks = ArrayList<Chunk>(8)
        var i = 0
        while (i < s.length) {
            val digit = isAsciiDigit(s[i])
            val start = i
            while (i < s.length && isAsciiDigit(s[i]) == digit) i++
            val text = s.substring(start, i)
            chunks += if (digit) Chunk.Num(text) else Chunk.Text(text)
        }
        return chunks
    }

    sealed interface Chunk {
        /** 数字段：数值语义 = 去前导零后的字面量 */
        data class Num(val raw: String) : Chunk {
            val sig: String = raw.trimStart('0').ifEmpty { "0" }
        }
        /** 文本段：忽略大小写比较 */
        data class Text(val raw: String) : Chunk
    }

    val byName: Comparator<String> = Comparator { x, y -> compare(x, y) }

    val bySortKey: Comparator<String> = Comparator { x, y ->
        val cx = sortKeyFor(x)
        val cy = sortKeyFor(y)
        val n = minOf(cx.size, cy.size)
        for (k in 0 until n) {
            val a = cx[k]
            val b = cy[k]
            val r = when {
                a is Chunk.Num && b is Chunk.Num -> {
                    var t = a.sig.length - b.sig.length
                    if (t == 0) t = a.sig.compareTo(b.sig)
                    if (t == 0) t = a.raw.length - b.raw.length
                    t
                }
                a is Chunk.Text && b is Chunk.Text ->
                    a.raw.compareTo(b.raw, ignoreCase = true)
                a is Chunk.Num -> -1
                else -> 1
            }
            if (r != 0) return@Comparator r
        }
        cx.size - cy.size
    }
}

/**
 * 标签排序键（v1.6.0，用户确认：音轨号优先 + 专辑号参与排序）：
 * 比较层级 = 专辑号（缺失排后）→ 音轨号（缺失排后）→ 名称自然序。
 */
data class AudioSortKey(
    val displayName: String,
    val disc: Int? = null,
    val track: Int? = null
) {
    companion object {
        /** 三级复合比较：disc → track → NaturalOrder；disc/track 缺失者排后 */
        val byName: Comparator<AudioSortKey> = Comparator { x, y ->
            var r = compareOptional(x.disc, y.disc)
            if (r != 0) return@Comparator r
            r = compareOptional(x.track, y.track)
            if (r != 0) return@Comparator r
            NaturalOrder.bySortKey.compare(x.displayName, y.displayName)
        }

        private fun compareOptional(a: Int?, b: Int?): Int = when {
            a != null && b != null -> a.compareTo(b)
            a != null -> -1 // 有值在前
            b != null -> 1
            else -> 0
        }
    }
}
