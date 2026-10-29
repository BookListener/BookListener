package com.booklistener.flow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.AlbumStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2.21 扫描两级超时重试 + 单文件手动重扫流程测试。
 *
 * 覆盖 SPECS（v2.2.21 扫描进度模块）：
 * 1) 第一轮超时（小文件 ≤5MB → 3s 档）→ 不判死，第二轮重试成功 → 行正常入库、无失败清单；
 * 2) 真失败（tagReader 返回 null）→ 进失败清单 + 行 error；
 * 3) rescanFile 单文件重扫成功 → 错误行替换为正常标签行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanRetryFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private lateinit var albumStore: AlbumStore
    private var entrySeq = 0

    private class FakeSource(val files: List<SourceFile>) : Source {
        override val type: SourceType = SourceType.LOCAL
        override suspend fun listFiles(rootUri: String): List<SourceFile> = files
    }

    /** ≤5MB 音频：命中第一轮 3s 超时档 */
    private fun smallFile(name: String) = SourceFile(
        name = name, path = "content://doc/$name", relativePath = "",
        isDirectory = false, sizeBytes = 3L * 1024 * 1024
    )

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

    private fun scan(
        entryId: String,
        files: List<SourceFile>,
        tagReader: suspend (SourceFile) -> ResolvedTags?
    ): ScanCoordinator.ScanResult {
        val coord = ScanCoordinator(
            source = FakeSource(files),
            tagReader = tagReader,
            cueTextReader = { null },
            indexStore = indexStore
        )
        return runBlocking { coord.scan(entryId) }
    }

    @Test
    fun `第一轮超时 第二轮重试成功 不产生失败 行正常`() {
        val id = "e_retry_ok"
        addEntry(id, "重试OK")
        var calls = 0
        val r = scan(id, listOf(smallFile("a.m4a"))) {
            calls++
            if (calls == 1) {
                // 第一次：挂起超过 3s 小文件档 → 第一轮 TimedOut
                kotlinx.coroutines.delay(3_500)
            }
            ResolvedTags.basic("a", "艺术家", "专辑", 1, 1, 60_000L)
        }
        assertEquals("重试成功后无失败清单", 0, r.failedFiles.size)
        assertEquals("第二轮确实重试了该文件", 2, calls)
        val rows = indexStore.loadEntry(id)
        val a = rows.first { it.uri == "content://doc/a.m4a" }
        assertEquals("标签回填成功", "专辑", a.album)
        assertEquals(60_000L, a.durationMs)
        assertNull("错误消除", a.error)
    }

    @Test
    fun `真失败进失败清单 行带error`() {
        val id = "e_fail"
        addEntry(id, "失败")
        val r = scan(id, listOf(smallFile("b.m4a"))) { null }
        assertEquals(1, r.failedFiles.size)
        assertEquals("content://doc/b.m4a", r.failedFiles[0].uri)
        val rows = indexStore.loadEntry(id)
        assertTrue("失败行带 error", rows.first { it.uri == "content://doc/b.m4a" }.error != null)
    }

    @Test
    fun `rescanFile 单文件重扫成功 错误行替换为正常行`() {
        val id = "e_rescan"
        addEntry(id, "重扫")
        val files = listOf(smallFile("c.m4a"))
        var failFirst = true
        val coord = ScanCoordinator(
            source = FakeSource(files),
            tagReader = {
                if (failFirst) null else ResolvedTags.basic("c", "艺术家", "专辑", 1, 1, 60_000L)
            },
            cueTextReader = { null },
            indexStore = indexStore
        )
        val r1 = runBlocking { coord.scan(id) }
        assertEquals("首次扫描失败", 1, r1.failedFiles.size)
        failFirst = false
        val rr = runBlocking { coord.rescanFile(id, id, "content://doc/c.m4a") }
        assertTrue("重扫成功", rr.success)
        val rows = indexStore.loadEntry(id)
        val c = rows.first { it.uri == "content://doc/c.m4a" }
        assertNull("重扫后错误消除", c.error)
        assertEquals("重扫后标签回填", "专辑", c.album)
    }

    @Test
    fun `rescanFile 重扫仍失败 返回详细错误`() {
        val id = "e_rescan_fail"
        addEntry(id, "重扫失败")
        val files = listOf(smallFile("d.m4a"))
        val coord = ScanCoordinator(
            source = FakeSource(files),
            tagReader = { null },
            cueTextReader = { null },
            indexStore = indexStore
        )
        runBlocking { coord.scan(id) }
        val rr = runBlocking { coord.rescanFile(id, id, "content://doc/d.m4a") }
        assertTrue("重扫仍失败", !rr.success)
        assertTrue("返回错误详情", rr.detail != null && rr.detail!!.isNotBlank())
    }
}
