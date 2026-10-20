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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.2 封面点击回归（ownerId 语义 "work:<workId>" → 来源条目反查）：
 * 1) 扫描后 getWorksByEntry 非空（openEntryAlbum 依赖链）
 * 2) workId → 来源条目：单条目正确命中
 * 3) 跨条目同名专辑合并 → 取 MIN entryId（与 AlbumStore 聚合口径一致）
 * 4) 无记录 workId → 反查 null（queueOwnerEntry 静默返回路径）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkEntryLookupTest {

    private lateinit var dao: AppDao

    private val entryA = "content://tree/a"
    private val entryB = "content://tree/b"

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(entryA, "文件夹A", entryA, "LOCAL", null, 0, 0L, null, 1L, 0),
                    SavedEntryEntity(entryB, "文件夹B", entryB, "LOCAL", null, 0, 0L, null, 1L, 1)
                )
            )
        }
    }

    private fun scan(entryId: String, files: List<IndexedFile>) = runBlocking {
        dao.saveEntryIndexWithWorks(entryId, if (entryId == entryA) "文件夹A" else "文件夹B", "LOCAL", files)
    }

    @Test
    fun scannedEntry_resolvesBackToEntry_viaWorkId() {
        scan(
            entryA, listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者", 2, 1, 90_000L, "02.mp3")
            )
        )
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        // openEntryAlbum 依赖链：作品 → 条目反查 → 该条目 works 非空
        val backEntry = runBlocking { dao.getEntryIdByWorkId(workId) }
        assertEquals("作品必须反查到来源条目", entryA, backEntry)
        assertTrue("来源条目的 works 非空（封面点击进列表依赖）",
            runBlocking { dao.getWorksByEntry(backEntry!!) }.isNotEmpty())
    }

    @Test
    fun crossEntryMerge_takesMinEntryId() {
        // 同名专辑跨条目（同 album/artist → 同 work key → 同一 workId）
        scan(entryA, listOf(IndexedFile("歌一", "uri://a1", "专辑甲", "作者", 1, 1, 60_000L, "01.mp3")))
        scan(entryB, listOf(IndexedFile("歌三", "uri://b1", "专辑甲", "作者", 1, 1, 60_000L, "03.mp3")))
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        val workIdB = runBlocking { dao.getWorkIdByUri("uri://b1") }!!
        assertEquals("跨条目同名专辑必须聚合为同一作品", workId, workIdB)
        assertEquals("反查取 MIN entryId（与 AlbumStore 口径一致）", entryA,
            runBlocking { dao.getEntryIdByWorkId(workId) })
    }

    @Test
    fun unknownWorkId_resolvesNull() {
        assertNull("无记录 workId 反查必须返回 null（queueOwnerEntry 静默路径）",
            runBlocking { dao.getEntryIdByWorkId(9999L) })
    }
}
