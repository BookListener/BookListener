package com.booklistener.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.20 设置页卡片风格重构测试：
 * 主页 6 入口按语义归为 3 组卡片；主题子页外观模式为图标化三元选择器（Light/Dark/Auto）。
 * 断言用默认 en 资源渲染（同 LanguageSubpageTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xhdpi")
class SettingsCardStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun newViewModel(): PlayerViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.booklistener.db.AppDatabase::class.java)
            .allowMainThreadQueries().build()
        return PlayerViewModel(
            app,
            database = db,
            credentialStore = com.booklistener.auth.CredentialStore(app, secure = false)
        )
    }

    @Test
    fun homeGroupsEntriesBySemantics() {
        val vm = newViewModel()
        composeRule.setContent { SettingsScreen(vm) }
        // 三个分组标题存在
        composeRule.onNodeWithText("Sources").assertExists()
        composeRule.onNodeWithText("Playback & appearance").assertExists()
        // "About" 既是分组标题又是入口，至少存在一个；入口以可点击行文本断言
        composeRule.onNodeWithText("Folders").assertExists()
        composeRule.onNodeWithText("WebDAV accounts").assertExists()
        composeRule.onNodeWithText("Sleep timer (global)").assertExists()
        composeRule.onNodeWithText("Theme").assertExists()
        composeRule.onNodeWithText("Cache management").assertExists()
    }

    @Test
    fun themeSubpage_hasThreeIconOptions() {
        val vm = newViewModel()
        composeRule.setContent { SettingsScreen(vm) }
        composeRule.onNodeWithText("Theme").performClick()
        // 外观模式三元选择器：Light / Dark / Auto 均存在（默认 Auto 或 Light）
        composeRule.onNodeWithText("Light").assertExists()
        composeRule.onNodeWithText("Dark").assertExists()
        composeRule.onNodeWithText("Auto").assertExists()
    }

    @Test
    fun themeSubpage_canSwitchAppearance() {
        val vm = newViewModel()
        composeRule.setContent { SettingsScreen(vm) }
        composeRule.onNodeWithText("Theme").performClick()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Auto").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Light").performClick()
        composeRule.waitForIdle()
        // 点击路径无异常、可连续切换
        composeRule.onNodeWithText("Theme").assertExists()
    }
}
