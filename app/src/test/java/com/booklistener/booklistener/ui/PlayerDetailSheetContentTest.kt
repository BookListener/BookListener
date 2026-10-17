package com.booklistener.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipeRight
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioMetaDefaults
import com.booklistener.core.SourceType
import com.booklistener.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放详情页回归测试：
 * 覆盖"歌名/进度/控制按钮展示、播放暂停图标随状态切换、无曲目不渲染、进度条存在"，
 * 以及进度条 valueRange 回归：Slider 必须按真实毫秒范围渲染，拖动 seek 必须提交真实 ms
 * （曾缺 valueRange 导致进度条永远满格、seek 只到 0/1ms）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xhdpi")
class PlayerDetailSheetContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val item = AudioItem("id1", "米店", "content://x/1", SourceType.LOCAL, 1000L)

    private fun state(
        playing: Boolean = false,
        position: Long = 49_000L,
        duration: Long = 334_000L,
        index: Int = 0,
        queueSize: Int = 3
    ) = PlaybackState(
        currentItem = item,
        isPlaying = playing,
        positionMs = position,
        durationMs = duration,
        queue = List(queueSize) { item },
        queueIndex = index
    )

    private fun render(state: PlaybackState) {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state,
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onSeek = {}
            )
        }
    }

    /** 回归：所属条目带真实封面 uri 时详情页安全渲染（加载失败回退占位，不崩溃） */
    @Test
    fun entryWithCoverUri_rendersSafelyWithFallback() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(),
                entry = com.booklistener.core.SavedEntry(
                    id = "content://tree/primary%3ADownload",
                    displayName = "哈利·波特",
                    uri = "content://tree/primary%3ADownload",
                    sourceType = SourceType.LOCAL,
                    coverUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/cover.jpg",
                    fileCount = 3
                ),
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onSeek = {}
            )
        }
        composeRule.waitForIdle()
        // 带真实封面 uri 渲染不崩溃，标题与进度正常（封面实际显示效果由真机验证；
        // Robolectric 无 content provider 且 bitmap 环境不可靠，不在此断言封面节点）
        composeRule.onNodeWithText("米店").assertExists()
        composeRule.onNodeWithText("Track 1 of 3").assertExists()
    }

    @Test
    fun showsTitleAndQueuePosition() {
        render(state())
        composeRule.onNodeWithText("米店").assertExists()
        composeRule.onNodeWithText("Track 1 of 3").assertExists()
    }

    @Test
    fun showsProgressTextAndSeekBar() {
        render(state())
        composeRule.onNodeWithText("00:49").assertExists()
        composeRule.onNodeWithText("05:34").assertExists()
        composeRule.onNodeWithTag("seek_bar").assertExists()
    }

    @Test
    fun paused_showsPlayIcon() {
        render(state(playing = false))
        composeRule.onNodeWithContentDescription("Play").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun playing_showsPauseIcon() {
        render(state(playing = true))
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun controlsExist() {
        render(state())
        composeRule.onNodeWithContentDescription("Previous").assertExists()
        composeRule.onNodeWithContentDescription("Next").assertExists()
    }

    // ---- v2.0.1：AB 未开始时隐藏"未设置 AB 点"与取消 X，仅留小旗；设点后恢复完整行 ----

    @Test
    fun abNotStarted_hidesTextAndClearButton_showsFlagOnly() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(),
                abA = null,
                abB = null,
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("未设置 AB 点").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Clear AB points").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Set repeat range").assertExists()
    }

    @Test
    fun abStarted_showsStatusAndClearButton() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(position = 120_000L),
                abA = 60_000L,
                abB = null,
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("A 01:00 (tap again to set B)").assertExists()
        composeRule.onNodeWithContentDescription("Clear AB points").assertExists()
        composeRule.onNodeWithContentDescription("Set repeat range").assertExists()
    }

    @Test
    fun abBothSet_showsRange() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(),
                abA = 60_000L,
                abB = 300_000L,
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("01:00 ~ 05:00").assertExists()
    }

    // ---- v2.0.1：循环入口暂时取消（业务逻辑保留），同位置为播放速度入口（点击弹动作条） ----

    @Test
    fun speedButton_showsCurrentTierText() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(),
                speed = 1.0f,
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("1.0x").assertExists()
        composeRule.onNodeWithContentDescription("顺序播放").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("单曲循环").assertDoesNotExist()
    }

    @Test
    fun speedButton_showsNonDefaultTier_andClickOpensSpeedSheet() {
        var opened = 0
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(),
                speed = 1.5f,
                onOpenSpeedSheet = { opened++ },
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("1.5x").assertExists()
        composeRule.onNodeWithText("1.5x").performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals(1, opened)
    }

    // ---- v2.0.1：详情页取消作者显示，仅保留专辑名 ----

    @Test
    fun artistHidden_albumOnlyShown() {
        val itemWithMeta = item.copy(artist = "J.K. Rowling", album = "哈利·波特与魔法石")
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state().copy(currentItem = itemWithMeta),
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("J.K. Rowling").assertDoesNotExist()
        composeRule.onNodeWithText("哈利·波特与魔法石").assertExists()
        composeRule.onNodeWithText("J.K. Rowling | 哈利·波特与魔法石").assertDoesNotExist()
    }

    /** 专辑名链路：current.album ?: entry.displayName —— 条目存在时专辑名恒有（文件夹名兜底） */
    @Test
    fun artistMissing_albumFallsBackToEntryDisplayName() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(), // item 无 album/artist
                entry = com.booklistener.core.SavedEntry(
                    id = "content://tree/primary%3ADownload",
                    displayName = "哈利·波特",
                    uri = "content://tree/primary%3ADownload",
                    sourceType = SourceType.LOCAL,
                    fileCount = 3
                ),
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText("哈利·波特").assertExists()
        composeRule.onNodeWithText(AudioMetaDefaults.UNKNOWN_ARTIST).assertDoesNotExist()
    }

    /** 仅直接打开单文件且无任何专辑来源的边缘场景才兜底 Unknown（防御，正常路径不可达） */
    @Test
    fun noEntryAndNoAlbum_showsUnknownFallback() {
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(),
                onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {}
            )
        }
        composeRule.onNodeWithText(AudioMetaDefaults.UNKNOWN_ARTIST).assertExists()
    }

    @Test
    fun noCurrentItem_rendersNothing() {
        render(PlaybackState())
        composeRule.onNodeWithText("米店").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        assertFalse(PlaybackState().hasItem)
        assertTrue(PlaybackState(queue = listOf(item), queueIndex = 0, currentItem = item).hasItem)
    }

    /**
     * 回归：Slider 必须使用真实毫秒 valueRange（0..duration），
     * 而不是 Material3 默认的 0f..1f —— 否则进度条永远满格。
     */
    @Test
    fun seekBar_range_matchesRealDuration() {
        render(state(position = 49_000L, duration = 334_000L))
        composeRule.onNodeWithTag("seek_bar").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(49_000f, 0f..334_000f, 0)
            )
        )
    }

    /**
     * 回归：拖动进度条到末端后提交的 seek 值必须是真实毫秒（≈durationMs），
     * 而不是被 0..1 压缩后的 0/1ms。
     */
    @Test
    fun dragSeek_commitsRealPositionMs() {
        var seeked = -1L
        composeRule.setContent {
            PlayerDetailSheetContent(
                state = state(position = 0L, duration = 334_000L),
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onSeek = { seeked = it }
            )
        }
        composeRule.onNodeWithTag("seek_bar").performTouchInput {
            // Compose 1.11 手势语义：swipe 系列 up 落点可能在节点外导致手势取消、不触发提交。
            // 显式手势：中心按下（Slider 跳转到 50%）→ 拖到 99% 节点内落点 → up。
            // 落点 ~96%（含 thumb/padding 偏移），在下方 5% 容差内。
            down(center)
            advanceEventTime(50)
            moveTo(Offset(width * 0.99f, center.y))
            advanceEventTime(50)
            up()
        }
        // 容差 5%：拖动起点含 thumb/padding 偏移，实际到 ~97%；
        // 该容差仍能抓住"seek 只到 0/1ms"的回归（偏差 100%）
        assertEquals(334_000.0, seeked.toDouble(), 334_000.0 * 0.05)
    }

    /**
     * 回归：进度条渲染位置与时间文本一致（满格时才 100%，46s/22:40 ≈ 3.4%）。
     * 语义层验证 range 端点即为此断言的补充；此处再验证时间文本与状态一致。
     */
    @Test
    fun progressText_matchesPositionAndDuration() {
        render(state(position = 46_000L, duration = 1_360_000L))
        composeRule.onNodeWithText("00:46").assertExists()
        composeRule.onNodeWithText("22:40").assertExists()
        composeRule.onNodeWithTag("seek_bar").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(46_000f, 0f..1_360_000f, 0)
            )
        )
    }
}
