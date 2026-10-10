package com.booklistener.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.booklistener.player.PlaybackState

/**
 * 播放详情弹层空态回归：无播放队列时点击播放入口，
 * 必须显示引导文案而非"毫无反应"（v1.5.4 修复）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerDetailSheetEmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyState_showsGuidance() {
        composeRule.setContent {
            PlayerDetailSheet(
                viewModel = PlayerViewModel(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    database = androidx.room.Room.inMemoryDatabaseBuilder(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        com.booklistener.db.AppDatabase::class.java
                    ).allowMainThreadQueries().build()
                ),
                state = PlaybackState(),
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("还没有正在播放的内容").assertExists()
        composeRule.onNodeWithText("请先添加播放文件夹并进入，\n或在已添加内容中点击条目开始播放").assertExists()
    }
}
