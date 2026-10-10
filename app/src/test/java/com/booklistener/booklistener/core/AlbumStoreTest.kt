package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.EntryIndexEntity
import com.booklistener.db.SavedEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7.0 专辑聚合测试：分区（条目 lastPlayedAt）、专辑名兜底（COALESCE album→文件夹名）、
 * 分页 LIMIT/OFFSET、专辑内歌曲 disc→track→名称自然排序与内存分页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumStoreTest {

    private lateinit var dao: AppDao
    private lateinit var store: AlbumStore

    private val entryA = "content://tree/a"
    private val entryB = "content://tree/b"

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
            // 条目A：2 首带 album/artist/时长 + 1 首无 album（兜底文件夹名）
            dao.insertEntryIndex(
                listOf(
                    EntryIndexEntity(entryA, "歌一", "uri://a1", "专辑甲", "作者甲", 1, 1, 60_000L, "01.mp3"),
                    EntryIndexEntity(entryA, "歌二", "uri://a2", "专辑甲", "作者甲", 2, 1, 90_000L, "02.mp3"),
                    EntryIndexEntity(entryA, "歌三", "uri://a3", null, "作者甲", null, null, 30_000L, "03.mp3")
                )
            )
            // 条目B：1 首无 album（兜底文件夹B），未播放
            dao.insertEntryIndex(
                listOf(
                    EntryIndexEntity(entryB, "歌四", "uri://b1", null, null, null, null, 10_000L, "04.mp3")
                )
            )
        }
    }

    @Test
    fun recentPlayed_groupsByAlbumAndOrdersByEntryRecency() {
        val rows = store.recentPlayed(0)
        assertEquals(2, rows.size)
        // 条目A 已播放 → 两个专辑（专辑甲 / 文件夹A 兜底）；按条目 lastPlayedAt 倒序
        assertEquals("专辑甲", rows[0].album)
        assertEquals(entryA, rows[0].entryId)
        assertEquals(2, rows[0].songCount)
        assertEquals(150_000L, rows[0].totalDurationMs)
        assertEquals("文件夹A", rows[1].album)
        assertEquals(1, rows[1].songCount)
    }

    @Test
    fun notPlayed_excludesPlayedEntries() {
        val rows = store.notPlayed(0)
        assertEquals(1, rows.size)
        assertEquals("文件夹B", rows[0].album)
        assertEquals(entryB, rows[0].entryId)
    }

    @Test
    fun paging_limitsToOneHundredAndOffsets() {
        // 已播放区仅 2 行：第 2 页为空
        assertTrue(store.recentPlayed(1).isEmpty())
    }

    @Test
    fun songs_naturalSortsByTrackThenName() {
        // 专辑甲：track 1/2 → 歌一、歌二
        val songs = store.songs(entryA, "专辑甲", 0)
        assertEquals(2, songs.size)
        assertEquals("歌一", songs[0].displayName)
        assertEquals("歌二", songs[1].displayName)
        assertEquals("01.mp3", songs[0].rawName)
    }

    @Test
    fun songs_albumFallbackUsesFolderName() {
        // 无 album 曲目按兜底专辑名（文件夹A）也能查到
        val songs = store.songs(entryA, "文件夹A", 0)
        assertEquals(1, songs.size)
        assertEquals("歌三", songs[0].displayName)
    }

    @Test
    fun songs_naturalOrder_overDictionaryOrder() {
        // 同 track 缺失时按显示名自然排序："2" 应排在 "10" 前
        runBlocking {
            dao.insertEntryIndex(
                listOf(
                    EntryIndexEntity(entryB, "10", "uri://b10", "专辑B", null, null, null, 1000L, "10.mp3"),
                    EntryIndexEntity(entryB, "2", "uri://b2", "专辑B", null, null, null, 1000L, "2.mp3")
                )
            )
        }
        val songs = store.songs(entryB, "专辑B", 0)
        assertEquals("2", songs[0].displayName)
        assertEquals("10", songs[1].displayName)
    }
}
