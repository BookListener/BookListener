package com.booklistener.core

import android.content.Context
import java.io.File

/**
 * 缓存目录唯一提供者（霰弹收敛 A4）：
 * audio_cache（完整音频缓存）与 covers（封面缓存）目录名与路径只在此定义，
 * 禁止在 CacheManager/CoverManager 等类中自行拼 cacheDir 路径。
 */
object CacheDirs {
    const val CACHE_DIR_NAME = "audio_cache"
    const val COVERS_DIR_NAME = "covers"

    /** 完整音频缓存目录（LRU 文件缓存） */
    fun audioCacheDir(context: Context): File = context.cacheDir.resolve(CACHE_DIR_NAME)

    /** 封面缓存目录（内嵌封面提取产物） */
    fun coverCacheDir(context: Context): File = context.cacheDir.resolve(COVERS_DIR_NAME)
}
