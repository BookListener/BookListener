package com.booklistener.core

/**
 * 自研 MP4 ilst（iTunes 元数据）解析器（spec: tag-fields-full）。
 *
 * 定位 moov → udta → meta → ilst 原子树，遍历每个子原子及其 `data` 子原子：
 * - typeFlags=1：UTF-8 文本原子（©ser/©sno/©nam/…）→ value=文本
 * - typeFlags=0：整数/二进制原子 → stik(2B) 数值；trkn/disk(8B [flags][n][tot]) → "n/total"；其余按 4B 读
 * - typeFlags=13/14：covr 图片 → 只记存在性 + mime（字节按需提取走 CoverManager，不随扫描全量读）
 * - `----`：com.apple.iTunes 自由格式（\0issuer\0name\0value）→ key="----:name", value=值
 * - chpl：章节原子 → 记存在性
 *
 * Media3 1.4.0 的 Mp4Extractor 只映射 iTunes 标准原子，©ser/©sno/stik 不可达
 * （探针已实测锁死）——本解析器补齐扩展原子，输出原始 fourcc 键名。
 *
 * 纯 box 结构遍历，解析失败返回空列表（不抛、不影响扫描）。
 */
object Mp4IlstReader {

    private const val MOOV = 0x6D6F6F76 // 'moov'
    private const val UDTA = 0x75647461 // 'udta'
    private const val META = 0x6D657461 // 'meta'
    private const val ILST = 0x696C7374 // 'ilst'
    private const val DATA = 0x64617461 // 'data'
    private const val CHPL = 0x6368706C // 'chpl'
    private const val DASHES = 0x2D2D2D2D // '----'
    private const val MVHD = 0x6D766864 // 'mvhd'

    /** 解析 ilst 原子树；无 meta/ilst 或结构异常 → 空列表 */
    fun read(bytes: ByteArray): List<TagEntry> = runCatching {
        val buf = Reader(bytes)
        buf.seekTo(0)
        val ilst = findBox(buf, MOOV, UDTA, META, ILST) ?: return emptyList()
        parseIlst(buf, ilst)
    }.getOrElse { emptyList() }

