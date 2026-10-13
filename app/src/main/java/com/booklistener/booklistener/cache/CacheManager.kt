package com.booklistener.cache

import android.content.Context
import com.booklistener.core.AudioItem
import com.booklistener.core.CacheConfig
import com.booklistener.core.CacheDirs
import com.booklistener.db.AppDatabase
import com.booklistener.db.CacheIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 完整文件 LRU 缓存：
 * - 音频整体下载到本地后播放（不做分片），最大容量 5GB
 * - Room 记录缓存索引，超容量时按 lastAccess 淘汰
 * - 文件写入加锁，防止并发重复下载；异常退出残留的半截文件启动时清理
 */
class CacheManager(private val context: Context) : CacheStore {

    private val db = AppDatabase.getInstance(context)
    private val lock = Mutex()
    private val maxBytes get() = CacheConfig.DEFAULT_MAX_BYTES

    /** 返回已缓存的完整文件；未缓存或缓存损坏返回 null */
    override suspend fun cachedFile(item: AudioItem): File? = withContext(Dispatchers.IO) {
        val index = db.dao().getCacheByItemId(item.id) ?: return@withContext null
        val f = File(index.localFilePath)
        if (f.exists() && f.length() == index.fileSize) {
            db.dao().updateCacheLastAccess(item.id, System.currentTimeMillis())
            f
        } else {
            db.dao().deleteCache(item.id)
            null
        }
    }

    override suspend fun cacheFull(
        item: AudioItem,
        downloader: suspend (target: File) -> Long
    ): File = lock.withLock {
        withContext(Dispatchers.IO) {
            val dir = CacheDirs.audioCacheDir(context)
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "${item.id}.part")
            val target = File(dir, "${item.id}.audio")
            val size = downloader(tmp)
            if (size <= 0) {
                tmp.delete()
                throw IllegalStateException("download failed, 0 bytes")
            }
            if (tmp.renameTo(target)) {
                db.dao().addCacheIndex(
                    CacheIndex(
                        itemId = item.id,
                        localFilePath = target.absolutePath,
                        fileSize = size,
                        lastAccess = System.currentTimeMillis()
                    )
                )
                evictIfNeeded()
                target
            } else {
                tmp.delete()
                throw IllegalStateException("rename cache failed")
            }
        }
    }

    /** 缓存总容量超限时，按最近使用时间淘汰最旧文件，直到不超限 */
    private suspend fun evictIfNeeded() {
        val indexes = db.dao().getAllCacheIndexList()
        var total = indexes.sumOf { it.fileSize }
        if (total <= maxBytes) return
        indexes.sortedBy { it.lastAccess }.forEach { index ->
            if (total <= maxBytes) return@forEach
            val f = File(index.localFilePath)
            if (f.delete()) {
                total -= index.fileSize
                db.dao().deleteCache(index.itemId)
            }
        }
    }

    /** 清除全部缓存文件与索引 */
    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            db.dao().getAllCacheIndexList().forEach { index ->
                File(index.localFilePath).delete()
                db.dao().deleteCache(index.itemId)
            }
            val dir = CacheDirs.audioCacheDir(context)
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** 启动时调用：清理残留 .part 半截文件（进程被杀中断的下载） */
    override fun cleanupPartialFiles() {
        val dir = CacheDirs.audioCacheDir(context)
        dir.listFiles { _, name -> name.endsWith(".part") }?.forEach { it.delete() }
    }
}
