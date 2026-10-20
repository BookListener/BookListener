package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v4.0 扫描防断链测试（§8 最关键用例）：
 * 1) 二次扫描 workId 稳定（禁止整删重建）→ 已存进度不丢
 * 2) 文件改名 → uri 失配 → orderIndex 近似恢复（matchMode=0）
 * 3) 删条目/删曲 → cleanupOrphanWorks 收敛无引用作品与进度
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanUpsertTest {

    private lateinit var dao: AppDao
    private lateinit var store: WorkStore

    private val entryId = "content://tree/a"

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        store = WorkStore(db.dao())
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

    private fun scan(files: List<IndexedFile>) = runBlocking {
        dao.saveEntryIndexWithWorks(entryId, "文件夹A", "LOCAL", files)
    }

    @Test
    fun rescan_keepsWorkIdStable_soProgressSurvives() {
        scan(
            listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者", 2, 1, 90_000L, "02.mp3")
            )
        )
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        store.save(workId, "uri://a1", 0, 12345L, matchMode = 1)
        // 二次扫描（内容等价，正常重扫路径）
        scan(
            listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者", 2, 1, 90_000L, "02.mp3")
            )
        )
        val workId2 = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        assertEquals("作品 id 必须稳定（upsert by key）", workId, workId2)
        val rec = store.load(workId2)
        assertNotNull("进度不得因扫描丢失", rec)
        assertEquals(12345L, rec!!.positionMs)
    }

    @Test
    fun rename_degradesToOrderIndexApproxMatch() {
        scan(
            listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者", 2, 1, 90_000L, "02.mp3")
            )
        )
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        store.save(workId, "uri://a1", 0, 5000L, matchMode = 1)
        // 文件改名：同位置新 uri（uri://a1-new），但顺序不变
        scan(
            listOf(
                IndexedFile("歌一改名", "uri://a1-new", "专辑甲", "作者", 1, 1, 60_000L, "01-new.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者", 2, 1, 90_000L, "02.mp3")
            )
        )
        val items = runBlocking { dao.allSongsByWork(workId) }
            .map {
                AudioItem(
                    it.uri, it.displayName, it.uri, SourceType.LOCAL, 0L,
                    it.album, it.artist, it.track, it.disc, it.durationMs, it.rawName
                )
            }
        val record = store.load(workId)
        val (startIndex, startPos) = FolderProgressLogic.resolveStart(items, record)
        assertEquals("uri 失配按 orderIndex 近似定位到同位置", 0, startIndex)
        assertEquals(5000L, startPos)
    }

    @Test
    fun clearEntry_removesOrphanWorksAndProgress() {
        scan(
            listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3")
            )
        )
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        store.save(workId, "uri://a1", 0, 1000L, matchMode = 1)
        runBlocking {
            dao.deleteEntryIndex(entryId)
            dao.cleanupOrphanWorks()
        }
        assertNull2(runBlocking { dao.getWorkById(workId) })
        assertNull2(store.load(workId))
        assertEquals(0, runBlocking { dao.getWorksByEntry(entryId) }.size)
    }

    @Test
    fun removeTrack_orphanCleanupKeepsOtherWorkProgress() {
        scan(
            listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", null, "作者", null, null, 90_000L, "02.mp3")
            )
        )
        val work1 = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        val work2 = runBlocking { dao.getWorkIdByUri("uri://a2") }!!
        store.save(work1, "uri://a1", 0, 111L, 1)
        store.save(work2, "uri://a2", 0, 222L, 1)
        // 删除整条目录（仅剩无 album 曲目 → 触发孤儿清理）
        runBlocking {
            dao.saveEntryIndexWithWorks(
                entryId, "文件夹A", "LOCAL",
                listOf(IndexedFile("歌二", "uri://a2", null, "作者", null, null, 90_000L, "02.mp3"))
            )
        }
        assertNull2(runBlocking { dao.getWorkById(work1) })
        assertEquals(222L, store.load(work2)!!.positionMs)
    }

    private fun assertNull2(any: Any?) {
        if (any != null) throw AssertionError("期望为空，实际=$any")
    }

    @Test
    fun legacySplitWork_progressMigratesToMergedWork_onRescan() {
        // v2.1.5：key 规则变更（local 去 artist）前，同专辑多表演者被拆成多个作品。
        // 模拟旧拆分作品（key 含 artist）+ 已存进度
        val legacy = com.booklistener.db.WorkEntity(
            key = "local:周杰伦 with 五月天阿信|最伟大的作品",
            title = "最伟大的作品", albumArtist = "周杰伦 with 五月天阿信",
            sourceScope = "LOCAL", coverUri = null, songCount = 1, totalDurationMs = 222_000L,
            lastPlayedAt = 500L, updatedAt = 500L
        )
        val legacyId = runBlocking { dao.insertWork(legacy) }
        runBlocking {
            dao.upsertWorkProgress(
                com.booklistener.db.WorkProgressEntity(
                    legacyId, "uri://co.wav", 0, 1, 12_000L, 1.0f, 500L
                )
            )
        }
        // 重扫：同专辑（新 key 无 artist）→ 合并 work；旧 work 变孤儿，进度必须转移到合并 work
        scan(
            listOf(IndexedFile("合作曲", "uri://co.wav", "最伟大的作品", "周杰伦 with 五月天阿信", 2, 1, 222_000L, "co.wav"))
        )
        val merged = runBlocking { dao.getWorkByKey("local:最伟大的作品") }
        assertNotNull("合并 work 必须存在", merged)
        val progress = runBlocking { dao.getWorkProgress(merged!!.id) }
        assertNotNull("旧拆分作品的进度必须迁移到合并 work", progress)
        assertEquals(12_000L, progress!!.positionMs)
        assertEquals("uri://co.wav", progress.fileUri)
        assertNull2(runBlocking { dao.getWorkByKey("local:周杰伦 with 五月天阿信|最伟大的作品") })
        assertNull2(runBlocking { dao.getWorkById(legacyId) })
    }

    @Test
    fun sameAlbumDifferentArtists_mergeIntoSingleWork_onScan() {
        // 用户场景：专辑"最伟大的作品"含多表演者曲目 → 必须聚合成一个专辑（不再按作者拆开）
        scan(
            listOf(
                IndexedFile("主曲", "uri://m1", "最伟大的作品", "周杰伦", 1, 1, 222_000L, "m1.wav"),
                IndexedFile("合作曲", "uri://c1", "最伟大的作品", "周杰伦 with 五月天阿信", 2, 1, 222_000L, "c1.wav"),
                IndexedFile("合作曲2", "uri://c2", "最伟大的作品", "周杰伦 with 杨瑞代", 3, 1, 270_000L, "c2.wav")
            )
        )
        val id1 = runBlocking { dao.getWorkIdByUri("uri://m1") }!!
        val id2 = runBlocking { dao.getWorkIdByUri("uri://c1") }!!
        val id3 = runBlocking { dao.getWorkIdByUri("uri://c2") }!!
        assertEquals("同专辑多表演者必须合并为同一作品", id1, id2)
        assertEquals(id1, id3)
        val work = runBlocking { dao.getWorkById(id1) }!!
        assertEquals(3, work.songCount)
        assertEquals(714_000L, work.totalDurationMs)
    }
}
