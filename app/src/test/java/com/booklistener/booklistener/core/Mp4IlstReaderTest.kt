package com.booklistener.booklistener.core

import com.booklistener.core.Mp4IlstReader
import com.booklistener.core.TagFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研 MP4 ilst 解析器测试（spec: tag-fields-full）。
 * 资源：tagprobe/probe_custom2.m4a（含 ©nam/©ART/©alb/aART/trkn/dis/©gen + 手工注入 ©ser/©sno/stik）。
 */
class Mp4IlstReaderTest {

    @Test
    fun `probe_custom2 解析出标准与扩展原子原始键名`() {
        val bytes = resource("tagprobe/probe_custom2.m4a")
        val entries = Mp4IlstReader.read(bytes)

        assertTrue("ilst 未解析出任何条目", entries.isNotEmpty())
        assertEntry(entries, "\u00A9ser", "哈利·波特系列")
        assertEntry(entries, "\u00A9sno", "1")
        assertEntry(entries, "stik", "2")
        // 二进制原子解析为 "n/total"
        assertEntry(entries, "trkn", "1/17")
        assertEntry(entries, "disk", "1/7")
        // 标准文本原子
        assertEntry(entries, "\u00A9nam", "大难不死的男孩")
        assertEntry(entries, "\u00A9ART", "朗读者甲")
        assertEntry(entries, "\u00A9alb", "哈利·波特与魔法石")
        assertEntry(entries, "aART", "J.K. Rowling")
        assertEntry(entries, "\u00A9gen", "Audiobook")
    }

    @Test
    fun `结构化提取 从 ilst 键值流聚合出系列与媒体类型`() {
        val entries = Mp4IlstReader.read(resource("tagprobe/probe_custom2.m4a"))
        assertEquals("哈利·波特系列", TagFields.series(entries))
        assertEquals("1", TagFields.seriesNumber(entries))
        // stik=2 → Audiobook（文档 §3：2=有声书）
        assertEquals("Audiobook", TagFields.mediaType(entries))
        assertEquals(1, TagFields.track(entries))
        assertEquals(1, TagFields.disc(entries))
        assertEquals("大难不死的男孩", TagFields.title(entries))
        assertEquals("J.K. Rowling", TagFields.albumArtist(entries))
    }

    @Test
    fun `非 mp4 内容 返回空列表不抛异常`() {
        val junk = ByteArray(4096) { it.toByte() }
        assertTrue(Mp4IlstReader.read(junk).isEmpty())
        assertTrue(Mp4IlstReader.read(ByteArray(0)).isEmpty())
    }

    @Test
    fun `文件头带非 box 前导 仍解析 ilst`() {
        // ID3 风格前导：size 字段巨大越界，旧逻辑整体放弃，v2.2.4 逐字节重对齐
        val junkPrefix = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x04,
            0, 0, 0, 0, 1, 2, 3, 4
        )
        val bytes = junkPrefix + resource("tagprobe/probe_custom2.m4a")
        val entries = Mp4IlstReader.read(bytes)
        assertEntry(entries, "\u00A9ser", "哈利·波特系列")
        assertEntry(entries, "\u00A9nam", "大难不死的男孩")
        assertEntry(entries, "\u00A9alb", "哈利·波特与魔法石")
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun assertEntry(entries: List<com.booklistener.core.TagEntry>, key: String, value: String) {
        val hit = entries.firstOrNull { it.key == key }
        assertTrue("缺少键 $key（实际键：" + entries.map { it.key } + "）", hit != null)
        assertEquals("键 $key 值不匹配", value, hit!!.value)
    }
}
