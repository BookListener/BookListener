package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v4.0 作品键/聚合/排序/老进度解析纯函数测试（无 Android 依赖，JVM 直跑）。
 */
class WorkBackfillTest {

    private fun f(
        name: String, uri: String,
        album: String? = null, artist: String? = null,
        track: Int? = null, disc: Int? = null,
        durationMs: Long? = null, albumArtist: String? = null
    ) = IndexedFile(name, uri, album, artist, track, disc, durationMs, name, albumArtist, null)

    // ---- key 规则 ----

    @Test
    fun workKey_localAlbumUsesTagNormalized() {
        assertEquals(
            "local:the beatles|abbey road",
            WorkBackfill.workKey("e1", "文件夹", "  Abbey   Road ", "The Beatles", "甲")
        )
        // albumArtist 优先于 artist
        assertEquals(
            "local:the beatles|abbey road",
            WorkBackfill.workKey("e1", "文件夹", "Abbey Road", "The Beatles", "The Beatles")
        )
    }

    @Test
    fun workKey_noAlbumFallsBackToEntryAndFolder() {
        assertEquals(
            "entry:e1|my folder",
            WorkBackfill.workKey("e1", "My Folder", null, null, "某歌手")
        )
    }

    @Test
    fun workKey_crossFolderMerge_sameAlbumSameKey_differentEntryNoMerge() {
        val keyA1 = WorkBackfill.workKey("e1", "夹1", "专辑X", null, "作者")
        val keyA2 = WorkBackfill.workKey("e2", "夹2", "专辑X", null, "作者")
        val keyB = WorkBackfill.workKey("e1", "夹1", null, null, "作者")
        assertEquals(keyA1, keyA2)   // 同名专辑跨文件夹 → 同一作品
        assertEquals("entry:e1|夹1", keyB) // 无 album 不跨源合并
    }

    // ---- buildWorks / mergeWorks ----

    @Test
    fun buildWorks_aggregatesByKey() {
        val files = listOf(
            f("1", "u1", "专辑甲", "作者", 1, 1, 60_000L),
            f("2", "u2", "专辑甲", "作者", 2, 1, 90_000L),
            f("3", "u3", null, "作者", null, null, 30_000L)
        )
        val works = WorkBackfill.buildWorks(files, "e1", "文件夹", "LOCAL")
        assertEquals(2, works.size)
        val albumWork = works.first { it.key.startsWith("local:") }
        assertEquals("专辑甲", albumWork.title)
        assertEquals(2, albumWork.songCount)
        assertEquals(150_000L, albumWork.totalDurationMs)
        val folderWork = works.first { it.key.startsWith("entry:") }
        assertEquals("文件夹", folderWork.title)
        assertEquals(1, folderWork.songCount)
        assertEquals(0L, folderWork.lastPlayedAt)
    }

    @Test
    fun mergeWorks_sumsContentAndKeepsMaxPlayed() {
        val a = WorkBackfill.buildWorks(
            listOf(f("1", "u1", "专辑甲", "作者", 1, 1, 60_000L)),
            "e1", "夹1", "LOCAL", lastPlayedAt = 100L
        )
        val b = WorkBackfill.buildWorks(
            listOf(f("2", "u2", "专辑甲", "作者", 2, 1, 90_000L)),
            "e2", "夹2", "LOCAL", lastPlayedAt = 300L
        )
        val merged = WorkBackfill.mergeWorks(a, b)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].songCount)
        assertEquals(150_000L, merged[0].totalDurationMs)
        assertEquals(300L, merged[0].lastPlayedAt)
    }

    // ---- assignSortRank ----

    @Test
    fun assignSortRank_ordersByDiscThenTrackThenName() {
        val files = listOf(
            f("10", "u10", "专辑", "作者", null, null),
            f("2", "u2", "专辑", "作者", 2, 1),
            f("1", "u1", "专辑", "作者", 1, 1),
            f("碟二", "u21", "专辑", "作者", 1, 2)
        )
        val ranks = WorkBackfill.assignSortRank(files).toMap()
        // disc1 track1 → disc1 track2 → disc2 → 无 track 自然序（2 在 10 前）
        assertEquals(0, ranks["u1"])
        assertEquals(1, ranks["u2"])
        assertEquals(2, ranks["u21"])
        assertEquals(3, ranks["u10"])
        assertEquals(4, ranks.size)
    }

    // ---- resolveLegacyProgress ----

    @Test
    fun resolveLegacyProgress_clampsAndMapsToOrderedUri() {
        val uris = listOf("a", "b", "c")
        val rec = FolderProgress("e1", trackIndex = 1, positionMs = 5000L, updatedAt = 1L)
        val (uri, idx) = WorkBackfill.resolveLegacyProgress(rec, uris)!!
        assertEquals("b", uri)
        assertEquals(1, idx)
        // 越界 → clamp 到末尾
        val over = FolderProgress("e1", trackIndex = 99, positionMs = 1L, updatedAt = 1L)
        assertEquals("c", WorkBackfill.resolveLegacyProgress(over, uris)!!.first)
        // 空列表 → null（丢弃）
        assertNull(WorkBackfill.resolveLegacyProgress(rec, emptyList()))
    }
}
