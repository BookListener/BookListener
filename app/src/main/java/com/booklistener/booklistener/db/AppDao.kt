package com.booklistener.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.booklistener.core.IndexedFile
import com.booklistener.core.WorkBackfill
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM play_record ORDER BY lastPlayTime DESC")
    fun getAllPlayRecords(): Flow<List<PlayRecord>>

    @Query("SELECT * FROM play_record WHERE itemId = :id LIMIT 1")
    suspend fun getPlayRecord(id: String): PlayRecord?

    @Insert
    suspend fun insertPlayRecord(record: PlayRecord)

    @Update
    suspend fun updatePlayRecord(record: PlayRecord)

    @Query("SELECT * FROM favorite")
    fun getAllFavorites(): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorite WHERE itemId = :id LIMIT 1")
    suspend fun getFavorite(id: String): FavoriteItem?

    @Insert
    suspend fun addFavorite(item: FavoriteItem)

    @Query("DELETE FROM favorite WHERE itemId = :id")
    suspend fun removeFavorite(id: String)

    // ---- v4.0：cache_index 搬家为 cloud_cache（表名改，API 不变）----

    @Query("SELECT * FROM cloud_cache")
    fun getAllCacheIndex(): Flow<List<CacheIndex>>

    @Query("SELECT * FROM cloud_cache")
    suspend fun getAllCacheIndexList(): List<CacheIndex>

    @Query("SELECT * FROM cloud_cache WHERE itemId = :id LIMIT 1")
    suspend fun getCacheByItemId(id: String): CacheIndex?

    @Query("UPDATE cloud_cache SET lastAccess = :lastAccess WHERE itemId = :id")
    suspend fun updateCacheLastAccess(id: String, lastAccess: Long)

    @Insert
    suspend fun addCacheIndex(cacheIndex: CacheIndex)

    @Query("DELETE FROM cloud_cache WHERE itemId = :id")
    suspend fun deleteCache(id: String)

    // ---- v1.6.0：业务 Store 表（saved_entry / folder_progress / entry_index）----

    @Query("SELECT * FROM saved_entry ORDER BY sortOrder")
    suspend fun getAllSavedEntries(): List<SavedEntryEntity>

    @Query("SELECT COUNT(*) FROM saved_entry")
    suspend fun countSavedEntries(): Int

    @Query("DELETE FROM saved_entry WHERE id = :id")
    suspend fun deleteSavedEntry(id: String)

    @Query("DELETE FROM saved_entry")
    suspend fun deleteAllSavedEntries()

    @Insert
    suspend fun insertSavedEntries(entries: List<SavedEntryEntity>)

    /** 单条 upsert（并发安全）：同 id 整行覆盖（REPLACE），不触碰其他行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedEntry(entry: SavedEntryEntity)

    @Query("UPDATE saved_entry SET lastPlayedAt = :ts WHERE id = :id")
    suspend fun updateSavedEntryLastPlayed(id: String, ts: Long)

    @Query("UPDATE saved_entry SET lastOpenedAt = :ts WHERE id = :id")
    suspend fun updateSavedEntryLastOpened(id: String, ts: Long)

    /** 整表替换（保序）：事务化（迁移/测试兼容用） */
    @androidx.room.Transaction
    suspend fun replaceAllSavedEntries(entries: List<SavedEntryEntity>) {
        deleteAllSavedEntries()
        insertSavedEntries(entries)
    }

    @Query("SELECT * FROM folder_progress")
    suspend fun getAllFolderProgress(): List<FolderProgressEntity>

    @Query("SELECT * FROM folder_progress WHERE folderUri = :folderUri LIMIT 1")
    suspend fun getFolderProgress(folderUri: String): FolderProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolderProgress(progress: FolderProgressEntity)

    @Query("DELETE FROM folder_progress WHERE folderUri = :folderUri")
    suspend fun deleteFolderProgress(folderUri: String)

    @Query("SELECT * FROM entry_index")
    suspend fun getAllEntryIndex(): List<EntryIndexEntity>

    /** 按条目加载 */
    @Query("SELECT * FROM entry_index WHERE entryId = :entryId ORDER BY sortRank ASC")
    suspend fun getEntryIndex(entryId: String): List<EntryIndexEntity>

    /** 按条目观察（UI 内容文件列表用） */
    @Query("SELECT * FROM entry_index WHERE entryId = :entryId ORDER BY sortRank ASC")
    fun observeEntryIndex(entryId: String): Flow<List<EntryIndexEntity>>

    /**
     * 网盘子目录导航：按相对路径精确筛选某目录下的文件。
     * relativePath 为目录相对路径（根目录为 ""）；只返回该目录直属文件（不含更深子目录）。
     */
    @Query(
        """
        SELECT * FROM entry_index
        WHERE entryId = :entryId
          AND (relativePath IS NULL OR relativePath = :dirPath)
        ORDER BY sortRank ASC
        """
    )
    suspend fun getEntryIndexByDir(entryId: String, dirPath: String): List<EntryIndexEntity>

    @Query(
        """
        SELECT * FROM entry_index
        WHERE entryId = :entryId
          AND (relativePath IS NULL OR relativePath = :dirPath)
        ORDER BY sortRank ASC
        """
    )
    fun observeEntryIndexByDir(entryId: String, dirPath: String): Flow<List<EntryIndexEntity>>

    /** 列出该条目下所有子目录（从 relativePath 提取，用于网盘导航） */
    @Query("SELECT DISTINCT relativePath FROM entry_index WHERE entryId = :entryId AND relativePath IS NOT NULL")
    suspend fun getSubDirs(entryId: String): List<String>

    @Query("DELETE FROM entry_index WHERE entryId = :entryId")
    suspend fun deleteEntryIndex(entryId: String)

    @Insert
    suspend fun insertEntryIndex(files: List<EntryIndexEntity>)

    /** 播放后补录/校正时长（网盘或扫描未知时长由播放器回写） */
    @Query("UPDATE entry_index SET durationMs = :durationMs WHERE entryId = :entryId AND uri = :uri")
    suspend fun updateIndexDuration(entryId: String, uri: String, durationMs: Long)

    /** 时长补录后同步重算作品总时长缓存（只重算受影响的作品） */
    @Query(
        """
        UPDATE works SET totalDurationMs = (SELECT COALESCE(SUM(durationMs), 0) FROM entry_index WHERE workId = works.id)
        WHERE id IN (SELECT i.workId FROM entry_index i WHERE i.entryId = :entryId AND i.uri = :uri AND i.workId IS NOT NULL)
        """
    )
    suspend fun refreshWorkDurationByFile(entryId: String, uri: String)

    /** 单条目文件清单整体替换：事务化（并发安全） */
    @androidx.room.Transaction
    suspend fun replaceEntryIndex(entryId: String, files: List<EntryIndexEntity>) {
        deleteEntryIndex(entryId)
        insertEntryIndex(files)
    }

    // ---- v5.0（spec: cue-virtual-tracks）：cue_sheets 关联表与虚拟轨查询 ----

    @Query("SELECT * FROM cue_sheets WHERE cueUri = :cueUri LIMIT 1")
    suspend fun getCueSheetByUri(cueUri: String): CueSheetEntity?

    /** 内容文件列表：某条目的全部 cue 文件行（含被吸收整轨 audioUri，UI 层与 entry_index 去重） */
    @Query("SELECT * FROM cue_sheets WHERE entryId = :entryId")
    suspend fun getCueSheetsByEntry(entryId: String): List<CueSheetEntity>

    /** 某条目的被吸收整轨 uri（audioUri 非空；UI 层判断是否已在 entry_index 中决定是否展示整轨行） */
    @Query("SELECT audioUri FROM cue_sheets WHERE entryId = :entryId AND audioUri IS NOT NULL")
    suspend fun getAbsorbedAudioUrisByEntry(entryId: String): List<String>

    @Query("DELETE FROM cue_sheets WHERE entryId = :entryId")
    suspend fun deleteCueSheetsByEntry(entryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCueSheet(sheet: CueSheetEntity)

    /** 虚拟轨列表页数据源：按作品查虚拟轨行（D5：db 查询，不重新解析 cue） */
    @Query("SELECT * FROM entry_index WHERE workId = :workId ORDER BY sortRank ASC")
    suspend fun listVirtualTracksByWork(workId: Long): List<EntryIndexEntity>

    /** cue_sheets 与 entry_index 同生命周期：扫描 replaceEntryIndex 时一并整体替换 */
    @androidx.room.Transaction
    suspend fun replaceCueSheets(entryId: String, sheets: List<CueSheetEntity>) {
        deleteCueSheetsByEntry(entryId)
        sheets.forEach { upsertCueSheet(it) }
    }

    // ---- v4.0：作品实体（works）与作品进度（work_progress）----

    @Query("SELECT * FROM works WHERE key = :key LIMIT 1")
    suspend fun getWorkByKey(key: String): WorkEntity?

    @Query("SELECT * FROM works WHERE id = :id LIMIT 1")
    suspend fun getWorkById(id: Long): WorkEntity?

    @Query("SELECT w.* FROM works w JOIN entry_index i ON i.workId = w.id WHERE i.entryId = :entryId ORDER BY w.title COLLATE NOCASE ASC")
    suspend fun getWorksByEntry(entryId: String): List<WorkEntity>

    /** 作品 → 来源条目（跨条目合并时取最小 entryId，与 AlbumStore 聚合口径一致） */
    @Query("SELECT MIN(entryId) FROM entry_index WHERE workId = :workId")
    suspend fun getEntryIdByWorkId(workId: Long): String?

    @Insert
    suspend fun insertWork(work: WorkEntity): Long

    @Query(
        """
        UPDATE works SET title = :title, albumArtist = :albumArtist, sourceScope = :sourceScope,
        coverUri = :coverUri, songCount = :songCount, totalDurationMs = :totalDurationMs, updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateWorkContent(
        id: Long, title: String, albumArtist: String?, sourceScope: String,
        coverUri: String?, songCount: Int, totalDurationMs: Long, updatedAt: Long
    )

    @Query("DELETE FROM works WHERE id = :id")
    suspend fun deleteWork(id: Long)

    @Query("UPDATE works SET lastPlayedAt = :ts WHERE id = :id")
    suspend fun touchWorkPlayed(id: Long, ts: Long)

    /** v2.1.15：扫描时按作品键回填封面（首曲内嵌封面提取后入库） */
    @Query("UPDATE works SET coverUri = :coverUri, updatedAt = :updatedAt WHERE key = :key")
    suspend fun updateWorkCoverByKey(key: String, coverUri: String?, updatedAt: Long)

    @Query("DELETE FROM work_progress WHERE workId NOT IN (SELECT id FROM works)")
    suspend fun deleteOrphanWorkProgress()

    @Query("SELECT * FROM works WHERE id NOT IN (SELECT DISTINCT workId FROM entry_index WHERE workId IS NOT NULL)")
    suspend fun getOrphanWorks(): List<WorkEntity>

    @Query("DELETE FROM works WHERE id NOT IN (SELECT DISTINCT workId FROM entry_index WHERE workId IS NOT NULL)")
    suspend fun deleteOrphanWorks()

    /**
     * 作品统计派生重算（跨条目合并/条目缩容后作品字段永远等于真实聚合，避免覆盖丢贡献）。
     * 扫描事务内 replaceEntryIndex 后对受影响作品调用。
     */
    @Query(
        """
        UPDATE works SET
          songCount = (SELECT COUNT(*) FROM entry_index WHERE entry_index.workId = works.id),
          totalDurationMs = (SELECT COALESCE(SUM(durationMs), 0) FROM entry_index WHERE entry_index.workId = works.id),
          updatedAt = :now
        WHERE id IN (:ids)
        """
    )
    suspend fun recomputeWorkStats(ids: List<Long>, now: Long)

    /** 删除无任何文件引用的作品，再清理其孤儿进度（顺序不可颠倒：先删作品，进度随之失引用） */
    @androidx.room.Transaction
    suspend fun cleanupOrphanWorks() {
        deleteOrphanWorks()
        deleteOrphanWorkProgress()
    }

    /**
     * v2.1.5：workKey 规则变更（local 键去掉 artist）后，重扫会把"同专辑多表演者"的旧拆分作品
     * 合并为一个新作品。合并前把将被删除的孤儿作品的**最近播放进度**转移到合并目标，
     * 避免进度静默丢失。转移规则：目标无进度 → 直接迁移；双方都有 → 保留 lastPlayedAt 较新者。
     */
    @androidx.room.Transaction
    suspend fun migrateOrphanWorkProgress() {
        getOrphanWorks().forEach { orphan ->
            val p = getWorkProgress(orphan.id) ?: return@forEach
            val key = orphan.key
            // 旧 local 键格式 "local:<norm(artist)>|<norm(album)>"；新格式 "local:<norm(album)>"（无 |，无转移目标）
            if (key.startsWith("local:") && key.contains('|')) {
                val targetKey = "local:${key.substringAfter('|')}"
                val target = getWorkByKey(targetKey) ?: return@forEach
                val tp = getWorkProgress(target.id)
                if (tp == null || orphan.lastPlayedAt > target.lastPlayedAt) {
                    upsertWorkProgress(p.copy(workId = target.id))
                }
            }
        }
    }

    /** 作品级进度：按 workId upsert（REPLACE） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkProgress(progress: WorkProgressEntity)

    @Query("SELECT * FROM work_progress WHERE workId = :workId LIMIT 1")
    suspend fun getWorkProgress(workId: Long): WorkProgressEntity?

    @Query("DELETE FROM work_progress WHERE workId = :workId")
    suspend fun deleteWorkProgress(workId: Long)

    /** 条目内最近更新的作品进度（杀后台/条目视图恢复用） */
    @Query(
        """
        SELECT wp.* FROM work_progress wp
        JOIN entry_index i ON i.workId = wp.workId
        WHERE i.entryId = :entryId
        ORDER BY wp.updatedAt DESC LIMIT 1
        """
    )
    suspend fun getMostRecentWorkProgressByEntry(entryId: String): WorkProgressEntity?

    /** 曲目 → 作品（进度保存按当前曲目反查） */
    @Query("SELECT workId FROM entry_index WHERE uri = :uri AND workId IS NOT NULL LIMIT 1")
    suspend fun getWorkIdByUri(uri: String): Long?

    /** 曲目 → 来源条目（时长补录定位） */
    @Query("SELECT entryId FROM entry_index WHERE uri = :uri LIMIT 1")
    suspend fun getEntryIdByUri(uri: String): String?

    /** 条目+专辑名 → 作品（搜索命中专辑懒解析 workId） */
    @Query(
        """
        SELECT id FROM works
        WHERE id IN (SELECT workId FROM entry_index WHERE entryId = :entryId)
          AND title = :title LIMIT 1
        """
    )
    suspend fun getWorkIdByEntryAndAlbum(entryId: String, title: String): Long?

    /**
     * v4.0 扫描入库（作品实体化核心，事务）：
     * 1) 逐文件算 workKey → buildWorks 聚合 → upsert works by key（id 保持稳定，禁止整删重建）
     * 2) 查回 workId → 写 entry_index（含 workId / sortRank / sizeBytes / albumArtist）
     * 3) 清理该条目失效行 + 无引用作品（连带进度）
     */
    @androidx.room.Transaction
    suspend fun saveEntryIndexWithWorks(
        entryId: String,
        folderName: String,
        sourceScope: String,
        files: List<IndexedFile>
    ) {
        val works = WorkBackfill.buildWorks(files, entryId, folderName, sourceScope)
        val workIdByKey = LinkedHashMap<String, Long>()
        works.forEach { w ->
            workIdByKey[w.key] = upsertWorkByKey(w)
        }
        val ranks = WorkBackfill.assignSortRank(files).toMap()
        val rows = files.map { f ->
            val key = WorkBackfill.workKey(entryId, folderName, f.album, f.albumArtist, f.artist)
            f.toEntity(
                entryId = entryId,
                workId = workIdByKey.getValue(key),
                sortRank = ranks.getValue(f.uri)
            )
        }
        replaceEntryIndex(entryId, rows)
        // 作品统计派生重算：跨条目同名专辑合并 songCount/时长累加、条目缩容正确收敛
        if (workIdByKey.isNotEmpty()) {
            recomputeWorkStats(workIdByKey.values.toList(), System.currentTimeMillis())
        }
        // v2.1.5：key 规则变更后先迁移孤儿进度（合并目标），再清理孤儿作品/进度
        migrateOrphanWorkProgress()
        cleanupOrphanWorks()
    }

    /** upsert works by key（SQLite 3.18 无 ON CONFLICT DO UPDATE，用 select-then-write 事务内实现） */
    @androidx.room.Transaction
    suspend fun upsertWorkByKey(work: WorkEntity): Long {
        val existing = getWorkByKey(work.key)
        return if (existing != null) {
            updateWorkContent(
                existing.id, work.title, work.albumArtist, work.sourceScope,
                work.coverUri, work.songCount, work.totalDurationMs, work.updatedAt
            )
            existing.id
        } else {
            insertWork(work)
        }
    }

    // ---- v4.0：专辑聚合（曲目界面，改走作品实体）----

    /**
     * 已播放作品：works.lastPlayedAt > 0，按播放时间新→旧。
     * entryId = 作品内来源条目（MIN(s.id)，跨条目合并取首条目；本地合并场景 sourceType 相同）；
     * albumCountInEntry = 作品覆盖的条目数（单条目=1 → 文件夹封面；跨条目 → 内嵌封面）。
     */
    @Query(
        """
        SELECT w.id AS workId, w.title AS album, MIN(s.id) AS entryId,
               COALESCE(w.albumArtist, MIN(s.artist)) AS artist,
               w.songCount AS songCount, w.totalDurationMs AS totalDurationMs,
               COUNT(DISTINCT s.id) AS albumCountInEntry,
               w.coverUri AS coverUri
        FROM works w
        JOIN entry_index i ON i.workId = w.id
        JOIN saved_entry s ON s.id = i.entryId
        WHERE w.lastPlayedAt > 0
        GROUP BY w.id
        ORDER BY w.lastPlayedAt DESC, w.title COLLATE NOCASE ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun worksOfPlayed(limit: Int, offset: Int): List<com.booklistener.core.AlbumRow>

    /**
     * 尚未播放作品：works.lastPlayedAt <= 0，按来源条目保存顺序（MIN(s.sortOrder)）稳定排序。
     */
    @Query(
        """
        SELECT w.id AS workId, w.title AS album, MIN(s.id) AS entryId,
               COALESCE(w.albumArtist, MIN(s.artist)) AS artist,
               w.songCount AS songCount, w.totalDurationMs AS totalDurationMs,
               COUNT(DISTINCT s.id) AS albumCountInEntry,
               w.coverUri AS coverUri
        FROM works w
        JOIN entry_index i ON i.workId = w.id
        JOIN saved_entry s ON s.id = i.entryId
        WHERE w.lastPlayedAt <= 0
        GROUP BY w.id
        ORDER BY MIN(s.sortOrder) ASC, w.title COLLATE NOCASE ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun worksOfNeverPlayed(limit: Int, offset: Int): List<com.booklistener.core.AlbumRow>

    /** 专辑内歌曲（作品）：按 sortRank 排序 + 分页下推 SQL（v4.0，替代内存排序） */
    @Query(
        "SELECT * FROM entry_index WHERE workId = :workId ORDER BY sortRank ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun albumSongsByWork(workId: Long, limit: Int, offset: Int): List<EntryIndexEntity>

    /** 专辑内全部歌曲（作品，排序后，供播放队列与"总数/总时长"） */
    @Query("SELECT * FROM entry_index WHERE workId = :workId ORDER BY sortRank ASC")
    suspend fun allSongsByWork(workId: Long): List<EntryIndexEntity>
}
