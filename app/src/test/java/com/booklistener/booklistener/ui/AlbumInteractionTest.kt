package com.booklistener.ui

import com.booklistener.auth.CredentialStore
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.AlbumSummary
import com.booklistener.db.AppDatabase
import com.booklistener.db.EntryIndexEntity
import com.booklistener.db.FolderProgressEntity
import com.booklistener.db.SavedEntryEntity
import com.booklistener.player.PlaybackController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专辑交互回归（v1.7.0 修复）：
 * 点击其他专辑后"暂停当前播放并把正在播放切换为该专辑记录、就绪不自动播放、弹详情"，
 * 点击正在播放的同一专辑不打断不弹详情。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumInteractionTest {

    private val treeA = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABook")
    private val treeB = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        // 隔离用例间单例残留：默认无队列归属
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    private fun seedAlbumA(album: String = "哈利·波特与魔法石") {
        runBlocking {
            db.dao().insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = treeA.toString(), displayName = "Book", uri = treeA.toString(),
                        sourceType = "LOCAL", coverUri = null, fileCount = 2, lastPlayedAt = 1_000L,
                        artist = "J.K. Rowling", lastOpenedAt = 0L, sortOrder = 0
                    )
                )
            )
            db.dao().insertEntryIndex(
                listOf(
                    EntryIndexEntity(treeA.toString(), "第1章", "uri://a1", album, null, 1, null, 120_000L, "第1章.mp3"),
                    EntryIndexEntity(treeA.toString(), "第2章", "uri://a2", album, null, 2, null, 150_000L, "第2章.mp3")
                )
            )
            // 独立播放记录：定位第 2 首 5 秒处
            db.dao().upsertFolderProgress(
                FolderProgressEntity(treeA.toString(), trackIndex = 1, positionMs = 5_000L, updatedAt = 1_000L)
            )
        }
    }

    private fun recentAlbum(): AlbumSummary = AlbumSummary(
        album = "哈利·波特与魔法石",
        entryId = treeA.toString(),
        artist = "J.K. Rowling",
        songCount = 2,
        totalDurationMs = 270_000L,
        coverUri = null
    )

    /** 等 enterAlbumWithRecord 的 IO 异步完成（Robolectric：主 looper 需 idle 推进 Main 调度） */
    private fun awaitQueueOwner(expected: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (PlaybackController.currentQueueOwnerId == expected) return
            Thread.sleep(20)
        }
        assertEquals(expected, PlaybackController.currentQueueOwnerId)
    }

    /** 通用异步断言：主 looper idle + 轮询，超时失败（避免全量跑时 IO 调度时序的偶发竞态） */
    private fun awaitTrue(msg: String, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (block()) return
            Thread.sleep(20)
        }
        assertTrue(msg, false)
    }

    /** 核心回归：点击其他专辑 → 播放上下文切到该专辑记录，就绪不自动播放，弹详情 */
    @Test
    fun openOtherAlbum_switchesPlaybackRecord_pausedAndShowsSheet() {
        seedAlbumA()
        val vm = newViewModel()
        vm.openAlbum(recentAlbum())

        // 队列归属切到目标专辑（等待 db 全量读取的异步完成）
        awaitQueueOwner(treeA.toString())
        // 就绪不自动播放（暂停态）
        assertFalse("切换后不应自动开始播放", PlaybackController.state.value.isPlaying)
        // 弹出播放详情（轮询等待：弹详情发生在 prepareFolder 之后同一续体，idle 时序偶发滞后）
        awaitTrue("切换专辑后应弹播放详情") { vm.requestPlayerSheet.value }
        // 进入专辑视图
        assertTrue(vm.inFolderView.value)
        assertEquals(treeA, vm.currentFolderUri.value)
    }

    /** 点击正在播放的同一专辑：不重建队列、不弹详情、保持播放不打断 */
    @Test
    fun openSamePlayingAlbum_doesNotInterrupt() {
        seedAlbumA()
        val vm = newViewModel()
        // 模拟：目标专辑正在播放
        PlaybackController.prepareFolder(emptyList(), 0, 0L, treeA.toString())
        vm.openAlbum(recentAlbum())

        assertEquals(treeA.toString(), PlaybackController.currentQueueOwnerId)
        assertFalse("正在播放同一专辑不应弹详情", vm.requestPlayerSheet.value)
    }
}
