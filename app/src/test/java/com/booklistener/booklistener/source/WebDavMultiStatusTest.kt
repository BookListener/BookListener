package com.booklistener.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV multistatus 解析回归（v1.6.26）：
 * 1) 带命名空间前缀（d:response/d:href/...）的响应必须正确解析出音频文件——
 *    此前 XmlPullParser 未开启 namespace 处理，parser.name 返回带前缀标签名，
 *    when 全部分支失配导致"明明有 m4a 却显示没有"。
 * 2) 目录条目（href 尾斜杠 / resourcetype 含 collection）与非音频文件必须被排除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavMultiStatusTest {

    @Test
    fun prefixedMultistatus_parsesAudio_excludesDirsAndNonAudio() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:ns="http://example.com/ns">
  <d:response>
    <d:href>https://dav.example.com/dav/</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>dav</d:displayname>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>https://dav.example.com/dav/books/</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>books</d:displayname>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>https://dav.example.com/dav/chapter01.m4a</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>chapter01.m4a</d:displayname>
        <d:getcontentlength>1234567</d:getcontentlength>
        <d:resourcetype/>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>https://dav.example.com/dav/readme.txt</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>readme.txt</d:displayname>
        <d:resourcetype/>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        val items = parseWebDavMultiStatus(xml, "https://dav.example.com/dav/")

        assertEquals("只应解析出 1 个音频文件", 1, items.size)
        val item = items[0]
        assertEquals("chapter01", item.displayName)
        assertEquals("chapter01.m4a", item.rawName)
        assertEquals("https://dav.example.com/dav/chapter01.m4a", item.path)
        assertEquals(1234567L, item.size)
    }

    @Test
    fun missingDisplayname_fallsBackToHrefFilename() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>https://dav.example.com/dav/part-02.mp3</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        val items = parseWebDavMultiStatus(xml, "https://dav.example.com/dav/")

        assertEquals(1, items.size)
        assertEquals("part-02", items[0].displayName)
        assertEquals("part-02.mp3", items[0].rawName)
    }

    @Test
    fun rootHrefOnly_returnsEmpty() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>https://dav.example.com/dav/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        assertTrue(parseWebDavMultiStatus(xml, "https://dav.example.com/dav/").isEmpty())
    }

    @Test
    fun detailed_countsSubdirs_excludesSelf() {
        // 根 href 用相对路径（坚果云风格），不应计入子目录；两个子目录应计数为 2
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/Movies/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/Books/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/song.flac</d:href>
    <d:propstat>
      <d:prop><d:resourcetype/></d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        val (items, dirs) = parseWebDavMultiStatusDetailed(xml, "https://dav.example.com/dav/")

        assertEquals("根自身(相对 href)不应计入子目录", 2, dirs)
        assertEquals(1, items.size)
        assertEquals("song", items[0].displayName)
    }

    @Test
    fun dirEntries_returnsDirsAndAudio_separated() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/Movies/</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>Movies</d:displayname>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/%E5%8F%A4%E8%AF%97/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/part01.m4a</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>part01.m4a</d:displayname>
        <d:resourcetype/>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        val (dirs, items) = parseWebDavDirEntries(xml, "https://dav.example.com/dav/")

        assertEquals("子目录 2 个（根自身排除）", 2, dirs.size)
        assertEquals("有 displayname 用明文", "Movies", dirs[0].name)
        assertEquals("无 displayname 回退 href 末段并解码", "古诗", dirs[1].name)
        // v1.6.28：href 解析为完整 URL（播放/导航统一）
        assertEquals("href 解析为完整 URL", "https://dav.example.com/dav/%E5%8F%A4%E8%AF%97/", dirs[1].href)
        assertEquals(1, items.size)
        assertEquals("part01", items[0].displayName)
    }
}
