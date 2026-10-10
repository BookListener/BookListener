package com.booklistener.core

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TagResolver 回退逻辑测试（v1.6.0）：
 * 注入假 extractor，覆盖 title 优先 / 无 tag 回退去扩展名 / ".mp3" 形 /
 * 提取失败不崩溃回退文件名。平台默认 MediaMetadataRetriever 无法在 Robolectric
 * 跑（无真实媒体），以注入路径验证纯逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagResolverTest {

    private fun resolver(extract: (Uri) -> TagResolver.AudioTags?) =
        TagResolver(
            ApplicationProvider.getApplicationContext(),
            extractor = extract
        )

    @Test
    fun titleTag_usedAsDisplayName() {
        val r = resolver { TagResolver.AudioTags("真实歌名", "歌手", "专辑", 3, 1) }
        assertEquals("真实歌名", r.displayNameFor(Uri.parse("content://a/1"), "01-filename.mp3"))
    }

    @Test
    fun blankTitle_fallsBackToFileNameWithoutExt() {
        val r = resolver { TagResolver.AudioTags("  ", "歌手", null, null, null) }
        assertEquals("01-filename", r.displayNameFor(Uri.parse("content://a/1"), "01-filename.mp3"))
    }

    @Test
    fun noTags_fallsBackToFileNameWithoutExt() {
        val r = resolver { null }
        assertEquals("01-filename", r.displayNameFor(Uri.parse("content://a/1"), "01-filename.mp3"))
    }

    @Test
    fun dotOnlyFileName_keepsOriginal() {
        val r = resolver { null }
        assertEquals(".mp3", r.displayNameFor(Uri.parse("content://a/1"), ".mp3"))
    }

    @Test
    fun multiDot_keepsLastExtensionOnly() {
        val r = resolver { null }
        assertEquals("song.name.v2", r.displayNameFor(Uri.parse("content://a/1"), "song.name.v2.flac"))
    }

    @Test
    fun extractorThrows_returnsNull_resolve() {
        val r = resolver { throw RuntimeException("io error") }
        assertNull(r.resolve(Uri.parse("content://a/1")))
        // 不崩溃，回退文件名
        assertEquals("01-filename", r.displayNameFor(Uri.parse("content://a/1"), "01-filename.mp3"))
    }

    @Test
    fun tags_parseFields() {
        val r = resolver { TagResolver.AudioTags("T", null, null, 3, 2) }
        val tags = r.resolve(Uri.parse("content://a/1"))
        assertEquals("T", tags?.title)
        assertEquals(3, tags?.track)
        assertEquals(2, tags?.disc)
    }
}
