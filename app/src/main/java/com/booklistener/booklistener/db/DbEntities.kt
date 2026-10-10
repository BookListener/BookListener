package com.booklistener.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_record")
data class PlayRecord(
    @PrimaryKey val itemId: String,
    val lastPositionMs: Long,
    val lastPlayTime: Long
)

@Entity(tableName = "favorite")
data class FavoriteItem(
    @PrimaryKey val itemId: String,
    val addTime: Long
)

@Entity(tableName = "cache_index")
data class CacheIndex(
    @PrimaryKey val itemId: String,
    val localFilePath: String,
    val fileSize: Long,
    val lastAccess: Long
)
