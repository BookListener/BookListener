package com.booklistener.player

import com.booklistener.core.AudioItem
import com.booklistener.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放队列状态的纯逻辑测试：
 * 覆盖"入队后 currentItem 缺失 / 切换后 UI 状态错乱"这类低级回归。
 */
class PlaybackQueueLogicTest {

    private val a = AudioItem("1", "a.mp3", "content://a", SourceType.LOCAL, 100L)
    private val b = AudioItem("2", "b.mp3", "content://b", SourceType.LOCAL, 200L)

    @Test
    fun buildQueueState_setsCurrentItemAtStartIndex() {
        val st = PlaybackQueueLogic.buildQueueState(listOf(a, b), 1)
        assertEquals(b, st.currentItem)
        assertEquals(1, st.queueIndex)
        assertEquals(2, st.queue.size)
        assertTrue(st.hasItem)
    }

    @Test
    fun buildQueueState_emptyQueue_hasNullCurrent() {
        val st = PlaybackQueueLogic.buildQueueState(emptyList(), 0)
        assertTrue(st.queue.isEmpty())
        assertNull(st.currentItem)
    }

    @Test
    fun buildQueueState_outOfBounds_hasNullCurrentButKeepsQueue() {
        // 调用方（playItems）负责 coerceIn 限制 startIndex；纯逻辑层对越界保持防御语义
        val st2 = PlaybackQueueLogic.buildQueueState(listOf(a, b), 100)
        assertNull(st2.currentItem)
        assertEquals(2, st2.queue.size)
        assertEquals(100, st2.queueIndex)
    }

    @Test
    fun transitionState_movesCurrentItemAndKeepsQueue() {
        val st = PlaybackQueueLogic.buildQueueState(listOf(a, b), 0)
        val next = PlaybackQueueLogic.transitionState(st, 1)
        assertEquals(b, next.currentItem)
        assertEquals(1, next.queueIndex)
        assertEquals(st.queue, next.queue)
        assertEquals(0L, next.positionMs)
        assertEquals(0L, next.durationMs)
    }

    @Test
    fun transitionState_outOfRange_hasNullCurrentButKeepsQueue() {
        val st = PlaybackQueueLogic.buildQueueState(listOf(a, b), 0)
        val next = PlaybackQueueLogic.transitionState(st, 5)
        assertNull(next.currentItem)
        assertEquals(2, next.queue.size)
    }

    @Test
    fun transitionState_preservesPlaybackFlags() {
        val st = PlaybackQueueLogic.buildQueueState(listOf(a, b), 0)
            .copy(isPlaying = true, isBuffering = false)
        val next = PlaybackQueueLogic.transitionState(st, 1)
        assertTrue(next.isPlaying)
        assertEquals(b, next.currentItem)
    }

    @Test
    fun isCurrentItem_matchesCurrentItemById() {
        val st = PlaybackQueueLogic.buildQueueState(listOf(a, b), 0)
        // 当前播放条目高亮命中；其余条目不命中
        assertTrue(PlaybackQueueLogic.isCurrentItem(st, a.id))
        org.junit.Assert.assertFalse(PlaybackQueueLogic.isCurrentItem(st, b.id))
        // 空队列 / 无当前条目 → 全部不命中
        assertFalse(PlaybackQueueLogic.isCurrentItem(PlaybackState(), a.id))
    }
}
