package com.booklistener.source

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * WebDAV 全链路列举回归（v1.6.26 / v1.6.28）：MockWebServer 模拟坚果云风格 PROPFIND 响应
 * （相对 href、目录带 collection、无 displayname 的条目回退文件名），
 * 验证 listDirectory 真实产出音频条目。此前用户实测"明明有 m4a 却显示没有"。
 * v1.6.28：listDirEntries/probeDirectory 接收完整目录 URL；href 解析为完整 URL。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavAudioSourceListTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/dav/").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueuePropfindResponse(expectAuth: String? = null) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>0</D:getcontentlength>
        <D:resourcetype><D:collection/></D:resourcetype>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/books/</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>0</D:getcontentlength>
        <D:resourcetype><D:collection/></D:resourcetype>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E7%AC%AC%E4%B8%80%E7%AB%A0.m4a</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>1234567</D:getcontentlength>
        <D:resourcetype/>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/readme.txt</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>99</D:getcontentlength>
        <D:resourcetype/>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(xml)
        )
    }

    @Test
    fun listRoot_returnsM4a_excludesDirsAndTxt() {
        enqueuePropfindResponse()
        val cred = WebDavCredential(baseUrl, "user", "pass")
        val source = WebDavAudioSource { cred }

        val result = runBlocking { source.listDirectory(baseUrl) }

        assertTrue("PROPFIND 应成功，实际失败: ${result.exceptionOrNull()}", result.isSuccess)
        val items = result.getOrThrow()
        assertEquals("只应解析出 1 个音频文件", 1, items.size)
        val item = items[0]
        assertEquals("第一章", item.displayName)
        assertEquals("第一章.m4a", item.rawName)
        // v1.6.28：href 解析为完整 URL
        assertEquals(baseUrl + "%E7%AC%AC%E4%B8%80%E7%AB%A0.m4a", item.path)
        assertEquals(1234567L, item.size)
    }

    @Test
    fun probeDirectory_countsAudioAndSubdirs() {
        enqueuePropfindResponse()
        val cred = WebDavCredential(baseUrl, "user", "pass")
        val source = WebDavAudioSource { cred }

        val result = runBlocking { source.probeDirectory(baseUrl) }

        assertTrue("PROPFIND 应成功，实际失败: ${result.exceptionOrNull()}", result.isSuccess)
        val (items, dirs) = result.getOrThrow()
        assertEquals(1, items.size)
        // 响应含根自身(/dav/)与 /dav/books/ 两个目录条目，根不计入 → 子目录 1
        assertEquals("子目录计数应为 1（根自身不计）", 1, dirs)
    }

    @Test
    fun listDirEntries_returnsSubdirsAndAudio() {
        enqueuePropfindResponse()
        val cred = WebDavCredential(baseUrl, "user", "pass")
        val source = WebDavAudioSource { cred }

        val result = runBlocking { source.listDirEntries(baseUrl) }

        assertTrue("PROPFIND 应成功，实际失败: ${result.exceptionOrNull()}", result.isSuccess)
        val (dirs, items) = result.getOrThrow()
        assertEquals("响应中根(/dav/)与 /dav/books/ 两个目录条目，根排除后子目录 1 个", 1, dirs.size)
        assertEquals("books", dirs[0].name)
        // v1.6.28：href 解析为完整 URL
        assertEquals(baseUrl + "books/", dirs[0].href)
        assertEquals(1, items.size)
        assertEquals("第一章", items[0].displayName)
    }

    @Test
    fun wrongPassword_returnsAuthFailed() {
        server.enqueue(MockResponse().setResponseCode(401))
        val cred = WebDavCredential(baseUrl, "user", "wrong")
        val source = WebDavAudioSource { cred }

        val result = runBlocking { source.listDirectory(baseUrl) }

        assertTrue(result.isFailure)
        assertTrue(
            "应为 AuthFailed，实际 ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.exceptionOrNull() is com.booklistener.core.AudioSourceException.AuthFailed
        )
    }

    /** 相对 href（服务器根 / 当前目录相对）解析为完整 URL（v1.6.28 播放/导航前置） */
    @Test
    fun resolveWebDavHref_relativeAndAbsolute() {
        val base = "https://dav.jianguoyun.com/dav/"
        assertEquals("https://dav.jianguoyun.com/dav/Movies/", resolveWebDavHref(base, "/dav/Movies/"))
        assertEquals("https://dav.jianguoyun.com/dav/Movies/", resolveWebDavHref(base, "Movies/"))
        assertEquals("https://dav.jianguoyun.com/dav/%E7%AC%AC%E4%B8%80%E7%AB%A0.m4a",
            resolveWebDavHref(base, "/dav/%E7%AC%AC%E4%B8%80%E7%AB%A0.m4a"))
        // 已是完整 URL 原样返回
        val absolute = "https://other.example/x/1.m4a"
        assertEquals(absolute, resolveWebDavHref(base, absolute))
    }
}
