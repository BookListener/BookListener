package com.booklistener.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.booklistener.core.AudioItem
import com.booklistener.core.SourceType
import com.booklistener.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放条 UI 回归测试：
 * 覆盖"能播放但界面不显示 / 无进度 / 无上一曲·下一曲按钮"这类低级回归。
 * 关键用例：StateFlow 变化必须驱动 Compose 重组（曾因直接读 .value 导致不显示）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val item = AudioItem("open_uri", "my-song.mp3", "content://x", SourceType.LOCAL, 1024L)

    @Test
    fun emptyState_hidesPlaybackBar() {
        composeRule.setContent {
            NowPlayingBar(state = PlaybackState(), onToggle = {}, onPrevious = {}, onNext = {}, onOpenPlayer = {})
        }
        composeRule.onNodeWithText("my-song.mp3").assertDoesNotExist()
    }

    /**
     * 回归覆盖：播放状态是 StateFlow，UI 必须用 collect 订阅；
     * 若直接读 .value，状态更新后界面不会重组，表现为"能播放但界面不显示"。
     */
    @Test
    fun stateFlowUpdate_rendersCurrentItem() {
        val flow = MutableStateFlow(PlaybackState())
        composeRule.setContent {
            val state by flow.collectAsStateWithLifecycle()
            NowPlayingBar(state = state, onToggle = {}, onPrevious = {}, onNext = {}, onOpenPlayer = {})
        }
        // 空状态：播放条不显示
        composeRule.onNodeWithText("my-song.mp3").assertDoesNotExist()

        // 模拟入队成功（PlaybackQueueLogic.buildQueueState）：UI 必须立即显示
        flow.value = PlaybackQueueLogicState.item()
        composeRule.onNodeWithText("my-song.mp3").assertExists()
    }

    @Test
    fun playingState_showsTitleProgressAndAllControls() {
        val playing = PlaybackQueueLogicState.item().copy(
            positionMs = 30_000,
            durationMs = 180_000,
            isPlaying = true
        )
        composeRule.setContent {
            NowPlayingBar(state = playing, onToggle = {}, onPrevious = {}, onNext = {}, onOpenPlayer = {})
        }
        composeRule.onNodeWithText("my-song.mp3").assertExists()
        composeRule.onNodeWithText("00:30 / 03:00").assertExists()
        composeRule.onNodeWithContentDescription("Previous").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithContentDescription("Next").assertExists()
    }

    @Test
    fun pausedState_showsPlayButton() {
        val paused = PlaybackQueueLogicState.item().copy(isPlaying = false)
        composeRule.setContent {
            NowPlayingBar(state = paused, onToggle = {}, onPrevious = {}, onNext = {}, onOpenPlayer = {})
        }
        composeRule.onNodeWithContentDescription("Play").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }
}

/** 复用播放队列纯逻辑构造状态，保持测试与实现同一来源 */
private object PlaybackQueueLogicState {
    private val item = AudioItem("open_uri", "my-song.mp3", "content://x", SourceType.LOCAL, 1024L)
    fun item(): PlaybackState =
        com.booklistener.player.PlaybackQueueLogic.buildQueueState(listOf(item), 0)
}
