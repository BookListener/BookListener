package com.booklistener.core

/**
 * 标签解析结果（spec: tag-fields-full，替换 TagResolver.AudioTags）：
 * 结构化字段（业务直接使用，入库 entry_index/works）+ 全量键值条目（入库 entry_tag_extras）。
 */
data class ResolvedTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val track: Int?,
    val disc: Int?,
    val durationMs: Long?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val composer: String?,
    val series: String?,
    val seriesNumber: String?,
    val mediaType: String?,
    val encoder: String?,
    /** 文档字段全集键值条目（原始键名，含未结构化字段） */
    val entries: List<TagEntry>
) {
    companion object {
        /** 从键值条目流提取结构化字段（TagFields 映射表） */
        fun fromEntries(entries: List<TagEntry>, durationMs: Long?): ResolvedTags =
            ResolvedTags(
                title = TagFields.title(entries),
                artist = TagFields.artist(entries),
                album = TagFields.album(entries),
                track = TagFields.track(entries),
                disc = TagFields.disc(entries),
                durationMs = durationMs,
                albumArtist = TagFields.albumArtist(entries),
                genre = TagFields.genre(entries),
                year = TagFields.year(entries),
                composer = TagFields.composer(entries),
                series = TagFields.series(entries),
                seriesNumber = TagFields.seriesNumber(entries),
                mediaType = TagFields.mediaType(entries),
                encoder = TagFields.encoder(entries),
                entries = entries
            )

        /** 基础字段便捷构造（测试注入/旧 AudioTags 语义迁移）；v2.2.1 增 series/seriesNumber 可选参 */
        fun basic(
            title: String?,
            artist: String?,
            album: String?,
            track: Int?,
            disc: Int?,
            durationMs: Long?,
            series: String? = null,
            seriesNumber: String? = null
        ): ResolvedTags = ResolvedTags(
            title = title, artist = artist, album = album, track = track, disc = disc,
            durationMs = durationMs, albumArtist = null, genre = null, year = null,
            composer = null, series = series, seriesNumber = seriesNumber, mediaType = null,
            encoder = null, entries = emptyList()
        )
    }
}
