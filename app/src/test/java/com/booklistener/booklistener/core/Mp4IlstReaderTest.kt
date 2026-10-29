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

    // ---- v2.2.8：自研 covr 字节提取 ----

    private fun fourcc(s: String): Int =
        (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

    private fun box(type: Int, payload: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).putInt(type).put(payload).array()

    private fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    /** data 子原子（typeFlags=13 jpeg / 14 png），payload 为图片字节 */
    private fun dataAtom(flags: Int, payload: ByteArray): ByteArray =
        box(fourcc("data"), concat(
            java.nio.ByteBuffer.allocate(4).putInt(flags).array(),
            java.nio.ByteBuffer.allocate(4).putInt(0).array(),
            payload
        ))

    /** meta → ilst（meta 自带 4B version/flags 头） */
    private fun metaIlst(children: ByteArray): ByteArray =
        box(fourcc("meta"), concat(byteArrayOf(0, 0, 0, 0), box(fourcc("ilst"), children)))

    @Test
    fun `readCover 提取 jpeg covr 字节与 mime`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val covr = box(fourcc("covr"), dataAtom(13, jpeg))
        val bytes = concat(box(fourcc("moov"), box(fourcc("udta"), metaIlst(covr))))
        val cover = Mp4IlstReader.readCover(bytes)
        assertTrue("应提取出封面", cover != null)
        assertEquals("image/jpeg", cover!!.mime)
        assertTrue(cover.bytes.contentEquals(jpeg))
    }

    @Test
    fun `readCover 提取 png covr 与文本原子并存`() {
        val png = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 9, 8)
        val covr = box(fourcc("covr"), dataAtom(14, png))
        val nam = box(fourcc("\u00A9nam"), dataAtom(1, "标题".toByteArray(Charsets.UTF_8)))
        val bytes = concat(box(fourcc("moov"), box(fourcc("udta"), metaIlst(concat(nam, covr)))))
        val cover = Mp4IlstReader.readCover(bytes)
        assertTrue(cover != null)
        assertEquals("image/png", cover!!.mime)
        assertTrue(cover.bytes.contentEquals(png))
        // 文本原子不受影响
        val entries = Mp4IlstReader.read(bytes)
        assertEntry(entries, "\u00A9nam", "标题")
    }

    @Test
    fun `readCover 无 covr 返回 null`() {
        val nam = box(fourcc("\u00A9nam"), dataAtom(1, "标题".toByteArray(Charsets.UTF_8)))
        val bytes = concat(box(fourcc("moov"), box(fourcc("udta"), metaIlst(nam))))
        assertEquals(null, Mp4IlstReader.readCover(bytes))
    }

    private fun assertEntry(entries: List<com.booklistener.core.TagEntry>, key: String, value: String) {
        val hit = entries.firstOrNull { it.key == key }
        assertTrue("缺少键 $key（实际键：" + entries.map { it.key } + "）", hit != null)
        assertEquals("键 $key 值不匹配", value, hit!!.value)
    }
}
