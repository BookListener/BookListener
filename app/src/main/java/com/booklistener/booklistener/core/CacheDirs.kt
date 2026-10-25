package com.booklistener.core

import android.content.Context
import java.io.File

/**
 * 缓存目录唯一提供者（霰弹收敛 A4）：
 * audio_cache（完整音频缓存）与 covers（封面存储）目录名与路径只在此定义，
 * 禁止在 CacheManager/CoverManager 等类中自行拼路径。
 *
 * v2.1.26 封面目录迁移：内嵌封面提取产物从 cacheDir/covers 移入 filesDir/covers——
 * works.coverUri 持久引用封面文件，必须与数据库同清理边界（清缓存不清、清数据连库一起删）。
 * coverCacheDir 保留仅供 CoverDirMigration 读取旧位置，新写入一律走 coverFilesDir。
 */
object CacheDirs {
    const val CACHE_DIR_NAME = "audio_cache"
    const val COVERS_DIR_NAME = "covers"

    /** 完整音频缓存目录（LRU 文件缓存，易失层，无持久引用） */
    fun audioCacheDir(context: Context): File = context.cacheDir.resolve(CACHE_DIR_NAME)

    /** 旧封面目录（v2.1.26 前内嵌封面提取产物，易失）；仅供迁移读取 */
    fun coverCacheDir(context: Context): File = context.cacheDir.resolve(COVERS_DIR_NAME)

    /** 封面存储目录（v2.1.26 起，持久层，与数据库同生命周期） */
    fun coverFilesDir(context: Context): File = context.filesDir.resolve(COVERS_DIR_NAME)
}
