package com.booklistener.ui

import com.booklistener.auth.CredentialStore
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专辑内标题显示模式（v1.6.3）：标签标题 ↔ 原始文件名，持久化到 prefs。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumTitleModeTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    private fun clearPrefs() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultMode_isTagTitle() {
        clearPrefs()
        assertFalse("默认应为标签标题模式（useRawName=false）", newViewModel().albumTitleMode.value)
    }

    @Test
    fun switchToRawName_persistsAcrossInstances() {
        clearPrefs()
        val vm = newViewModel()
        vm.setAlbumTitleMode(useRawName = true)
        assertTrue("切换后应为文件名模式（useRawName=true）", vm.albumTitleMode.value)
        // 新实例（模拟重启）仍保持
        assertTrue("模式应持久化", newViewModel().albumTitleMode.value)
    }

    @Test
    fun switchBackToTagTitle_persists() {
        clearPrefs()
        val vm = newViewModel()
        vm.setAlbumTitleMode(useRawName = true)
        vm.setAlbumTitleMode(useRawName = false)
        assertFalse("切回后应为标签标题模式", newViewModel().albumTitleMode.value)
    }
}
