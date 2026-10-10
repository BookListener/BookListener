package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主界面分区纯逻辑测试（v1.5.0）：
 * 最近播放 = 所有已播放过的文件夹（含当前播放，按最近播放时间新→旧）；
 * 尚未播放 = 从未播放过的文件夹。
 */
class HomeSectionsTest {

    private fun entry(id: String, lastPlayedAt: Long = 0L) = SavedEntry(
        id = id,
        displayName = id,
        uri = id,
        sourceType = SourceType.LOCAL,
        lastPlayedAt = lastPlayedAt
    )

    @Test
    fun emptyList_twoEmptySections() {
        val s = splitHomeSections(emptyList())
        assertTrue(s.recentPlayed.isEmpty())
        assertTrue(s.notPlayed.isEmpty())
    }

    @Test
    fun allNeverPlayed_goesToNotPlayed() {
        val s = splitHomeSections(listOf(entry("a"), entry("b")))
        assertTrue(s.recentPlayed.isEmpty())
        assertEquals(listOf("a", "b"), s.notPlayed.map { it.id })
    }

    @Test
    fun playedEntries_sortedNewToOld() {
        val old = entry("old", lastPlayedAt = 1_000L)
        val newer = entry("newer", lastPlayedAt = 3_000L)
        val newest = entry("newest", lastPlayedAt = 2_000L) // 乱序插入
        val s = splitHomeSections(listOf(old, newest, newer))
        assertEquals(listOf("newer", "newest", "old"), s.recentPlayed.map { it.id })
        assertTrue(s.notPlayed.isEmpty())
    }

    @Test
    fun mixed_playedVsNeverPlayed() {
        val played = entry("played", lastPlayedAt = 5_000L)
        val never = entry("never")
        val s = splitHomeSections(listOf(never, played))
        assertEquals(listOf("played"), s.recentPlayed.map { it.id })
        assertEquals(listOf("never"), s.notPlayed.map { it.id })
    }

    @Test
    fun zeroLastPlayed_countsAsNeverPlayed() {
        val s = splitHomeSections(listOf(entry("a", lastPlayedAt = 0L)))
        assertTrue(s.recentPlayed.isEmpty())
        assertEquals(listOf("a"), s.notPlayed.map { it.id })
    }
}
