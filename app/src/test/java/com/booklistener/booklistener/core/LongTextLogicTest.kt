package com.booklistener.core

import com.booklistener.ui.fileExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.6.4 超长文本处理逻辑测试：
 * - fileExtension：文件名扩展名提取（决定是否保留尾部）；
 * - formatDuration：时长格式支持小时位（>=1h → h:mm:ss，保持 mm:ss 回归）。
 */
class LongTextLogicTest {

    // ---- fileExtension ----

    @Test
    fun extension_normalAudioFile() {
        assertEquals("mp3", fileExtension("大难不死的男孩.mp3"))
        assertEquals("flac", fileExtension("track01.flac"))
    }

    @Test
    fun extension_dottedName_keepsLastSegment() {
        assertEquals("mp3", fileExtension("哈利·波特与魔法石.第1集.mp3"))
    }

    @Test
    fun extension_none_returnsNull() {
        assertNull(fileExtension("哈利·波特与魔法石"))
        assertNull(fileExtension("第1集 大难不死的男孩"))
    }

    @Test
    fun extension_edgeCases() {
        // 点号在开头（隐藏文件）或结尾 → 无扩展名语义
        assertNull(fileExtension(".hidden"))
        assertNull(fileExtension("song."))
        // 扩展名超长（非音频后缀场景）→ 不当作扩展名
        assertNull(fileExtension("name.tar.gzip-extra"))
    }

    // ---- formatDuration 小时位 ----

    @Test
    fun duration_underOneHour_keepsMmSs() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:00", formatDuration(-5))
        assertEquals("00:59", formatDuration(59_000))
        assertEquals("19:10", formatDuration(1_150_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun duration_exactlyOneHour_showsHours() {
        assertEquals("1:00:00", formatDuration(3_600_000))
    }

    @Test
    fun duration_overOneHour_showsHours() {
        assertEquals("1:01:01", formatDuration(3_661_000))
        assertEquals("2:03:45", formatDuration(7_425_000))
    }
}
