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
        isDirectory = isDir, sizeBytes = 1048576L  // 1MB，避免被过小文件过滤
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
            tagReader = { TagResolver.AudioTags("标题", "歌手", "专辑", 1, 1, 180000L) },
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
    fun `non-audio file is still indexed but does not form a work`() = runBlocking {
        val source = FakeSource(listOf(file("readme.txt")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { null },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        // 内容文件列表：非音频文件仍入库（忠实显示）
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertNull(rows[0].error)
        // 但不属于任何作品（workId=null）
        assertNull("非媒体文件 workId 必须为 null", rows[0].workId)
        // 专辑列表为空：不产生"文件夹名"专辑
        val albumStore = AlbumStore(dao)
        val albums = albumStore.notPlayed(0) + albumStore.recentPlayed(0)
        assertTrue("非媒体文件不应产生任何专辑", albums.isEmpty())
    }

    @Test
    fun `mixed folder non-media files do not pollute album aggregation`() = runBlocking {
        // 用户真实场景：CUE+FLAC整轨 + 同名普通音频 + JPG + 种子
        val cueText = """
            PERFORMER "歌手"
            TITLE "CUE专辑"
            FILE "专辑.flac" WAVE
              TRACK 01 AUDIO
                TITLE "歌一"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "歌二"
                INDEX 01 03:00:00
        """.trimIndent()
        val source = FakeSource(
            listOf(
                file("专辑.cue"),
                file("专辑.flac"),
                file("封面.jpg"),
                file("种子.torrent"),
                file("readme.txt")
            )
        )
        val coord = ScanCoordinator(
            source = source,
            tagReader = { sf ->
                if (sf.name == "专辑.flac")
                    TagResolver.AudioTags("专辑", "歌手", "CUE专辑", null, null, 180_000L)
                else null
            },
            cueTextReader = { if (it.name.endsWith(".cue")) cueText else null },
            indexStore = indexStore
        )
        coord.scan(entryId)

        // 内容文件列表：所有文件忠实显示（2 虚拟轨 + cue + jpg + torrent + txt = 6）
        val rows = dao.getEntryIndex(entryId)
        val names = rows.map { it.displayName }
        assertTrue("内容文件列表必须包含 cue", names.contains("专辑"))
        assertTrue("内容文件列表必须包含封面", names.contains("封面"))
        assertTrue("内容文件列表必须包含种子", names.contains("种子"))
        assertTrue("内容文件列表必须包含 readme", names.contains("readme"))

        // 专辑列表：只有 CUE专辑 一个作品，非媒体文件不产生"文件夹名"专辑
        val albumStore = AlbumStore(dao)
        val albums = albumStore.notPlayed(0) + albumStore.recentPlayed(0)
        assertEquals("只能聚合出一个 CUE 专辑", 1, albums.size)
        assertEquals("CUE专辑", albums[0].album)
        // 该专辑只有 2 首虚拟轨（不含任何非媒体文件）
        assertEquals(2, albums[0].songCount)
        val songs = albumStore.allSongs(albums[0].workId)
        assertEquals(2, songs.size)
        assertTrue(songs.all { it.uri.contains("#track_") })
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

    @Test
    fun `tiny audio files are skipped by size`() = runBlocking {
        // 50KB 音频文件 < 100KB 阈值 → 跳过
        val tiny = SourceFile(
            name = "ringtone.mp3", path = "content://doc/ringtone.mp3",
            relativePath = "", isDirectory = false, sizeBytes = 50L * 1024L
        )
        val normal = file("01.flac")
        val source = FakeSource(listOf(tiny, normal))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { TagResolver.AudioTags("标题", "歌手", "专辑", 1, 1, 180000L) },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(1, rows.size)
        assertEquals("标题", rows[0].displayName)
    }

    @Test
    fun `short duration audio files are skipped`() = runBlocking {
        // 5 秒音频 < 10 秒阈值 → 跳过
        val source = FakeSource(listOf(file("short.mp3")))
        val coord = ScanCoordinator(
            source = source,
            tagReader = { TagResolver.AudioTags("短音效", null, null, null, null, 5000L) },
            cueTextReader = { null },
            indexStore = indexStore
        )
        coord.scan(entryId)
        val rows = dao.getEntryIndex(entryId)
        assertEquals(0, rows.size)
    }
}
