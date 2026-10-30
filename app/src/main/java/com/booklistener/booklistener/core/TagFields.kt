package com.booklistener.core

/**
 * 统一标签键值模型与业务映射（spec: tag-fields-full）。
 *
 * 解析层（Mp4IlstReader / MetadataExtractor）输出统一的 [TagEntry] 键值条目流，
 * 本文档聚合：业务语义 → 键 的映射表（tag_fields_reference.md §5 对照总表）、
 * 结构化字段提取、媒体类型归一。
 */
data class TagEntry(
    /** 原始键名：MP4 原子 fourcc（©ser/trkn/…）/ ID3 帧 id（TIT2/…）/ TXXX 描述（SERIES/…） */
    val key: String,
    /** 文本化值（二进制原子如 trkn/disk 已解析为 "n/total"；封面只记存在性） */
    val value: String,
    /** 来源体系：ID3 / MP4（写入 entry_tag_extras.source） */
    val source: String
)

object TagFields {

    // ---- 来源常量 ----
    const val SRC_ID3 = "ID3"
    const val SRC_MP4 = "MP4"

    // ---- 结构化字段映射（键名 = 首选 → 次选，按 §5 对照总表） ----

    private const val K_TITLE = "TIT2"
    private const val K_ARTIST = "TPE1"
    private const val K_ALBUM = "TALB"
    private const val K_ALBUM_ARTIST = "TPE2"
    private const val K_GENRE = "TCON"
    private const val K_TRACK = "TRCK"
    private const val K_DISC = "TPOS"
    private const val K_YEAR = "TDRC"
    private const val K_YEAR_ALT = "TYER"
    private const val K_COMPOSER = "TCOM"
    private const val K_SERIES = "SERIES"
    private const val K_SERIES_ALT = "TIT1"
    private const val K_SERIES_NUMBER = "SERIESNUMBER"
    private const val K_MEDIA_TYPE = "MEDIATYPE"
    private const val K_ENCODER = "TSSE"

    // MP4 对应键
    private const val A_TITLE = "\u00A9nam"
    private const val A_ARTIST = "\u00A9ART"
    private const val A_ALBUM = "\u00A9alb"
    private const val A_ALBUM_ARTIST = "aART"
    private const val A_GENRE = "\u00A9gen"
    private const val A_TRACK = "trkn"
    private const val A_DISC = "disk"
    private const val A_YEAR = "\u00A9day"
    private const val A_COMPOSER = "\u00A9wrt"
    private const val A_SERIES = "\u00A9ser"
    private const val A_SERIES_NUMBER = "\u00A9sno"
    private const val A_STIK = "stik"
    private const val A_ENCODER = "\u00A9too"

    /** stik 数值 → 语义名（文档 §3：2=有声书 9=播客） */
    private val STIK_MAP = mapOf(
        "1" to "Music",
        "2" to "Audiobook",
        "6" to "Audiobook",
        "9" to "Podcast",
        "10" to "Audiobook",
        "11" to "Podcast"
    )

    fun first(entries: List<TagEntry>, vararg keys: String): String? =
        entries.firstOrNull { it.key in keys }?.value?.takeIf { it.isNotBlank() }

    fun firstAny(entries: List<TagEntry>, keys: List<List<String>>): String? {
        for (candidates in keys) {
            first(entries, *candidates.toTypedArray())?.let { return it }
        }
        return null
    }

    /** 标题：TIT2 / ©nam */
    fun title(entries: List<TagEntry>): String? = first(entries, K_TITLE, A_TITLE)

    /** 表演者：TPE1 / ©ART */
    fun artist(entries: List<TagEntry>): String? = first(entries, K_ARTIST, A_ARTIST)

    /** 专辑：TALB / ©alb */
    fun album(entries: List<TagEntry>): String? = first(entries, K_ALBUM, A_ALBUM)

    /** 专辑艺术家：TPE2 / aART */
    fun albumArtist(entries: List<TagEntry>): String? = first(entries, K_ALBUM_ARTIST, A_ALBUM_ARTIST)

    /** 曲目号：TRCK/trkn 文本 "n/total" → 前段整数 */
    fun track(entries: List<TagEntry>): Int? =
        first(entries, K_TRACK, A_TRACK)?.substringBefore('/')?.trim()?.toIntOrNull()

    /** 碟号：TPOS/disk "n/total" → 前段整数 */
    fun disc(entries: List<TagEntry>): Int? =
        first(entries, K_DISC, A_DISC)?.substringBefore('/')?.trim()?.toIntOrNull()

    /** 流派：TCON / ©gen */
    fun genre(entries: List<TagEntry>): String? = first(entries, K_GENRE, A_GENRE)

    /** 年份：TDRC/TYER / ©day（取 4 位数字年份） */
    fun year(entries: List<TagEntry>): Int? {
        val raw = first(entries, K_YEAR, K_YEAR_ALT, A_YEAR) ?: return null
        return Regex("\\d{4}").find(raw)?.value?.toIntOrNull()
    }

