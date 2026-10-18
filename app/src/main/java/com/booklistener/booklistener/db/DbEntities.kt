package com.booklistener.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "play_record")
data class PlayRecord(
    @PrimaryKey val itemId: String,
    val lastPositionMs: Long,
    val lastPlayTime: Long
)

@Entity(tableName = "favorite", indices = [Index(value = ["workId"])])
data class FavoriteItem(
    @PrimaryKey val itemId: String,
    val addTime: Long,
    /** v4.0：收藏粒度升到作品；老 itemId 映射不到作品的保留 NULL（UI 标"未知条目"，不静默删除） */
    val workId: Long? = null
)

/**
 * v4.0：cache_index 搬家为 cloud_cache（缓存管理层，非媒体库）。
 * 类名保持 CacheIndex 减少引用面；minSdk 26 不支持 ALTER TABLE RENAME，迁移走建新表→搬数据→删旧表。
 */
@Entity(tableName = "cloud_cache")
data class CacheIndex(
    @PrimaryKey val itemId: String,
    val localFilePath: String,
    val fileSize: Long,
    val lastAccess: Long
)
