package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
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
 * ScanCoordinator 降级与忠实显示测试（v2.2.0 设置侧链路重构）：
 * - 标签解析失败：文件名兜底 + error 记录，仍入库
 * - CUE 读取失败：error 记录，仍显示
 * - 非音频文件：仍入库（内容文件列表忠实显示）
 * - 标签成功：使用标签字段
 * - 目录条目不入库
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCoordinatorTest {

    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private val entryId = "content://tree/a"

    private class FakeSource(val files: List<SourceFile>) : Source {
        override val type: SourceType = SourceType.LOCAL
        override suspend fun listFiles(rootUri: String): List<SourceFile> = files
    }

    private fun file(
        name: String,
        path: String = "content://doc/$name",
        relativePath: String = "",
        isDir: Boolean = false
    ) = SourceFile(
        name = name, path = path, relativePath = relativePath,
        isDirectory = isDir, sizeBytes = 100L
    )

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        indexStore = EntryIndexStore(dao)
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryId, displayName = "文件夹A", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    @Test
    fun `tag success uses tag fields`() = runBlocking {
        val source = FakeSource(listOf(file("01.flac")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { TagResolver.AudioTags("标题", "歌手", "专辑", 1, 1, 1000L) },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertEquals("标题", rows[0].displayName)
        assertEquals("歌手", rows[0].artist)
        assertEquals("专辑", rows[0].album)
        assertNull(rows[0].error)
    }

    @Test
    fun `tag failure falls back to filename and records error`() = runBlocking {
        val source = FakeSource(listOf(file("01.flac")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { null },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertEquals("01", rows[0].displayName)
        assertNotNull(rows[0].error)
    }

    @Test
    fun `cue read failure records error but stays visible`() = runBlocking {
        val source = FakeSource(listOf(file("disc.cue")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { null },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertEquals("disc", rows[0].displayName)
        assertNotNull(rows[0].error)
    }

    @Test
    fun `cue success collects candidate without error`() = runBlocking {
        val source = FakeSource(listOf(file("disc.cue")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { null },
            cueTextReader = { "REM GENRE Test" },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertNull(rows[0].error)
    }

    @Test
    fun `non-audio file is still indexed`() = runBlocking {
        val source = FakeSource(listOf(file("readme.txt")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { null },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertNull(rows[0].error)
    }

    @Test
    fun `directory entries are not indexed`() = runBlocking {
        val source = FakeSource(
            listOf(
                file("subdir", isDir = true),
                file("01.flac")
            )
        )
        val coord = ScanCoordinator(
            source = source,
            tagReader = { null },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertFalse(rows[0].displayName == "subdir")
    }
}
