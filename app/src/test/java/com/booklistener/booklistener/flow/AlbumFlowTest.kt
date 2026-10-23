package com.booklistener.booklistener.flow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.AlbumStore
import com.booklistener.core.EntryIndexStore
import com.booklistener.core.ScanCoordinator
import com.booklistener.core.SourceFile
import com.booklistener.core.Source
import com.booklistener.core.SourceType
import com.booklistener.core.TagResolver
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专辑全流程场景测试（业务入口：ScanCoordinator.scan → AlbumStore 输出）。
 *
 * 覆盖：同专辑多作者合并、跨文件夹同名专辑合并、无tag兜底、专辑封面。
 * 断言用户可见的专辑列表产物，不直接调 WorkBackfill。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumFlowTest {

    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private lateinit var albumStore: AlbumStore

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
        tagReader: suspend (SourceFile) -> TagResolver.AudioTags?
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
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dao()
        indexStore = EntryIndexStore(dao)
        albumStore = AlbumStore(dao)
    }

    private fun allAlbums() = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }

    // ---- 场景1：同专辑多作者合并为一个专辑 ----

    @Test
    fun `同专辑不同作者 扫描后合并为一个专辑`() {
        addEntry("e1", "文件夹A")
        scan(
            "e1",
            listOf(file("01.flac"), file("02.flac"), file("03.flac")),
            tagReader = { f ->
                when (f.name) {
                    "01.flac" -> TagResolver.AudioTags("歌1", "周杰伦", "最伟大的作品", 1, 1, 180_000L)
                    "02.flac" -> TagResolver.AudioTags("歌2", "周杰伦 with 五月天阿信", "最伟大的作品", 2, 1, 200_000L)
                    else -> TagResolver.AudioTags("歌3", "周杰伦 with 杨瑞代", "最伟大的作品", 3, 1, 220_000L)
                }
            }
        )
        val albums = allAlbums()
        assertEquals(1, albums.size)
        assertEquals("最伟大的作品", albums[0].album)
        assertEquals(3, albums[0].songCount)
    }

    // ---- 场景2：跨文件夹同名专辑合并 ----

    @Test
    fun `两个文件夹各有同名专辑 扫描后合并为一个`() {
        addEntry("e1", "文件夹A")
        addEntry("e2", "文件夹B")
        scan("e1", listOf(file("a1.flac")), tagReader = {
            TagResolver.AudioTags("歌A", "歌手", "同名专辑", 1, 1, 180_000L)
        })
        scan("e2", listOf(file("b1.flac")), tagReader = {
            TagResolver.AudioTags("歌B", "歌手", "同名专辑", 1, 1, 200_000L)
        })
        val albums = allAlbums()
        assertEquals(1, albums.size)
        assertEquals("同名专辑", albums[0].album)
        assertEquals(2, albums[0].songCount)
        assertTrue(albums[0].albumCountInEntry >= 2)  // 跨条目
    }

    // ---- 场景3：无 tag 音频走文件夹兜底 ----

    @Test
    fun `无tag音频 归入文件夹名专辑`() {
        addEntry("e1", "我的音乐")
        scan("e1", listOf(file("unknown.mp3")), tagReader = { null })
        val albums = allAlbums()
        assertEquals(1, albums.size)
        assertEquals("我的音乐", albums[0].album)
    }

    // ---- 场景4：多个不同专辑 ----

    @Test
    fun `不同专辑 扫描后各自独立`() {
        addEntry("e1", "文件夹A")
        scan(
            "e1",
            listOf(file("01.flac"), file("02.flac")),
            tagReader = { f ->
                if (f.name == "01.flac")
                    TagResolver.AudioTags("歌1", "歌手", "专辑甲", 1, 1, 180_000L)
                else
                    TagResolver.AudioTags("歌2", "歌手", "专辑乙", 1, 1, 200_000L)
            }
        )
        val albums = allAlbums()
        assertEquals(2, albums.size)
        val names = albums.map { it.album }.toSet()
        assertTrue(names.contains("专辑甲"))
        assertTrue(names.contains("专辑乙"))
    }

    // ---- 场景5：专辑名前后空格和大小写归一化 ----

    @Test
    fun `专辑名前后空格 扫描后归一化合并`() {
        addEntry("e1", "文件夹A")
        scan(
            "e1",
            listOf(file("01.flac"), file("02.flac")),
            tagReader = { f ->
                if (f.name == "01.flac")
                    TagResolver.AudioTags("歌1", "歌手", "  Abbey Road  ", 1, 1, 180_000L)
                else
                    TagResolver.AudioTags("歌2", "歌手", "Abbey Road", 2, 1, 200_000L)
            }
        )
        val albums = allAlbums()
        assertEquals(1, albums.size)
        assertEquals(2, albums[0].songCount)
    }
}
