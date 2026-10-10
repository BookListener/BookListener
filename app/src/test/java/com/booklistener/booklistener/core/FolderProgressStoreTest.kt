package com.booklistener.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 文件夹独立播放进度持久化回归测试（Room 版，v1.6.0）：
 * 覆盖"多文件夹互不覆盖、同文件夹覆盖更新、删除单条不串、重启后仍在"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderProgressStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: FolderProgressStore

    private val folderA = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private val folderB = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = FolderProgressStore(db.dao())
    }

    @Test
    fun saveThenLoad_roundTrip() {
        store.save(folderA, 2, 135_000L)
        val rec = store.load(folderA)
        assertEquals(2, rec?.trackIndex)
        assertEquals(135_000L, rec?.positionMs)
        assertEquals(folderA, rec?.folderUri)
    }

    @Test
    fun noRecord_returnsNull() {
        assertNull(store.load(folderA))
    }

    @Test
    fun multipleFolders_isolated() {
        store.save(folderA, 2, 135_000L)
        store.save(folderB, 0, 42_000L)
        val a = store.load(folderA)
        val b = store.load(folderB)
        assertEquals(2, a?.trackIndex)
        assertEquals(135_000L, a?.positionMs)
        assertEquals(0, b?.trackIndex)
        assertEquals(42_000L, b?.positionMs)
    }

    @Test
    fun saveSameFolder_overwritesOnlyThatFolder() {
        store.save(folderA, 2, 135_000L)
        store.save(folderB, 0, 42_000L)
        store.save(folderA, 5, 10_000L)
        val a = store.load(folderA)
        val b = store.load(folderB)
        assertEquals(5, a?.trackIndex)
        assertEquals(10_000L, a?.positionMs)
        assertEquals(42_000L, b?.positionMs) // 不受影响
    }

    @Test
    fun remove_keepsOthers() {
        store.save(folderA, 2, 135_000L)
        store.save(folderB, 0, 42_000L)
        store.remove(folderA)
        assertNull(store.load(folderA))
        assertEquals(42_000L, store.load(folderB)?.positionMs)
    }

    @Test
    fun newStoreInstance_dataPersists() {
        store.save(folderA, 3, 60_000L)
        val fresh = FolderProgressStore(db.dao())
        val rec = fresh.load(folderA)
        assertEquals(3, rec?.trackIndex)
        assertEquals(60_000L, rec?.positionMs)
    }

    @Test
    fun saveNegativePosition_coercedToZero() {
        store.save(folderA, 1, -5L)
        assertEquals(0L, store.load(folderA)?.positionMs)
    }
}
