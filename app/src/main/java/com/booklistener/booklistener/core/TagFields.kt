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

    /** 系列名：TXXX:SERIES / TIT1 / ©ser */
    fun series(entries: List<TagEntry>): String? =
        first(entries, K_SERIES, A_SERIES) ?: first(entries, K_SERIES_ALT)

    /** 系列序号：TXXX:SERIESNUMBER / ©sno（字符串保序，文档建议纯数字） */
    fun seriesNumber(entries: List<TagEntry>): String? = first(entries, K_SERIES_NUMBER, A_SERIES_NUMBER)

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
}
