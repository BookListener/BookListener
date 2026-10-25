package com.booklistener.ui

import com.booklistener.auth.CredentialStore
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.AlbumStore
import com.booklistener.core.AlbumSummary
import com.booklistener.core.AudioItemFactory
import com.booklistener.core.IndexedFile
import com.booklistener.core.SourceType
import com.booklistener.core.WorkStore
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import com.booklistener.player.PlayableItem
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
 * 专辑交互回归（v1.7.0 修复 + v4.0 作品化）：
 * 点击其他专辑后"暂停当前播放并把正在播放切换为该作品记录、就绪不自动播放、弹详情"，
 * 点击正在播放的同一作品不打断不弹详情。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumInteractionTest {

    private val treeA = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABook")
    private val treeB = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    private lateinit var db: AppDatabase
    private var workId: Long = 0L

    @Before
    fun setUp() {
        // 隔离用例间单例残留：默认无队列归属
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        seedAlbumA()
        workId = runBlocking {
            db.dao().getWorkIdByEntryAndAlbum(treeA.toString(), "哈利·波特与魔法石")!!
        }
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
            // v4.0 作品化扫描：作品实体 + entry_index(workId/sortRank)
            db.dao().saveEntryIndexWithWorks(
                entryId = treeA.toString(),
                folderName = "Book",
                sourceScope = "LOCAL",
                files = listOf(
                    IndexedFile("第1章", "uri://a1", album, null, 1, null, 120_000L, "第1章.mp3"),
                    IndexedFile("第2章", "uri://a2", album, null, 2, null, 150_000L, "第2章.mp3")
                )
            )
            // 作品级播放记录：定位第 2 首 5 秒处（matchMode=1 精确）
            val workId = db.dao().getWorkIdByUri("uri://a1")!!
            WorkStore(db.dao()).save(workId, "uri://a2", 1, 5_000L, matchMode = 1)
        }
    }

    private fun recentAlbum(): AlbumSummary = AlbumSummary(
        workId = workId,
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

    /** 核心回归：点击其他专辑 → 播放上下文切到该作品记录，就绪不自动播放，弹详情 */
    @Test
    fun openOtherAlbum_switchesPlaybackRecord_pausedAndShowsSheet() {
        val vm = newViewModel()
        vm.openAlbum(recentAlbum())

        // 队列归属切到目标作品（等待 db 全量读取的异步完成）
        awaitQueueOwner("work:$workId")
        // 就绪不自动播放（暂停态）
        assertFalse("切换后不应自动开始播放", PlaybackController.state.value.isPlaying)
        // 弹出播放详情（轮询等待：弹详情发生在 prepareFolder 之后同一续体，idle 时序偶发滞后）
        awaitTrue("切换专辑后应弹播放详情") { vm.requestPlayerSheet.value }
        // 进入专辑视图
        assertTrue(vm.inFolderView.value)
        assertEquals(treeA, vm.currentFolderUri.value)
    }

    /** v2.0.7：点击正在播放的同一作品——不重建队列（保持播放不打断），但必须弹"正在播放页" */
    @Test
    fun openSamePlayingAlbum_doesNotInterrupt() {
        val vm = newViewModel()
        // 模拟：目标作品正在播放（非空队列 → queueReady=true，正在播放同一作品）
        val item = AudioItemFactory.fromSearch(SourceType.LOCAL, "第1章", "uri://a1")
        PlaybackController.prepareFolder(
            listOf(PlayableItem(item, item.path)), 0, 0L, "work:$workId"
        )
        vm.openAlbum(recentAlbum())

        assertEquals("正在播放同一作品不应重建队列（保持播放）", "work:$workId", PlaybackController.currentQueueOwnerId)
        assertTrue("点击专辑必弹正在播放页（v2.0.7 bugfix），正在播放同一作品也弹", vm.requestPlayerSheet.value)
    }

    /** v2.1.22：进入专辑一次全量查询——albumSongs 直接全量（=全部曲目），无分页残留 */
    @Test
    fun openAlbum_loadsAllSongsInOneQuery() {
        val vm = newViewModel()
        vm.openAlbum(recentAlbum())
        awaitTrue("openAlbum 后 albumSongs 应为全量曲目") {
            vm.albumSongs.value.size == 2
        }
        assertEquals("albumSongs 全部为同一作品曲目", 2, vm.albumSongs.value.size)
        assertEquals("曲目顺序按 sortRank（第1章在前）", "第1章", vm.albumSongs.value[0].displayName)
        // 队列与列表同源：ownerId 仍指向该作品（openAlbum 复用同一份数据构建队列）
        awaitQueueOwner("work:$workId")
    }

    /**
     * v2.1.25：动作条封面点击（openCurrentWorkAlbum）进入专辑列表——
     * 封面取作品封面（works.coverUri），嵌套场景不再因条目封面 null 丢失（此前的污染路径）。
     */
    @Test
    fun openCurrentWorkAlbum_usesWorkCover() {
        runBlocking {
            db.dao().updateWorkCoverByKey(
                "local:哈利·波特与魔法石", "content://cover/album.jpg", System.currentTimeMillis()
            )
        }
        val probe = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        PlaybackController.attach(probe)
        val vm = newViewModel()
        // 先建立作品级播放队列（ownerId="work:..."）
        vm.openAlbum(recentAlbum().copy(coverUri = "content://cover/album.jpg"))
        awaitQueueOwner("work:$workId")
        // 动作条封面点击 → 进入专辑列表
        vm.openCurrentWorkAlbum()
        awaitTrue("openCurrentWorkAlbum 后当前专辑封面应为作品封面") {
            vm.currentAlbum.value?.coverUri == "content://cover/album.jpg"
        }
        assertEquals("统一取源应为作品封面", "content://cover/album.jpg", vm.currentPlaybackCover())
        PlaybackController.detach(probe)
        probe.release()
    }

    /**
     * v2.1.25：退出专辑视图后动作条封面保留——队列封面生命周期独立于 _currentAlbum，
     * 不再因 exitAlbumView 清空当前专辑而回退条目封面（嵌套场景 null → 丢封面）。
     */
    @Test
    fun exitAlbumView_keepsQueueCover() {
        runBlocking {
            db.dao().updateWorkCoverByKey(
                "local:哈利·波特与魔法石", "content://cover/album.jpg", System.currentTimeMillis()
            )
        }
        val probe = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        PlaybackController.attach(probe)
        val vm = newViewModel()
        vm.openAlbum(recentAlbum().copy(coverUri = "content://cover/album.jpg"))
        awaitQueueOwner("work:$workId")
        vm.exitAlbumView()
        assertTrue("退出专辑视图后当前专辑应为空", vm.currentAlbum.value == null)
        awaitTrue("退出专辑视图后动作条封面仍为队列封面") {
            vm.currentPlaybackCover() == "content://cover/album.jpg"
        }
        PlaybackController.detach(probe)
        probe.release()
    }

    /**
     * v2.1.24：通知栏/锁屏封面与专辑列表同源——播放队列 artwork 应为作品封面（works.coverUri），
     * 而不是条目封面（嵌套场景条目封面为 null，是此前通知无封面的根因）。
     */
    @Test
    fun openAlbum_queueArtwork_usesWorkCover() {
        // 作品封面入库（v2.1.23 扫描产物，AlbumStore 查询可得）
        runBlocking {
            db.dao().updateWorkCoverByKey(
                "local:哈利·波特与魔法石", "content://cover/album.jpg", System.currentTimeMillis()
            )
        }
        // 从 AlbumStore（真实入口）取摘要：coverUri 应为作品封面
        val summary = runBlocking {
            (AlbumStore(db.dao()).notPlayed() + AlbumStore(db.dao()).recentPlayed())
                .first { it.album == "哈利·波特与魔法石" }
        }
        assertEquals("作品封面已入库并可读", "content://cover/album.jpg", summary.coverUri)

        // 确保队列落进可达 player（service 未接管时用本测试 player；接管时读同一引用）
        val probe = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        PlaybackController.attach(probe)
        val vm = newViewModel()
        vm.openAlbum(summary)
        awaitQueueOwner("work:$workId")
        awaitTrue("通知栏封面应为作品封面（与专辑列表同源）") {
            PlaybackController.currentArtworkUri?.toString() == "content://cover/album.jpg"
        }
        PlaybackController.detach(probe)
        probe.release()
    }
}
