package com.booklistener.core

import com.booklistener.db.AppDao
import com.booklistener.db.EntryIndexEntity

/**
 * v1.7.0 专辑层：曲目界面按专辑聚合 + 专辑内歌曲列表，全部从 Room 查询（不重新扫描）。
 * - 专辑名 = COALESCE(entry_index.album, saved_entry.displayName)（album 缺失按文件夹名兜底）
 * - 分区：来源条目 lastPlayedAt > 0 为已播放（沿用条目级"播放过"语义，零新状态）
 * - 分页：LIMIT/OFFSET（两处列表均 ≤100 条/页，滑动加载下一页）
 * - 专辑内排序：disc → track → 名称自然序（与 v1.6.0 显示顺序一致）
 */
data class AlbumSummary(
    val album: String,          // 展示用专辑名（已兜底）
    val entryId: String,        // 来源条目（封面/播放进度定位）
    val artist: String?,        // 组内首个非空作者
    val songCount: Int,
    val totalDurationMs: Long,
    val albumCountInEntry: Int = 1, // 来源条目聚合出的专辑总数（封面规则：单专辑用文件夹图/多专辑各用内嵌）
    val coverUri: String?       // 专辑封面（查询时置空，由调用方按规则解析填充）
)

class AlbumStore(private val dao: AppDao) {

    companion object {
        const val PAGE_SIZE = 100
    }

    /** 已播放分区：来源条目播放过（lastPlayedAt>0），新→旧 */
    fun recentPlayed(page: Int): List<AlbumSummary> = blockingRun {
        dao.albumsOfPlayed(PAGE_SIZE, page * PAGE_SIZE).map { it.toSummary() }
    }

    /** 尚未播放分区：来源条目未播放过，按条目顺序 */
    fun notPlayed(page: Int): List<AlbumSummary> = blockingRun {
        dao.albumsOfNeverPlayed(PAGE_SIZE, page * PAGE_SIZE).map { it.toSummary() }
    }

    /** 专辑内歌曲：全量取回后按 disc→track→名称自然序排序，内存分页（SQLite 无自然排序） */
    fun songs(entryId: String, album: String, page: Int): List<IndexedFile> = blockingRun {
        dao.albumSongs(entryId, album)
            .sortedWith(compareBy(AudioSortKey.byName) { it.sortKey() })
            .drop(page * PAGE_SIZE)
            .take(PAGE_SIZE)
            .map { IndexedFile.fromEntity(it) }
    }

    /** 专辑内全部歌曲（排序后，供播放队列与"总数/总时长"） */
    fun allSongs(entryId: String, album: String): List<IndexedFile> = blockingRun {
        dao.albumSongs(entryId, album)
            .sortedWith(compareBy(AudioSortKey.byName) { it.sortKey() })
            .map { IndexedFile.fromEntity(it) }
    }

    private fun <T> blockingRun(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}

// ---- SQL 结果行映射 ----

/** 专辑聚合查询结果行（Room 自动映射） */
data class AlbumRow(
    val album: String,
    val entryId: String,
    val artist: String?,
    val songCount: Int,
    val totalDurationMs: Long,
    val albumCountInEntry: Int
)

fun AlbumRow.toSummary(): AlbumSummary = AlbumSummary(
    album = album,
    entryId = entryId,
    artist = artist,
    songCount = songCount,
    totalDurationMs = totalDurationMs,
    albumCountInEntry = albumCountInEntry,
    coverUri = null
)

/** 专辑内歌曲行（Room 映射到实体） */
typealias AlbumSongEntity = EntryIndexEntity

/** 实体 → 排序键（disc→track→显示名自然序，缺档排后） */
fun EntryIndexEntity.sortKey(): AudioSortKey = AudioSortKey(
    displayName = displayName,
    disc = disc,
    track = track
)
