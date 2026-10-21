package com.booklistener.ui

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.auth.CredentialStore
import com.booklistener.core.IndexedFile
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import com.booklistener.player.PlaybackController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.5/v2.1.6：封面点击作品定位回归测试。
 * 覆盖三种播放队列归属场景，确保封面点击始终定位到正在播放的作品，而非条目下第一个作品。
 *
 * 背景：v2.1.0 作品化后播放队列归属有三种形态：
 * 1. ownerId = "work:<id>"（主页专辑播放 → enterAlbumWithRecord）
 * 2. ownerId = entryId（网盘条目进入 → enterFolderWithRecord）
 * 3. ownerId = null（搜索结果/文件夹视图点击单曲 → playItems）
 * 旧实现 openEntryAlbum 取条目下 works.firstOrNull()，场景 2/3 必然跳错专辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverClickWorkResolutionTest {

    private lateinit var db: AppDatabase
    private val entryId = "content://tree/a"

    @Before
    fun setUp() {
        // 隔离用例间单例残留：默认无队列归属、无播放状态
        PlaybackController.setStateForTest(com.booklistener.player.PlaybackState())
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        val ctx: android.content.Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            db.dao().insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryId, displayName = "多专辑文件夹", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
            // 同一条目下两个作品（专辑甲、专辑乙），模拟多专辑文件夹
            db.dao().saveEntryIndexWithWorks(
                entryId, "多专辑文件夹", "LOCAL",
                listOf(
                    IndexedFile("甲1", "uri://a1", "专辑甲", "作者A", 1, 1, 60_000L, "a1.mp3"),
                    IndexedFile("甲2", "uri://a2", "专辑甲", "作者A", 2, 1, 90_000L, "a2.mp3"),
                    IndexedFile("乙1", "uri://b1", "专辑乙", "作者B", 1, 1, 120_000L, "b1.mp3")
                )
            )
        }
        // 隔离用例间单例残留
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(
            ApplicationProvider.getApplicationContext(),
            database = db,
            credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false)
        )

    private fun workIdOf(uri: String): Long =
        runBlocking { db.dao().getWorkIdByUri(uri) }!!

    // ---- resolveCurrentWorkId 三分支 ----

    @Test
    fun resolveCurrentWorkId_workPrefix_directlyReturnsWorkId() {
        val vm = newViewModel()
        // 作品级队列：ownerId 直接含 workId，不依赖当前曲目
        assertEquals(42L, vm.resolveCurrentWorkId("work:42", null))
        assertEquals(42L, vm.resolveCurrentWorkId("work:42", "uri://anything"))
        // 非法 workId → null
        assertNull(vm.resolveCurrentWorkId("work:abc", null))
    }

    @Test
    fun resolveCurrentWorkId_entryOwner_resolvesByCurrentTrackUri() {
        val vm = newViewModel()
        val workB = workIdOf("uri://b1")
        // 条目级队列（ownerId=entryId）：必须从当前播放曲目反查作品，不能取条目第一个
        assertEquals(
            "正在播放专辑乙的曲目时应解析到专辑乙的 workId，而非条目下第一个作品",
            workB,
            vm.resolveCurrentWorkId(entryId, "uri://b1")
        )
        val workA = workIdOf("uri://a1")
        assertEquals(workA, vm.resolveCurrentWorkId(entryId, "uri://a2"))
    }

    @Test
    fun resolveCurrentWorkId_nullOwner_resolvesByCurrentTrackUri() {
        val vm = newViewModel()
        val workB = workIdOf("uri://b1")
        // 无主队列（搜索/单曲播放）：同样从当前曲目反查
        assertEquals(workB, vm.resolveCurrentWorkId(null, "uri://b1"))
        // 当前曲目不在索引中 → null
        assertNull(vm.resolveCurrentWorkId(null, "uri://nonexistent"))
        // 无当前曲目 → null
        assertNull(vm.resolveCurrentWorkId(null, null))
    }

    // ---- openCurrentWorkAlbum 端到端：作品级队列 ----

    @Test
    fun openCurrentWorkAlbum_workOwner_opensPlayingWorkNotFirst() {
        val vm = newViewModel()
        val workB = workIdOf("uri://b1")
        // 挂载作品级队列（ownerId=work:专辑乙），模拟正在播放专辑乙
        PlaybackController.prepareFolder(emptyList(), 0, 0L, "work:$workB")
        vm.openCurrentWorkAlbum()
        val current = vm.currentAlbum.value
        assertNotNull("封面点击后应打开正在播放的作品", current)
        assertEquals(
            "应打开正在播放的专辑乙，而非条目下第一个作品（专辑甲）",
            workB, current!!.workId
        )
        assertEquals("专辑乙", current.album)
    }

    @Test
    fun openCurrentWorkAlbum_noOwnerAndNoTrack_doesNothing() {
        val vm = newViewModel()
        // 无队列归属且无当前曲目 → 静默返回，不崩溃
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        vm.openCurrentWorkAlbum()
        assertNull(vm.currentAlbum.value)
    }

    // ---- shouldRestartForWork：进入专辑时是否暂停（v2.1.7 修复）----

    private fun playingState(path: String) = com.booklistener.player.PlaybackState(
        currentItem = com.booklistener.core.AudioItem(
            id = path, displayName = path, path = path,
            sourceType = com.booklistener.core.SourceType.LOCAL, size = 1L
        ),
        isPlaying = true,
        queueIndex = 0
    )

    @Test
    fun shouldRestartForWork_samePlayingWork_doesNotRestart() {
        val vm = newViewModel()
        val workA = workIdOf("uri://a1")
        PlaybackController.setStateForTest(playingState("uri://a1"))
        PlaybackController.prepareFolder(emptyList(), 0, 0L, "work:$workA")
        // queueReady: pendingQueue 为空但 _state.currentItem != null → true
        assertFalse(
            "正在播放同一作品且队列就绪 → 不应重启（不暂停）",
            vm.shouldRestartForWork(workA)
        )
    }

    @Test
    fun shouldRestartForWork_differentWork_restarts() {
        val vm = newViewModel()
        val workA = workIdOf("uri://a1")
        val workB = workIdOf("uri://b1")
        PlaybackController.setStateForTest(playingState("uri://a1"))
        PlaybackController.prepareFolder(emptyList(), 0, 0L, "work:$workA")
        assertTrue(
            "正在播放作品 A，进入作品 B → 应重启（暂停并加载 B）",
            vm.shouldRestartForWork(workB)
        )
    }

    @Test
    fun shouldRestartForWork_entryOwner_sameWorkResolvesByTrack() {
        val vm = newViewModel()
        val workB = workIdOf("uri://b1")
        // 条目级队列（ownerId=entryId）：从当前播放曲目反查作品
        PlaybackController.setStateForTest(playingState("uri://b1"))
        PlaybackController.prepareFolder(emptyList(), 0, 0L, entryId)
        assertFalse(
            "条目级队列正在播放专辑乙的曲目 → 进入专辑乙不应重启",
            vm.shouldRestartForWork(workB)
        )
        val workA = workIdOf("uri://a1")
        assertTrue(
            "条目级队列正在播放专辑乙 → 进入专辑甲应重启",
            vm.shouldRestartForWork(workA)
        )
    }

    @Test
    fun shouldRestartForWork_nullOwner_sameWorkResolvesByTrack() {
        val vm = newViewModel()
        val workA = workIdOf("uri://a1")
        // 无主队列（ownerId=null，搜索/单曲播放）：从当前曲目反查
        PlaybackController.setStateForTest(playingState("uri://a1"))
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        assertFalse(
            "无主队列正在播放专辑甲的曲目 → 进入专辑甲不应重启",
            vm.shouldRestartForWork(workA)
        )
    }

    @Test
    fun shouldRestartForWork_queueNotReady_restarts() {
        val vm = newViewModel()
        val workA = workIdOf("uri://a1")
        // 队列未就绪（空 state + 空 pendingQueue）→ 即使 ownerId 匹配也应重启
        PlaybackController.setStateForTest(com.booklistener.player.PlaybackState())
        PlaybackController.prepareFolder(emptyList(), 0, 0L, "work:$workA")
        assertTrue(
            "队列未就绪时应重启（加载作品记录）",
            vm.shouldRestartForWork(workA)
        )
    }

    // ---- 同专辑多作者合并后封面定位（v2.1.5 workKey 去 artist 的联动）----

    @Test
    fun openCurrentWorkAlbum_sameAlbumDifferentArtists_mergedWorkResolves() {
        val vm = newViewModel()
        // 同专辑多表演者：v2.1.5 后合并为一个作品
        runBlocking {
            db.dao().saveEntryIndexWithWorks(
                entryId, "多专辑文件夹", "LOCAL",
                listOf(
                    IndexedFile("主曲", "uri://m1", "最伟大的作品", "周杰伦", 1, 1, 222_000L, "m1.wav"),
                    IndexedFile("合作曲", "uri://c1", "最伟大的作品", "周杰伦 with 五月天阿信", 2, 1, 222_000L, "c1.wav")
                )
            )
        }
        val workId = workIdOf("uri://c1")
        // 两条目应合并为同一作品
        assertEquals(workId, workIdOf("uri://m1"))
        PlaybackController.prepareFolder(emptyList(), 0, 0L, "work:$workId")
        vm.openCurrentWorkAlbum()
        assertEquals("最伟大的作品", vm.currentAlbum.value?.album)
        assertEquals(2, vm.currentAlbum.value?.songCount)
    }
}
