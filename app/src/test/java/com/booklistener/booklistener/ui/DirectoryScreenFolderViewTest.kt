package com.booklistener.ui

import com.booklistener.auth.CredentialStore
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import org.robolectric.Shadows.shadowOf
import com.booklistener.db.EntryIndexEntity
import com.booklistener.db.SavedEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 文件夹视图与多固化文件夹回归测试：
 * 覆盖"打开文件夹 → 列举 → 返回本地音乐"状态切换、
 * "多个固化文件夹互不覆盖、重启后仍可进入"的持久化行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryScreenFolderViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val treeA = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
    private val treeB = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        // v1.6.0：Store 切 Room 后注入 in-memory db（隔离用例间数据，避免全局单例串数据）
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    @Test
    fun scanFolder_entersFolderView() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        assertTrue("scanFolder 应同步进入文件夹视图", vm.inFolderView.value)
        assertEquals(treeA, vm.currentFolderUri.value)
    }

    /** 回归：扫描重建条目不得清零 lastPlayedAt（否则"正在播放"清后台后漂移成"尚未播放"） */
    @Test
    fun scanFolder_keepsLastPlayedAt() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences(
            "saved_folders", android.content.Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val obj = org.json.JSONObject().apply {
            put("id", treeA.toString())
            put("displayName", "Download")
            put("uri", treeA.toString())
            put("sourceType", "LOCAL")
            put("lastPlayedAt", 1_000L)
        }
        prefs.edit().putString("saved_entries_json", org.json.JSONArray().put(obj).toString()).commit()
        // v1.6.0：prefs 旧数据 → Room 首启迁移（真实启动路径）
        com.booklistener.core.StoreMigration.run(prefs, db)

        val vm = newViewModel()
        vm.scanFolder(treeA)

        // 等异步 replace 完成（replace 后条目应保留 lastPlayedAt=1000；清零则是回归 bug）
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val entry = vm.savedEntries.value.firstOrNull { it.id == treeA.toString() }
            if (entry != null && entry.lastPlayedAt == 1_000L) {
                assertEquals(1_000L, entry.lastPlayedAt)
                return
            }
            Thread.sleep(50)
        }
        org.junit.Assert.fail("扫描 replace 后 lastPlayedAt 被清零（正在播放漂移成尚未播放）")
    }

    @Test
    fun scanFolder_persistsSavedFolders() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        assertEquals(listOf(treeA.toString()), vm.savedFolders.value.map { it.toString() })
        // 新 ViewModel（模拟重启）应读到持久化的固化文件夹
        val vm2 = newViewModel()
        assertEquals(listOf(treeA.toString()), vm2.savedFolders.value.map { it.toString() })
    }

    @Test
    fun saveMultipleFolders_doNotOverwrite() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        vm.exitFolderView()
        vm.saveFolder(treeB)
        // 两个都保留，最新保存的在前
        val saved = vm.savedFolders.value.map { it.toString() }
        assertEquals(2, saved.size)
        assertTrue(saved.contains(treeA.toString()))
        assertTrue(saved.contains(treeB.toString()))
        // 新 ViewModel（模拟重启）两者仍在
        val vm2 = newViewModel()
        val saved2 = vm2.savedFolders.value.map { it.toString() }
        assertEquals(2, saved2.size)
        assertTrue(saved2.contains(treeA.toString()))
        assertTrue(saved2.contains(treeB.toString()))
    }

    @Test
    fun saveDuplicateFolder_noDuplication() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        vm.saveFolder(treeA) // 重复保存
        assertEquals(1, vm.savedFolders.value.size)
    }

    @Test
    fun clearOneFolder_keepsOthers() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        vm.exitFolderView()
        vm.saveFolder(treeB)
        vm.clearSavedFolder(treeA)
        assertEquals(listOf(treeB.toString()), vm.savedFolders.value.map { it.toString() })
        // 新 ViewModel（模拟重启）确认只剩 B
        val vm2 = newViewModel()
        assertEquals(listOf(treeB.toString()), vm2.savedFolders.value.map { it.toString() })
    }

    @Test
    fun exitFolderView_clearsState() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        vm.exitFolderView()
        assertFalse(vm.inFolderView.value)
        assertTrue(vm.folderItems.value.isEmpty())
        assertEquals(null, vm.currentFolderUri.value)
    }

    /** 回归：点击底部"正在播放"按钮静默切回所属文件夹（showSheet=false 进入且不触发播放详情） */
    @Test
    fun openSavedEntry_showSheetFalse_entersFolderViewWithoutSheet() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        vm.exitFolderView()

        val entry = vm.savedEntryById(treeA.toString())
        org.junit.Assert.assertNotNull("已固化条目应可通过 id 找到", entry)
        vm.openSavedEntry(entry!!, showSheet = false)

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (vm.inFolderView.value) break
            Thread.sleep(50)
        }
        assertTrue("静默进入应切到条目视图", vm.inFolderView.value)
        assertEquals(treeA, vm.currentFolderUri.value)
        assertFalse("showSheet=false 不应触发播放详情弹出", vm.requestPlayerSheet.value)
    }

    /** UI 回归：点击"← 返回"必须退出条目视图 */
    @Test
    fun backButtonClick_exitsFolderView() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        composeRule.setContent { DirectoryScreen(vm) }
        composeRule.onNodeWithContentDescription("返回").assertExists()
        composeRule.onNodeWithText("目录内未发现可播放音频").assertExists()

        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("目录内未发现可播放音频").assertDoesNotExist()
        assertFalse(vm.inFolderView.value)
    }

    /** UI 回归：系统返回键（安卓返回）应关闭条目视图而非直接退出 App */
    @Test
    fun systemBack_closesFolderView() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        composeRule.setContent { DirectoryScreen(vm) }
        composeRule.onNodeWithText("目录内未发现可播放音频").assertExists()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertFalse("系统返回键应退出条目视图", vm.inFolderView.value)
    }

    /** UI 回归：主界面专辑行（专辑名=文件夹名兜底）点击后进入专辑视图（db 加载，不重扫） */
    @Test
    fun savedFolderEntry_click_entersFolderView() {
        // Robolectric 无真实 DocumentsProvider，扫描返回空；直接注入条目+索引（专辑数据路径）
        runBlocking {
            db.dao().insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = treeA.toString(), displayName = "Download", uri = treeA.toString(),
                        sourceType = "LOCAL", coverUri = null, fileCount = 1, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 0L, sortOrder = 0
                    )
                )
            )
            db.dao().insertEntryIndex(
                listOf(
                    EntryIndexEntity(
                        treeA.toString(), "歌", "uri://song1", null, null,
                        null, null, 1000L, "歌.mp3"
                    )
                )
            )
        }
        val vm = newViewModel()
        composeRule.setContent { DirectoryScreen(vm) }
        // 前序测试的 viewModelScope 协程（Robolectric 不调 onCleared）会活锁 Main 队列，
        // 先手动推进 Main 调度，确保 refreshAlbums 的 launch 得到执行
        // refreshAlbums 由组合帧的 LaunchedEffect 触发，且前序测试遗留的 viewModelScope
        // 协程会占用 Main 队列；waitUntil 每轮先推进 Main 调度再判条件
        composeRule.waitUntil(timeoutMillis = 30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.neverAlbums.value.isNotEmpty() || vm.recentAlbums.value.isNotEmpty()
        }
        composeRule.onNodeWithText("尚未播放").assertExists()
        composeRule.onNodeWithText("Download").assertExists()

        composeRule.onNodeWithText("Download").performClick()

        assertTrue(vm.inFolderView.value)
        assertTrue("点击专辑应打开专辑视图", vm.currentAlbum.value?.album == "Download")
        composeRule.onNodeWithContentDescription("返回").assertExists()
    }

    /** UI 回归：文件夹管理页（设置顶层）列举多个条目、删除一个不影响其余 */
    @Test
    fun multipleSavedEntries_deleteOne_keepsOther() {
        val vm = newViewModel()
        vm.scanFolder(treeA)
        vm.exitFolderView()
        vm.saveFolder(treeB)
        composeRule.setContent { FolderManageScreen(vm, onBack = {}) }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.savedEntries.value.size >= 2
        }
        composeRule.onNodeWithText("文件夹管理").assertExists()
        composeRule.onNodeWithText("Download").assertExists()
        composeRule.onNodeWithText("Music").assertExists()

        composeRule.onNodeWithContentDescription("删除 Download").performClick()
        // v1.7.0：删除需二次确认
        composeRule.onNodeWithText("删除确认").assertExists()
        composeRule.onNodeWithText("删除").performClick()

        assertTrue(vm.savedFolders.value.map { it.toString() } == listOf(treeB.toString()))
        composeRule.onNodeWithText("Music").assertExists()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
    }
}
