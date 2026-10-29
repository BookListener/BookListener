package com.booklistener.core

/** m4b 章节（起始毫秒 + 标题；标题可能为空，由调用方兜底「第 N 章」） */
data class Chapter(val startMs: Long, val title: String)

/**
 * m4b 章节解析器（spec: m4b-audiobook-chapters，v2.2.4）。
 *
 * iTunes 章节表优先（moov 内主轨 tref/chap 引用 → 章节轨 stbl 样本时间戳 = 章节起点，
 * 章节名取章节轨 ilst 文本原子按样本序匹配；无 ilst 名 → 空标题走「第 N 章」兜底）；
 * chpl 原子兜底（moov/udta/chpl，时间轴按 mvhd movie timescale 换算毫秒）。
 * 两者都无 → 空列表（调用方退化普通音频）。
 *
 * 纯 box 结构遍历，解析失败返回空列表（不抛、不影响扫描）。
 */
object Mp4ChapterReader {

    private const val MOOV = 0x6D6F6F76 // 'moov'
    private const val UDTA = 0x75647461 // 'udta'
    private const val META = 0x6D657461 // 'meta'
    private const val ILST = 0x696C7374 // 'ilst'
    private const val DATA = 0x64617461 // 'data'
    private const val TRAK = 0x7472616B // 'trak'
    private const val TKHD = 0x746B6864 // 'tkhd'
    private const val TREF = 0x74726566 // 'tref'
    private const val CHAP = 0x63686170 // 'chap'
    private const val MDIA = 0x6D646961 // 'mdia'
    private const val MDHD = 0x6D646864 // 'mdhd'
    private const val MINF = 0x6D696E66 // 'minf'
    private const val STBL = 0x7374626C // 'stbl'
    private const val STTS = 0x73747473 // 'stts'

    /** iTunes 章节表优先，chpl 兜底；无章节 → 空列表 */
    fun read(bytes: ByteArray): List<Chapter> = runCatching {
        val itunes = readITunesChapters(bytes)
        if (itunes.isNotEmpty() && itunes.any { it.title.isNotBlank() }) return itunes
        // v2.2.8：iTunes 时间点成功但标题全空（常见：章节轨无 ilst 名）→ chpl 补标题。
        // chpl 章节数与 iTunes 一致 → 按序覆盖标题（时间点以 iTunes 为准）；数量不一致
        // 说明时间轴不同（章节轨/电影 timescale 差异），不强补，标题空走「第 N 章」兜底。
        val chpl = ChplChapterReader.read(bytes)
        when {
            itunes.isEmpty() -> chpl
            chpl.isEmpty() -> itunes
            chpl.size == itunes.size -> itunes.mapIndexed { i, c -> c.copy(title = chpl[i].title) }
            else -> itunes
        }
    }.getOrElse { emptyList() }

    private fun readITunesChapters(bytes: ByteArray): List<Chapter> {
        val buf = Mp4BoxReader(bytes)
        val moov = findBoxChain(buf, buf.wholeRange(), MOOV) ?: return emptyList()
        val traks = enumerateBoxes(buf, moov, TRAK)
        if (traks.isEmpty()) return emptyList()

        val trakById = HashMap<Int, IntRange>()
        for (t in traks) {
            val id = readTrackId(buf, t) ?: continue
            trakById[id] = t
        }

        // 主轨 tref → chap → 章节轨 track_ID（取首个含 chap 引用的主轨）
        var chapterTrackId: Int? = null
        for (t in traks) {
            val ref = readChapRef(buf, t) ?: continue
            chapterTrackId = ref
            break
        }
        if (chapterTrackId == null) return emptyList()

        val chapterTrak = trakById[chapterTrackId] ?: return emptyList()
        val timescale = readMdhdTimescale(buf, chapterTrak) ?: return emptyList()
        val starts = readSttsSampleStarts(buf, chapterTrak) ?: return emptyList()
        if (starts.isEmpty()) return emptyList()

        // 章节名：章节轨 ilst 文本原子（按出现顺序匹配样本；不足/缺失 → 空标题兜底）
        val names = readTrackIlstTexts(buf, chapterTrak)
        return starts.mapIndexed { i, ts ->
            Chapter(startMs = ts * 1000 / timescale, title = names.getOrNull(i) ?: "")
        }
    }

