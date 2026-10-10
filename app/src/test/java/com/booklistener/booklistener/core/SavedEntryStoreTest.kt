package com.booklistener.core

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SavedEntryStore（Room 版）回归测试（v1.6.0）：
 * upsert 去重、remove、replace 原位、touchLastPlayed/touchOpened 持久化、
 * 新实例"重启后仍在"。旧数据迁移语义移入 StoreMigrationTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedEntryStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: SavedEntryStore

    private val treeA = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private val treeB = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = SavedEntryStore(db.dao())
    }

    @Test
    fun emptyStore_returnsEmpty() {
        assertEquals(0, store.load().size)
    }

    @Test
    fun upsert_dedupById() {
        val e = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), e)
        list = store.upsert(list, e.copy(fileCount = 3))
        assertEquals(1, list.size)
        assertEquals(3, list[0].fileCount)
        assertEquals(1, store.load().size)
    }

    @Test
    fun upsert_newId_appendsAtEnd_keepsOrder() {
        val a = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        val b = SavedEntry(id = treeB, displayName = "Music", uri = treeB, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), a)
        list = store.upsert(list, b)
        assertEquals(listOf(treeA, treeB), store.load().map { it.id })
    }

    @Test
    fun remove_keepsOthers() {
        val a = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        val b = SavedEntry(id = treeB, displayName = "Music", uri = treeB, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), a)
        list = store.upsert(list, b)
        list = store.remove(list, treeA)
        assertEquals(listOf(treeB), list.map { it.id })
        assertEquals(listOf(treeB), store.load().map { it.id })
    }

    @Test
    fun replace_keepsPosition() {
        val a = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        val b = SavedEntry(id = treeB, displayName = "Music", uri = treeB, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), a)
        list = store.upsert(list, b)
        list = store.replace(list, a.copy(fileCount = 9))
        assertEquals(listOf(treeA, treeB), list.map { it.id })
        assertEquals(9, store.load()[0].fileCount)
    }

    @Test
    fun touchLastPlayed_persists() {
        val a = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), a)
        list = store.touchLastPlayed(list, treeA, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, list[0].lastPlayedAt)
        assertEquals(1_700_000_000_000L, store.load()[0].lastPlayedAt)
    }

    @Test
    fun touchOpened_persists() {
        val a = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), a)
        list = store.touchOpened(list, treeA, 1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, list[0].lastOpenedAt)
        assertEquals(1_800_000_000_000L, store.load()[0].lastOpenedAt)
    }

    @Test
    fun newStoreInstance_dataPersists() {
        val e = SavedEntry(id = treeA, displayName = "Download", uri = treeA, sourceType = SourceType.LOCAL)
        store.upsert(emptyList(), e)
        val fresh = SavedEntryStore(db.dao())
        assertEquals(1, fresh.load().size)
        assertEquals(treeA, fresh.load()[0].id)
    }

    @Test
    fun load_returnsSortedByOrder() {
        val a = SavedEntry(id = treeA, displayName = "B", uri = treeA, sourceType = SourceType.LOCAL)
        val b = SavedEntry(id = treeB, displayName = "A", uri = treeB, sourceType = SourceType.LOCAL)
        var list = store.upsert(emptyList(), a)
        list = store.upsert(list, b)
        // 保持插入序（sortOrder），不做名称排序
        assertTrue(store.load()[0].id == treeA)
    }
}
