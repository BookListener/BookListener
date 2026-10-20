package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
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
 * v2.1.2 混合目录回归（cue 整轨吸收 + 普通音频并存 → 专辑列表加载）：
 * 覆盖 ScanCueTest 未覆盖的混合场景——吸收整轨的虚拟轨与未被吸收的普通 flac
 * 同条目入库后，专辑聚合（主页分区）与专辑内歌曲（播放队列数据源）必须正常，
 * 不抛异常、条目数正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCueMixedTest {

    private lateinit var dao: AppDao
    private lateinit var store: EntryIndexStore

    private val entryId = "content://tree/mixed-dir"

    private val cueText = """
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

    private val wholeFile = IndexedFile("整轨", "uri://whole.flac", "某某专辑", "某某乐队", null, null, null, "整轨.flac")
    private val plainFlac = IndexedFile("普通歌曲", "uri://plain.flac", "某某专辑", "某某乐队", null, null, 200_000L, "普通.flac")

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
        store = EntryIndexStore(db.dao())
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryId, displayName = "混合文件夹", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    @Test
    fun mixedDir_absorbedCuePlusPlainFlac_albumListsLoadFine() {
        runBlocking {
            store.save(
                entryId,
                listOf(wholeFile, plainFlac),
                listOf(CueIndexer.CueCandidate("uri://整轨.cue", "整轨.cue", cueText))
            )
        }
        // 整轨被吸收（虚拟轨 2 条 + 普通 flac 1 条），仅虚拟轨+普通 flac 入库
        assertEquals(3, runBlocking { dao.getEntryIndex(entryId) }.size)
        assertTrue(runBlocking { dao.getWorkIdByUri("uri://whole.flac#track_0") } != null)
        assertTrue(runBlocking { dao.getWorkIdByUri("uri://plain.flac") } != null)

        // 主页专辑分区（专辑列表页入口）不得抛异常
        val albumStore = AlbumStore(dao)
        val notPlayed = albumStore.notPlayed(0)
        val recent = albumStore.recentPlayed(0)
        val all = notPlayed + recent
        assertTrue("混合目录必须至少聚合出一个作品", all.isNotEmpty())

        // 每个作品内歌曲列表（进入专辑列表页数据源）可正常读取
        all.forEach { album ->
            val songs = albumStore.allSongs(album.workId)
            assertTrue("作品 ${album.album} 必须有歌曲", songs.isNotEmpty())
        }

        // 混合目录的两个作品：虚拟轨作品（专辑名）与普通 flac 作品（同专辑名则合并）均非空
        val titles = all.map { it.album }
        assertTrue("必须包含专辑作品", titles.contains("某某专辑"))
    }

    @Test
    fun mixedDir_noCueTitle_plainFlacNamedAsFolder_twoWorksSameTitle_stillDistinctKeys() {
        // cue 无 TITLE → 虚拟轨作品走 entry:<entryId>|<folderName>（title=文件夹名）；
        // 普通 flac 内嵌专辑名 = 文件夹名 → 走 local:...（title=文件夹名）。
        // 两 work 同条目、同展示 title → 聚合必须返回两行且 workId 互异（UI 列表 key 用 workId 不重复）。
        val noTitleCue = """
            PERFORMER "某某乐队"
            FILE "整轨.flac" WAVE
              TRACK 01 AUDIO
                TITLE "第一首"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "第二首"
                INDEX 01 04:32:15
        """.trimIndent()
        val flacNamedAsFolder = IndexedFile(
            "普通歌曲", "uri://plain2.flac", "混合文件夹", "某某乐队", null, null, 200_000L, "普通.flac"
        )
        runBlocking {
            store.save(
                entryId,
                listOf(wholeFile, flacNamedAsFolder),
                listOf(CueIndexer.CueCandidate("uri://整轨.cue", "整轨.cue", noTitleCue))
            )
        }
        val albumStore = AlbumStore(dao)
        val all = albumStore.notPlayed(0) + albumStore.recentPlayed(0)
        val sameTitle = all.filter { it.album == "混合文件夹" }
        assertTrue("cue 虚拟轨作品与 flac 作品 title 撞名时必须聚合出两个不同作品", sameTitle.size >= 2)
        val distinctWorkIds = sameTitle.map { it.workId }.distinct()
        assertEquals("两个作品的 workId 必须互异（LazyColumn key 唯一性依赖）", sameTitle.size, distinctWorkIds.size)
        // 每个作品可正常读歌曲（进入各自专辑列表页）
        sameTitle.forEach { assertTrue(albumStore.allSongs(it.workId).isNotEmpty()) }
    }
}
