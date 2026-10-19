package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import com.booklistener.db.CueSheetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v5.0 v4→v5 迁移测试（沿用 Migration34Test 的 FrameworkSQLiteOpenHelper 模式）：
 * 手建 v4 库（8 表 + 老数据）→ Room version 5 执行 MIGRATION_4_5 →
 * 验证 cue_sheets 结构与索引、旧表完好、老数据不丢、DAO 可读写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration45Test {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun createV4Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS play_record (itemId TEXT NOT NULL PRIMARY KEY, " +
                "lastPositionMs INTEGER NOT NULL, lastPlayTime INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS favorite (itemId TEXT NOT NULL PRIMARY KEY, " +
                "addTime INTEGER NOT NULL, workId INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_workId ON favorite(workId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cloud_cache (itemId TEXT NOT NULL PRIMARY KEY, " +
                "localFilePath TEXT NOT NULL, fileSize INTEGER NOT NULL, lastAccess INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS saved_entry (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, " +
                "uri TEXT NOT NULL, sourceType TEXT NOT NULL, coverUri TEXT, fileCount INTEGER NOT NULL, " +
                "lastPlayedAt INTEGER NOT NULL, artist TEXT, lastOpenedAt INTEGER NOT NULL, sortOrder INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS folder_progress (folderUri TEXT NOT NULL PRIMARY KEY, " +
                "trackIndex INTEGER NOT NULL, positionMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS entry_index (entryId TEXT NOT NULL, displayName TEXT NOT NULL, " +
                "uri TEXT NOT NULL, album TEXT, artist TEXT, track INTEGER, disc INTEGER, durationMs INTEGER, " +
                "rawName TEXT, workId INTEGER, sortRank INTEGER, sizeBytes INTEGER, albumArtist TEXT, " +
                "PRIMARY KEY(entryId, uri))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_entry_index_workId ON entry_index(workId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_entry_index_entryId_workId ON entry_index(entryId, workId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_entry_index_entryId_sortRank ON entry_index(entryId, sortRank)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS works (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, key TEXT NOT NULL, " +
                "title TEXT NOT NULL, albumArtist TEXT, sourceScope TEXT NOT NULL, coverUri TEXT, " +
                "songCount INTEGER NOT NULL, totalDurationMs INTEGER NOT NULL, " +
                "lastPlayedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_works_key ON works(key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_works_lastPlayedAt ON works(lastPlayedAt)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS work_progress (workId INTEGER NOT NULL PRIMARY KEY, fileUri TEXT NOT NULL, " +
                "orderIndex INTEGER NOT NULL, matchMode INTEGER NOT NULL, positionMs INTEGER NOT NULL, " +
                "speed REAL NOT NULL, updatedAt INTEGER NOT NULL)"
        )
    }

    private fun seedV4Data(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO saved_entry (id, displayName, uri, sourceType, coverUri, fileCount, lastPlayedAt, artist, lastOpenedAt, sortOrder) " +
                "VALUES ('e1','某专辑文件夹','e1','LOCAL',NULL,2,500,'某作者',1,0)"
        )
        db.execSQL(
            "INSERT INTO entry_index (entryId, displayName, uri, album, artist, track, disc, durationMs, rawName, workId, sortRank) VALUES " +
                "('e1','歌一','uri://a1','专辑甲','某作者',1,1,60000,'01.flac',1,0), " +
                "('e1','歌二','uri://a2','专辑甲','某作者',2,1,90000,'02.flac',1,1)"
        )
        db.execSQL(
            "INSERT INTO works (id, key, title, albumArtist, sourceScope, coverUri, songCount, totalDurationMs, lastPlayedAt, updatedAt) " +
                "VALUES (1,'local:某作者|专辑甲','专辑甲','某作者','LOCAL',NULL,2,150000,500,1)"
        )
        db.execSQL(
            "INSERT INTO work_progress (workId, fileUri, orderIndex, matchMode, positionMs, speed, updatedAt) " +
                "VALUES (1,'uri://a2',1,1,45000,1.0,900)"
        )
    }

    private fun buildV4Db(name: String) {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createV4Schema(db)
                    seedV4Data(db)
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
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migration_createsCueSheets_keepsOldData() {
        buildV4Db("migration_v5_struct.db")
        val room = openRoom("migration_v5_struct.db")
        val db = room.openHelper.writableDatabase

        // cue_sheets 表与列
        db.query("PRAGMA table_info(cue_sheets)").use { c ->
            val cols = mutableListOf<String>()
            while (c.moveToNext()) cols.add(c.getString(1))
            listOf("cueUri", "entryId", "audioUri", "workId").forEach {
                assertTrue("cue_sheets 缺列 $it", it in cols)
            }
        }
        // cue_sheets 索引
        db.query("PRAGMA index_list(cue_sheets)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names.add(c.getString(1))
            assertTrue("缺 entryId 索引", names.any { it.contains("entryId") })
            assertTrue("缺 workId 索引", names.any { it.contains("workId") })
        }
        // 旧表保留 + 老数据不丢
        db.query("SELECT COUNT(*) FROM works").use { c ->
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
        }
        db.query("SELECT positionMs FROM work_progress WHERE workId=1").use { c ->
            c.moveToFirst()
            assertEquals(45_000L, c.getLong(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='folder_progress'").use { c ->
            assertTrue(c.moveToFirst())
        }
        room.close()
    }

    @Test
    fun migration_daoRoundTrip() = kotlinx.coroutines.runBlocking {
        buildV4Db("migration_v5_dao.db")
        val room = openRoom("migration_v5_dao.db")
        val dao = room.dao()

        dao.upsertCueSheet(CueSheetEntity(cueUri = "cue://album.cue", entryId = "e1", audioUri = "uri://a1", workId = 1L))
        val sheet = dao.getCueSheetByUri("cue://album.cue")
        assertEquals("e1", sheet?.entryId)
        assertEquals("uri://a1", sheet?.audioUri)
        assertEquals(1L, sheet?.workId)

        // replaceCueSheets 整体替换语义
        dao.replaceCueSheets(
            "e1",
            listOf(CueSheetEntity("cue://x.cue", "e1", null, null))
        )
        assertEquals(1, dao.getCueSheetsByEntry("e1").size)
        assertEquals(null, dao.getCueSheetByUri("cue://album.cue"))
        room.close()
    }
}
