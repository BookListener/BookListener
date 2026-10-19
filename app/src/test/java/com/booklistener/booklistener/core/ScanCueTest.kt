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
 * v5.0 CUE 扫描集成测试（spec: cue-virtual-tracks · 整轨吸收与退化 / 虚拟轨入库与关联）：
 * 走真实 EntryIndexStore.save（含 cue 预处理）验证：
 * 1) 吸收成功：cue+flac 同目录 → 专辑只有虚拟轨、整轨不入库、cue_sheets 落库
 * 2) 解析失败/未定位退化：整轨保持普通条目、cue_sheets 记录存在（audioUri/workId 空）
 * 3) 二次扫描稳定：虚拟轨 uri 与 workId 稳定、进度不丢
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCueTest {

    private lateinit var dao: AppDao
    private lateinit var store: EntryIndexStore

    private val entryId = "content://tree/cue-dir"

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
                        id = entryId, displayName = "某专辑文件夹", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    private fun saveWithCue(cue: CueIndexer.CueCandidate) = runBlocking {
        store.save(entryId, listOf(wholeFile), listOf(cue))
    }

    @Test
    fun absorbedCue_virtualTracksOnly_wholeNotInIndex() {
        saveWithCue(CueIndexer.CueCandidate("uri://整轨.cue", "整轨.cue", cueText))
        runBlocking {
            // 整轨被吸收：不在 entry_index
            assertNull(dao.getEntryIdByUri("uri://whole.flac"))
            // 虚拟轨入库（普通媒体条目）
            val track1 = dao.getEntryIdByUri("uri://whole.flac#track_0")
            val track2 = dao.getEntryIdByUri("uri://whole.flac#track_272200")
            assertTrue("虚拟轨 1 应入库", track1 != null)
            assertTrue("虚拟轨 2 应入库", track2 != null)
            val files = dao.getEntryIndex(entryId)
            assertEquals(2, files.size)
            assertEquals("第一首", files[0].displayName)
            assertEquals("某某专辑", files[0].album)
            assertEquals("某某乐队", files[0].artist)
            assertEquals(1, files[0].track)
            // 作品聚合：两条虚拟轨同作品（cue 专辑键）
            val wid1 = dao.getWorkIdByUri("uri://whole.flac#track_0")
            val wid2 = dao.getWorkIdByUri("uri://whole.flac#track_272200")
            assertTrue(wid1 != null && wid1 == wid2)
            // cue_sheets 关联（audioUri=整轨、workId 已回填）
            val sheet = dao.getCueSheetByUri("uri://整轨.cue")
            assertEquals("uri://whole.flac", sheet?.audioUri)
            assertEquals(wid1, sheet?.workId)
        }
    }

    @Test
    fun parseFailure_wholeStaysNormal_cueSheetWithoutLink() {
        val badCue = CueIndexer.CueCandidate("uri://bad.cue", "bad.cue", "REM 空文件")
        saveWithCue(badCue)
        runBlocking {
            // 整轨退化为普通条目
            assertTrue(dao.getEntryIdByUri("uri://whole.flac") != null)
            // cue_sheets 记录存在但无定位/作品
            val sheet = dao.getCueSheetByUri("uri://bad.cue")
            assertNull(sheet?.audioUri)
            assertNull(sheet?.workId)
            // 无虚拟轨
            assertNull(dao.getEntryIdByUri("uri://whole.flac#track_0"))
        }
    }

    @Test
    fun unmatchedAudioFile_wholeStaysNormal() {
        // cue 引用不存在的文件（FILE 未命中 + 同名探测未中）→ 退化
        val cue = CueIndexer.CueCandidate(
            "uri://x.cue", "x.cue",
            """
            TITLE "专辑X"
            FILE "不存在的文件.flac" WAVE
              TRACK 01 AUDIO
                TITLE "歌"
                INDEX 01 00:00:00
            """.trimIndent()
        )
        saveWithCue(cue)
        runBlocking {
            assertTrue("整轨应保持普通条目", dao.getEntryIdByUri("uri://whole.flac") != null)
            assertNull(dao.getCueSheetByUri("uri://x.cue")?.audioUri)
        }
    }

    @Test
    fun rescan_virtualTrackUrisAndWorkIdStable() {
        saveWithCue(CueIndexer.CueCandidate("uri://整轨.cue", "整轨.cue", cueText))
        val widBefore = runBlocking { dao.getWorkIdByUri("uri://whole.flac#track_0") }
        // 进度存到虚拟轨
        WorkStore(dao).save(widBefore!!, "uri://whole.flac#track_0", 0, 12_345L, matchMode = 1)
        // 二次扫描（内容等价）
        saveWithCue(CueIndexer.CueCandidate("uri://整轨.cue", "整轨.cue", cueText))
        runBlocking {
            val widAfter = dao.getWorkIdByUri("uri://whole.flac#track_0")
            assertEquals("虚拟轨作品 id 必须稳定", widBefore, widAfter)
            val rec = WorkStore(dao).load(widAfter!!)
            assertEquals("虚拟轨进度不得因扫描丢失", 12_345L, rec?.positionMs)
        }
    }

    @Test
    fun multipleCues_eachAbsorbsItsOwnWhole() {
        val cue2 = """
            TITLE "另一专辑"
            FILE "另一整轨.ape" WAVE
              TRACK 01 AUDIO
                TITLE "另一首"
                INDEX 01 00:00:00
        """.trimIndent()
        val whole2 = IndexedFile("另一整轨", "uri://whole2.ape", "另一专辑", null, null, null, null, "另一整轨.ape")
        runBlocking {
            store.save(
                entryId,
                listOf(wholeFile, whole2),
                listOf(
                    CueIndexer.CueCandidate("uri://整轨.cue", "整轨.cue", cueText),
                    CueIndexer.CueCandidate("uri://另一.cue", "另一.cue", cue2)
                )
            )
        }
        runBlocking {
            // 两个整轨都被吸收，仅虚拟轨入库
            assertNull(dao.getEntryIdByUri("uri://whole.flac"))
            assertNull(dao.getEntryIdByUri("uri://whole2.ape"))
            assertEquals(3, dao.getEntryIndex(entryId).size)
            assertEquals(2, dao.getCueSheetsByEntry(entryId).size)
            // 两个 cue 作品不同
            val w1 = dao.getWorkIdByUri("uri://whole.flac#track_0")
            val w2 = dao.getWorkIdByUri("uri://whole2.ape#track_0")
            assertTrue(w1 != w2)
        }
    }
}