    /**
     * v2.2.18：自研 mvhd 时长（毫秒）——Media3 Mp4Extractor 对「只有 moov 无 mdat」的字节
     * 解析不出 SeekMap/时长（实测 dur=null），导致所有 m4a/m4b 列表「时长未知」。
     * 直接从 moov 内 mvhd 读 timescale+duration（version 0/1），不依赖 Media3 结构。
     * 无 mvhd / 结构异常 / timescale 非法 → null。
     */
    fun readDurationMs(bytes: ByteArray): Long? = runCatching {
        val buf = Reader(bytes)
        val mvhd = findBox(buf, MOOV, MVHD) ?: return null
        val base = mvhd.first + 8 // 跳过 size+type → version/flags
        val version = buf.u8(base)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            // creation(8) modification(8) timescale(4) duration(8)
            timescale = u32At(buf, base + 20).toLong() and 0xFFFFFFFFL
            duration = u64At(bytes, base + 24)
        } else {
            // creation(4) modification(4) timescale(4) duration(4)
            timescale = u32At(buf, base + 12).toLong() and 0xFFFFFFFFL
            duration = u32At(buf, base + 16).toLong() and 0xFFFFFFFFL
        }
        if (timescale <= 0) null else duration * 1000 / timescale
    }.getOrElse { null }

    private fun u32At(buf: Reader, off: Int): Int {
        buf.seekTo(off)
        return buf.u32()
    }

    private fun u64At(data: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or ((data.getOrNull(off + i)?.toLong() ?: 0L) and 0xFF)
        return v
    }

    /** 内嵌封面（covr 原子 → 图片字节；typeFlags=13 jpeg / 14 png）。无 covr/结构异常 → null */
    data class CoverData(val mime: String, val bytes: ByteArray)

    /** 最大封面字节数（正常内嵌封面 <1MB；超限视为异常不提取） */
    private const val MAX_COVER_BYTES = 4 * 1024 * 1024

    /**
     * v2.2.8：自研 covr 字节提取（moov 区间已读入内存，零额外 IO；
     * 替代扫描封面回填依赖 MediaMetadataRetriever 对 615MB 级 m4b 的慢速/不稳提取）。
     * 遍历 ilst 子原子找 covr → 其 data 子原子 payload（跳过 size/type/data 头 16B）。
     */
    fun readCover(bytes: ByteArray): CoverData? = runCatching {
        val buf = Reader(bytes)
        val ilst = findBox(buf, MOOV, UDTA, META, ILST) ?: return null
        var pos = ilst.first
        while (pos + 8 <= ilst.last) {
            buf.seekTo(pos)
            val size = buf.u32()
            val type = buf.u32()
            if (size < 8) break
            val boxEnd = (pos + size).coerceAtMost(ilst.last + 1)
            if (type == 0x636F7672 /* covr */) {
                var p = pos + 8
                while (p + 8 <= boxEnd) {
                    buf.seekTo(p)
                    val dSize = buf.u32()
                    val dType = buf.u32()
                    if (dSize < 8) break
                    val dEnd = (p + dSize).coerceAtMost(boxEnd)
                    if (dType == DATA) {
                        buf.seekTo(p + 8)
                        val flags = buf.u32()
                        if (flags == 13 || flags == 14) {
                            val len = dEnd - (p + 16)
                            if (len in 1..MAX_COVER_BYTES) {
                                return CoverData(
                                    if (flags == 13) "image/jpeg" else "image/png",
                                    buf.copyOfRange(p + 16, len)
                                )
                            }
                        }
                        break
                    }
                    p = dEnd
                }
            }
            pos = boxEnd
        }
        null
    }.getOrNull()

    /** 从 start 起遍历同一层 box，找 type 链（如 moov→udta→meta→ilst）；返回目标 box 的范围 */
    private fun findBox(buf: Reader, vararg types: Int): IntRange? {
        var pos = 0
        for (depth in types.indices) {
            var found: IntRange? = null
            while (pos + 8 <= buf.size) {
                buf.seekTo(pos)
                val size = buf.u32()
                val type = buf.u32()
                // v2.2.4：损坏/越界/未知类型 box（文件头非 box 前导，如 ID3/私有头）逐字节重对齐，
                // 不再整体放弃本层（Media3 Mp4Extractor 对同类前导可容错，自研解析器需对齐；
                // 未知类型一律逐字节，防垃圾区偶然组成的伪 box 带偏遍历）
                val validSize = size >= 8 && pos.toLong() + size.toLong() <= buf.size && type in KNOWN_BOX_TYPES
                if (validSize) {
                    if (type == types[depth]) {
                        val start = pos
                        val end = (pos + size).coerceAtMost(buf.size)
                        if (depth == types.lastIndex) {
                            // meta 原子有 4 字节 version/flags 头，其子 box 从 start+8+4 开始
                            val childStart = if (type == META) start + 12 else start + 8
                            return childStart..end
                        }
                        found = childRange(start, end, type)
                        pos = found.first // 进入下一层
                        break
                    }
                    pos += size
                } else {
                    pos += 1 // 损坏 box：逐字节前进找下一个合法 box
                }
            }
            if (found == null) return null
            pos = found.first
        }
        return null
    }

    /** 顶层找 moov；再逐层下钻（返回下一层子 box 的起始范围） */
    private fun childRange(start: Int, end: Int, type: Int): IntRange {
        val childStart = if (type == META) start + 12 else start + 8
        return childStart..end
    }

    /** 解析 ilst 子原子列表（每个子原子含 data 子原子） */
    private fun parseIlst(buf: Reader, range: IntRange): List<TagEntry> {
        val out = mutableListOf<TagEntry>()
        var pos = range.first
        while (pos + 8 <= range.last) {
            buf.seekTo(pos)
            val size = buf.u32()
            val type = buf.u32()
            if (size < 8) break
            val boxEnd = (pos + size).coerceAtMost(range.last + 1)
            if (type == DATA || size > (range.last + 1 - pos)) break
            parseItem(buf, pos + 8, boxEnd, type)?.let { out += it }
            pos = boxEnd
        }
        return out
    }

    /** 解析单个 ilst 子原子：找 data 子原子，按 flags 分派 */
    private fun parseItem(buf: Reader, start: Int, end: Int, type: Int): TagEntry? {
        var pos = start
        while (pos + 8 <= end) {
            buf.seekTo(pos)
            val size = buf.u32()
            val childType = buf.u32()
            if (size < 8) return null
            val childEnd = (pos + size).coerceAtMost(end)
            if (childType == DATA) {
                buf.seekTo(pos + 8)
                val typeFlags = buf.u32() // 4B version/flags（flags=1 文本 / 0 数值 / 13,14 图片）
                val payloadStart = pos + 16 // 跳过 size/type + data 头（8+8）
                val payloadLen = childEnd - payloadStart
                return when {
                    // ---- 自由格式：\0issuer\0name\0value
                    type == DASHES -> parseFreeForm(buf, payloadStart, payloadLen)
                    typeFlags == 1 -> buf.utf8(payloadStart, payloadLen)
                        ?.takeIf { it.isNotBlank() }?.let { TagEntry(fourcc(type), it, TagFields.SRC_MP4) }
                    typeFlags == 13 || typeFlags == 14 -> TagEntry(
                        fourcc(type),
                        if (typeFlags == 13) "image/jpeg" else "image/png",
                        TagFields.SRC_MP4
                    )
                    type == CHPL -> TagEntry("chpl", "present", TagFields.SRC_MP4)
                    type == 0x74726B6E || type == 0x6469736B -> { // trkn / disk（8B [flags][n][tot]）
                        val n = buf.u16(payloadStart + 2)
                        val tot = buf.u16(payloadStart + 4)
                        TagEntry(fourcc(type), "$n/$tot", TagFields.SRC_MP4)
                    }
                    type == 0x7374696B -> { // stik（常见 2B/4B，前导可能为 0）
                        val first = buf.u16(payloadStart)
                        val v = if (first == 0 && payloadLen >= 4) buf.u16(payloadStart + 2) else first
                        TagEntry("stik", v.toString(), TagFields.SRC_MP4)
                    }
                    else -> { // 其他数值原子：按 payload 长度自适应（cpil=1B、多数 4B）
                        val v = when {
                            payloadLen >= 4 -> buf.i32(payloadStart)
                            payloadLen >= 2 -> buf.u16(payloadStart)
                            payloadLen >= 1 -> buf.u8(payloadStart).toLong()
                            else -> 0L
                        }
                        TagEntry(fourcc(type), v.toString(), TagFields.SRC_MP4)
                    }
                }
            }
            pos = childEnd
        }
        return null
    }

    /**
     * ---- 解析：payload = \0issuer\0name\0value（空字段表示缺省）。
     * v2.2.22（spec: m4a-m4b-series-freeform-priority）：保留命名空间（issuer），
     * 键 = "----:<issuer>:<name>"；issuer 缺省保持旧格式 "----:<name>"（视为独立命名空间）。
     */
    private fun parseFreeForm(buf: Reader, start: Int, len: Int): TagEntry? {
        if (len <= 0) return null
        val raw = buf.slice(start, len)
        // 首个字节可能为 \0（mean 缺省）；按 \0 拆分前两段后，余下为值
        val parts = raw.split('\u0000')
        val issuer = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val value = parts.drop(2).joinToString("").takeIf { it.isNotBlank() }
        val key = when {
            name == null -> "----"
            issuer != null -> "----:$issuer:$name"
            else -> "----:$name"
        }
        return if (value != null) TagEntry(key, value, TagFields.SRC_MP4) else null
    }

    private fun fourcc(type: Int): String {
        val b = byteArrayOf(
            (type shr 24 and 0xFF).toByte(),
            (type shr 16 and 0xFF).toByte(),
            (type shr 8 and 0xFF).toByte(),
            (type and 0xFF).toByte()
        )
        return String(b, Charsets.ISO_8859_1)
    }

    /** 大端读取器（mp4 box 结构全部大端） */
    private class Reader(private val data: ByteArray) {
        val size: Int get() = data.size
        private var pos = 0

        fun seekTo(p: Int) {
            pos = p.coerceIn(0, data.size)
        }

        fun u32(): Int {
            val i = pos
            pos += 4
            return ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
        }

        fun u16(offset: Int): Int {
            val i = offset.coerceIn(0, data.size - 2)
            return ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        }

        fun u8(offset: Int): Int {
            val i = offset.coerceIn(0, data.size - 1)
            return data[i].toInt() and 0xFF
        }

        fun i32(offset: Int): Int {
            val i = offset.coerceIn(0, data.size - 4)
            return ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
        }

        fun utf8(offset: Int, len: Int): String? {
            if (len <= 0) return null
            val i = offset.coerceIn(0, data.size)
            val n = len.coerceAtMost(data.size - i)
            return String(data, i, n, Charsets.UTF_8).trimEnd('\u0000')
        }

        fun slice(offset: Int, len: Int): String {
            val i = offset.coerceIn(0, data.size)
            val n = len.coerceAtMost(data.size - i)
            return String(data, i, n, Charsets.UTF_8).trimEnd('\u0000')
        }

        /** 复制 [start, start+len) 字节（越界截断） */
        fun copyOfRange(start: Int, len: Int): ByteArray {
            val i = start.coerceIn(0, data.size)
            val n = len.coerceAtMost(data.size - i)
            return data.copyOfRange(i, i + n)
        }
    }
}
