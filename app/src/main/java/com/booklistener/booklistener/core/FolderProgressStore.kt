package com.booklistener.core

import com.booklistener.db.AppDao
import com.booklistener.db.FolderProgressEntity

/** 单个文件夹的播放进度记录 */
data class FolderProgress(
    val folderUri: String,
    val trackIndex: Int,
    val positionMs: Long,
    val updatedAt: Long
)

/**
 * 每个文件夹独立的播放进度持久化（v1.6.0：prefs → Room，API 不变）。
 * 以 folderUri 为唯一键，保存/更新一条不影响其他文件夹。
 * 旧数据首启迁移统一走 StoreMigration。
 */
class FolderProgressStore(private val dao: AppDao) {

    fun load(folderUri: String): FolderProgress? =
        all().firstOrNull { it.folderUri == folderUri }

    /** 保存/更新指定文件夹的进度（同文件夹覆盖旧值，异文件夹保留） */
    fun save(folderUri: String, trackIndex: Int, positionMs: Long) = kotlinx.coroutines.runBlocking {
        dao.upsertFolderProgress(
            FolderProgressEntity(
                folderUri = folderUri,
                trackIndex = trackIndex.coerceAtLeast(0),
                positionMs = positionMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 删除指定文件夹的进度记录，其余保留 */
    fun remove(folderUri: String) = kotlinx.coroutines.runBlocking {
        dao.deleteFolderProgress(folderUri)
    }

    fun all(): List<FolderProgress> = kotlinx.coroutines.runBlocking {
        dao.getAllFolderProgress().map { FolderProgress(it.folderUri, it.trackIndex, it.positionMs, it.updatedAt) }
    }
}
