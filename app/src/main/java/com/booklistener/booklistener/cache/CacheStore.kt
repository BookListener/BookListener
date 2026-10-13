package com.booklistener.cache

import com.booklistener.core.AudioItem
import java.io.File

/**
 * 完整文件缓存存储抽象（v1.6.31）：
 * 生产实现 [CacheManager]（Room 索引 + 5GB LRU 淘汰），
 * 测试注入 fake 以覆盖数据源解析器的缓存开关行为。
 */
interface CacheStore {

    /** 返回已缓存的完整文件；未缓存或缓存损坏返回 null */
    suspend fun cachedFile(item: AudioItem): File?

    /** 下载完整音频到缓存目录并记录索引；返回最终缓存文件 */
    suspend fun cacheFull(
        item: AudioItem,
        downloader: suspend (target: File) -> Long
    ): File

    /** 清除全部缓存文件与索引 */
    suspend fun clearAll()

    /** 启动时清理残留 .part 半截文件 */
    fun cleanupPartialFiles()
}
