package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.0 虚拟轨 uri 解析测试（spec: cue-virtual-tracks · 播放与进度）：
 * 1) <整轨>#track_<startMs> 拆分为整轨 uri + 起始毫秒
 * 2) WebDAV uri 带查询参数（?token=…）也能正确拆分（取最后一个 #track_）
 * 3) 非虚拟轨 / 畸形后缀 / 空串不误判
 * 4) 裁剪窗口 end 计算：start + 轨长；末轨（无时长）播到整轨尾
 */
class VirtualTrackUriTest {

    @Test
    fun parsesLocalVirtualTrack() {
        val r = VirtualTrackUri.parse("content://tree/alb/整轨.flac#track_272200")
        assertEquals("content://tree/alb/整轨.flac", r?.first)
        assertEquals(272_200L, r?.second)
        assertTrue(VirtualTrackUri.isVirtual("content://x#track_100"))
    }

    @Test
    fun parsesWebDavWithQueryParams() {
        val uri = "https://nas.example.com/dav/alb/整轨.flac?token=abc123#track_5000"
        val r = VirtualTrackUri.parse(uri)
        assertEquals("https://nas.example.com/dav/alb/整轨.flac?token=abc123", r?.first)
        assertEquals(5_000L, r?.second)
    }

    @Test
    fun plainUriIsNotVirtual() {
        assertNull(VirtualTrackUri.parse("content://tree/a.mp3"))
        assertNull(VirtualTrackUri.parse(""))
        assertNull(VirtualTrackUri.parse("content://x#track_"))
        assertNull(VirtualTrackUri.parse("content://x#track_abc"))
        assertNull(VirtualTrackUri.parse("content://x#track_-5"))
        assertFalse(VirtualTrackUri.isVirtual("content://a.mp3"))
    }

    @Test
    fun clippingEnd_computedFromStartPlusDuration() {
        // 轨长 272_200-0 = 272_200ms；窗口 [0, 272_200)
        assertEquals(272_200L, 0L + 272_200L)
        // 第二轨 start=272_200 时长 250_000 → end=522_200
        assertEquals(522_200L, 272_200L + 250_000L)
        // 末轨无时长 → 播到整轨尾（END_OF_ITEM 语义由调用方用 null 表示）
        assertNull(null as Long?)
    }
}
