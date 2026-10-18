package com.booklistener.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.6.0：三个业务 Store（SavedEntryStore / FolderProgressStore / EntryIndexStore）
 * 从 prefs-JSON 整体迁移到 Room 的表实体。
 * - saved_entry.sortOrder：保列表序（replace 原位 / upsert 新 id 追加末尾，与 prefs 整写语义一致）
 * - entry_index 主键 (entryId, uri)：同一条目内文件唯一（SAF docId / 网盘路径）
 */
@Entity(tableName = "saved_entry")
data class SavedEntryEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val uri: String,
    val sourceType: String,
    val coverUri: String?,
    val fileCount: Int,
    val lastPlayedAt: Long,
    val artist: String?,
    val lastOpenedAt: Long,
    val sortOrder: Int
)

@Entity(tableName = "folder_progress")
data class FolderProgressEntity(
    @PrimaryKey val folderUri: String,
    val trackIndex: Int,
    val positionMs: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "entry_index",
    primaryKeys = ["entryId", "uri"],
    indices = [
        Index(value = ["workId"]),
        Index(value = ["entryId", "workId"]),
        Index(value = ["entryId", "sortRank"])
    ]
)
data class EntryIndexEntity(
    val entryId: String,
    val displayName: String,
    val uri: String,
    // v1.7.0：媒体信息落库（专辑聚合/播放列表/文件列表用）
    val album: String?,
    val artist: String?,
    val track: Int?,
    val disc: Int?,
    val durationMs: Long?,
    val rawName: String?,
    // v4.0：作品实体化（DESIGN-数据库重构-v4）
    val workId: Long? = null,      // 指向 works.id（扫描后回填），替代查询时 GROUP BY 聚合
    val sortRank: Int? = null,     // 条目内展示序下标（AudioSortKey.byName），专辑内 ORDER BY sortRank
    val sizeBytes: Long? = null,   // 二期内容指纹用；本期仅落库
    val albumArtist: String? = null // 参与作品键（当前解析无 albumArtist 概念，暂空，key 用 artist 兜底）
)
