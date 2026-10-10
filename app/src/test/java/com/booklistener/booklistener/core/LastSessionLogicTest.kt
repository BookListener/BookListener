package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 杀后台恢复：最近播放条目选择逻辑（lastPlayedAt 最大且 > 0） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastSessionLogicTest {

    private fun entry(id: String, playedAt: Long, openedAt: Long = 0L) = SavedEntry(
        id = id,
        displayName = id,
        uri = "content://x/$id",
        sourceType = SourceType.LOCAL,
        lastPlayedAt = playedAt,
        lastOpenedAt = openedAt
    )

    @Test
    fun emptyEntries_returnsNull() {
        assertNull(LastSessionLogic.pickRecent(emptyList()))
    }

    @Test
    fun neverPlayed_returnsNull() {
        val entries = listOf(
            entry("a", 0L),
            entry("b", 0L)
        )
        assertNull("从未播放过且从未打开不应恢复", LastSessionLogic.pickRecent(entries))
    }

    @Test
    fun picksMaxLastPlayedAt() {
        val entries = listOf(
            entry("a", 100L),
            entry("b", 0L),
            entry("c", 300L)
        )
        assertEquals("c", LastSessionLogic.pickRecent(entries)?.id)
    }

    @Test
    fun mixedWithZero_picksOnlyPlayed() {
        val entries = listOf(
            entry("a", 0L),
            entry("b", 50L)
        )
        assertEquals("b", LastSessionLogic.pickRecent(entries)?.id)
    }

    @Test
    fun playedAlwaysWinsOverOpened() {
        val entries = listOf(
            entry("olderPlayed", 100L, openedAt = 50L),
            entry("newestOpened", 0L, openedAt = 900L)
        )
        assertEquals("已播放过的会话优先于仅浏览的会话", "olderPlayed", LastSessionLogic.pickRecent(entries)?.id)
    }

    @Test
    fun noPlayed_fallsBackToMaxOpened() {
        val entries = listOf(
            entry("a", 0L, openedAt = 100L),
            entry("b", 0L, openedAt = 300L),
            entry("c", 0L, openedAt = 200L)
        )
        assertEquals("无播放记录时回退最近打开", "b", LastSessionLogic.pickRecent(entries)?.id)
    }
}
