package com.booklistener.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlayRecord::class,
        FavoriteItem::class,
        CacheIndex::class,
        SavedEntryEntity::class,
        FolderProgressEntity::class,
        EntryIndexEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1.6.0：v1 → v2 仅新增三张业务 Store 表，现有表不动 */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                    "CREATE TABLE IF NOT EXISTS `folder_progress` (" +
                        "`folderUri` TEXT NOT NULL PRIMARY KEY, " +
                        "`trackIndex` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `entry_index` (" +
                        "`entryId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "PRIMARY KEY(`entryId`, `uri`))"
                )
            }
        }

        /**
         * v1.7.0：v2 → v3 仅给 entry_index 追加媒体信息列（专辑/作者/音轨/碟号/时长/原始文件名）。
         * rawName 旧数据回填 displayName（旧索引无原始文件名，近似；下次扫描覆盖）；
         * album 不在此回填——旧索引 displayName 可能是标题或文件名，无法推导专辑，
         * 查询侧统一按 saved_entry.displayName 兜底（文件夹名），下次扫描写入真实 album。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `artist` TEXT")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `track` INTEGER")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `disc` INTEGER")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `durationMs` INTEGER")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `rawName` TEXT")
                db.execSQL("UPDATE `entry_index` SET `rawName` = `displayName` WHERE `rawName` IS NULL")
            }
        }

        fun getInstance(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val ins = Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "music_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = ins
                ins
            }
        }
    }
}
