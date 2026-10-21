package com.booklistener.core

import com.booklistener.db.AppDao
import com.booklistener.db.EntryIndexEntity

/**
 * v4.0 作品层：曲目界面按作品（works）聚合 + 作品内歌曲列表，全部从 Room 查询（不重新扫描）。
 * - 作品 = 一等实体：tag 优先跨文件夹合并；无 tag/网盘按 (条目, 文件夹名) 独立
 * - 分区：works.lastPlayedAt > 0 为已播放（作品级"播放过"语义），新→旧
 * - 分页：LIMIT/OFFSET（两处列表均 ≤100 条/页，滑动加载下一页）
 * - 作品内排序：sortRank 落库（扫描时按 AudioSortKey.byName 排序写下标），SQL 直接 ORDER BY + 分页下推
 */
data class AlbumSummary(
    val workId: Long,           // 作品 id（播放/进度/歌曲列表定位）
    val album: String,          // 展示用专辑名（= works.title，已兜底）
    val entryId: String,        // 来源条目（MIN(s.id)；封面/播放 sourceType 定位）
    val artist: String?,        // 作品作者（works.albumArtist 优先，兜底组内条目 artist）
    val songCount: Int,         // 作品歌曲数（works 缓存，免 JOIN 聚合）
    val totalDurationMs: Long,  // 作品总时长（works 缓存）
    val albumCountInEntry: Int = 1, // 作品覆盖的来源条目数（1=单条目→文件夹封面；>1=跨条目→内嵌封面）
    val coverUri: String?       // 作品封面（查询时置空，由调用方按规则解析填充）
)

class AlbumStore(private val dao: AppDao) {

    companion object {
        const val PAGE_SIZE = 100
    }

    /** 已播放分区：作品播放过（works.lastPlayedAt>0），新→旧 */
    fun recentPlayed(page: Int): List<AlbumSummary> = blockingRun {
        dao.worksOfPlayed(PAGE_SIZE, page * PAGE_SIZE).map { it.toSummary() }
    }

    /** 尚未播放分区：作品未播放过，按来源条目顺序稳定排序 */
    fun notPlayed(page: Int): List<AlbumSummary> = blockingRun {
        dao.worksOfNeverPlayed(PAGE_SIZE, page * PAGE_SIZE).map { it.toSummary() }
    }

    /** 作品内歌曲：按 sortRank 排序 + 分页下推 SQL（v4.0，替代内存排序） */
    fun songs(workId: Long, page: Int): List<IndexedFile> = blockingRun {
        dao.albumSongsByWork(workId, PAGE_SIZE, page * PAGE_SIZE).map { IndexedFile.fromEntity(it) }
    }

    /** 作品内全部歌曲（按 sortRank 排序，供播放队列与"总数/总时长"） */
    fun allSongs(workId: Long): List<IndexedFile> = blockingRun {
        dao.allSongsByWork(workId).map { IndexedFile.fromEntity(it) }
    }

    private fun <T> blockingRun(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}

// ---- SQL 结果行映射 ----

/** 作品聚合查询结果行（Room 自动映射） */
data class AlbumRow(
    val workId: Long,
    val album: String,
    val entryId: String,
    val artist: String?,
    val songCount: Int,
    val totalDurationMs: Long,
    val albumCountInEntry: Int,
    val coverUri: String?
)

fun AlbumRow.toSummary(): AlbumSummary = AlbumSummary(
    workId = workId,
    album = album,
    entryId = entryId,
    artist = artist,
    songCount = songCount,
    totalDurationMs = totalDurationMs,
    albumCountInEntry = albumCountInEntry,
    coverUri = coverUri
)

/** 作品内歌曲行（Room 映射到实体） */
typealias AlbumSongEntity = EntryIndexEntity

/** 实体 → 排序键（disc→track→显示名自然序，缺档排后；v4.0 起仅测试/兼容用，运行路径走 sortRank） */
fun EntryIndexEntity.sortKey(): AudioSortKey = AudioSortKey(
    displayName = displayName,
    disc = disc,
    track = track
)
