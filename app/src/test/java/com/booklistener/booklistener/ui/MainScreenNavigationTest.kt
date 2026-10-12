package com.booklistener.ui

import com.booklistener.auth.CredentialStore
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.AudioItem
import com.booklistener.core.SourceType
import com.booklistener.db.AppDatabase
import com.booklistener.player.PlaybackController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主界面导航行为回归（v1.5.7）：
 * - 点击"曲目"tab 在条目视图内应退回文件夹列表（逻辑 = exitFolderView，行为由 exitFolderView_clearsState 覆盖；
 *   MainScreen 组合渲染受 Robolectric 不支持 EncryptedSharedPreferences 限制，由真机验证）
 * - 进入条目视图时"是否重新加载队列"的判定：判定源为播放层队列所属（currentQueueOwnerId）+
 *   队列就绪（queueReady），队列所属相同且就绪的同一文件夹保持会话不重启（播放/暂停均不打断）
 *   v1.6.9：判定源由 isPlaying 改为 queueReady——暂停态点击封面恢复播放是异步的，
 *   旧判定在暂停态恒重启队列并覆盖刚恢复的播放
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val treeA = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
    private val treeB = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    /**
     * 模拟"播放层已挂载某队列"（player 未 attach 走暂存路径，但 ownerId 在入口即生效）。
     * withItem=true 挂非空暂存队列（queueReady=true，模拟播放会话就绪）；
     * withItem=false 挂空队列（queueReady=false，模拟会话未就绪）。
     */
    private fun mountQueue(ownerId: String?, withItem: Boolean = true) {
        val items = if (withItem) {
            listOf(
                com.booklistener.player.PlayableItem(
                    item = AudioItem(
                        id = "a.mp3",
                        displayName = "a.mp3",
                        path = "a.mp3",
                        sourceType = SourceType.LOCAL,
                        size = 1L
                    ),
                    uri = "a.mp3"
                )
            )
        } else emptyList()
        PlaybackController.prepareFolder(items, 0, 0L, ownerId)
    }

    @Before
    fun unmountQueue() {
        // 隔离用例间单例残留：默认无队列归属
        mountQueue(null)
        // v1.6.0：ViewModel 注入 in-memory db（隔离用例数据）
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private lateinit var db: AppDatabase

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    @Test
    fun shouldRestartOnEnter_samePlayingFolder_keepsPlaying() {
        mountQueue(treeA.toString())
        // 队列已就绪且所属 == 目标文件夹 → 不重启队列（保持播放不暂停）
        assertFalse(
            "正在播放的同一文件夹再次进入不应重启队列",
            newViewModel()
                .shouldRestartOnEnter(treeA.toString(), isPlaying = true)
        )
    }

    @Test
    fun shouldRestartOnEnter_sameFolder_paused_keepsSession() {
        mountQueue(treeA.toString())
        // v1.6.9：队列就绪时暂停中进入同一文件夹 → 不重启（避免覆盖恢复中的播放）
        assertFalse(
            "队列就绪时暂停中进入同一文件夹不应重启队列",
            newViewModel()
                .shouldRestartOnEnter(treeA.toString(), isPlaying = false)
        )
    }

    @Test
    fun shouldRestartOnEnter_switchingFolder_restarts() {
        mountQueue(treeA.toString())
        // 目标文件夹 != 播放层所属 → 走记录恢复
        assertTrue(
            "切换到另一文件夹应重启队列",
            newViewModel()
                .shouldRestartOnEnter(treeB.toString(), isPlaying = true)
        )
        // 队列未就绪（有归属但无内容）→ 走记录恢复
        mountQueue(treeA.toString(), withItem = false)
        assertTrue(
            "队列未就绪时应重启队列",
            newViewModel()
                .shouldRestartOnEnter(treeA.toString(), isPlaying = true)
        )
        // 播放层无队列归属 → 走记录恢复
        mountQueue(null)
        assertTrue(
            "无队列归属时应重启队列",
            newViewModel()
                .shouldRestartOnEnter(treeA.toString(), isPlaying = true)
        )
    }
}
