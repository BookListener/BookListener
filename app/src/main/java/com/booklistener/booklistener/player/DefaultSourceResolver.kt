package com.booklistener.player

import com.booklistener.cache.CacheStore
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioSource
import com.booklistener.core.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认数据源解析器：把 AudioItem 解析为可播放 Uri。
 * 本地/WebDAV 直接返回原地址（均支持 Range）；
 * 阿里云盘优先命中本地缓存，未缓存则获取临时直链。
 *
 * fullFileCacheEnabled（v1.6.31 起可运行时切换）：关闭后网络源不再命中/写入完整文件缓存，
 * 一律在线播放原地址；开启后恢复缓存优先。
 */
class DefaultSourceResolver(
    private val sources: Map<SourceType, AudioSource>,
    private val cache: CacheStore,
    /** 完整文件缓存开关（默认开启，与设置页缓存子页开关联动） */
    var fullFileCacheEnabled: Boolean = true
) {

    suspend fun resolve(item: AudioItem): Result<String> = withContext(Dispatchers.IO) {
        when (item.sourceType) {
            SourceType.LOCAL -> Result.success(item.path)

            SourceType.WEBDAV -> {
                // v5.0：虚拟轨共用同一整轨文件——缓存键必须归一到整轨 uri，避免同文件缓存两份
                val cacheItem = cacheKeyItem(item)
                if (fullFileCacheEnabled) {
                    val cached = cache.cachedFile(cacheItem)
                    if (cached != null) {
                        Result.success(cached.toURI().toString())
                    } else {
                        Result.success(item.path)
                    }
                } else {
                    Result.success(item.path)
                }
            }

            SourceType.ALIYUN -> {
                if (!fullFileCacheEnabled) {
                    val source = sources[SourceType.ALIYUN]
                        ?: return@withContext Result.failure(IllegalStateException("aliyun source missing"))
                    return@withContext source.getPlayUri(item)
                }
                val cached = cache.cachedFile(item)
                if (cached != null) {
                    Result.success(cached.toURI().toString())
                } else {
                    val source = sources[SourceType.ALIYUN]
                        ?: return@withContext Result.failure(IllegalStateException("aliyun source missing"))
                    source.getPlayUri(item)
                }
            }
        }
    }

    /** 整体缓存一个阿里云盘音频（≤200MB，受完整文件缓存开关控制） */
    suspend fun cacheAliyunItem(item: AudioItem): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!fullFileCacheEnabled) {
                    val source = sources[SourceType.ALIYUN]
                        ?: throw IllegalStateException("aliyun source missing")
                    return@runCatching source.getPlayUri(item).getOrThrow()
                }
                val cached = cache.cachedFile(item)
                if (cached != null) {
                    return@runCatching cached.toURI().toString()
                }
                val source = sources[SourceType.ALIYUN]
                    ?: throw IllegalStateException("aliyun source missing")
                val url = source.getPlayUri(item).getOrThrow()
                cache.cacheFull(item) { target ->
                    downloadToFile(url, target)
                }.toURI().toString()
            }
        }
    }

    private fun downloadToFile(url: String, target: java.io.File): Long {
        val request = okhttp3.Request.Builder().url(url).build()
        com.booklistener.net.OkHttpHolder.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("download http ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
            return target.length()
        }
    }

    /** 虚拟轨的缓存键归一：id 落到整轨 uri（ClippingConfiguration 播放同一文件） */
    private fun cacheKeyItem(item: AudioItem): AudioItem {
        val whole = com.booklistener.core.VirtualTrackUri.parse(item.path)?.first ?: return item
        return item.copy(id = whole)
    }
}
