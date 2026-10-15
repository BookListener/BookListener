package com.booklistener.ui

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MarqueeText 行为测试（Compose UI 层）。
 *
 * 说明：像素级动画验证在 Robolectric 不可用（captureToImage 抛 WindowCapture 异常；
 * waitForIdle 不感知动画帧——见对照实验结论）。因此本套测试覆盖：
 * 1) 短文本静态不滚动（布局 bounds 不变）；
 * 2) 超长/短文本进入正确的滚动/静态分支（testTag 判定）；
 * 3) 官方 basicMarquee 在超长文本下按容器约束布局（Text 布局宽被约束到容器宽，
 *    而非无约束自然宽）。
 *
 * 根因记录（v2.0.2→v2.0.3）：官方 basicMarquee 位置参数曾被误传为
 * (0, Immediately, Int.MAX_VALUE, 0, spacing, velocity)，导致 iterations=0
 * （官方语义：禁用动画）→ 真机"字不动"。修复：全部改用命名参数。
 * 此参数语义无法在 Robolectric 像素级验证，真机验收为准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarqueeTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val shortText = "短标题"
    private val longText =
        "290392331)_20261012212913 化子(19: Movieswoninykogengninonwningjuronrotonwojilu 超长专辑名称滚动测试"
    // Robolectric 字体测量失真（≈2.2px/字符）：500 字符 ≈ 1100px，远超 300dp 容器，确保官方判定超宽
    private val megaText = "A".repeat(500)

    private fun setMarquee(widthDp: Int, text: String) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp)) {
                    MarqueeText(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    @Test
    fun shortText_staysStatic() {
        setMarquee(widthDp = 300, text = shortText)
        compose.waitForIdle()
        val before = compose.onNodeWithText(shortText).fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val after = compose.onNodeWithText(shortText).fetchSemanticsNode().boundsInRoot
        // 短文本：动画推进后布局 bounds 完全不变（不滚动）
        assertEquals("短文本不应滚动", before, after)
    }

    @Test
    fun longText_rendersAndIsVisible() {
        setMarquee(widthDp = 300, text = longText)
        compose.waitForIdle()
        assertEquals("超长文本节点应渲染", 1, compose.onAllNodesWithText(longText).fetchSemanticsNodes().size)
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        // 滚动多轮后文本语义节点仍完整可见（渲染不崩溃、不丢内容）
        assertEquals("滚动后文本节点应仍存在", 1, compose.onAllNodesWithText(longText).fetchSemanticsNodes().size)
    }

    @Test
    fun longText_entersScrollBranch() {
        // 超长文本（≈1100px > 300dp 容器）必须进入滚动分支（marquee-scroll），且不进入静态分支
        setMarquee(widthDp = 300, text = megaText)
        compose.waitForIdle()
        assertEquals("超长文本应进入滚动分支", 1, compose.onAllNodesWithTag("marquee-scroll").fetchSemanticsNodes().size)
        assertEquals("超长文本不应进入静态分支", 0, compose.onAllNodesWithTag("marquee-static").fetchSemanticsNodes().size)
    }

    @Test
    fun shortText_staysInStaticBranch() {
        // 短文本必须进入静态分支（marquee-static），且不进入滚动分支
        setMarquee(widthDp = 300, text = shortText)
        compose.waitForIdle()
        assertEquals("短文本应进入静态分支", 1, compose.onAllNodesWithTag("marquee-static").fetchSemanticsNodes().size)
        assertEquals("短文本不应进入滚动分支", 0, compose.onAllNodesWithTag("marquee-scroll").fetchSemanticsNodes().size)
    }

    @Test
    fun officialBasicMarquee_appliesBoundedLayout() {
        // basicMarquee 生效时：容器有界（300dp），Text 布局宽被约束到容器宽（而非 1100px 自然宽）
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    Text(
                        text = megaText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            animationMode = MarqueeAnimationMode.Immediately,
                            repeatDelayMillis = 0,
                            initialDelayMillis = 0,
                            spacing = MarqueeSpacing(10.dp),
                            velocity = 33.dp
                        )
                    )
                }
            }
        }
        compose.waitForIdle()
        val widthPx = compose.onNodeWithText(megaText).fetchSemanticsNode().boundsInRoot.width
        println("DBG-layout widthPx=$widthPx")
        assertTrue(
            "basicMarquee 应将文本约束到容器宽（300px），实际=$widthPx",
            widthPx < 1000f
        )
    }
}
