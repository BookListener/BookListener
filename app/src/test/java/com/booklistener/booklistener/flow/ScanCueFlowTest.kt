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
 * CUE 全流程场景测试（业务入口：ScanCoordinator.scan）。
 *
 * 覆盖：CUE+整轨吸收、中文编码、混合目录、FILE不匹配退化、多CUE、无TITLE兜底。
 * 断言数据库产物（entry_index 虚拟轨 / works 聚合 / cue_sheets），不直接调 CueParser。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCueFlowTest {

    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private lateinit var albumStore: AlbumStore
    private val entryId = "content://tree/cue-flow"

    private class FakeSource(val files: List<SourceFile>) : Source {
        override val type: SourceType = SourceType.LOCAL
        override suspend fun listFiles(rootUri: String): List<SourceFile> = files
    }

    private fun file(name: String, sizeBytes: Long = 1048576L) =
        SourceFile(name = name, path = "content://doc/$name", relativePath = "", isDirectory = false, sizeBytes = sizeBytes)

    private fun scan(
        files: List<SourceFile>,
        cueTextReader: suspend (SourceFile) -> String? = { null },
        tagReader: suspend (SourceFile) -> TagResolver.AudioTags? = {
            TagResolver.AudioTags(it.name.substringBeforeLast('.'), "歌手", "测试专辑", 1, 1, 180_000L)
        }
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
                        id = entryId, displayName = "CUE测试文件夹", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    private val standardCue = """
        PERFORMER "某某乐队"
        TITLE "某某专辑"
        FILE "整轨.flac" WAVE
          TRACK 01 AUDIO
            TITLE "第一首"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "第二首"
            INDEX 01 04:32:15
    """.trimIndent()

    // ---- 场景1：CUE+整轨标准吸收 ----

    @Test
    fun `CUE加整轨 扫描后整轨被吸收 虚拟轨入库`() {
        scan(listOf(file("整轨.flac"), file("整轨.cue")), cueTextReader = { standardCue })
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        // 2 虚拟轨 + 1 CUE 文件本身（内容文件列表忠实显示）
        val virtual = rows.filter { it.uri.contains("#track_") }
        val cueFile = rows.filter { it.displayName == "整轨" && !it.uri.contains("#track_") }
        assertEquals(2, virtual.size)
        assertEquals(1, cueFile.size)
        // 整轨不入库
        assertFalse(rows.any { it.uri == "content://doc/整轨.flac" })
        // 专辑聚合：1 个 CUE 专辑
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(1, albums.size)
        assertEquals("某某专辑", albums[0].album)
        assertEquals(2, albums[0].songCount)
    }

    // ---- 场景2：CUE 中文 GBK 编码 ----

    @Test
    fun `CUE中文GBK 扫描后中文不乱码`() {
        // 用 UTF-8 文本模拟（CueTextDecoder 会探测编码）
        val gbkCue = """
            PERFORMER "周杰伦"
            TITLE "范特西"
            FILE "范特西.flac" WAVE
              TRACK 01 AUDIO
                TITLE "爱在西元前"
                INDEX 01 00:00:00
        """.trimIndent()
        scan(listOf(file("范特西.flac"), file("范特西.cue")), cueTextReader = { gbkCue })
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        val virtual = rows.filter { it.uri.contains("#track_") }
        assertEquals(1, virtual.size)
        assertEquals("爱在西元前", virtual[0].displayName)
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals("范特西", albums[0].album)
    }

    // ---- 场景3：CUE+普通音频混合 ----

    @Test
    fun `CUE整轨加独立音频 扫描后产生两个专辑`() {
        scan(
            listOf(file("整轨.flac"), file("整轨.cue"), file("独立歌.flac")),
            cueTextReader = { standardCue },
            tagReader = {
                if (it.name == "独立歌.flac")
                    TagResolver.AudioTags("独立歌", "独立歌手", "独立专辑", 1, 1, 200_000L)
                else
                    TagResolver.AudioTags("整轨", "某某乐队", "某某专辑", 1, 1, 600_000L)
            }
        )
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        // 2 虚拟轨 + 1 独立音频 + 1 CUE 文件 = 4
        val virtual = rows.filter { it.uri.contains("#track_") }
        val standalone = rows.filter { it.uri == "content://doc/独立歌.flac" }
        assertEquals(2, virtual.size)
        assertEquals(1, standalone.size)
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(2, albums.size)
        val albumNames = albums.map { it.album }.toSet()
        assertTrue(albumNames.contains("某某专辑"))
        assertTrue(albumNames.contains("独立专辑"))
    }

    // ---- 场景4：CUE FILE 不匹配退化 ----

    @Test
    fun `CUE引用不存在的文件且无同名探测 整轨保持普通条目`() {
        // CUE 基名（other）与整轨基名（整轨）不同，FILE 声明也不匹配 → 退化
        val mismatchedCue = standardCue.replace("整轨.flac", "不存在.flac")
        scan(listOf(file("整轨.flac"), file("other.cue")), cueTextReader = { mismatchedCue })
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        val whole = rows.find { it.uri == "content://doc/整轨.flac" }
        assertNotNull("整轨应保持普通条目入库", whole)
        assertEquals("整轨", whole?.displayName)
        val playableVirtual = rows.filter { it.uri.contains("#track_") }
        assertEquals(0, playableVirtual.size)
    }

    // ---- 场景5：多 CUE 多整轨 ----

    @Test
    fun `两个CUE两个整轨 各自吸收互不干扰`() {
        val cue1 = standardCue
        val cue2 = standardCue.replace("某某专辑", "另一专辑").replace("整轨.flac", "整轨2.flac")
        scan(
            listOf(file("整轨.flac"), file("整轨.cue"), file("整轨2.flac"), file("整轨2.cue")),
            cueTextReader = { f ->
                when (f.name) {
                    "整轨.cue" -> cue1
                    "整轨2.cue" -> cue2
                    else -> null
                }
            },
            tagReader = { TagResolver.AudioTags(it.name.substringBeforeLast('.'), "歌手", "专辑", 1, 1, 600_000L) }
        )
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        // 4 虚拟轨（2 专辑 × 2 轨）+ 2 CUE 文件 = 6
        val virtual = rows.filter { it.uri.contains("#track_") }
        assertEquals(4, virtual.size)
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(2, albums.size)
    }

    // ---- 场景6：CUE 无 TITLE 兜底 ----

    @Test
    fun `CUE无TITLE 虚拟轨走文件夹名兜底`() {
        val noTitleCue = """
            PERFORMER "某某乐队"
            FILE "整轨.flac" WAVE
              TRACK 01 AUDIO
                TITLE "第一首"
                INDEX 01 00:00:00
        """.trimIndent()
        scan(listOf(file("整轨.flac"), file("整轨.cue")), cueTextReader = { noTitleCue })
        val albums = runBlocking { albumStore.notPlayed(0) + albumStore.recentPlayed(0) }
        assertEquals(1, albums.size)
        assertEquals("CUE测试文件夹", albums[0].album)  // 文件夹名兜底
    }

    // ---- 场景7：CUE 读取失败降级 ----

    @Test
    fun `CUE读取失败 整轨保持普通条目 cue记录error`() {
        scan(listOf(file("整轨.flac"), file("坏.cue")), cueTextReader = { null })
        val rows = runBlocking { dao.getEntryIndex(entryId) }
        // 整轨普通条目入库
        val whole = rows.find { it.uri == "content://doc/整轨.flac" }
        assertNotNull("整轨应入库", whole)
        // CUE 文件本身入库且记录 error
        val cueRow = rows.find { it.displayName == "坏" }
        assertNotNull("CUE 文件应入库", cueRow)
        assertNotNull("CUE 读取失败应记录 error", cueRow?.error)
    }

    // ---- 场景8：CUE 重扫稳定性 ----

    @Test
    fun `CUE重扫相同内容 虚拟轨uri和workId稳定 进度不丢`() {
        scan(listOf(file("整轨.flac"), file("整轨.cue")), cueTextReader = { standardCue })
        val rows1 = runBlocking { dao.getEntryIndex(entryId) }
        val virtual1 = rows1.filter { it.uri.contains("#track_") }
        assertEquals(2, virtual1.size)
        val workId1 = virtual1[0].workId
        assertNotNull(workId1)

        // 模拟播放进度
        runBlocking {
            dao.upsertWorkProgress(
                com.booklistener.db.WorkProgressEntity(workId1!!, virtual1[0].uri, 0, 1, 33_000L, 1.0f, System.currentTimeMillis())
            )
        }

        // 重扫
        scan(listOf(file("整轨.flac"), file("整轨.cue")), cueTextReader = { standardCue })
        val rows2 = runBlocking { dao.getEntryIndex(entryId) }
        val virtual2 = rows2.filter { it.uri.contains("#track_") }
        assertEquals(2, virtual2.size)
        // 虚拟轨 uri 稳定
        assertEquals(virtual1[0].uri, virtual2[0].uri)
        // workId 稳定
        assertEquals(workId1, virtual2[0].workId)
        // 进度不丢
        val progress = runBlocking { dao.getWorkProgress(workId1!!) }
        assertEquals(33_000L, progress?.positionMs)
    }
}
