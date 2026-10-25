package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import com.booklistener.db.WorkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CoverDirMigration 封面目录迁移测试（v2.1.26）：
 * 内嵌封面提取产物 cache/covers → files/covers，works.coverUri 前缀改写；
 * 顺序保证：复制 → 改库 → 删旧目录（任一失败不删旧，不悬空）；无旧目录跳过；幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverDirMigrationTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // 清掉真实目录残留，保证用例独立
        CacheDirs.coverCacheDir(ctx).deleteRecursively()
        CacheDirs.coverFilesDir(ctx).deleteRecursively()
        ctx.getSharedPreferences("saved_folders", Context.MODE_PRIVATE)
            .edit().remove("cover_dir_migration_v1_done").commit()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    private fun seedLegacyCover(name: String, content: ByteArray) {
        val dir = CacheDirs.coverCacheDir(ctx).apply { mkdirs() }
        File(dir, name).writeBytes(content)
    }

    private fun seedWork(key: String, coverUri: String) {
        kotlinx.coroutines.runBlocking {
            db.dao().insertWork(
                WorkEntity(
                    key = key, title = "t", albumArtist = null, sourceScope = "local",
                    coverUri = coverUri, songCount = 1, totalDurationMs = 1000L,
                    lastPlayedAt = 0L, updatedAt = 0L
                )
            )
        }
    }

    private fun workCover(key: String): String? =
        kotlinx.coroutines.runBlocking { db.dao().getWorkByKey(key) }?.coverUri

    @Test
    fun legacyCacheMigrated_filesMoved_prefixRewritten_oldDirDeleted() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        seedLegacyCover("abc.jpg", bytes)
        seedWork("k1", "file:///data/user/0/com.booklistener/cache/covers/abc.jpg")
        seedWork("k2", "file:///data/user/0/com.booklistener/cache/covers/def.jpg") // 文件不存在：前缀仍改写（库一致性），显示按需

        CoverDirMigration.run(ctx, db)

        val dst = File(CacheDirs.coverFilesDir(ctx), "abc.jpg")
        assertTrue("文件应复制到 files/covers", dst.exists())
        assertTrue("内容一致", dst.readBytes().contentEquals(bytes))
        assertFalse("旧目录应删除", CacheDirs.coverCacheDir(ctx).exists())

        val covers = mapOf("k1" to workCover("k1"), "k2" to workCover("k2"))
        assertEquals(
            "file:///data/user/0/com.booklistener/files/covers/abc.jpg",
            covers["k1"]
        )
        assertEquals(
            "file:///data/user/0/com.booklistener/files/covers/def.jpg",
            covers["k2"]
        )
    }

    @Test
    fun noLegacyDir_flagSet_dbUntouched() {
        seedWork("k1", "file:///data/user/0/com.booklistener/cache/covers/abc.jpg")
        val dbBefore = workCover("k1")

        CoverDirMigration.run(ctx, db)

        assertTrue(
            "无旧产物也应置标记（下次跳过）",
            ctx.getSharedPreferences("saved_folders", Context.MODE_PRIVATE)
                .getBoolean("cover_dir_migration_v1_done", false)
        )
        assertEquals("DB 不动", dbBefore, workCover("k1"))
        assertFalse(CacheDirs.coverFilesDir(ctx).exists())
    }

    @Test
    fun idempotent_flagSet_secondRunSkips() {
        seedLegacyCover("abc.jpg", byteArrayOf(9))
        seedWork("k1", "file:///data/user/0/com.booklistener/cache/covers/abc.jpg")

        CoverDirMigration.run(ctx, db)
        val movedName = CacheDirs.coverFilesDir(ctx).listFiles()?.singleOrNull()?.name

        // 标记已置；再跑一次（模拟二次启动）不应重复搬移/改写/报错
        CoverDirMigration.run(ctx, db)

        assertEquals("abc.jpg", movedName)
        assertEquals(
            "file:///data/user/0/com.booklistener/files/covers/abc.jpg",
            workCover("k1")
        )
    }

    @Test
    fun copyFailure_keepsLegacyDir_noHangingUri() {
        // 构造"目标目录不可创建"场景：files/covers 位置被同名普通文件占据 → mkdirs 失败 → 迁移中止
        seedLegacyCover("abc.jpg", byteArrayOf(1))
        val coversPath = CacheDirs.coverFilesDir(ctx)
        coversPath.parentFile?.mkdirs()
        coversPath.writeBytes(byteArrayOf(7)) // covers 以普通文件存在，阻塞目录创建
        seedWork("k1", "file:///data/user/0/com.booklistener/cache/covers/abc.jpg")

        CoverDirMigration.run(ctx, db)

        // 目标不可建 → 不置标记、不改库、不删旧目录（旧 uri 仍指向旧位置，显示不受影响）
        assertTrue("旧目录保留", CacheDirs.coverCacheDir(ctx).exists())
        assertFalse(
            "失败不置标记",
            ctx.getSharedPreferences("saved_folders", Context.MODE_PRIVATE)
                .getBoolean("cover_dir_migration_v1_done", false)
        )
        assertEquals(
            "DB 不改写",
            "file:///data/user/0/com.booklistener/cache/covers/abc.jpg",
            workCover("k1")
        )
    }
}
