package com.booklistener.player

import com.booklistener.cache.CacheManager
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioSource
import com.booklistener.core.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认数据源解析器：把 AudioItem 解析为可播放 Uri。
 * 本地/WebDAV 直接返回原地址（均支持 Range）；
 * 阿里云盘优先命中本地缓存，未缓存则获取临时直链。
 */
class DefaultSourceResolver(
    private val sources: Map<SourceType, AudioSource>,
    private val cache: CacheManager
) {

    suspend fun resolve(item: AudioItem): Result<String> = withContext(Dispatchers.IO) {
        when (item.sourceType) {
            SourceType.LOCAL -> Result.success(item.path)

            SourceType.WEBDAV -> {
                val cached = cache.cachedFile(item)
                if (cached != null) {
                    Result.success(cached.toURI().toString())
                } else {
                    Result.success(item.path)
                }
            }

            SourceType.ALIYUN -> {
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

    /** 整体缓存一个阿里云盘音频（≤200MB，可选开关） */
    suspend fun cacheAliyunItem(item: AudioItem): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
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
}
