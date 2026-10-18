package com.booklistener.core

import com.booklistener.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v4.0 恢复起点计算：内容引用命中（matchMode=1）/ 失配按 orderIndex 近似（matchMode=0）/ 无记录 / 越界 clamp。
 */
class FolderProgressLogicTest {

    private val items = listOf(
        AudioItem("a1", "歌一", "uri://a1", SourceType.LOCAL, 0L, "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
        AudioItem("a2", "歌二", "uri://a2", SourceType.LOCAL, 0L, "专辑甲", "作者", 2, 1, 90_000L, "02.mp3"),
        AudioItem("a3", "歌三", "uri://a3", SourceType.LOCAL, 0L, "专辑甲", "作者", 3, 1, 30_000L, "03.mp3")
    )

    private fun rec(uri: String, index: Int, pos: Long, match: Int = 1) =
        WorkProgress(1L, uri, index, match, pos, 1f, 0L)

    @Test
    fun noRecord_startsAtZero() {
        val (idx, pos) = FolderProgressLogic.resolveStart(items, null)
        assertEquals(0, idx)
        assertEquals(0L, pos)
    }

    @Test
    fun emptyList_startsAtZero() {
        val (idx, pos) = FolderProgressLogic.resolveStart(emptyList(), rec("uri://a1", 3, 1000L))
        assertEquals(0, idx)
        assertEquals(0L, pos)
    }

    @Test
    fun uriHit_usesExactPosition() {
        val (idx, pos) = FolderProgressLogic.resolveStart(items, rec("uri://a2", 1, 100_000L))
        assertEquals(1, idx)
        assertEquals(100_000L, pos)
    }

    @Test
    fun uriMiss_fallsBackToOrderIndex() {
        // 文件改名（uri 失配）：按 orderIndex 近似定位到同位置
        val (idx, pos) = FolderProgressLogic.resolveStart(items, rec("uri://a2-old", 1, 100_000L, match = 0))
        assertEquals(1, idx)
        assertEquals(100_000L, pos)
    }

    @Test
    fun uriMissIndexBeyondList_clampedToLast() {
        val (idx, pos) = FolderProgressLogic.resolveStart(items, rec("uri://gone", 9, 100_000L, match = 0))
        assertEquals(2, idx)
        assertEquals(100_000L, pos)
    }
}
