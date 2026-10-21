package com.booklistener.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * StoreMigration 首启迁移测试（v1.6.0）：
 * 覆盖新格式 saved_entries_json / 旧 legacy（单 Uri、Uri 数组）/ folder_progress /
 * entry_index 全部搬运；幂等（标记后不重搬）；不删 prefs（回滚安全）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreMigrationTest {

    private lateinit var ctx: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var db: AppDatabase

    private val treeA = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private val treeB = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        prefs = ctx.getSharedPreferences("migration_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @Test
    fun emptyPrefs_nothingMigrated_flagSet() {
        StoreMigration.run(prefs, db)
        assertTrue(prefs.getBoolean("store_migration_v1_done", false))
        assertEquals(0, kotlinx.coroutines.runBlocking { db.dao().countSavedEntries() })
    }

    @Test
    fun legacySingleUri_migratesToLocalEntry() {
        prefs.edit().putString("saved_folder_uri", treeA).commit()
        StoreMigration.run(prefs, db)
        val entries = SavedEntryStore(db.dao()).load()
        assertEquals(1, entries.size)
        assertEquals(treeA, entries[0].id)
        assertEquals(SourceType.LOCAL, entries[0].sourceType)
        // 不删 prefs：回滚安全
        assertEquals(treeA, prefs.getString("saved_folder_uri", null))
    }

    @Test
    fun legacyArrayAndSingle_mergedWithoutDuplicate() {
        prefs.edit().putString("saved_folder_uri", treeA).commit()
        prefs.edit().putString("saved_folders_json", JSONArray().apply { put(treeA); put(treeB) }.toString()).commit()
        StoreMigration.run(prefs, db)
        assertEquals(setOf(treeA, treeB), SavedEntryStore(db.dao()).load().map { it.id }.toSet())
    }

    @Test
    fun newFormatJson_migratesWithFields() {
        val obj = JSONObject().apply {
            put("id", treeA)
            put("displayName", "Download")
            put("uri", treeA)
            put("sourceType", "LOCAL")
            put("fileCount", 5)
            put("lastPlayedAt", 123L)
            put("lastOpenedAt", 456L)
        }
        prefs.edit().putString("saved_entries_json", JSONArray().put(obj).toString()).commit()
        StoreMigration.run(prefs, db)
        val e = SavedEntryStore(db.dao()).load()[0]
        assertEquals("Download", e.displayName)
        assertEquals(5, e.fileCount)
        assertEquals(123L, e.lastPlayedAt)
        assertEquals(456L, e.lastOpenedAt)
    }

    @Test
    fun folderProgress_migrates() {
        prefs.edit().putString(
            "folder_progress_json",
            JSONArray().apply {
                put(JSONObject().apply {
                    put("folderUri", treeA)
                    put("trackIndex", 3)
                    put("positionMs", 99_000L)
                    put("updatedAt", 1L)
                })
            }.toString()
        ).commit()
        StoreMigration.run(prefs, db)
        // v4.0：老 folder_progress 停写保留（回滚触发器），迁移验证直接查老表
        val rec = kotlinx.coroutines.runBlocking { db.dao().getFolderProgress(treeA) }
        assertEquals(3, rec?.trackIndex)
        assertEquals(99_000L, rec?.positionMs)
    }

    @Test
    fun entryIndex_migrates() {
        val files = JSONArray().apply {
            put(JSONObject().apply { put("displayName", "a.mp3"); put("uri", "content://x/1") })
            put(JSONObject().apply { put("displayName", "b.flac"); put("uri", "content://x/2") })
        }
        prefs.edit().putString(
            "saved_entries_index_json",
            JSONArray().apply {
                put(JSONObject().apply { put("entryId", "e1"); put("files", files) })
            }.toString()
        ).commit()
        StoreMigration.run(prefs, db)
        val index = EntryIndexStore(db.dao()).load()
        assertEquals(2, index["e1"]?.size)
        assertEquals("a.mp3", index["e1"]?.get(0)?.displayName)
    }

    @Test
    fun secondRun_idempotent_noDuplicates() {
        prefs.edit().putString("saved_folder_uri", treeA).commit()
        StoreMigration.run(prefs, db)
        // 表已有数据：二次运行跳过搬运
        StoreMigration.run(prefs, db)
        assertEquals(1, kotlinx.coroutines.runBlocking { db.dao().countSavedEntries() })
    }

    @Test
    fun flagSet_skipsEvenIfTableEmpty() {
        prefs.edit().putString("saved_folder_uri", treeA).commit()
        prefs.edit().putBoolean("store_migration_v1_done", true).commit()
        StoreMigration.run(prefs, db)
        // 标记已置：不搬运（表空但信任标记，避免重复开销）
        assertEquals(0, kotlinx.coroutines.runBlocking { db.dao().countSavedEntries() })
    }

    // ---- v1→v2 真实迁移路径（补盲区：既有用例均为新建 v2 库，未覆盖老用户升级）----

    @Test
    fun migrateV1Db_v1toV2_newTablesCreated_oldTablesIntact() {
        // 1) 用 sqlite-framework 手建 v1 结构（三张旧表，无新表）
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration_v1_test.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV1Schema(db)
                }
                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        val v1Helper = helper.create(config)
        v1Helper.writableDatabase

        // 2) 用当前 Room 定义打开同一 db 文件：应走 MIGRATION_1_2 并成功
        val roomDb = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration_v1_test.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        // 触发 open（首个查询）
        kotlinx.coroutines.runBlocking { roomDb.dao().countSavedEntries() }

        // 3) 新表可查、旧表仍在且数据完整
        kotlinx.coroutines.runBlocking {
            roomDb.dao().upsertFolderProgress(
                com.booklistener.db.FolderProgressEntity("uri://x", 3, 12_000L, 1L)
            )
        }
        assertEquals(1, kotlinx.coroutines.runBlocking { roomDb.dao().getAllFolderProgress().size })
        // 旧表可查（v1 迁移后结构完整）
        assertEquals(0, kotlinx.coroutines.runBlocking { roomDb.dao().getAllCacheIndexList().size })
        roomDb.close()
    }

    @Test
    fun migrateV1Db_existingPlayRecord_survivesMigration() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration_v1_record.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV1Schema(db)
                    db.execSQL(
                        "INSERT INTO play_record(itemId, lastPositionMs, lastPlayTime) " +
                            "VALUES('song-a', 30000, 1000)"
                    )
                }
                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        helper.create(config).writableDatabase

        val roomDb = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration_v1_record.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking { roomDb.dao().countSavedEntries() }
        val record = kotlinx.coroutines.runBlocking { roomDb.dao().getPlayRecord("song-a") }
        assertEquals(30000L, record?.lastPositionMs)
        roomDb.close()
    }

    private fun createV1Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
    }
}
