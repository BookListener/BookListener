package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import com.booklistener.db.EntryIndexEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7.0 v2→v3 迁移测试（沿用 v1→v2 已验证的 FrameworkSQLiteOpenHelper 模式）：
 * 手建 v2 库（saved_entry + entry_index 3 列）→ Room version 3 打开执行 MIGRATION_2_3 →
 * 验证 6 新列存在、rawName 回填 displayName、新列可写可查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration23Test {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun createV2Schema(db: SupportSQLiteDatabase) {
        // v1 旧表（Room 校验需要全部实体表存在）
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
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`displayName` TEXT NOT NULL, " +
                "`uri` TEXT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, " +
                "`coverUri` TEXT, " +
                "`fileCount` INTEGER NOT NULL, " +
                "`lastPlayedAt` INTEGER NOT NULL, " +
                "`artist` TEXT, " +
                "`lastOpenedAt` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `entry_index` (" +
                "`entryId` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`uri` TEXT NOT NULL, " +
                "PRIMARY KEY(`entryId`, `uri`))"
        )
        db.execSQL(
            "INSERT INTO entry_index (entryId, displayName, uri) VALUES ('e1', '旧歌名', 'uri://old1')"
        )
    }

    private fun buildV2Db(name: String) {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = createV2Schema(db)
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
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migration_addsSixColumnsAndBackfillsRawName() {
        buildV2Db("migration_v2_cols.db")
        val room = openRoom("migration_v2_cols.db")
        val cols = mutableSetOf<String>()
        room.openHelper.writableDatabase.query("PRAGMA table_info(entry_index)").use { c ->
            while (c.moveToNext()) cols.add(c.getString(1))
        }
        listOf("album", "artist", "track", "disc", "durationMs", "rawName").forEach { c ->
            assertTrue("缺少列 $c", c in cols)
        }
        room.openHelper.writableDatabase.query(
            "SELECT rawName, displayName FROM entry_index WHERE uri='uri://old1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("旧歌名", c.getString(0))
            assertEquals("旧歌名", c.getString(1))
        }
        room.close()
    }

    @Test
    fun migration_newColumnsWritableAndQueriable() {
        buildV2Db("migration_v2_write.db")
        val room = openRoom("migration_v2_write.db")
        val dao = room.dao()
        runBlocking {
            dao.insertEntryIndex(
                listOf(
                    EntryIndexEntity(
                        "e2", "新歌", "uri://new1", "专辑乙", "作者乙",
                        3, 1, 120_000L, "新歌.flac"
                    )
                )
            )
        }
        val row = runBlocking { dao.getAllEntryIndex() }.first { it.uri == "uri://new1" }
        assertEquals("专辑乙", row.album)
        assertEquals("作者乙", row.artist)
        assertEquals(3, row.track)
        assertEquals(1, row.disc)
        assertEquals(120_000L, row.durationMs)
        assertEquals("新歌.flac", row.rawName)
        room.close()
    }
}