    /** 作曲：TCOM / ©wrt */
    fun composer(entries: List<TagEntry>): String? = first(entries, K_COMPOSER, A_COMPOSER)

    /**
     * 媒体类型归一（文档 §5/§7）：
     * - MP4 stik 数值 → 语义名（2→Audiobook、9→Podcast…）
     * - ID3 TXXX:MEDIATYPE / TCON 原样保留
     */
    fun mediaType(entries: List<TagEntry>): String? {
        first(entries, A_STIK)?.let { stik ->
            return STIK_MAP[stik.trim()] ?: stik.trim().takeIf { it.isNotBlank() }
        }
        return first(entries, K_MEDIA_TYPE) ?: first(entries, K_GENRE)
    }

    /** 编码工具：TSSE / ©too */
    fun encoder(entries: List<TagEntry>): String? = first(entries, K_ENCODER, A_ENCODER)

    // ---- v2.2.22（spec: m4a-m4b-series-freeform-priority）：自由格式系列键 ----

    private const val FF_PREFIX = "----:"
    private const val FF_SERIES = "SERIES"
    private const val FF_SERIES_PART = "SERIES-PART"

    /**
     * 自由格式系列键（----:<issuer>:<name>；issuer 缺省为旧格式 ----:<name>，视为独立命名空间）
     * 按命名空间分组，系列名与系列序号**必须同源**（同一命名空间）：
     * 1) 同一命名空间成对提供名+序号 → 两者同取；
     * 2) 只提供其一 → 另一字段回退字段级标准降级链（返回 null，由 series()/seriesNumber() 降级 ©ser/©sno）；
     * 3) 禁止跨命名空间拼凑（系列名取命名空间 A、系列序号取命名空间 B）。
     * 命名空间内大小写不敏感归一（SERIES/series、SERIES-PART/series-part）；同名双键冲突取大写键。
     * 返回 (系列名, 系列序号)，缺省侧为 null。
     */
    private fun freeformSeriesPair(entries: List<TagEntry>): Pair<String?, String?> {
        data class Bucket(
            var nameUpper: String? = null, var nameLower: String? = null,
            var partUpper: String? = null, var partLower: String? = null
        )

        val buckets = LinkedHashMap<String, Bucket>()
        for (e in entries) {
            if (!e.key.startsWith(FF_PREFIX)) continue
            val rest = e.key.removePrefix(FF_PREFIX)
            val idx = rest.lastIndexOf(':')
            val ns = if (idx >= 0) rest.substring(0, idx) else ""
            val nm = if (idx >= 0) rest.substring(idx + 1) else rest
            if (!nm.equals(FF_SERIES, ignoreCase = true) && !nm.equals(FF_SERIES_PART, ignoreCase = true)) continue
            val b = buckets.getOrPut(ns) { Bucket() }
            when {
                nm == FF_SERIES -> if (b.nameUpper == null) b.nameUpper = e.value
                nm.equals(FF_SERIES, ignoreCase = true) -> if (b.nameLower == null) b.nameLower = e.value
                nm == FF_SERIES_PART -> if (b.partUpper == null) b.partUpper = e.value
                else -> if (b.partLower == null) b.partLower = e.value
            }
        }
        if (buckets.isEmpty()) return null to null
        fun nameOf(ns: String): String? = buckets[ns]?.nameUpper ?: buckets[ns]?.nameLower
        fun partOf(ns: String): String? = buckets[ns]?.partUpper ?: buckets[ns]?.partLower

        // 1) 同命名空间成对优先
        for (ns in buckets.keys) {
            val n = nameOf(ns)
            val p = partOf(ns)
            if (n != null && p != null) return n to p
        }
        // 2) 仅名 → 名取之，序号回退（null → 调用方降级）
        for (ns in buckets.keys) {
            val n = nameOf(ns)
            if (n != null) return n to null
        }
        // 3) 仅序号 → 序号取之，名回退
        for (ns in buckets.keys) {
            val p = partOf(ns)
            if (p != null) return null to p
        }
        return null to null
    }

    /** 系列名：MP4 自由格式 ----:*:SERIES/series（命名空间同源）→ ©ser；ID3 TXXX:SERIES → TIT1 */
    fun series(entries: List<TagEntry>): String? =
        freeformSeriesPair(entries).first
            ?: first(entries, A_SERIES)
            ?: first(entries, K_SERIES)
            ?: first(entries, K_SERIES_ALT)

    /** 系列序号：MP4 自由格式 ----:*:SERIES-PART/series-part（命名空间同源）→ ©sno；ID3 TXXX:SERIESNUMBER */
    fun seriesNumber(entries: List<TagEntry>): String? =
        freeformSeriesPair(entries).second
            ?: first(entries, A_SERIES_NUMBER)
            ?: first(entries, K_SERIES_NUMBER)
}
