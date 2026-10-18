package com.booklistener.core

import com.booklistener.db.AppDao
import com.booklistener.db.WorkProgressEntity

/** 作品级播放进度记录（v4.0，替代 FolderProgress） */
data class WorkProgress(
    val workId: Long,
    val fileUri: String,
    val orderIndex: Int,
    val matchMode: Int,
    val positionMs: Long,
    val speed: Float,
    val updatedAt: Long
)

/**
 * v4.0 作品进度读写门面（替代 FolderProgressStore 的写入路径；folder_progress 停写保留）。
 * - 锚点 = (workId, fileUri) 内容引用 + orderIndex 近似回退（matchMode 诚实标注）
 * - 每作品一个当前进度点；删除作品连带删除进度
 */
class WorkStore(private val dao: AppDao) {

    fun load(workId: Long): WorkProgress? = kotlinx.coroutines.runBlocking {
        dao.getWorkProgress(workId)?.toProgress()
    }

    /** 条目内最近更新的作品进度（条目视图恢复：该条目上次播放的作品） */
    fun loadMostRecentByEntry(entryId: String): WorkProgress? = kotlinx.coroutines.runBlocking {
        dao.getMostRecentWorkProgressByEntry(entryId)?.toProgress()
    }

    /** 曲目 → 作品 id（进度保存按当前曲目反查） */
    fun workIdForUri(uri: String): Long? = kotlinx.coroutines.runBlocking {
        dao.getWorkIdByUri(uri)
    }

    /** 保存/更新作品进度（同 workId 覆盖） */
    fun save(
        workId: Long,
        fileUri: String,
        orderIndex: Int,
        positionMs: Long,
        matchMode: Int,
        speed: Float = 1f
    ) = kotlinx.coroutines.runBlocking {
        dao.upsertWorkProgress(
            WorkProgressEntity(
                workId = workId,
                fileUri = fileUri,
                orderIndex = orderIndex.coerceAtLeast(0),
                matchMode = if (matchMode == 1) 1 else 0,
                positionMs = positionMs.coerceAtLeast(0L),
                speed = speed,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 播放开始时标记作品"已播放过"（更新 works.lastPlayedAt，支撑最近播放分区） */
    fun touchPlayed(workId: Long, ts: Long) = kotlinx.coroutines.runBlocking {
        dao.touchWorkPlayed(workId, ts)
    }

    private fun WorkProgressEntity.toProgress() = WorkProgress(
        workId = workId,
        fileUri = fileUri,
        orderIndex = orderIndex,
        matchMode = matchMode,
        positionMs = positionMs,
        speed = speed,
        updatedAt = updatedAt
    )
}
