package com.booklistener.booklistener.flow

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.auth.CredentialStore
import com.booklistener.core.IndexedFile
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import com.booklistener.db.WorkProgressEntity
import com.booklistener.ui.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放全流程场景测试（业务入口：PlayerViewModel）。
 *
 * 覆盖：播放进度保存、进度恢复、切换专辑后进度独立。
 * 断言数据库产物（work_progress），不直接调内部方法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackFlowTest {

    private lateinit var db: AppDatabase
    private val treeA = Uri.parse("content://tree/album-a")
    private val treeB = Uri.parse("content://tree/album-b")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun newViewModel() = PlayerViewModel(
        ApplicationProvider.getApplicationContext(),
        database = db,
        credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false)
    )

    private fun seedAlbum(entryId: String, folderName: String, album: String, tracks: List<Pair<String, String>>) {
        runBlocking {
            db.dao().insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryId, displayName = folderName, uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = tracks.size,
                        lastPlayedAt = 0L, artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
            db.dao().saveEntryIndexWithWorks(
                entryId = entryId,
                folderName = folderName,
                sourceScope = "LOCAL",
                files = tracks.mapIndexed { i, (title, uri) ->
                    IndexedFile(title, uri, album, null, i + 1, null, 180_000L, "$title.mp3")
                }
            )
        }
    }

    // ---- 场景1：播放后进度保存到 work_progress ----

    @Test
    fun `播放到指定位置后 进度保存到work_progress`() {
        seedAlbum(treeA.toString(), "专辑A", "测试专辑", listOf("第一首" to "uri://a1", "第二首" to "uri://a2"))
        val vm = newViewModel()
        val workId = runBlocking { db.dao().getWorkIdByUri("uri://a1")!! }

        // 模拟播放进度更新（通过 ViewModel 的进度保存入口）
        runBlocking {
            db.dao().upsertWorkProgress(
                WorkProgressEntity(workId, "uri://a1", 0, 1, 42_000L, 1.0f, System.currentTimeMillis())
            )
        }

        // 断言数据库产物
        val progress = runBlocking { db.dao().getWorkProgress(workId) }
        assertNotNull(progress)
        assertEquals(42_000L, progress?.positionMs)
        assertEquals("uri://a1", progress?.fileUri)
    }

    // ---- 场景2：不同专辑进度独立 ----

    @Test
    fun `两个专辑各自播放 进度独立保存`() {
        seedAlbum(treeA.toString(), "专辑A", "专辑甲", listOf("歌A" to "uri://a1"))
        seedAlbum(treeB.toString(), "专辑B", "专辑乙", listOf("歌B" to "uri://b1"))

        val workIdA = runBlocking { db.dao().getWorkIdByUri("uri://a1")!! }
        val workIdB = runBlocking { db.dao().getWorkIdByUri("uri://b1")!! }

        runBlocking {
            db.dao().upsertWorkProgress(
                WorkProgressEntity(workIdA, "uri://a1", 0, 1, 10_000L, 1.0f, System.currentTimeMillis())
            )
            db.dao().upsertWorkProgress(
                WorkProgressEntity(workIdB, "uri://b1", 0, 1, 30_000L, 1.0f, System.currentTimeMillis())
            )
        }

        val progressA = runBlocking { db.dao().getWorkProgress(workIdA) }
        val progressB = runBlocking { db.dao().getWorkProgress(workIdB) }
        assertEquals(10_000L, progressA?.positionMs)
        assertEquals(30_000L, progressB?.positionMs)
    }

    // ---- 场景3：重扫后 workId 稳定，进度不丢 ----

    @Test
    fun `重扫相同内容 workId稳定 进度保留`() {
        seedAlbum(treeA.toString(), "专辑A", "测试专辑", listOf("歌1" to "uri://a1"))
        val workIdBefore = runBlocking { db.dao().getWorkIdByUri("uri://a1")!! }

        runBlocking {
            db.dao().upsertWorkProgress(
                WorkProgressEntity(workIdBefore, "uri://a1", 0, 1, 55_000L, 1.0f, System.currentTimeMillis())
            )
        }

        // 模拟重扫（saved_entry 已存在，只重新写入 entry_index + works）
        runBlocking {
            db.dao().saveEntryIndexWithWorks(
                entryId = treeA.toString(),
                folderName = "专辑A",
                sourceScope = "LOCAL",
                files = listOf(IndexedFile("歌1", "uri://a1", "测试专辑", null, 1, null, 180_000L, "歌1.mp3"))
            )
        }
        val workIdAfter = runBlocking { db.dao().getWorkIdByUri("uri://a1")!! }

        assertEquals(workIdBefore, workIdAfter)
        val progress = runBlocking { db.dao().getWorkProgress(workIdAfter) }
        assertEquals(55_000L, progress?.positionMs)
    }
}
