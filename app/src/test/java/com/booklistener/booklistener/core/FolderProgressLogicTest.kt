package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 恢复起点计算：无记录 / 越界 clamp / 正常记录 */
class FolderProgressLogicTest {

    private fun rec(index: Int, pos: Long) = FolderProgress("uri", index, pos, 0L)

    @Test
    fun noRecord_startsAtZero() {
        val (idx, pos) = FolderProgressLogic.resolveStart(10, null)
        assertEquals(0, idx)
        assertEquals(0L, pos)
    }

    @Test
    fun emptyList_startsAtZero() {
        val (idx, pos) = FolderProgressLogic.resolveStart(0, rec(3, 1000L))
        assertEquals(0, idx)
        assertEquals(0L, pos)
    }

    @Test
    fun validRecord_usesRecordValues() {
        val (idx, pos) = FolderProgressLogic.resolveStart(10, rec(3, 100_000L))
        assertEquals(3, idx)
        assertEquals(100_000L, pos)
    }

    @Test
    fun recordIndexBeyondList_clampedToLast() {
        val (idx, pos) = FolderProgressLogic.resolveStart(4, rec(9, 100_000L))
        assertEquals(3, idx)
        assertEquals(100_000L, pos)
    }
}
