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
 * v4.0 作品化专辑聚合测试：
 * 分区按 works.lastPlayedAt（touchWorkPlayed）、跨条目同名专辑合并为一个作品、
 * 作品内歌曲按 sortRank 排序分页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumStoreTest {

    private lateinit var dao: AppDao
    private lateinit var store: AlbumStore

    private val entryA = "content://tree/a"
    private val entryB = "content://tree/b"

    private fun scanEntryA() = runBlocking {
        dao.saveEntryIndexWithWorks(
            entryId = entryA,
            folderName = "文件夹A",
            sourceScope = "LOCAL",
            files = listOf(
                IndexedFile("歌一", "uri://a1", "专辑甲", "作者甲", 1, 1, 60_000L, "01.mp3"),
                IndexedFile("歌二", "uri://a2", "专辑甲", "作者甲", 2, 1, 90_000L, "02.mp3"),
                IndexedFile("歌三", "uri://a3", null, "作者甲", null, null, 30_000L, "03.mp3")
            )
        )
    }

    private fun scanEntryB() = runBlocking {
        dao.saveEntryIndexWithWorks(
            entryId = entryB,
            folderName = "文件夹B",
            sourceScope = "LOCAL",
            files = listOf(
                IndexedFile("歌四", "uri://b1", null, null, null, null, 10_000L, "04.mp3")
            )
        )
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        store = AlbumStore(db.dao())
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryA, displayName = "文件夹A", uri = entryA,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 200L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    ),
                    SavedEntryEntity(
                        id = entryB, displayName = "文件夹B", uri = entryB,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 1
                    )
                )
            )
        }
        scanEntryA()
        scanEntryB()
    }

    @Test
    fun notPlayed_containsAllWorksInitially() {
        // 全部未 touchPlayed → 2 个作品（专辑甲 / 文件夹A 兜底 / 文件夹B 兜底 = 3 个）
        val rows = store.notPlayed(0)
        assertEquals(3, rows.size)
        val albumA = rows.first { it.album == "专辑甲" }
        assertEquals(2, albumA.songCount)
        assertEquals(150_000L, albumA.totalDurationMs)
        assertEquals(entryA, albumA.entryId)
    }

    @Test
    fun recentPlayed_afterTouchMovesWorkToPlayedPartition() {
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        runBlocking { dao.touchWorkPlayed(workId, System.currentTimeMillis()) }
        val recent = store.recentPlayed(0)
        assertEquals(1, recent.size)
        assertEquals("专辑甲", recent[0].album)
        val never = store.notPlayed(0)
        assertEquals(2, never.size)
    }

    @Test
    fun crossFolderSameAlbum_mergesIntoOneWork() {
        // 条目B 也扫描同名"专辑甲" → 跨条目合并为同一作品，songCount 累加
        runBlocking {
            dao.saveEntryIndexWithWorks(
                entryId = entryB,
                folderName = "文件夹B",
                sourceScope = "LOCAL",
                files = listOf(
                    IndexedFile("歌四", "uri://b1", "专辑甲", "作者甲", 1, 1, 10_000L, "04.mp3")
                )
            )
        }
        val rows = store.notPlayed(0)
        val albumA = rows.first { it.album == "专辑甲" }
        assertEquals(3, albumA.songCount)        // 2 + 1
        assertEquals(160_000L, albumA.totalDurationMs) // 150 + 10
        assertEquals(2, albumA.albumCountInEntry)      // 覆盖两个条目
    }

    @Test
    fun paging_offsetsEmptyPage() {
        // 未播放 3 行：第 1 页（100/页）已含全部，第 2 页为空
        assertEquals(3, store.notPlayed(0).size)
        assertEquals(0, store.notPlayed(1).size)
    }

    @Test
    fun songs_byWorkIdOrdersBySortRank() {
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        val songs = store.songs(workId, 0)
        assertEquals(2, songs.size)
        assertEquals("歌一", songs[0].displayName)
        assertEquals("歌二", songs[1].displayName)
        assertEquals("01.mp3", songs[0].rawName)
    }

    @Test
    fun allSongs_byWorkIdReturnsFullQueue() {
        val workId = runBlocking { dao.getWorkIdByUri("uri://a1") }!!
        val all = store.allSongs(workId)
        assertEquals(2, all.size)
        assertEquals("歌一", all[0].displayName)
    }

    @Test
    fun folderFallbackWork_songsQueryable() {
        // 无 album 曲目 → 独立作品（key=entry:entryA|文件夹A）
        val workId = runBlocking { dao.getWorkIdByUri("uri://a3") }!!
        val songs = store.allSongs(workId)
        assertEquals(1, songs.size)
        assertEquals("歌三", songs[0].displayName)
    }
}
