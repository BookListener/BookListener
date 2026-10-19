package com.booklistener.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v4.0 作品实体（DESIGN-数据库重构-v4）：
 * 作品（专辑）从"查询时派生"升为一等实体，tag 优先聚合，跨文件夹可合并。
 * - key：作品键 = 本地有 album tag → "local:<albumArtist|artist>|<album>"；无 tag/网盘 → "entry:<entryId>|<folderName>"
 * - id 由 upsert-by-key 保持稳定（禁止整删重建），work_progress.workId 依赖其稳定
 */
@Entity(
    tableName = "works",
    indices = [Index(value = ["key"], unique = true), Index(value = ["lastPlayedAt"])]
)
data class WorkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 作品键（唯一）：见 workKey() 规则 */
    val key: String,
    /** 展示名 = album ?: 文件夹名 */
    val title: String,
    /** 专辑作者（v3 无此列，当前解析无 albumArtist 概念，回填/扫描时用 artist 兜底） */
    val albumArtist: String?,
    /** LOCAL / WEBDAV / ALIYUN（收口为枚举名） */
    val sourceScope: String,
    val coverUri: String?,
    /** 扫描时缓存，列表页免 JOIN 聚合 */
    val songCount: Int,
    val totalDurationMs: Long,
    val lastPlayedAt: Long,
    val updatedAt: Long
)

/**
 * 作品级播放进度（替代 folder_progress 的写入路径；folder_progress 停写保留）。
 * - workId 作主键：每作品只有一个"当前进度点"（多断点=书签属二期）
 * - matchMode：1 = uri 精确命中；0 = 按 orderIndex 近似（UI 提示"可能定位不准"，诚实性设计）
 */
@Entity(tableName = "work_progress")
data class WorkProgressEntity(
    @PrimaryKey val workId: Long,
    /** 内容引用（主键级） */
    val fileUri: String,
    /** uri 匹配失败时的近似回退 */
    val orderIndex: Int,
    /** 1 = uri 命中；0 = 按 orderIndex 近似 */
    val matchMode: Int,
    val positionMs: Long,
    /** 每作品独立倍速（顺带，零成本） */
    val speed: Float,
    val updatedAt: Long
)

/**
 * v5.0（spec: cue-virtual-tracks）CUE 整轨虚拟分轨关联表：
 * cue 文件本身**不是**媒体条目（不入 entry_index），用本表记录其存在与归属。
 * - cueUri 主键：cue 文件 uri（本地 SAF docId / 网盘 href）
 * - audioUri：定位到的整轨 uri（FILE 声明优先 + 同名扩展名探测兜底）；解析失败/未定位 = null
 * - workId：cue 专辑作品 id（无有效轨 = null）；虚拟轨行（uri=<audioUri>#track_<startMs>）在 entry_index 中按此 workId 聚合
 */
@Entity(
    tableName = "cue_sheets",
    indices = [Index(value = ["entryId"]), Index(value = ["workId"]), Index(value = ["audioUri"])]
)
data class CueSheetEntity(
    @PrimaryKey val cueUri: String,
    val entryId: String,
    val audioUri: String?,
    val workId: Long?
)
