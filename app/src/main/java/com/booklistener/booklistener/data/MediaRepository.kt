package com.booklistener.data

import android.content.Context
import com.booklistener.auth.AliyunAuthManager
import com.booklistener.auth.CredentialStore
import com.booklistener.cache.CacheManager
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioSource
import com.booklistener.core.AudioSourceException
import com.booklistener.core.SourceType
import com.booklistener.db.AppDatabase
import com.booklistener.db.FavoriteItem
import com.booklistener.db.PlayRecord
import com.booklistener.player.DefaultSourceResolver
import com.booklistener.source.AliyunAudioSource
import com.booklistener.source.LocalAudioSource
import com.booklistener.source.WebDavAudioSource
import com.booklistener.source.WebDavDirEntry
import com.booklistener.source.normalizeWebDavUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 媒体仓库：UI 的唯一数据入口。
 * 聚合本地/WebDAV/阿里云三个数据源 + 缓存 + 数据库，对外暴露高层操作。
 */
class MediaRepository(
    private val context: Context,
    /** 测试注入：非加密 CredentialStore（JVM 单测无 AndroidKeyStore） */
    credentialStore: CredentialStore? = null
) {

    val credentialStore = credentialStore ?: CredentialStore(context)
    val aliyunAuth = AliyunAuthManager(context)
    val cache = CacheManager(context)

    private val db = AppDatabase.getInstance(context)

    private val localSource: AudioSource by lazy { LocalAudioSource(context) }
    private val aliyunSource: AudioSource by lazy { AliyunAudioSource(aliyunAuth) }

    val sources: Map<SourceType, AudioSource> = mapOf(
        SourceType.LOCAL to localSource,
        SourceType.WEBDAV to (webDavSourceFor("") ?: WebDavAudioSource { null }),
        SourceType.ALIYUN to aliyunSource
    )

    /**
     * v1.6.25 多账号：按归一化地址找到对应 WebDAV 数据源；未配置返回 null。
     * 每个账号独立持有凭证，互不影响。
     */
    fun webDavSourceFor(baseUrl: String): WebDavAudioSource? {
        val norm = normalizeWebDavUrl(baseUrl)
        val cred = credentialStore.webDavAccounts()
            .firstOrNull { it.normalizedUrl == norm }
            ?: return null
        return WebDavAudioSource { cred }
    }

    /** 按账号列 WebDAV 目录（path 为空 = 账号根目录） */
    suspend fun listWebDavDirectory(baseUrl: String, path: String): Result<List<AudioItem>> {
        val source = webDavSourceFor(baseUrl)
            ?: return Result.failure(AudioSourceException.AuthFailed("WebDAV not configured"))
        return source.listDirectory(path)
    }

    /** 按账号列 WebDAV 目录（子目录 + 音频混合，v1.6.27 目录导航用） */
    suspend fun listWebDavDirEntries(
        baseUrl: String,
        path: String
    ): Result<Pair<List<WebDavDirEntry>, List<AudioItem>>> {
        val source = webDavSourceFor(baseUrl)
            ?: return Result.failure(AudioSourceException.AuthFailed("WebDAV not configured"))
        return source.listDirEntries(path)
    }

    val resolver: DefaultSourceResolver by lazy {
        DefaultSourceResolver(sources, cache)
    }

    // ---- 目录浏览 ----

    suspend fun listDirectory(type: SourceType, path: String): Result<List<AudioItem>> {
        val source = sources[type] ?: return Result.failure(IllegalStateException("no source"))
        return source.listDirectory(path)
    }

    // ---- 收藏 ----

    fun favorites(): Flow<List<FavoriteItem>> = db.dao().getAllFavorites()

    suspend fun isFavorite(id: String): Boolean =
        withContext(Dispatchers.IO) { db.dao().getFavorite(id) != null }

    suspend fun toggleFavorite(item: AudioItem) {
        withContext(Dispatchers.IO) {
            if (db.dao().getFavorite(item.id) == null) {
                db.dao().addFavorite(FavoriteItem(item.id, System.currentTimeMillis()))
            } else {
                db.dao().removeFavorite(item.id)
            }
        }
    }

    // ---- 播放记录 ----

    suspend fun savePlayRecord(item: AudioItem, positionMs: Long) {
        withContext(Dispatchers.IO) {
            val rec = PlayRecord(item.id, positionMs, System.currentTimeMillis())
            if (db.dao().getPlayRecord(item.id) == null) {
                db.dao().insertPlayRecord(rec)
            } else {
                db.dao().updatePlayRecord(rec)
            }
        }
    }

    fun playRecords(): Flow<List<PlayRecord>> = db.dao().getAllPlayRecords()

    // ---- 缓存 ----

    suspend fun clearCache() = cache.clearAll()

    fun cleanupPartial() = cache.cleanupPartialFiles()

    suspend fun cacheItem(item: AudioItem): Result<String> {
        return when (item.sourceType) {
            SourceType.ALIYUN -> resolver.cacheAliyunItem(item)
            else -> Result.success(item.path)
        }
    }
}