    /** tkhd.track_ID（v0/v1 通用） */
    private fun readTrackId(buf: Mp4BoxReader, trak: IntRange): Int? {
        val tkhd = findBoxChain(buf, trak, TKHD) ?: return null
        val boxStart = tkhd.first - 8 // findBoxChain 返回子 box 起始（= box 头后），头固定 8B
        val version = buf.u8(boxStart + 8)
        val idOffset = if (version == 1) boxStart + 28 else boxStart + 20
        return if (idOffset + 4 <= buf.size) buf.u32(idOffset) else null
    }

    /** trak → tref → chap 的第一个 track_ID */
    private fun readChapRef(buf: Mp4BoxReader, trak: IntRange): Int? {
        val tref = findBoxChain(buf, trak, TREF) ?: return null
        for (r in enumerateRaw(buf, tref, CHAP)) {
            // chap payload：track_ID 数组（4B each）
            if (r.first + 4 <= r.last) return buf.u32(r.first)
        }
        return null
    }

    /** mdia → mdhd.timescale（v0/v1 通用） */
    private fun readMdhdTimescale(buf: Mp4BoxReader, trak: IntRange): Long? {
        val mdhd = findBoxChain(buf, trak, MDIA, MDHD) ?: return null
        val boxStart = mdhd.first - 8
        val version = buf.u8(boxStart + 8)
        val tsOffset = if (version == 1) boxStart + 28 else boxStart + 20
        return if (tsOffset + 4 <= buf.size) buf.u32(tsOffset).toLong() and 0xFFFFFFFFL else null
    }

    /** mdia → minf → stbl → stts：每样本开始时间（track timescale 单位，ts=0 起累积） */
    private fun readSttsSampleStarts(buf: Mp4BoxReader, trak: IntRange): List<Long>? {
        val stts = findBoxChain(buf, trak, MDIA, MINF, STBL, STTS) ?: return null
        val payloadStart = stts.first
        if (payloadStart + 8 > stts.last + 1) return emptyList()
        // version(1) flags(3) entryCount(4)
        val entryCount = buf.u32(payloadStart + 4)
        val out = mutableListOf<Long>()
        var t = 0L
        var pos = payloadStart + 8
        for (i in 0 until entryCount) {
            if (pos + 8 > stts.last + 1) break
            val sampleCount = buf.u32(pos)
            val delta = buf.u32(pos + 4).toLong() and 0xFFFFFFFFL
            pos += 8
            for (s in 0 until sampleCount) {
                out += t
                t += delta
            }
        }
        return out
    }

    /** trak → udta → meta → ilst 的文本原子值（typeFlags=1，UTF-8；跳过 covr/----） */
    private fun readTrackIlstTexts(buf: Mp4BoxReader, trak: IntRange): List<String> {
        val ilst = findBoxChain(buf, trak, UDTA, META, ILST) ?: return emptyList()
        val out = mutableListOf<String>()
        var pos = ilst.first
        while (pos + 8 <= ilst.last) {
            val size = boxSize(buf, pos, ilst.last).toInt()
            val type = buf.u32(pos + 4)
            if (size < 8) break
            val boxEnd = (pos + size).coerceAtMost(ilst.last + 1)
            // 文本原子：子 data box 的 typeFlags==1
            var p = pos + 8
            while (p + 8 <= boxEnd) {
                val childSize = boxSize(buf, p, boxEnd).toInt()
                val childType = buf.u32(p + 4)
                if (childSize < 8) break
                val childEnd = (p + childSize).coerceAtMost(boxEnd)
                if (childType == DATA) {
                    val flags = buf.u32(p + 8)
                    if (flags == 1) {
                        buf.utf8(p + 16, childEnd - p - 16)?.takeIf { it.isNotBlank() }?.let { out += it }
                    }
                    break
                }
                p = childEnd
            }
            pos = boxEnd
        }
        return out
    }
}

