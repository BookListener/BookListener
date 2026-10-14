package com.booklistener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MarqueeText 滚动判定纯逻辑测试：仅文本实际宽度超过可视宽度才滚动 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarqueeTextTest {

    @Test
    fun shouldMarquee_shortText_staysStill() {
        assertFalse("短文本不应滚动", shouldMarquee(textWidth = 100, containerWidth = 300))
        assertFalse("恰好相等不应滚动", shouldMarquee(textWidth = 300, containerWidth = 300))
    }

    @Test
    fun shouldMarquee_overflowingText_scrolls() {
        assertTrue("超长文本应向左滚动", shouldMarquee(textWidth = 400, containerWidth = 300))
        assertTrue("仅超出 1px 也应滚动", shouldMarquee(textWidth = 301, containerWidth = 300))
    }

    @Test
    fun shouldMarquee_zeroContainer_stillScrolls() {
        assertTrue("可视宽度为 0 时也应滚动", shouldMarquee(textWidth = 10, containerWidth = 0))
    }

    // v2.0.1：无缝回绕间隔必须 ≥ 视口宽，否则滚动中首尾叠加
    @Test
    fun marqueeGap_coversViewport_preventsHeadTailOverlap() {
        // 间隔 ≥ 视口宽：第一份文本完全离开后第二份才进入，视口内同时最多一份
        assertEquals(300, marqueeGap(twoCharWidth = 24, containerWidth = 300))
        assertEquals(320, marqueeGap(twoCharWidth = 320, containerWidth = 300))
        assertEquals(200, marqueeGap(twoCharWidth = 24, containerWidth = 200))
    }

    @Test
    fun marqueeGap_zeroViewport_fallsBackToTwoCharWidth() {
        // 首帧视口未测得（0）：退化为两字符宽下限
        assertEquals(24, marqueeGap(twoCharWidth = 24, containerWidth = 0))
    }
}
