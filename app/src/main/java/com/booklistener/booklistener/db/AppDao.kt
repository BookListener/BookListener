package com.booklistener.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    @Query("SELECT * FROM cache_index")
    fun getAllCacheIndex(): Flow<List<CacheIndex>>

    @Query("SELECT * FROM cache_index")
    suspend fun getAllCacheIndexList(): List<CacheIndex>

    @Query("SELECT * FROM cache_index WHERE itemId = :id LIMIT 1")
    suspend fun getCacheByItemId(id: String): CacheIndex?

    @Query("UPDATE cache_index SET lastAccess = :lastAccess WHERE itemId = :id")
    suspend fun updateCacheLastAccess(id: String, lastAccess: Long)

    @Insert
    suspend fun addCacheIndex(cacheIndex: CacheIndex)

    @Query("DELETE FROM cache_index WHERE itemId = :id")
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

    /** 单条 upsert（并发安全）：同 id 整行覆盖（REPLACE），不触碰其他行。
     * v1.6.0 修正：整表替换在并发（异步扫描 afterLoad 与用户操作）下
     * 会以过期列表覆盖先提交数据（丢条目），逐行写从根本上消除该竞态。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedEntry(entry: SavedEntryEntity)

    @Query("UPDATE saved_entry SET lastPlayedAt = :ts WHERE id = :id")
    suspend fun updateSavedEntryLastPlayed(id: String, ts: Long)

    @Query("UPDATE saved_entry SET lastOpenedAt = :ts WHERE id = :id")
    suspend fun updateSavedEntryLastOpened(id: String, ts: Long)

    /** 整表替换（保序）：事务化，避免并发 DELETE+INSERT 交错触发主键冲突（迁移/测试兼容用） */
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

    /** 按条目加载（v1.6.14：进入条目视图改从 db 读取，不再每次全量扫描） */
    @Query("SELECT * FROM entry_index WHERE entryId = :entryId")
    suspend fun getEntryIndex(entryId: String): List<EntryIndexEntity>

    @Query("DELETE FROM entry_index WHERE entryId = :entryId")
    suspend fun deleteEntryIndex(entryId: String)

    @Insert
    suspend fun insertEntryIndex(files: List<EntryIndexEntity>)

    /** 播放后补录/校正时长（v1.7.0：网盘或扫描未知时长由播放器回写） */
    @Query("UPDATE entry_index SET durationMs = :durationMs WHERE entryId = :entryId AND uri = :uri")
    suspend fun updateIndexDuration(entryId: String, uri: String, durationMs: Long)

    /** 单条目文件清单整体替换：事务化（并发安全） */
    @androidx.room.Transaction
    suspend fun replaceEntryIndex(entryId: String, files: List<EntryIndexEntity>) {
        deleteEntryIndex(entryId)
        insertEntryIndex(files)
    }

    // ---- v1.7.0：专辑聚合（曲目界面）----

    /**
     * 已播放专辑：来源条目播放过（s.lastPlayedAt > 0），按播放时间新→旧。
     * 专辑名 = COALESCE(i.album, s.displayName)（缺失按文件夹名兜底）；
     * artist 取组内首个非空（MIN 保证确定性）；totalDurationMs 汇总时长（NULL → 0）。
     */
    @Query(
        """
        SELECT COALESCE(i.album, s.displayName) AS album, s.id AS entryId,
               MIN(i.artist) AS artist, COUNT(*) AS songCount,
               COALESCE(SUM(i.durationMs), 0) AS totalDurationMs,
               (SELECT COUNT(DISTINCT COALESCE(i2.album, s2.displayName))
                FROM entry_index i2 JOIN saved_entry s2 ON s2.id = i2.entryId
                WHERE s2.id = s.id) AS albumCountInEntry
        FROM entry_index i JOIN saved_entry s ON s.id = i.entryId
        WHERE s.lastPlayedAt > 0 AND s.sourceType != 'WEBDAV'
        GROUP BY album, s.id
        ORDER BY s.lastPlayedAt DESC, album ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun albumsOfPlayed(limit: Int, offset: Int): List<com.booklistener.core.AlbumRow>

    /**
     * 尚未播放专辑：来源条目未播放过（s.lastPlayedAt <= 0），按条目保存顺序（sortOrder ASC）。
     */
    @Query(
        """
        SELECT COALESCE(i.album, s.displayName) AS album, s.id AS entryId,
               MIN(i.artist) AS artist, COUNT(*) AS songCount,
               COALESCE(SUM(i.durationMs), 0) AS totalDurationMs,
               (SELECT COUNT(DISTINCT COALESCE(i2.album, s2.displayName))
                FROM entry_index i2 JOIN saved_entry s2 ON s2.id = i2.entryId
                WHERE s2.id = s.id) AS albumCountInEntry
        FROM entry_index i JOIN saved_entry s ON s.id = i.entryId
        WHERE s.lastPlayedAt <= 0 AND s.sourceType != 'WEBDAV'
        GROUP BY album, s.id
        ORDER BY s.sortOrder ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun albumsOfNeverPlayed(limit: Int, offset: Int): List<com.booklistener.core.AlbumRow>

    /**
     * 专辑内歌曲（全量）：disc → track → 名称**自然序**在 Kotlin 层完成
     * （SQLite 无自然排序，AlbumStore 取回后按 AudioSortKey 排序再内存分页），
     * 这里只按条目+专辑过滤，保证分页与排序语义一致。
     */
    @Query(
        """
        SELECT * FROM entry_index
        WHERE entryId = :entryId AND
              COALESCE(album, (SELECT displayName FROM saved_entry WHERE saved_entry.id = entry_index.entryId)) = :album
        """
    )
    suspend fun albumSongs(entryId: String, album: String): List<EntryIndexEntity>
}
