package com.booklistener.player

import com.booklistener.cache.CacheStore
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioMeta
import com.booklistener.core.AudioSource
import com.booklistener.core.SourceType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 回归 UT（v1.6.31 缓存开关）：
 * fullFileCacheEnabled=false 时网络源必须在线播放原地址，不触碰缓存；
 * true 时恢复缓存优先（命中返回缓存文件，未命中回退原地址 / 主动下载）。
 */
class DefaultSourceResolverCacheToggleTest {

    private class FakeCache : CacheStore {
        var cachedCalls = 0
        var fullCalls = 0
        var hit: File? = null

        override suspend fun cachedFile(item: AudioItem): File? {
            cachedCalls++
            return hit
        }

        override suspend fun cacheFull(item: AudioItem, downloader: suspend (File) -> Long): File {
            fullCalls++
            val f = File.createTempFile("cache-test", ".audio")
            f.deleteOnExit()
            downloader(f)
            return f
        }

        override suspend fun clearAll() {}

        override fun cleanupPartialFiles() {}
    }

    private class FakeSource(private val uri: String) : AudioSource {
        override suspend fun listDirectory(path: String): Result<List<AudioItem>> =
            Result.success(emptyList())

        override suspend fun getPlayUri(item: AudioItem): Result<String> = Result.success(uri)

        override suspend fun refreshCredential(): Result<Unit> = Result.success(Unit)

        override suspend fun getMeta(item: AudioItem): Result<AudioMeta> =
            Result.success(AudioMeta(item.displayName, "", 0L))
    }

    private fun item(type: SourceType, path: String = "https://example.com/a.m4a") = AudioItem(
        id = "id-$type-$path",
        displayName = "a",
        path = path,
        sourceType = type,
        size = 100L
    )

    private fun resolver(cache: FakeCache, enabled: Boolean): DefaultSourceResolver {
        val sources = mapOf(
            SourceType.WEBDAV to FakeSource("webdav://play"),
            SourceType.ALIYUN to FakeSource("aliyun://play")
        )
        return DefaultSourceResolver(sources, cache, fullFileCacheEnabled = enabled)
    }

    // ---- 开关关闭：不查缓存、不回退缓存，直接在线播放 ----

    @Test
    fun `cache off - webdav resolves original path without touching cache`() = runBlocking {
        val cache = FakeCache()
        val r = resolver(cache, enabled = false)
        val res = r.resolve(item(SourceType.WEBDAV))
        assertEquals("https://example.com/a.m4a", res.getOrThrow())
        assertEquals(0, cache.cachedCalls)
    }

    @Test
    fun `cache off - aliyun resolves play uri without touching cache`() = runBlocking {
        val cache = FakeCache()
        val r = resolver(cache, enabled = false)
        val res = r.resolve(item(SourceType.ALIYUN))
        assertEquals("aliyun://play", res.getOrThrow())
        assertEquals(0, cache.cachedCalls)
    }

    @Test
    fun `cache off - cacheAliyunItem returns play uri and does not download`() = runBlocking {
        val cache = FakeCache()
        val r = resolver(cache, enabled = false)
        val res = r.cacheAliyunItem(item(SourceType.ALIYUN))
        assertEquals("aliyun://play", res.getOrThrow())
        assertEquals(0, cache.cachedCalls)
        assertEquals(0, cache.fullCalls)
    }

    // ---- 开关开启：恢复缓存优先 ----

    @Test
    fun `cache on - webdav hit returns cached file`() = runBlocking {
        val cache = FakeCache().apply { hit = File("/tmp/cached.webdav.audio") }
        val r = resolver(cache, enabled = true)
        val res = r.resolve(item(SourceType.WEBDAV))
        assertEquals(hitUri("cached.webdav.audio"), res.getOrThrow())
        assertEquals(1, cache.cachedCalls)
    }

    @Test
    fun `cache on - webdav miss falls back to original path`() = runBlocking {
        val cache = FakeCache() // hit = null
        val r = resolver(cache, enabled = true)
        val res = r.resolve(item(SourceType.WEBDAV))
        assertEquals("https://example.com/a.m4a", res.getOrThrow())
        assertEquals(1, cache.cachedCalls)
    }

    @Test
    fun `cache on - aliyun hit returns cached file`() = runBlocking {
        val cache = FakeCache().apply { hit = File("/tmp/cached.aliyun.audio") }
        val r = resolver(cache, enabled = true)
        val res = r.resolve(item(SourceType.ALIYUN))
        assertEquals(hitUri("cached.aliyun.audio"), res.getOrThrow())
        assertEquals(1, cache.cachedCalls)
    }

    @Test
    fun `cache on - cacheAliyunItem downloads when miss`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("fake-audio-bytes"))
        server.start()
        try {
            val cache = FakeCache() // hit = null
            val sources = mapOf(SourceType.ALIYUN to FakeSource(server.url("/a.m4a").toString()))
            val r = DefaultSourceResolver(sources, cache, fullFileCacheEnabled = true)
            val res = r.cacheAliyunItem(item(SourceType.ALIYUN))
            assertNull(res.exceptionOrNull())
            assertEquals(1, cache.cachedCalls)
            assertEquals(1, cache.fullCalls)
        } finally {
            server.shutdown()
        }
    }

    private fun hitUri(name: String) = File("/tmp/$name").toURI().toString()
}
