package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0.1 播放速度档位纯逻辑回归：循环顺序 / 末档回卷 / 脏数据防御 / 档位文字 */
class PlaybackSpeedTiersTest {

    @Test
    fun cycle_followsAscendingTiers() {
        // 0.5 → 0.75 → 1.0 → 1.25 → 1.5 → 2.0 → 0.5
        val seq = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        seq.zipWithNext().forEach { (cur, nxt) ->
            assertEquals("$cur 的下一档应为 $nxt", nxt, PlaybackSpeedTiers.next(cur), 0.001f)
        }
        assertEquals("末档 2.0 应回卷到 0.5", 0.5f, PlaybackSpeedTiers.next(2.0f), 0.001f)
    }

    @Test
    fun cycle_unknownValue_takesNearestHigherTier() {
        // 持久化脏数据防御：不在档位内取最近大于它的档
        assertEquals(1.25f, PlaybackSpeedTiers.next(1.1f), 0.001f)
        assertEquals(0.5f, PlaybackSpeedTiers.next(0.4f), 0.001f)
        assertEquals(0.5f, PlaybackSpeedTiers.next(3.0f), 0.001f)
    }

    @Test
    fun label_formatsTierText() {
        assertEquals("1.0x", PlaybackSpeedTiers.label(1.0f))
        assertEquals("2.0x", PlaybackSpeedTiers.label(2.0f))
        assertEquals("0.5x", PlaybackSpeedTiers.label(0.5f))
        assertEquals("0.75x", PlaybackSpeedTiers.label(0.75f))
        assertEquals("1.25x", PlaybackSpeedTiers.label(1.25f))
        assertEquals("1.5x", PlaybackSpeedTiers.label(1.5f))
    }

    @Test
    fun tiers_coverMinToMaxInclusive() {
        assertTrue(PlaybackSpeedTiers.MIN <= 0.5f)
        assertTrue(PlaybackSpeedTiers.MAX >= 2.0f)
        PlaybackSpeedTiers.TIERS.forEach { assertTrue("档位 $it 应在 0.5-2.0 内", it in 0.5f..2.0f) }
    }
}
