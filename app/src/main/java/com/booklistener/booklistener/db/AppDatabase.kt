package com.booklistener.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.booklistener.core.FolderProgress
import com.booklistener.core.IndexedFile
import com.booklistener.core.WorkBackfill

@Database(
    entities = [
        PlayRecord::class,
        FavoriteItem::class,
        CacheIndex::class,
        SavedEntryEntity::class,
        FolderProgressEntity::class,
        EntryIndexEntity::class,
        WorkEntity::class,
        WorkProgressEntity::class,
        CueSheetEntity::class
    ],
    version = 6,
    exportSchema = true
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

        /**
         * v4.0（DESIGN-数据库重构-v4）：作品实体化 + 进度重构。
         * - entry_index +4 列（workId/sortRank/sizeBytes/albumArtist）；favorite +workId
         * - 新增 works / work_progress
         * - cache_index 搬家为 cloud_cache（minSdk 26 的 SQLite 3.18 不支持 RENAME，走建新表→搬数据→删旧表）
         * - 回填顺序（§5.2）：works（含 entry_index.workId）→ sortRank → 老进度 → favorite.workId
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // -- 1) 结构变更 --
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `workId` INTEGER")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `sortRank` INTEGER")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `sizeBytes` INTEGER")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `albumArtist` TEXT")
                db.execSQL("ALTER TABLE `favorite` ADD COLUMN `workId` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `works` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `key` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `albumArtist` TEXT, `sourceScope` TEXT NOT NULL, " +
                        "`coverUri` TEXT, `songCount` INTEGER NOT NULL, `totalDurationMs` INTEGER NOT NULL, " +
                        "`lastPlayedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_works_key` ON `works`(`key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_works_lastPlayedAt` ON `works`(`lastPlayedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `work_progress` (" +
                        "`workId` INTEGER NOT NULL PRIMARY KEY, `fileUri` TEXT NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, `matchMode` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, `speed` REAL NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                // -- 2) cache_index 搬家 --
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_cache` (" +
                        "`itemId` TEXT NOT NULL PRIMARY KEY, `localFilePath` TEXT NOT NULL, " +
                        "`fileSize` INTEGER NOT NULL, `lastAccess` INTEGER NOT NULL)"
                )
                db.execSQL("INSERT INTO `cloud_cache` SELECT `itemId`, `localFilePath`, `fileSize`, `lastAccess` FROM `cache_index`")
                db.execSQL("DROP TABLE `cache_index`")
                // -- 3) 索引 --
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entry_index_workId` ON `entry_index`(`workId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entry_index_entry_work` ON `entry_index`(`entryId`, `workId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entry_index_rank` ON `entry_index`(`entryId`, `sortRank`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorite_workId` ON `favorite`(`workId`)")
                // -- 4) 数据回填 --
                backfillWorks(db)
                backfillSortRank(db)
                backfillProgress(db)
                backfillFavorite(db)
            }

            /** 回填 works（跨条目同 key 合并）+ entry_index.workId */
            private fun backfillWorks(db: SupportSQLiteDatabase) {
                val rows = mutableListOf<Row>()
                db.query(
                    "SELECT i.entryId, i.uri, i.album, i.artist, i.durationMs, i.rawName, " +
                        "s.displayName, s.sourceType, s.lastPlayedAt " +
                        "FROM entry_index i JOIN saved_entry s ON s.id = i.entryId"
                ).use { c ->
                    val eIdx = c.getColumnIndex("entryId")
                    val uIdx = c.getColumnIndex("uri")
                    val aIdx = c.getColumnIndex("album")
                    val arIdx = c.getColumnIndex("artist")
                    val dIdx = c.getColumnIndex("durationMs")
                    val rIdx = c.getColumnIndex("rawName")
                    val nIdx = c.getColumnIndex("displayName")
                    val tIdx = c.getColumnIndex("sourceType")
                    val pIdx = c.getColumnIndex("lastPlayedAt")
                    while (c.moveToNext()) {
                        rows += Row(
                            entryId = c.getString(eIdx),
                            uri = c.getString(uIdx),
                            album = c.getString(aIdx),
                            artist = c.getString(arIdx),
                            durationMs = if (c.isNull(dIdx)) null else c.getLong(dIdx),
                            rawName = if (c.isNull(rIdx)) null else c.getString(rIdx),
                            folderName = c.getString(nIdx),
                            sourceType = c.getString(tIdx),
                            lastPlayedAt = c.getLong(pIdx)
                        )
                    }
                }
                if (rows.isEmpty()) return
                // 按条目分组 → buildWorks → 跨条目 merge（同 key 同名专辑合并）
                val grouped = rows.groupBy { it.entryId }
                val allWorks = grouped.map { (entryId, list) ->
                    val files = list.map {
                        IndexedFile(
                            displayName = it.uri,
                            uri = it.uri,
                            album = it.album,
                            artist = it.artist,
                            durationMs = it.durationMs,
                            rawName = it.rawName
                        )
                    }
                    WorkBackfill.buildWorks(
                        files, entryId, list.first().folderName, list.first().sourceType,
                        lastPlayedAt = list.maxOf { it.lastPlayedAt }
                    )
                }.fold(emptyList<WorkEntity>()) { acc, works -> WorkBackfill.mergeWorks(acc, works) }
                // 插入全部 works → key→id 映射
                val keyToId = HashMap<String, Long>()
                allWorks.forEach { w ->
                    db.execSQL(
                        "INSERT INTO `works` (`key`, `title`, `albumArtist`, `sourceScope`, `coverUri`, " +
                            "`songCount`, `totalDurationMs`, `lastPlayedAt`, `updatedAt`) VALUES (?,?,?,?,?,?,?,?,?)",
                        arrayOf(w.key, w.title, w.albumArtist, w.sourceScope, w.coverUri,
                            w.songCount, w.totalDurationMs, w.lastPlayedAt, w.updatedAt)
                    )
                    keyToId[w.key] = lastInsertRowId(db)
                }
                // 回填 entry_index.workId（逐行按 key 定位）
                rows.forEach { r ->
                    val key = WorkBackfill.workKey(r.entryId, r.folderName, r.album, null, r.artist)
                    val id = keyToId[key] ?: return@forEach
                    db.execSQL(
                        "UPDATE `entry_index` SET `workId` = ? WHERE `entryId` = ? AND `uri` = ?",
                        arrayOf(id, r.entryId, r.uri)
                    )
                }
            }

            /** 回填 sortRank：逐条目按 AudioSortKey.byName（disc→track→名称自然序）排序写下标 */
            private fun backfillSortRank(db: SupportSQLiteDatabase) {
                val entries = mutableListOf<Pair<String, MutableList<IndexedFile>>>()
                db.query(
                    "SELECT entryId, uri, album, artist, track, disc, durationMs, displayName " +
                        "FROM entry_index ORDER BY entryId"
                ).use { c ->
                    val eIdx = c.getColumnIndex("entryId")
                    val uIdx = c.getColumnIndex("uri")
                    val aIdx = c.getColumnIndex("album")
                    val arIdx = c.getColumnIndex("artist")
                    val tIdx = c.getColumnIndex("track")
                    val dIdx = c.getColumnIndex("disc")
                    val mIdx = c.getColumnIndex("durationMs")
                    val nIdx = c.getColumnIndex("displayName")
                    var current: Pair<String, MutableList<IndexedFile>>? = null
                    while (c.moveToNext()) {
                        val entryId = c.getString(eIdx)
                        if (current?.first != entryId) {
                            current = entryId to mutableListOf()
                            entries += current
                        }
                        current!!.second += IndexedFile(
                            displayName = c.getString(nIdx),
                            uri = c.getString(uIdx),
                            album = c.getString(aIdx),
                            artist = c.getString(arIdx),
                            track = if (c.isNull(tIdx)) null else c.getInt(tIdx),
                            disc = if (c.isNull(dIdx)) null else c.getInt(dIdx),
                            durationMs = if (c.isNull(mIdx)) null else c.getLong(mIdx)
                        )
                    }
                }
                entries.forEach { (entryId, files) ->
                    WorkBackfill.assignSortRank(files).forEach { (uri, rank) ->
                        db.execSQL(
                            "UPDATE `entry_index` SET `sortRank` = ? WHERE `entryId` = ? AND `uri` = ?",
                            arrayOf(rank, entryId, uri)
                        )
                    }
                }
            }

            /** 回填老进度：folder_progress → work_progress（matchMode=0 保守，不假装精确） */
            private fun backfillProgress(db: SupportSQLiteDatabase) {
                val recs = mutableListOf<FolderProgress>()
                db.query("SELECT folderUri, trackIndex, positionMs, updatedAt FROM folder_progress").use { c ->
                    val fIdx = c.getColumnIndex("folderUri")
                    val tIdx = c.getColumnIndex("trackIndex")
                    val pIdx = c.getColumnIndex("positionMs")
                    val uIdx = c.getColumnIndex("updatedAt")
                    while (c.moveToNext()) {
                        recs += FolderProgress(
                            folderUri = c.getString(fIdx),
                            trackIndex = c.getInt(tIdx),
                            positionMs = c.getLong(pIdx),
                            updatedAt = c.getLong(uIdx)
                        )
                    }
                }
                if (recs.isEmpty()) return
                recs.forEach { rec ->
                    // 该条目按 sortRank 序的 uris（folderUri 即条目 id）
                    val orderedUris = mutableListOf<Pair<String, Long?>>()
                    db.query(
                        "SELECT uri, workId FROM entry_index WHERE entryId = ? ORDER BY sortRank ASC",
                        arrayOf(rec.folderUri)
                    ).use { c ->
                        val uIdx = c.getColumnIndex("uri")
                        val wIdx = c.getColumnIndex("workId")
                        while (c.moveToNext()) {
                            orderedUris += c.getString(uIdx) to
                                (if (c.isNull(wIdx)) null else c.getLong(wIdx))
                        }
                    }
                    val resolved = WorkBackfill.resolveLegacyProgress(
                        rec, orderedUris.map { it.first }
                    ) ?: return@forEach
                    val workId = orderedUris.firstOrNull { it.first == resolved.first }?.second
                        ?: return@forEach
                    db.execSQL(
                        "INSERT OR REPLACE INTO `work_progress` " +
                            "(`workId`, `fileUri`, `orderIndex`, `matchMode`, `positionMs`, `speed`, `updatedAt`) " +
                            "VALUES (?,?,?,0,?,1.0,?)",
                        arrayOf(workId, resolved.first, resolved.second, rec.positionMs, rec.updatedAt)
                    )
                }
            }

            /** 回填 favorite.workId：itemId 能映射到 entry_index.uri 的取该行 workId，其余保留 NULL */
            private fun backfillFavorite(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `favorite` SET `workId` = " +
                        "(SELECT `workId` FROM `entry_index` WHERE `entry_index`.`uri` = `favorite`.`itemId` LIMIT 1)"
                )
            }

            private fun lastInsertRowId(db: SupportSQLiteDatabase): Long =
                db.query("SELECT last_insert_rowid()").use { c ->
                    c.moveToFirst()
                    c.getLong(0)
                }
        }

        /**
         * v5.0（spec: cue-virtual-tracks）：仅新增 cue_sheets 关联表，无数据搬移、无既有表结构变更。
         * cue_sheets 与 entry_index 同生命周期：扫描时 replaceCueSheets 整体替换。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cue_sheets` (" +
                        "`cueUri` TEXT NOT NULL PRIMARY KEY, " +
                        "`entryId` TEXT NOT NULL, " +
                        "`audioUri` TEXT, " +
                        "`workId` INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cue_sheets_entryId` ON `cue_sheets`(`entryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cue_sheets_workId` ON `cue_sheets`(`workId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cue_sheets_audioUri` ON `cue_sheets`(`audioUri`)")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `relativePath` TEXT")
                db.execSQL("ALTER TABLE `entry_index` ADD COLUMN `error` TEXT")
            }
        }

        /** 迁移回填用行（entry_index JOIN saved_entry） */
        private data class Row(
            val entryId: String,
            val uri: String,
            val album: String?,
            val artist: String?,
            val durationMs: Long?,
            val rawName: String?,
            val folderName: String,
            val sourceType: String,
            val lastPlayedAt: Long
        )

        fun getInstance(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val ins = Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "music_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
                INSTANCE = ins
                ins
            }
        }
    }
}
