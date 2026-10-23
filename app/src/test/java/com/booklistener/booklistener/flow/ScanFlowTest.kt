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
import com.booklistener.db.WorkProgressEntity
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
 * 扫描全流程场景测试（业务入口：ScanCoordinator.scan）。
 *
 * 从"用户添加文件夹 → 扫描 → 作品聚合 → 曲目排序"完整链路验证，
 * 断言数据库产物（entry_index / works）与用户可见输出（AlbumStore），
 * 不直接调用内部方法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanFlowTest {

    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private lateinit var albumStore: AlbumStore
    private val entryId = "content://tree/scan-flow"

    private class FakeSource(val files: List<SourceFile>) : Source {
        override val type: SourceType = SourceType.LOCAL
        override suspend fun listFiles(rootUri: String): List<SourceFile> = files
    }

    private fun file(
        name: String,
        path: String = "content://doc/$name",
        relativePath: String = "",
        isDir: Boolean = false,
        sizeBytes: Long = 1048576L
    ) = SourceFile(name = name, path = path, relativePath = relativePath, isDirectory = isDir, sizeBytes = sizeBytes)

    private fun scan(
        files: List<SourceFile>,
        tagReader: suspend (SourceFile) -> TagResolver.AudioTags? = {
            TagResolver.AudioTags(it.name.substringBeforeLast('.'), "歌手", "测试专辑", 1, 1, 180_000L)
        },
        cueTextReader: suspend (SourceFile) -> String? = { null }
    ) {
        val coord = ScanCoordinator(
            source = FakeSource(files),
            tagReader = tagReader,
            cueTextReader = cueTextReader,
            indexStore = indexStore
        )
        runBlocking { coord.scan(entryId) }
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dao()
        indexStore = EntryIndexStore(dao)
        albumStore = AlbumStore(dao)
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryId, displayName = "扫描测试文件夹", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    // ---- 场景1：普通音频文件夹 ----

    @Test
    fun `普通音频文件夹 扫描后聚合为一个专辑`() {
        scan(
            listOf(file("01.flac"), file("02.flac"), file("03.flac")),
            tagReader = { TagResolver.AudioTags("歌${it.name[1]}", "歌手", "测试专辑", it.name[1].digitToInt(), 1, 180_000L) }
        )
        // entry_index：3 首音频入库
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(3, rows.size)
        // works：聚合出 1 个专辑
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(1, albums.size)
        assertEquals("测试专辑", albums[0].album)
        assertEquals(3, albums[0].songCount)
    }

    // ---- 场景2：无 tag 音频走文件夹兜底 ----

    @Test
    fun `无tag音频 扫描后归入文件夹名专辑`() {
        scan(
            listOf(file("unknown.mp3")),
            tagReader = { null }
        )
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(1, albums.size)
        assertEquals("扫描测试文件夹", albums[0].album)
        assertEquals(1, albums[0].songCount)
    }

    // ---- 场景3：非媒体文件不参与聚合 ----

    @Test
    fun `非媒体文件 入库但不产生专辑`() {
        scan(
            listOf(file("01.flac"), file("封面.jpg"), file("readme.txt"), file("种子.torrent")),
            tagReader = { if (it.name.endsWith(".flac")) TagResolver.AudioTags("歌", "歌手", "专辑", 1, 1, 180_000L) else null }
        )
        // 内容文件列表：4 个文件都入库
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(4, rows.size)
        // 非媒体文件 workId=null
        val nonMedia = rows.filter { !it.uri.endsWith(".flac") }
        assertTrue(nonMedia.isNotEmpty())
        nonMedia.forEach { assertNull("非媒体文件 workId 必须为 null", it.workId) }
        // 专辑：只有 1 个（音频），非媒体不产生文件夹名专辑
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(1, albums.size)
        assertEquals(1, albums[0].songCount)
    }

    // ---- 场景4：过小/过短音频被跳过 ----

    @Test
    fun `过小音频 不入库`() {
        val tiny = file("ringtone.mp3", sizeBytes = 50L * 1024)  // 50KB < 100KB
        val normal = file("01.flac")
        scan(listOf(tiny, normal))
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        assertEquals("01", rows[0].displayName)
    }

    @Test
    fun `过短音频 不入库`() {
        scan(
            listOf(file("short.mp3")),
            tagReader = { TagResolver.AudioTags("短音效", null, null, null, null, 5_000L) }  // 5秒 < 30秒
        )
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(0, rows.size)
    }

    // ---- 场景5：隐藏子文件夹内文件不入库 ----

    @Test
    fun `隐藏子文件夹内文件 不入库`() {
        val hidden = file("secret.mp3", relativePath = ".hidden")
        val normal = file("01.flac")
        scan(listOf(hidden, normal))
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        assertEquals("01", rows[0].displayName)
    }

    // ---- 场景6：曲目排序（disc → track → 名称自然序） ----

    @Test
    fun `多disc多track 扫描后按disc和track排序`() {
        scan(
            listOf(file("d2t1.flac"), file("d1t2.flac"), file("d1t1.flac")),
            tagReader = {
                val disc = it.name[1].digitToInt()
                val track = it.name[3].digitToInt()
                TagResolver.AudioTags("歌$track", "歌手", "专辑", track, disc, 180_000L)
            }
        )
        val rows = runBlocking { dao.getEntryIndex(entryId) }.sortedBy { it.sortRank }
        assertEquals(3, rows.size)
        assertEquals("歌1", rows[0].displayName)  // d1t1
        assertEquals("歌2", rows[1].displayName)  // d1t2
        assertEquals("歌1", rows[2].displayName)  // d2t1
    }

    // ---- 场景7：重扫稳定性（workId 不变，进度不丢） ----

    @Test
    fun `连续扫描相同内容 workId稳定 进度不丢`() {
        scan(listOf(file("01.flac")))
        val workIdBefore = runBlocking { dao.getWorkIdByUri("content://doc/01.flac") }
        assertNotNull(workIdBefore)
        // 模拟播放进度
        runBlocking {
            dao.upsertWorkProgress(
                WorkProgressEntity(workIdBefore!!, "content://doc/01.flac", 0, 1, 42_000L, 1.0f, System.currentTimeMillis())
            )
        }
        // 重扫
        scan(listOf(file("01.flac")))
        val workIdAfter = runBlocking { dao.getWorkIdByUri("content://doc/01.flac") }
        assertEquals(workIdBefore, workIdAfter)
        val progress = runBlocking { dao.getWorkProgress(workIdAfter!!) }
        assertNotNull(progress)
        assertEquals(42_000L, progress?.positionMs)
    }

    // ---- 场景8：tag 解析失败降级 ----

    @Test
    fun `tag解析失败 文件名兜底并记录error`() {
        scan(
            listOf(file("broken.flac")),
            tagReader = { null }
        )
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        assertEquals("broken", rows[0].displayName)  // 文件名兜底
        assertNotNull(rows[0].error)  // error 记录
    }

    // ---- 场景9：目录条目不入库 ----

    @Test
    fun `目录条目 不入库`() {
        scan(listOf(file("subdir", isDir = true), file("01.flac")))
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        assertFalse(rows.any { it.displayName == "subdir" })
    }
}