/**
 * Nero chpl 章节原子解析（moov → udta → chpl）。
 * 时间轴 = movie timescale（moov/mvhd），换算毫秒；chpl 缺失/结构异常 → 空列表。
 */
object ChplChapterReader {

    private const val MOOV = 0x6D6F6F76 // 'moov'
    private const val UDTA = 0x75647461 // 'udta'
    private const val MVHD = 0x6D766864 // 'mvhd'
    private const val CHPL = 0x6368706C // 'chpl'

    fun read(bytes: ByteArray): List<Chapter> = runCatching {
        val buf = Mp4BoxReader(bytes)
        val moov = findBoxChain(buf, buf.wholeRange(), MOOV) ?: return emptyList()
        val timescale = readMovieTimescale(buf, moov) ?: 1000L
        val chpl = findBoxChain(buf, moov, UDTA, CHPL) ?: return emptyList()
        parseChpl(buf, chpl, timescale)
    }.getOrElse { emptyList() }

    private fun readMovieTimescale(buf: Mp4BoxReader, moov: IntRange): Long? {
        val mvhd = findBoxChain(buf, moov, MVHD) ?: return null
        val boxStart = mvhd.first - 8
        val version = buf.u8(boxStart + 8)
        val tsOffset = if (version == 1) boxStart + 28 else boxStart + 20
        return if (tsOffset + 4 <= buf.size) buf.u32(tsOffset).toLong() and 0xFFFFFFFFL else null
    }

    private fun parseChpl(buf: Mp4BoxReader, range: IntRange, timescale: Long): List<Chapter> {
        // payload：version(1) flags(3) unknown(4) count(1) + entries{startTime(8B) titleLen(1) title}
        var pos = range.first + 9
        if (pos > range.last + 1) return emptyList()
        val count = buf.u8(pos - 1)
        val out = mutableListOf<Chapter>()
        repeat(count) {
            if (pos + 9 > range.last + 1) return@repeat
            val ts = buf.u64(pos)
            pos += 8
            val len = buf.u8(pos)
            pos += 1
            val title = buf.utf8(pos, len) ?: ""
            pos += len
            out += Chapter(startMs = ts * 1000 / timescale, title = title)
        }
        return out
    }
}

/** MP4 大端 box 读取器（全部 offset 式，不维护游标） */
internal class Mp4BoxReader(private val data: ByteArray) {
    val size: Int get() = data.size

    fun wholeRange(): IntRange = 0..data.size

    fun u8(offset: Int): Int {
        val i = offset.coerceIn(0, data.size - 1)
        return data[i].toInt() and 0xFF
    }

    fun u32(offset: Int): Int {
        val i = offset.coerceIn(0, data.size - 4)
        return ((data[i].toInt() and 0xFF) shl 24) or
            ((data[i + 1].toInt() and 0xFF) shl 16) or
            ((data[i + 2].toInt() and 0xFF) shl 8) or
            (data[i + 3].toInt() and 0xFF)
    }

    fun u64(offset: Int): Long {
        val i = offset.coerceIn(0, data.size - 8)
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (data[i + k].toLong() and 0xFF)
        return v
    }

    fun utf8(offset: Int, len: Int): String? {
        if (len <= 0) return null
        val i = offset.coerceIn(0, data.size)
        val n = len.coerceAtMost(data.size - i)
        return String(data, i, n, Charsets.UTF_8).trimEnd('\u0000')
    }
}

/** MP4 常见 box 类型（findBoxChain/enumerateRaw 对齐用白名单：
 * 仅已知类型才按 size 跳转，未知类型一律逐字节，防止垃圾前导偶然组成的伪 box 带偏遍历） */
