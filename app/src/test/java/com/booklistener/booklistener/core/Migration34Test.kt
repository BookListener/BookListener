package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v4.0 v3→v4 迁移测试（沿用 Migration23Test 的 FrameworkSQLiteOpenHelper 模式）：
 * 手建 v3 库（含老数据）→ Room version 4 打开执行 MIGRATION_3_4 →
 * 验证结构（works/work_progress/cloud_cache/新列/老表保留）与四步回填结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration34Test {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun createV3Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS play_record (itemId TEXT NOT NULL PRIMARY KEY, " +
                "lastPositionMs INTEGER NOT NULL, lastPlayTime INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS favorite (itemId TEXT NOT NULL PRIMARY KEY, " +
                "addTime INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cache_index (itemId TEXT NOT NULL PRIMARY KEY, " +
                "localFilePath TEXT NOT NULL, fileSize INTEGER NOT NULL, lastAccess INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `folder_progress` (" +
                "`folderUri` TEXT NOT NULL PRIMARY KEY, " +
                "`trackIndex` INTEGER NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_entry` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `displayName` TEXT NOT NULL, `uri` TEXT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `coverUri` TEXT, `fileCount` INTEGER NOT NULL, " +
                "`lastPlayedAt` INTEGER NOT NULL, `artist` TEXT, `lastOpenedAt` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `entry_index` (" +
                "`entryId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `uri` TEXT NOT NULL, " +
                "`album` TEXT, `artist` TEXT, `track` INTEGER, `disc` INTEGER, " +
                "`durationMs` INTEGER, `rawName` TEXT, " +
                "PRIMARY KEY(`entryId`, `uri`))"
        )
    }

    private fun seedV3Data(db: SupportSQLiteDatabase) {
        // 两个条目，各自含同名专辑"专辑甲"（跨条目合并验证）+ 一个无 album 曲目
        db.execSQL(
            "INSERT INTO saved_entry (id, displayName, uri, sourceType, coverUri, fileCount, lastPlayedAt, artist, lastOpenedAt, sortOrder) " +
                "VALUES ('e1','文件夹A','e1','LOCAL',NULL,2,500,NULL,1,0), " +
                "('e2','文件夹B','e2','LOCAL',NULL,1,100,NULL,1,1)"
        )
        db.execSQL(
            "INSERT INTO entry_index (entryId, displayName, uri, album, artist, track, disc, durationMs, rawName) VALUES " +
                "('e1','歌一','uri://a1','专辑甲','作者',1,1,60000,'01.mp3'), " +
                "('e1','歌二','uri://a2','专辑甲','作者',2,1,90000,'02.mp3'), " +
                "('e1','歌三','uri://a3',NULL,'作者',NULL,NULL,30000,'03.mp3'), " +
                "('e2','歌四','uri://b1','专辑甲','作者',1,1,10000,'04.mp3')"
        )
        // 老进度：e1 的专辑甲 → 歌二（下标 1）
        db.execSQL(
            "INSERT INTO folder_progress (folderUri, trackIndex, positionMs, updatedAt) " +
                "VALUES ('e1', 1, 45000, 900)"
        )
        // 收藏（itemId 是 AudioItem.id，老数据不映射 uri → 预期 NULL）
        db.execSQL("INSERT INTO favorite (itemId, addTime) VALUES ('fav-old', 1)")
        // 缓存搬家数据
        db.execSQL(
            "INSERT INTO cache_index (itemId, localFilePath, fileSize, lastAccess) " +
                "VALUES ('c1', '/cache/1.mp3', 1024, 111)"
        )
    }

    private fun buildV3Db(name: String) {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createV3Schema(db)
                    seedV3Data(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        helper.create(config).writableDatabase
    }

    private fun openRoom(name: String): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migration_createsStructuresAndMovesCache() {
        buildV3Db("migration_v4_struct.db")
        val room = openRoom("migration_v4_struct.db")
        val db = room.openHelper.writableDatabase

        // works 表 + key 唯一索引
        db.query("PRAGMA table_info(works)").use { c ->
            val cols = mutableListOf<String>()
            while (c.moveToNext()) cols.add(c.getString(1))
            listOf("id", "key", "title", "albumArtist", "sourceScope", "songCount", "totalDurationMs", "lastPlayedAt", "updatedAt")
                .forEach { assertTrue("works 缺列 $it", it in cols) }
        }
        // work_progress 表
        db.query("PRAGMA table_info(work_progress)").use { c ->
            assertTrue(c.moveToFirst()) // 至少有一列
        }
        // cloud_cache 搬家成功 + cache_index 已删
        db.query("SELECT itemId, fileSize FROM cloud_cache WHERE itemId='c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1024L, c.getLong(1))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cache_index'").use { c ->
            assertTrue(!c.moveToFirst())
        }
        // entry_index 新列
        db.query("PRAGMA table_info(entry_index)").use { c ->
            val cols = mutableListOf<String>()
            while (c.moveToNext()) cols.add(c.getString(1))
            listOf("workId", "sortRank", "sizeBytes", "albumArtist").forEach { assertTrue("entry_index 缺列 $it", it in cols) }
        }
        // 老表保留（回滚能力）
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='folder_progress'").use { c ->
            assertTrue(c.moveToFirst())
        }
        room.close()
    }

    @Test
    fun migration_backfillsWorksAndWorkIds() {
        buildV3Db("migration_v4_backfill.db")
        val room = openRoom("migration_v4_backfill.db")
        val dao = room.dao()
        val db = room.openHelper.writableDatabase

        // 作品数：专辑甲（跨条目合并 1 个）+ 文件夹A 兜底 1 个 = 2
        db.query("SELECT COUNT(*) FROM works").use { c ->
            c.moveToFirst()
            assertEquals(2L, c.getLong(0))
        }
        // 专辑甲合并：songCount=3、时长 160s、lastPlayedAt=MAX(e1=500,e2=100)=500
        db.query("SELECT songCount, totalDurationMs, lastPlayedAt FROM works WHERE title='专辑甲'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
            assertEquals(160_000L, c.getLong(1))
            assertEquals(500L, c.getLong(2))
        }
        // entry_index.workId 已回填：uri://a1 与 uri://b1 同作品
        db.query(
            "SELECT workId FROM entry_index WHERE uri='uri://a1'"
        ).use { c ->
            c.moveToFirst()
            val idA = c.getLong(0)
            db.query("SELECT workId FROM entry_index WHERE uri='uri://b1'").use { c2 ->
                c2.moveToFirst()
                assertEquals(idA, c2.getLong(0))
            }
        }
        // sortRank 已回填：条目 e1 内 歌一=0
        db.query("SELECT sortRank FROM entry_index WHERE uri='uri://a1'").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        room.close()
    }

    @Test
    fun migration_backfillsProgressAndFavorite() {
        buildV3Db("migration_v4_progress.db")
        val room = openRoom("migration_v4_progress.db")
        val dao = room.dao()
        val db = room.openHelper.writableDatabase

        // 老进度 e1(trackIndex=1) → 专辑甲 的 uri://a2（歌二），matchMode=0
        db.query("SELECT wp.fileUri, wp.orderIndex, wp.matchMode, wp.positionMs FROM work_progress wp JOIN works w ON w.id=wp.workId WHERE w.title='专辑甲'").use { c ->
            assertTrue("应有 1 条作品进度", c.moveToFirst())
            assertEquals("uri://a2", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(0, c.getInt(2))
            assertEquals(45_000L, c.getLong(3))
        }
        // favorite.workId：老 itemId 映射不到 → NULL
        db.query("SELECT workId FROM favorite WHERE itemId='fav-old'").use { c ->
            c.moveToFirst()
            assertTrue(c.isNull(0))
        }
        room.close()
    }
}
