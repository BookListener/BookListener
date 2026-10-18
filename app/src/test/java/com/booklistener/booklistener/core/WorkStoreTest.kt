package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v4.0 作品进度读写测试：按曲目反查 workId、每作品单进度点覆盖保存、
 * 条目最近进度恢复、touchPlayed 驱动最近播放分区。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkStoreTest {

    private lateinit var dao: AppDao
    private lateinit var store: WorkStore
    private lateinit var albumStore: AlbumStore

    private val entryId = "content://tree/a"

    private fun scanOnce() = runBlocking {
        dao.saveEntryIndexWithWorks(
            entryId = entryId,
            folderName = "文件夹A",
            sourceScope = "LOCAL",
            files = listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者", 2, 1, 90_000L, "02.mp3")
            )
        )
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        store = WorkStore(db.dao())
        albumStore = AlbumStore(db.dao())
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
        scanOnce()
    }

    @Test
    fun workIdForUri_resolvesScannedTrack() {
        assertEquals(1, store.workIdForUri("uri://a1")!!)
        assertNull(store.workIdForUri("uri://not-scanned"))
    }

    @Test
    fun saveAndLoad_roundTrips() {
        val workId = store.workIdForUri("uri://a1")!!
        store.save(workId, "uri://a1", orderIndex = 0, positionMs = 12345L, matchMode = 1, speed = 1.5f)
        val rec = store.load(workId)!!
        assertEquals("uri://a1", rec.fileUri)
        assertEquals(0, rec.orderIndex)
        assertEquals(1, rec.matchMode)
        assertEquals(12345L, rec.positionMs)
        assertEquals(1.5f, rec.speed, 0.001f)
    }

    @Test
    fun save_overwritesSingleProgressPointPerWork() {
        val workId = store.workIdForUri("uri://a1")!!
        store.save(workId, "uri://a1", 0, 1000L, 1)
        store.save(workId, "uri://a2", 1, 2000L, 1)
        val rec = store.load(workId)!!
        assertEquals("uri://a2", rec.fileUri) // 同作品仅一个当前进度点
        assertEquals(2000L, rec.positionMs)
    }

    @Test
    fun loadMostRecentByEntry_returnsLatestTouchedWork() {
        val work1 = store.workIdForUri("uri://a1")!!
        val work2 = runBlocking {
            // 第二作品：无 album 曲目
            dao.saveEntryIndexWithWorks(
                entryId = entryId, folderName = "文件夹A", sourceScope = "LOCAL",
                files = listOf(IndexedFile("歌三", "uri://a3", null, "作者", null, null, 30_000L, "03.mp3"))
            )
            dao.getWorkIdByUri("uri://a3")!!
        }
        store.save(work1, "uri://a1", 0, 100L, 1)
        store.save(work2, "uri://a3", 0, 999L, 1)
        val rec = store.loadMostRecentByEntry(entryId)!!
        assertEquals(work2, rec.workId)
        assertEquals(999L, rec.positionMs)
    }

    @Test
    fun touchPlayed_drivesRecentPartition() {
        val workId = store.workIdForUri("uri://a1")!!
        store.touchPlayed(workId, System.currentTimeMillis())
        val recent = albumStore.recentPlayed(0)
        assertEquals(1, recent.size)
        assertEquals("专辑甲", recent[0].album)
    }
}
