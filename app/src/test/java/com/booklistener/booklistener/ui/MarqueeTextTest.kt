package com.booklistener.ui

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
}
