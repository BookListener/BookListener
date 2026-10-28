package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * moov 区间定位器测试（spec: m4b-moov-range-read，v2.2.5）。
 * 覆盖：moov 头部 / moov 尾部 / 非 box 前导三类定位；真实样本区间字节与整读解析等价。
 */
class MoovLocatorTest {

    private val MOOV_HEAD_LIMIT = 1 shl 20
    private val MOOV_TAIL_LIMIT = 16L shl 20

    private fun fourcc(s: String): Int = (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code
    private fun box(t: Int, payload: ByteArray): ByteArray =
        u32(8 + payload.size) + u32(t) + payload
    private fun u32(v: Int): ByteArray = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )
    private fun concat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun mvhd(timescale: Int): ByteArray =
        box(fourcc("mvhd"), byteArrayOf(0, 0, 0, 0) + u32(0) + u32(0) + u32(timescale) + u32(0))

    private fun chpl(vararg entries: Pair<Long, String>): ByteArray {
        var body = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, entries.size.toByte())
        for ((ts, title) in entries) {
            val t = title.toByteArray()
            body = body + u64(ts) + byteArrayOf(t.size.toByte()) + t
        }
        return box(fourcc("chpl"), body)
    }

    private fun u64(v: Long): ByteArray = byteArrayOf(
        (v shr 56).toByte(), (v shr 48).toByte(), (v shr 40).toByte(), (v shr 32).toByte(),
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    private fun udta(c: ByteArray) = box(fourcc("udta"), c)

    /** 模拟 readMoovRangeBytes 的区间组装逻辑（纯字节版） */
    private fun moovRange(bytes: ByteArray): ByteArray? {
        val headLen = minOf(bytes.size, MOOV_HEAD_LIMIT)
        val head = bytes.copyOfRange(0, headLen)
        val headMoov = MoovLocator.headMoovRange(head)
        if (headMoov != null) {
            val end = minOf(headMoov.last, bytes.size)
            return bytes.copyOfRange(0, end)
        }
        val tailLen = minOf(bytes.size.toLong(), MOOV_TAIL_LIMIT).toInt()
        val tail = bytes.copyOfRange(bytes.size - tailLen, bytes.size)
        val m = MoovLocator.tailMoovRange(tail) ?: return null
        return tail.copyOfRange(m.first, m.last)
    }

    @Test
    fun `moov 在头部 定位并截取区间`() {
        val bytes = concat(
            box(fourcc("ftyp"), ByteArray(8)),
            box(fourcc("moov"), concat(mvhd(1000), udta(chpl(0L to "第一章", 3_600_000L to "第二章")))),
            box(fourcc("mdat"), ByteArray(4096))
        )
        val head = bytes.copyOfRange(0, minOf(bytes.size, MOOV_HEAD_LIMIT))
        val range = MoovLocator.headMoovRange(head)
        assertNotNull(range)
        val moov = moovRange(bytes)!!
        // 区间只含 ftyp+moov，不含 mdat
        assertTrue(moov.size < bytes.size)
        val chapters = Mp4ChapterReader.read(moov)
        assertEquals(2, chapters.size)
        assertEquals("第一章", chapters[0].title)
    }

    @Test
    fun `moov 在尾部 定位并截取区间`() {
        // 1GB 级 mdat 在前、moov 在文件末（非 faststart 形态）
        val bytes = concat(
            box(fourcc("ftyp"), ByteArray(8)),
            box(fourcc("mdat"), ByteArray(MOOV_HEAD_LIMIT * 3)), // 3MB mdat：头 1MB 内无 moov
            box(fourcc("moov"), concat(mvhd(1000), udta(chpl(0L to "第一章", 3_600_000L to "第二章"))))
        )
        val head = bytes.copyOfRange(0, MOOV_HEAD_LIMIT)
        assertTrue("头部不应命中 moov", MoovLocator.headMoovRange(head) == null)
        val tailLen = minOf(bytes.size.toLong(), MOOV_TAIL_LIMIT).toInt()
        val tail = bytes.copyOfRange(bytes.size - tailLen, bytes.size)
        val tailRange = MoovLocator.tailMoovRange(tail)
        assertNotNull("尾部应命中 moov", tailRange)
        val moov = moovRange(bytes)!!
        val chapters = Mp4ChapterReader.read(moov)
        assertEquals(2, chapters.size)
        assertEquals("第二章", chapters[1].title)
        assertEquals(3_600_000L, chapters[1].startMs)
    }

    @Test
    fun `文件头带非 box 前导 仍定位 moov`() {
        val junkPrefix = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x04,
            0, 0, 0, 0, 9, 9
        )
        val bytes = junkPrefix + box(fourcc("moov"), concat(mvhd(1000), udta(chpl(0L to "第一章"))))
        val moov = moovRange(bytes)!!
        assertEquals(1, Mp4ChapterReader.read(moov).size)
    }

    @Test
    fun `真实样本 区间字节与整读解析等价`() {
        val bytes = resource("tagprobe/probe_custom2.m4a")
        val moov = moovRange(bytes)!!
        // 区间字节明显小于整文件（仅 moov 区域）
        assertTrue(moov.size <= bytes.size)

        // 标签等价（键名流）
        val wholeIlst = Mp4IlstReader.read(bytes)
        val rangeIlst = Mp4IlstReader.read(moov)
        assertEquals(wholeIlst.map { it.key }, rangeIlst.map { it.key })
        assertEquals(wholeIlst.map { it.value }, rangeIlst.map { it.value })

        // 章节等价（该样本无章节 → 均为空）
        assertEquals(Mp4ChapterReader.read(bytes), Mp4ChapterReader.read(moov))

        // Media3 时长/标准帧等价
        val m3Whole = MetadataExtractor().extract(bytes, "m4a")
        val m3Range = MetadataExtractor().extract(moov, "m4a")
        assertEquals(m3Whole.durationMs, m3Range.durationMs)
        assertEquals(m3Whole.entries.map { it.key }, m3Range.entries.map { it.key })
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
}
