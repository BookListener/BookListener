package com.booklistener.core

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenFileItemFactory（SAF/外部打开 → AudioItem）的元数据构造逻辑测试：
 * 覆盖"能播放但名字/大小错乱"这类低级回归。
 * 查询函数注入假实现，不依赖真实 ContentResolver。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenFileItemFactoryTest {

    private val uri = Uri.parse("content://com.android.providers.downloads.documents/document/123")

    @Test
    fun build_usesQueriedNameAndSize() {
        val factory = OpenFileItemFactory { _ -> "my-song.mp3" to 2048L }
        val item = factory.build(uri)
        assertEquals("my-song.mp3", item.displayName)
        assertEquals(2048L, item.size)
        assertEquals(uri.toString(), item.path)
        assertEquals(SourceType.LOCAL, item.sourceType)
    }

    @Test
    fun build_blankName_fallsBackToLastPathSegment() {
        val factory = OpenFileItemFactory { _ -> "   " to 1L }
        assertEquals("123", factory.build(uri).displayName)
    }

    @Test
    fun build_queryFailure_fallsBackToLastPathSegmentAndZeroSize() {
        val factory = OpenFileItemFactory { _ -> null }
        val item = factory.build(uri)
        assertEquals("123", item.displayName)
        assertEquals(0L, item.size)
    }

    @Test
    fun build_uriWithoutPathSegment_fallsBackToAudio() {
        val factory = OpenFileItemFactory { _ -> null }
        val bare = Uri.parse("content://provider/")
        assertEquals("audio", factory.build(bare).displayName)
    }
}
