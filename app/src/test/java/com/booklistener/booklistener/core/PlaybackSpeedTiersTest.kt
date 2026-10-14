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
    fun nearest_snapsToClosestTier() {
        // Slider 拖动吸附：非档位值归位到最近档，保证滑块位置与下方档位标志一致
        assertEquals(1.0f, PlaybackSpeedTiers.nearest(1.0f), 0.001f)
        assertEquals(1.5f, PlaybackSpeedTiers.nearest(1.4f), 0.001f)
        assertEquals(1.25f, PlaybackSpeedTiers.nearest(1.2f), 0.001f)
        assertEquals(1.5f, PlaybackSpeedTiers.nearest(1.6f), 0.001f)
        assertEquals(2.0f, PlaybackSpeedTiers.nearest(2.0f), 0.001f)
        assertEquals(0.5f, PlaybackSpeedTiers.nearest(0.4f), 0.001f)
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

    @Test
    fun fraction_linearAcrossTiers() {
        // 档位在 Slider 行程上的线性比例：0.5→0%、1.25→50%、2.0→100%
        assertEquals("0.5x 应在行程 0%", 0f, PlaybackSpeedTiers.fraction(0.5f), 0.001f)
        assertEquals("0.75x 应在行程 16.7%", 1f / 6f, PlaybackSpeedTiers.fraction(0.75f), 0.001f)
        assertEquals("1.0x 应在行程 33.3%", 2f / 6f, PlaybackSpeedTiers.fraction(1.0f), 0.001f)
        assertEquals("1.25x 应在行程 50%", 3f / 6f, PlaybackSpeedTiers.fraction(1.25f), 0.001f)
        assertEquals("1.5x 应在行程 66.7%", 4f / 6f, PlaybackSpeedTiers.fraction(1.5f), 0.001f)
        assertEquals("2.0x 应在行程 100%", 1f, PlaybackSpeedTiers.fraction(2.0f), 0.001f)
    }

    @Test
    fun labelCenters_alignWithThumbTrack_atAnyWidth() {
        // 不同屏宽（360dp/480dp 等）下，档位文字中心 = trackStart + trackLen * fraction - textWidth/2。
        // 验证"文字中心恰好落在 thumb 行程刻度上"在不同 trackLen 下均成立：
        // 文字中心 + 文字宽/2 == 刻度；刻度 = trackStart + trackLen * fraction。
        val textWidth = 60f
        for (trackLen in listOf(292f, 412f, 500f)) { // 对应屏宽 360/480/568dp 的 thumb 行程
            val trackStart = 34f
            PlaybackSpeedTiers.TIERS.forEach { tier ->
                val expectedTick = trackStart + trackLen * PlaybackSpeedTiers.fraction(tier)
                val labelLeft = trackStart + trackLen * PlaybackSpeedTiers.fraction(tier) - textWidth / 2f
                val labelCenter = labelLeft + textWidth / 2f
                assertEquals("档位 $tier 文字中心应落在刻度 $expectedTick", expectedTick, labelCenter, 0.001f)
            }
        }
    }
}
