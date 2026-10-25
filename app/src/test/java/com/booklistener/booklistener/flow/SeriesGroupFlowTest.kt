package com.booklistener.flow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.auth.CredentialStore
import com.booklistener.core.AlbumStore
import com.booklistener.core.EntryIndexStore
import com.booklistener.core.HomeAlbumItem
import com.booklistener.core.ResolvedTags
import com.booklistener.core.ScanCoordinator
import com.booklistener.core.Source
import com.booklistener.core.SourceFile
import com.booklistener.core.SourceType
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import com.booklistener.player.PlaybackController
import com.booklistener.ui.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2.1 系列分组全流程场景测试（业务入口：ScanCoordinator.scan → PlayerViewModel 输出）。
 *
 * 覆盖 SPECS §十三：多专辑系列聚合与系列序号自然排序、单专辑系列折叠、
 * 系列整体归属已播放分区（不跨分区）、缺失序列号排末、分组头默认收起与会话级收展记忆。
 * 断言用户可见的分区条目产物，不直接调 HomeAlbumGrouping（SPECS §十二：纯函数行为由流程测试覆盖）。
 * 注意：scan 对同一 entry 整体替换索引，故每个专辑使用独立条目（album tag 使同名专辑跨条目合并）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeriesGroupFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private lateinit var albumStore: AlbumStore
    private var entrySeq = 0

    private class FakeSource(val files: List<SourceFile>) : Source {
        override val type: SourceType = SourceType.LOCAL
        override suspend fun listFiles(rootUri: String): List<SourceFile> = files
    }

    private fun file(name: String) = SourceFile(
        name = name, path = "content://doc/$name", relativePath = "",
        isDirectory = false, sizeBytes = 1048576L
    )

    private fun scan(
        entryId: String,
        files: List<SourceFile>,
        tagReader: suspend (SourceFile) -> ResolvedTags?
    ) {
        val coord = ScanCoordinator(
            source = FakeSource(files),
            tagReader = tagReader,
            cueTextReader = { null },
            indexStore = indexStore
        )
        runBlocking { coord.scan(entryId) }
    }

    private fun addEntry(id: String, name: String) {
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        // lastOpenedAt=0：阻断 init 的 restoreLastSession（pickRecent 会按 lastOpenedAt 兜底选中）
                        // 否则其异步 resumePlaybackOfEntry → refreshAlbums 会与用例的刷新竞态、制造瞬时空列表
                        id = id, displayName = name, uri = id,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 0L, sortOrder = 0
                    )
                )
            )
        }
    }

    /** 单文件专辑（series 可空），独立条目扫描：作品层 series/seriesNumber 取首曲值 */
    private fun seedAlbum(album: String, series: String?, seriesNumber: String?, name: String) {
        val id = "content://tree/entry${entrySeq++}"
        addEntry(id, "书架${entrySeq}")
        scan(id, listOf(file(name)), tagReader = {
            ResolvedTags.basic(name, "作者", album, 1, 1, 120_000L, series, seriesNumber)
        })
    }

    private fun touchPlayed(uri: String, atMs: Long) {
        runBlocking { dao.touchWorkPlayed(dao.getWorkIdByUri(uri)!!, atMs) }
    }

    @Before
    fun setUp() {
        // 隔离用例间单例残留：默认无队列归属
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        indexStore = EntryIndexStore(dao)
        albumStore = AlbumStore(dao)
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(
            ApplicationProvider.getApplicationContext(),
            database = db,
            credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false)
        )

    /** 通用异步断言：主 looper idle + 轮询，超时失败（refreshAlbums 走 IO 调度） */
    private fun awaitTrue(msg: String, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (block()) return
            Thread.sleep(20)
        }
        assertTrue(msg, false)
    }

    // ---- 场景1：多专辑系列分组 + 系列序号自然排序 + 默认收起 ----

    @Test
    fun `多专辑系列 分组头按系列序号自然排序 默认收起`() {
        seedAlbum("三体·黑暗森林", "三体", "2", "02.flac")
        seedAlbum("三体·死神永生", "三体", "10", "03.flac")
        seedAlbum("三体", "三体", "1", "01.flac")
        seedAlbum("平凡的世界", null, null, "04.flac")

        val vm = newViewModel()
        vm.refreshAlbums()
        awaitTrue("neverItems 应含 1 系列组 + 1 普通行") { vm.neverItems.value.size == 2 }

        val group = vm.neverItems.value[0] as HomeAlbumItem.SeriesGroup
        assertEquals("分组头显示原始系列名", "三体", group.name)
        assertEquals(3, group.albumCount)
        assertTrue("默认收起：组内专辑不可见", group.albums.isEmpty())
        assertTrue("无系列专辑保持普通行", vm.neverItems.value[1] is HomeAlbumItem.Plain)

        // 展开后：组内按系列序号自然排序（1 < 2 < 10，数字感知）
        vm.toggleSeries(group.key)
        awaitTrue("展开后组内专辑按序列号排序") {
            (vm.neverItems.value.firstOrNull() as? HomeAlbumItem.SeriesGroup)?.albums?.map { it.album } ==
                listOf("三体", "三体·黑暗森林", "三体·死神永生")
        }
    }

    // ---- 场景2：单专辑系列折叠为普通行 ----

    @Test
    fun `单专辑系列 折叠为普通行 不产生分组头`() {
        seedAlbum("孤册", "独册", "1", "01.flac")
        seedAlbum("普通专辑", null, null, "02.flac")

        val vm = newViewModel()
        vm.refreshAlbums()
        awaitTrue("neverItems 应全为普通行") { vm.neverItems.value.size == 2 }
        assertTrue(vm.neverItems.value.all { it is HomeAlbumItem.Plain })
    }

    // ---- 场景3：系列整体归属已播放分区（不跨分区）----

    @Test
    fun `系列内任一专辑播放过 整系列归已播放分区 组内仍按序列号排`() {
        seedAlbum("三体·黑暗森林", "三体", "2", "02.flac")
        seedAlbum("三体", "三体", "1", "01.flac")
        seedAlbum("平凡的世界", null, null, "03.flac")
        // 只播放 三体 卷2（较晚），卷1 未播放
        touchPlayed("content://doc/02.flac", 3_000L)

        val vm = newViewModel()
        vm.refreshAlbums()
        awaitTrue("recentItems 含整个系列组 且 未播放分区只剩普通行") {
            vm.recentItems.value.size == 1 && vm.neverItems.value.size == 1
        }
        val group = vm.recentItems.value[0] as HomeAlbumItem.SeriesGroup
        assertEquals(2, group.albumCount)
        assertTrue("未播放分区仅剩无系列专辑", vm.neverItems.value[0] is HomeAlbumItem.Plain)

        vm.toggleSeries(group.key)
        awaitTrue("组内按序列号排（卷1在前，即使卷2播放更晚）") {
            (vm.recentItems.value.firstOrNull() as? HomeAlbumItem.SeriesGroup)?.albums?.map { it.album } ==
                listOf("三体", "三体·黑暗森林")
        }
    }

    // ---- 场景4：缺失序列号排末 ----

    @Test
    fun `缺失序列号 排在系列末 末内按专辑名自然序`() {
        seedAlbum("三体C", "三体", "10", "03.flac")
        seedAlbum("三体B", "三体", null, "02.flac")
        seedAlbum("三体A", "三体", "2", "01.flac")

        val vm = newViewModel()
        vm.refreshAlbums()
        awaitTrue("neverItems 含系列组") { vm.neverItems.value.size == 1 }
        val group = vm.neverItems.value[0] as HomeAlbumItem.SeriesGroup
        vm.toggleSeries(group.key)
        awaitTrue("序号自然序后缺失排末") {
            (vm.neverItems.value.firstOrNull() as? HomeAlbumItem.SeriesGroup)?.albums?.map { it.album } ==
                listOf("三体A", "三体C", "三体B")
        }
    }

    // ---- 场景5：已播放分区时间线交错（组位置=组内最近播放时间）----

    @Test
    fun `已播放分区 系列组位置取组内最近播放时间 与普通行交错`() {
        seedAlbum("三体·黑暗森林", "三体", "2", "02.flac")
        seedAlbum("三体", "三体", "1", "01.flac")
        seedAlbum("黑镜", null, null, "03.flac")
        // 播放时间：三体卷1=1s、黑镜=2s、三体卷2=3s → 系列组位置=3s 置顶
        touchPlayed("content://doc/01.flac", 1_000L)
        touchPlayed("content://doc/03.flac", 2_000L)
        touchPlayed("content://doc/02.flac", 3_000L)

        val vm = newViewModel()
        vm.refreshAlbums()
        awaitTrue("recentItems 含系列组 + 普通行") { vm.recentItems.value.size == 2 }
        assertTrue("组位置=组内最近播放（3s），应排在普通行(2s)之前",
            vm.recentItems.value.firstOrNull() is HomeAlbumItem.SeriesGroup)
        assertTrue("普通行按自身播放时间排其后",
            vm.recentItems.value.getOrNull(1) is HomeAlbumItem.Plain)
    }

    // ---- 场景6：收展状态会话级（toggle 翻转 + refresh 保留）----

    @Test
    fun `收展状态会话级 toggle翻转 refresh后保留`() {
        seedAlbum("三体·黑暗森林", "三体", "2", "02.flac")
        seedAlbum("三体", "三体", "1", "01.flac")

        val vm = newViewModel()
        vm.refreshAlbums()
        awaitTrue("neverItems 含系列组") { vm.neverItems.value.size == 1 }
        val key = (vm.neverItems.value[0] as HomeAlbumItem.SeriesGroup).key

        vm.toggleSeries(key)
        awaitTrue("展开后组内专辑可见") {
            (vm.neverItems.value[0] as HomeAlbumItem.SeriesGroup).albums.size == 2
        }

        vm.refreshAlbums()
        awaitTrue("refresh 后仍保持展开（会话级记忆）") {
            (vm.neverItems.value.firstOrNull() as? HomeAlbumItem.SeriesGroup)?.albums?.size == 2
        }

        vm.toggleSeries(key)
        awaitTrue("再次点击收起") {
            (vm.neverItems.value.firstOrNull() as? HomeAlbumItem.SeriesGroup)?.albums?.isEmpty() == true
        }
    }
}