internal val KNOWN_BOX_TYPES = setOf(
    0x66747970, // ftyp
    0x6D6F6F76, // moov
    0x6D646174, // mdat
    0x66726565, // free
    0x736B6970, // skip
    0x77696465, // wide
    0x75647461, // udta
    0x6D657461, // meta
    0x696C7374, // ilst
    0x64617461, // data
    0x7472616B, // trak
    0x6D646961, // mdia
    0x6D696E66, // minf
    0x7374626C, // stbl
    0x6D766864, // mvhd
    0x746B6864, // tkhd
    0x6D646864, // mdhd
    0x73747473, // stts
    0x74726566, // tref
    0x63686170, // chap
    0x6368706C, // chpl
    0x6D657474, // mett
    0x6D657478, // metx
    0x74696D65, // time
    0x73617473  // sats
)

/** box size（含头）；支持 64 位 largesize（size==1）。size==0 按损坏返回 0（v2.2.4：
 * 文件头垃圾中的全 0 若解释为"延伸到范围尾"会直接跳到文件尾丢失对齐，逐字节重对齐更可靠） */
internal fun boxSize(buf: Mp4BoxReader, start: Int, endExclusive: Int): Long {
    if (start + 8 > endExclusive) return 0
    val s = buf.u32(start).toLong() and 0xFFFFFFFFL
    return when {
        s == 1L -> if (start + 16 <= buf.size) buf.u64(start + 8) else 0
        s == 0L -> 0
        else -> s
    }
}

/** 在 [range] 内逐层找 type 链；返回目标 box 的子 box 起始 range（meta 自带 4B version/flags 头） */
internal fun findBoxChain(buf: Mp4BoxReader, range: IntRange, vararg types: Int): IntRange? {
    var searchRange = range
    for (depth in types.indices) {
        var found: IntRange? = null
        var pos = searchRange.first
        while (pos + 8 <= searchRange.last) {
            val size = boxSize(buf, pos, searchRange.last + 1)
            val type = buf.u32(pos + 4)
            // v2.2.4：损坏/越界/未知类型 box（文件头非 box 前导）逐字节重对齐，不整体放弃本层
            if (size < 8 || pos.toLong() + size > (searchRange.last + 1).toLong() || type !in KNOWN_BOX_TYPES) {
                pos += 1
                continue
            }
            val end = (pos + size).coerceAtMost((searchRange.last + 1).toLong()).toInt()
            if (type == types[depth]) {
                if (depth == types.lastIndex) {
                    val childStart = if (type == 0x6D657461 /* meta */) pos + 12 else pos + 8
                    return childStart..end
                }
                // 中间层：进入子 box 层时同样跳过 meta 的 4B version/flags 头
                found = if (type == 0x6D657461 /* meta */) (pos + 12)..end else (pos + 8)..end
                pos = found.first
                break
            }
            pos = end
        }
        if (found == null) return null
        searchRange = found
    }
    return null
}

/** 枚举 [range] 内全部指定 type 的 box（含 64 位 size），返回 payload 起始..末尾 */
internal fun enumerateBoxes(buf: Mp4BoxReader, range: IntRange, type: Int): List<IntRange> {
    return enumerateRaw(buf, range, type)
}

/** 枚举 [range] 内全部指定 type 的 box；返回 payload 起始..末尾（末尾 exclusive） */
internal fun enumerateRaw(buf: Mp4BoxReader, range: IntRange, type: Int): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var pos = range.first
    while (pos + 8 <= range.last) {
        val size = boxSize(buf, pos, range.last + 1)
        // v2.2.4：损坏/越界/未知类型 box 逐字节重对齐
        if (size < 8 || pos.toLong() + size > (range.last + 1).toLong() || buf.u32(pos + 4) !in KNOWN_BOX_TYPES) {
            pos += 1
            continue
        }
        val boxType = buf.u32(pos + 4)
        val end = (pos + size).coerceAtMost((range.last + 1).toLong()).toInt()
        if (boxType == type) out += (pos + 8)..end
        pos = end
    }
    return out
}
