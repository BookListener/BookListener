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
 * v2.2.0 v5→v6 迁移测试：
 * 手建 v5 库（含 v5 全部表 + cue_sheets + 老数据）→ Room version 6 执行 MIGRATION_5_6 →
 * 验证 entry_index 新增 relativePath/error 两列（默认 NULL）、老数据不丢、DAO 可读写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration56Test {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun createV5Schema(db: SupportSQLiteDatabase) {
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
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cue_sheets (cueUri TEXT NOT NULL PRIMARY KEY, " +
                "entryId TEXT NOT NULL, audioUri TEXT, workId INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cue_sheets_entryId ON cue_sheets(entryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cue_sheets_workId ON cue_sheets(workId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cue_sheets_audioUri ON cue_sheets(audioUri)")
    }

    private fun seedV5Data(db: SupportSQLiteDatabase) {
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
        db.execSQL(
            "INSERT INTO cue_sheets (cueUri, entryId, audioUri, workId) " +
                "VALUES ('cue://album.cue','e1','uri://a1',1)"
        )
    }

    private fun buildV5Db(name: String) {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createV5Schema(db)
                    seedV5Data(db)
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
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migration_addsColumns_keepsOldData() {
        buildV5Db("migration_v6_struct.db")
        val room = openRoom("migration_v6_struct.db")
        val db = room.openHelper.writableDatabase

        // 新增两列
        db.query("PRAGMA table_info(entry_index)").use { c ->
            val cols = mutableListOf<String>()
            while (c.moveToNext()) cols.add(c.getString(1))
            assertTrue("缺 relativePath", "relativePath" in cols)
            assertTrue("缺 error", "error" in cols)
        }
        // 老数据的新列默认 NULL
        db.query("SELECT relativePath, error FROM entry_index WHERE uri='uri://a1'").use { c ->
            c.moveToFirst()
            assertNull(c.getString(0))
            assertNull(c.getString(1))
        }
        // 老数据不丢
        db.query("SELECT COUNT(*) FROM entry_index").use { c ->
            c.moveToFirst()
            assertEquals(2L, c.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM cue_sheets").use { c ->
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
        }
        room.close()
    }

    @Test
    fun migration_daoRoundTrip() = kotlinx.coroutines.runBlocking {
        buildV5Db("migration_v6_dao.db")
        val room = openRoom("migration_v6_dao.db")
        val dao = room.dao()

        // 写入带 error/relativePath 的行
        val rows = dao.getEntryIndex("e1").toMutableList()
        dao.deleteEntryIndex("e1")
        dao.insertEntryIndex(
            rows.map { it.copy(relativePath = "CD1", error = "tag parse failed") }
        )
        val reloaded = dao.getEntryIndex("e1")
        assertEquals(2, reloaded.size)
        assertEquals("CD1", reloaded.first().relativePath)
        assertEquals("tag parse failed", reloaded.first().error)

        // 按目录筛选
        val cd1 = dao.getEntryIndexByDir("e1", "CD1")
        assertEquals(2, cd1.size)
        val root = dao.getEntryIndexByDir("e1", "")
        assertEquals(0, root.size)

        room.close()
    }
}
