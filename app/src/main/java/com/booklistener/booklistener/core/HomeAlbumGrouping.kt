package com.booklistener.core

/**
 * v2.2.1 主页专辑列表系列分组（SPECS §十三）：
 * - 分区内按归一化系列名（WorkBackfill.norm）聚合为系列组（≥2 张）；单专辑系列折叠为普通行
 * - 组内按系列序号数字感知自然排序（缺失/非数字排末，末内按专辑名自然序；同序号按专辑名破平）
 * - 交错排序：已播放分区按 lastPlayedAt 时间线（组位置=组内最近播放时间，新→旧，平局按名称自然序）；
 *   未播放分区按统一键自然序（系列按系列名、普通行按专辑名，同键系列组在前）
 * - 行为由 flow 测试经 PlayerViewModel 覆盖（SPECS §十二：不直接单测纯函数）
 */
sealed interface HomeAlbumItem {
    /** 系列分组头（albums 为收展过滤后的可见专辑；albumCount 为真实专辑数） */
    data class SeriesGroup(
        val key: String,
        val name: String,
        val albumCount: Int,
        val albums: List<AlbumSummary>
    ) : HomeAlbumItem

    /** 普通专辑行（含折叠的单专辑系列） */
    data class Plain(val album: AlbumSummary) : HomeAlbumItem
}

object HomeAlbumGrouping {

    /**
     * v2.2.1 系列感知分区（SPECS §十三.2 / ADR-0002）：系列不跨分区——
     * 系列内任一专辑播放过（lastPlayedAt>0）→ 整系列（含未播放专辑）归已播放分区。
     * 非系列专辑按作品级 lastPlayedAt 归属。返回 (recent, never) 原始专辑列表。
     */
    fun partitionAlbums(albums: List<AlbumSummary>): Pair<List<AlbumSummary>, List<AlbumSummary>> {
        val playedSeries = albums
            .filter { it.lastPlayedAt > 0L }
            .map { WorkBackfill.norm(it.series) }
            .filter { it.isNotEmpty() }
            .toSet()
        val recent = albums.filter { it.lastPlayedAt > 0L || WorkBackfill.norm(it.series) in playedSeries }
        val never = albums.filter { it.lastPlayedAt <= 0L && WorkBackfill.norm(it.series) !in playedSeries }
        return recent to never
    }

    /** 分区内分组 + 交错排序（不含收展过滤，由调用方按 expanded 集合过滤可见专辑） */
    fun buildPartitionItems(albums: List<AlbumSummary>, playedPartition: Boolean): List<HomeAlbumItem> {
        val groups = LinkedHashMap<String, MutableList<AlbumSummary>>()
        val plains = mutableListOf<AlbumSummary>()
        for (a in albums) {
            val key = WorkBackfill.norm(a.series)
            if (key.isEmpty()) plains.add(a) else groups.getOrPut(key) { mutableListOf() }.add(a)
        }
        val items = mutableListOf<HomeAlbumItem>()
        groups.forEach { (key, list) ->
            if (list.size < 2) {
                // 单专辑系列折叠为普通行（SPECS §十三.4）
                plains.addAll(list)
            } else {
                val name = list.first { it.series != null }.series!!
                items += HomeAlbumItem.SeriesGroup(
                    key = key,
                    name = name,
                    albumCount = list.size,
                    albums = sortGroup(list)
                )
            }
        }
        val all = items + plains.map { HomeAlbumItem.Plain(it) }
        return if (playedPartition) {
            // 已播放：时间线交错，组位置 = 组内最近播放时间
            all.sortedWith { x, y ->
                val c = y.position().compareTo(x.position())
                if (c != 0) c else naturalCompare(x.nameKey(), y.nameKey())
            }
        } else {
            // 未播放：统一键自然序，同键系列组在前
            all.sortedWith { x, y ->
                val c = naturalCompare(x.nameKey(), y.nameKey())
                if (c != 0) c else x.typeRank().compareTo(y.typeRank())
            }
        }
    }

    /** 组内专辑排序：系列序号自然序 → 专辑名自然序破平 */
    fun sortGroup(list: List<AlbumSummary>): List<AlbumSummary> =
        list.sortedWith { a, b ->
            val c = compareSeriesNumber(a.seriesNumber, b.seriesNumber)
            if (c != 0) c else naturalCompare(a.album, b.album)
        }

    /** 系列序号比较：缺失/空白排后（末内按专辑名由 sortGroup 兜底） */
    fun compareSeriesNumber(a: String?, b: String?): Int = when {
        a.isNullOrBlank() && b.isNullOrBlank() -> 0
        a.isNullOrBlank() -> 1
        b.isNullOrBlank() -> -1
        else -> naturalCompare(a, b)
    }

    /** 数字感知自然比较："2"<"10"、"Vol 2"<"Vol 10"；数值相等（如 02 vs 2）按原始文本稳定序 */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val la = a.length
        val lb = b.length
        while (i < la && j < lb) {
            val ca = a[i]
            val cb = b[j]
            val aDigit = ca.isDigit()
            val bDigit = cb.isDigit()
            if (aDigit && bDigit) {
                val aStart = i
                while (i < la && a[i].isDigit()) i++
                val bStart = j
                while (j < lb && b[j].isDigit()) j++
                val numA = a.substring(aStart, i)
                val numB = b.substring(bStart, j)
                val na = numA.trimStart('0')
                val nb = numB.trimStart('0')
                val lenCmp = na.length.compareTo(nb.length)
                if (lenCmp != 0) return lenCmp
                val valCmp = na.compareTo(nb)
                if (valCmp != 0) return valCmp
                val rawCmp = numA.compareTo(numB)
                if (rawCmp != 0) return rawCmp
            } else if (aDigit != bDigit) {
                return if (aDigit) -1 else 1
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (la - i) - (lb - j)
    }

    private fun HomeAlbumItem.position(): Long = when (this) {
        is HomeAlbumItem.SeriesGroup -> albums.maxOf { it.lastPlayedAt }
        is HomeAlbumItem.Plain -> album.lastPlayedAt
    }

    private fun HomeAlbumItem.nameKey(): String = when (this) {
        is HomeAlbumItem.SeriesGroup -> name
        is HomeAlbumItem.Plain -> album.album
    }

    private fun HomeAlbumItem.typeRank(): Int = if (this is HomeAlbumItem.SeriesGroup) 0 else 1
}
