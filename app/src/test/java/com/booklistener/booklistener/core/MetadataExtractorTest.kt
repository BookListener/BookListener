package com.booklistener.booklistener.core

import com.booklistener.core.MetadataExtractor
import com.booklistener.core.TagFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Media3 直驱元数据读取器测试（spec: tag-fields-full）。
 * 资源：tagprobe/probe_txxx.mp3（TXXX 规范 \0 帧 + ffmpeg 冒号风格帧）、probe_custom2.m4a。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataExtractorTest {

    @Test
    fun `mp3 TXXX 双风格拆分与标准帧全量`() {
        val bytes = resource("tagprobe/probe_txxx.mp3")
        val result = MetadataExtractor().extract(bytes, "mp3")
        val entries = result.entries

        assertTrue("mp3 未产出元数据条目", entries.isNotEmpty())
        // 规范 \0 帧：desc=SERIES → 键 SERIES
        assertEntry(entries, "SERIES", "哈利·波特系列")
        // ffmpeg 冒号风格：desc 空白、value=SERIESNUMBER:1 → 按首冒号拆键值
        assertEntry(entries, "SERIESNUMBER", "1")
        // 标准文本帧
        assertEntry(entries, "TIT2", "大难不死的男孩")
        assertEntry(entries, "TPE2", "J.K. Rowling")
        assertEntry(entries, "TRCK", "1/17")
        assertEntry(entries, "TPOS", "1/7")
        assertEntry(entries, "TCON", "Audiobook")
        // 结构化提取（业务语义）
        assertEquals("哈利·波特系列", TagFields.series(entries))
        assertEquals("1", TagFields.seriesNumber(entries))
        assertEquals("Audiobook", TagFields.mediaType(entries)) // TCON=Audiobook 原样保留
        assertEquals(1, TagFields.track(entries))
    }

    @Test
    fun `m4a 标准原子映射帧与时长可达`() {
        val bytes = resource("tagprobe/probe_custom2.m4a")
        val result = MetadataExtractor().extract(bytes, "m4a")
        val entries = result.entries

        // Media3 1.4.0：MP4 iTunes 标准原子映射为 ID3 等价帧
        assertEntry(entries, "TIT2", "大难不死的男孩")
        assertEntry(entries, "TPE1", "朗读者甲")
        assertEntry(entries, "TALB", "哈利·波特与魔法石")
        assertEntry(entries, "TPE2", "J.K. Rowling")
        // 时长来自 SeekMap（moov mvhd）
        assertNotNull("m4a 时长缺失", result.durationMs)
        assertTrue("m4a 时长异常", (result.durationMs ?: 0L) > 0L)
    }

    @Test
    fun `无法识别的容器 返回空结果`() {
        val junk = ByteArray(2048) { it.toByte() }
        val result = MetadataExtractor().extract(junk, "mp3")
        assertTrue(result.entries.isEmpty())
        assertTrue(result.durationMs == null)
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun assertEntry(entries: List<com.booklistener.core.TagEntry>, key: String, value: String) {
        val hit = entries.firstOrNull { it.key == key }
        assertTrue("缺少键 $key（实际键：" + entries.map { it.key } + "）", hit != null)
        assertEquals("键 $key 值不匹配", value, hit!!.value)
    }
}
