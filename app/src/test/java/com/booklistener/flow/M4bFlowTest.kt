package com.booklistener.flow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.AlbumStore
import com.booklistener.core.Chapter
import com.booklistener.core.EntryIndexStore
import com.booklistener.core.ResolvedTags
import com.booklistener.core.ScanCoordinator
import com.booklistener.core.Source
import com.booklistener.core.SourceFile
import com.booklistener.core.SourceType
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import com.booklistener.player.PlaybackController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2.4 m4b 章节虚拟轨全流程场景测试（业务入口：ScanCoordinator.scan → EntryIndexStore/AlbumStore 产物）。
 *
 * 覆盖 SPECS §十五：有章节 m4b 生成章节虚拟轨（uri=<整本>#track_<startMs>）且整本被吸收、
 * 章节名空「第 N 章」兜底、无章节退化普通媒体条目、章节归属作品/系列聚合（1 作品 = 1 本书）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class M4bFlowTest {

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
        isDirectory = false, sizeBytes = 10_485_760L
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
                        id = id, displayName = name, uri = id,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 0L, sortOrder = 0
                    )
                )
            )
        }
    }

    /** 单本 m4b 书籍：章节虚拟轨经 tagReader 注入（同本地 Mp4ChapterReader 产物形态） */
    private fun seedBook(
        album: String,
        series: String?,
        seriesNumber: String?,
        chapters: List<Chapter>?,
        name: String = "book.m4b",
        durationMs: Long? = 10_800_000L
    ): String {
        val id = "content://tree/entry${entrySeq++}"
        addEntry(id, "书架${entrySeq}")
        scan(id, listOf(file(name)), tagReader = {
            ResolvedTags.basic(
                title = name.substringBeforeLast('.'),
                artist = "作者",
                album = album,
                track = null,
                disc = null,
                durationMs = durationMs,
                series = series,
                seriesNumber = seriesNumber,
                chapters = chapters
            )
        })
        return id
    }

    @Before
    fun setUp() {
        PlaybackController.prepareFolder(emptyList(), 0, 0L, null)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        indexStore = EntryIndexStore(dao)
        albumStore = AlbumStore(dao)
    }

    private val threeChapters = listOf(
        Chapter(0L, "第一章"),
        Chapter(3_600_000L, "第二章"),
        Chapter(7_200_000L, "第三章")
    )

    // ---- 场景1：有章节 → 章节虚拟轨 + 原始 .m4b 行保留（v2.2.10） ----

    @Test
    fun `有章节 m4b 生成章节虚拟轨 原始行保留且不参与聚合`() {
        val id = seedBook("三体", "三体", "1", threeChapters)

        val indexed = indexStore.loadEntry(id)
        val wholeUri = "content://doc/book.m4b"
        // v2.2.20：保留原始 .m4b 行正常入库（内容文件列表显示原始文件）；聚合排除由
        // WorkBackfill「虚拟轨整轨源排除」承担（曲目列表断言见"归属单作品"场景）
        assertTrue("原始 .m4b 行保留在内容文件索引", indexed.any { it.uri == wholeUri })

        val tracks = indexed.filter { it.uri.startsWith(wholeUri) && it.uri != wholeUri }
        assertEquals("3 条章节虚拟轨", 3, tracks.size)
        assertEquals(
            listOf("content://doc/book.m4b#track_0", "content://doc/book.m4b#track_3600000", "content://doc/book.m4b#track_7200000"),
            tracks.map { it.uri }
        )
        assertEquals(listOf("第一章", "第二章", "第三章"), tracks.map { it.displayName })
        assertEquals(listOf(1, 2, 3), tracks.map { it.track })
        assertEquals(listOf(3_600_000L, 3_600_000L, 3_600_000L), tracks.map { it.durationMs })
        assertEquals(listOf("book.m4b#1", "book.m4b#2", "book.m4b#3"), tracks.map { it.rawName })
    }

    @Test
    fun `有章节 m4b 归属单作品 专辑列表一栏 曲目 3 章`() {
        seedBook("三体", "三体", "1", threeChapters)

        val works = albumStore.notPlayed()
        val book = works.firstOrNull { it.album == "三体" }
        assertTrue("专辑列表含该书（1 作品 = 1 本书）", book != null)
        assertEquals("作品系列归属", "三体", book?.series)
        assertEquals("作品歌曲数 = 章节数（原始行被排除）", 3, book?.songCount)
        assertEquals("作品总时长 = 整本时长", 10_800_000L, book?.totalDurationMs)
        // 曲目列表只含章节虚拟轨，无原始 .m4b 重复行
        val songs = albumStore.allSongs(book!!.workId)
        assertEquals("曲目数 = 章节数", 3, songs.size)
        assertTrue("曲目均为章节虚拟轨", songs.all { it.uri.contains("#track_") })
        assertTrue("无原始 .m4b 行", songs.none { it.uri == "content://doc/book.m4b" })
    }

    // ---- 场景2：章节名空 →「第 N 章」兜底 ----

    @Test
    fun `章节名空 以第N章兜底`() {
        val id = seedBook(
            "平凡的世界", null, null,
            listOf(Chapter(0L, ""), Chapter(1_800_000L, "第二章"))
        )
        val tracks = indexStore.loadEntry(id).filter { it.uri.contains("#track_") }
        assertEquals(listOf("第 1 章", "第二章"), tracks.map { it.displayName })
    }

    // ---- 场景3：无章节 → 退化普通媒体条目 ----

    @Test
    fun `无章节 m4b 退化为普通媒体条目`() {
        val id = seedBook("单本无章", null, null, chapters = null)

        val indexed = indexStore.loadEntry(id)
        assertEquals("整本以普通媒体条目入库", listOf("content://doc/book.m4b"), indexed.map { it.uri })
        assertEquals(listOf("book"), indexed.map { it.displayName })
        assertEquals("作品为普通专辑", 1, albumStore.notPlayed().firstOrNull { it.album == "单本无章" }?.songCount)
    }

    // ---- 场景4：章节归属系列 → 系列聚合 ----

    @Test
    fun `章节 m4b 按系列聚合 序列号排序`() {
        seedBook("三体·黑暗森林", "三体", "2", threeChapters, name = "dark.m4b")
        seedBook("三体", "三体", "1", listOf(Chapter(0L, "开篇"), Chapter(3_600_000L, "后章")), name = "santi.m4b")

        val works = albumStore.notPlayed().filter { it.series == "三体" }
        assertEquals("两本同系列书籍均聚合为系列作品", 2, works.size)
        assertEquals("按系列序号排序（1 前 2 后）", listOf("三体", "三体·黑暗森林"), works.sortedBy { it.seriesNumber }.map { it.album })
    }
}
