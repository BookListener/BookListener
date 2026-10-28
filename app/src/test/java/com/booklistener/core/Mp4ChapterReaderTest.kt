package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * m4b 章节解析器构造字节单测（spec: m4b-audiobook-chapters）。
 * 纯 box 结构字节级验证：chpl 时间轴换算、iTunes 章节轨（tref/chap + stts + ilst 名）优先、兜底与退化。
 */
class Mp4ChapterReaderTest {

    // ---- 构造 helpers（大端） ----
    private fun fourcc(s: String): Int =
        (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

    private fun box(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).putInt(type).put(payload).array()

    private fun u32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
    private fun u64(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
    private fun u8(v: Int): ByteArray = byteArrayOf(v.toByte())
    private fun text(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

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

    /** mvhd（v0）：timescale 在 payload offset 12 */
    private fun mvhd(timescale: Int): ByteArray =
        box(fourcc("mvhd"), concat(u8(0), u8(0), u8(0), u8(0), u32(0), u32(0), u32(timescale), u32(0)))

    /** tkhd（v0）：track_ID 在 payload offset 8 */
    private fun tkhd(trackId: Int): ByteArray =
        box(fourcc("tkhd"), concat(u8(0), u8(0), u8(0), u8(0), u32(0), u32(0), u32(trackId), u32(0), u32(0)))

    /** mdhd（v0）：timescale 在 payload offset 12 */
    private fun mdhd(timescale: Int): ByteArray =
        box(fourcc("mdhd"), concat(u8(0), u8(0), u8(0), u8(0), u32(0), u32(0), u32(timescale), u32(0)))

    /** stts：entries = [(sampleCount, delta)]，样本时间从 0 累积 */
    private fun stts(vararg entries: Pair<Int, Int>): ByteArray {
        val payload = concat(u8(0), u8(0), u8(0), u8(0), u32(entries.size))
        var body = payload
        for ((count, delta) in entries) body = concat(body, u32(count), u32(delta))
        return box(fourcc("stts"), body)
    }

    private fun mdia(children: ByteArray): ByteArray = box(fourcc("mdia"), children)
    private fun minf(children: ByteArray): ByteArray = box(fourcc("minf"), children)
    private fun stbl(children: ByteArray): ByteArray = box(fourcc("stbl"), children)
    private fun udta(children: ByteArray): ByteArray = box(fourcc("udta"), children)

    /** trak → tref → chap（track_ID 数组） */
    private fun chapRef(trackId: Int): ByteArray =
        box(fourcc("tref"), box(fourcc("chap"), u32(trackId)))

    /** 文本原子：typeFlags=1 的 data 子原子 */
    private fun textAtom(type: String, value: String): ByteArray =
        box(fourcc(type), box(fourcc("data"), concat(u32(1), u32(0), text(value))))

    private fun ilst(children: ByteArray): ByteArray =
        box(fourcc("meta"), concat(u8(0), u8(0), u8(0), u8(0), box(fourcc("ilst"), children)))

    // ---- chpl ----

    /** chpl：payload = version(1) flags(3) unknown(4) count(1) + entries{startTime(8) len(1) title} */
    private fun chpl(vararg entries: Pair<Long, String>): ByteArray {
        var body = concat(u8(0), u8(0), u8(0), u8(0), u32(0), u8(entries.size))
        for ((ts, title) in entries) {
            body = concat(body, u64(ts), u8(title.toByteArray(Charsets.UTF_8).size), text(title))
        }
        return box(fourcc("chpl"), body)
    }

    @Test
    fun `chpl 解析并按 mvhd timescale 换算毫秒`() {
        val bytes = concat(
            box(fourcc("moov"), concat(mvhd(1000), udta(chpl(0L to "第一章", 3_600_000L to "第二章"))))
        )
        val chapters = ChplChapterReader.read(bytes)
        assertEquals(2, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("第一章", chapters[0].title)
        assertEquals(3_600_000L, chapters[1].startMs)
        assertEquals("第二章", chapters[1].title)
    }

    @Test
    fun `chpl 无 mvhd 时按默认 timescale 1000 换算`() {
        val bytes = concat(box(fourcc("moov"), udta(chpl(0L to "A", 10_000L to "B"))))
        val chapters = ChplChapterReader.read(bytes)
        assertEquals(2, chapters.size)
        assertEquals(10_000L, chapters[1].startMs)
    }

    // ---- iTunes 章节表 ----

    private fun itunesBook(
        chapTrackId: Int,
        sttsEntries: Array<Pair<Int, Int>>,
        names: List<String> = emptyList()
    ): ByteArray {
        val mainTrak = box(fourcc("trak"), concat(tkhd(1), chapRef(chapTrackId)))
        val nameAtoms = names.mapIndexed { i, n -> textAtom("©nam", n) }
        val nameIlst = if (nameAtoms.isEmpty()) ByteArray(0) else ilst(concat(*nameAtoms.toTypedArray()))
        val chapterTrak = box(
            fourcc("trak"),
            concat(
                tkhd(chapTrackId),
                mdia(concat(mdhd(1000), minf(stbl(stts(*sttsEntries))))),
                if (nameIlst.isEmpty()) ByteArray(0) else udta(nameIlst)
            )
        )
        return concat(box(fourcc("moov"), concat(mvhd(1000), mainTrak, chapterTrak)))
    }

    @Test
    fun `iTunes 章节表优先解析 stts 时间戳与 ilst 名`() {
        // stts delta = 样本时长；章节开始 = 累计时长（样本0 开始 0，样本1 开始 3600000）
        val bytes = itunesBook(
            chapTrackId = 2,
            sttsEntries = arrayOf(1 to 3_600_000, 1 to 3_600_000),
            names = listOf("第一章", "第二章")
        )
        val chapters = Mp4ChapterReader.read(bytes)
        assertEquals(2, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("第一章", chapters[0].title)
        assertEquals(3_600_000L, chapters[1].startMs)
        assertEquals("第二章", chapters[1].title)
    }

    @Test
    fun `iTunes 优先于 chpl（两者并存取 iTunes）`() {
        val itunes = itunesBook(2, arrayOf(1 to 1_000, 1 to 1_000), names = listOf("IT1", "IT2"))
        val chplBytes = udta(chpl(0L to "CH1", 500L to "CH2"))
        // 手工拼：iTunes 完整 moov payload + 另挂 chpl（iTunes 分支应命中，chpl 被忽略）
        val itunesWithChpl = concat(
            box(
                fourcc("moov"),
                concat(mvhd(1000), chplBytes, dropMoov(itunes))
            )
        )
        val chapters = Mp4ChapterReader.read(itunesWithChpl)
        assertEquals(2, chapters.size)
        assertEquals("IT1", chapters[0].title)
        assertEquals("IT2", chapters[1].title)
    }

    /** 从完整 moov box 中去掉外层 moov 头（取 payload） */
    private fun dropMoov(full: ByteArray): ByteArray =
        full.copyOfRange(8, full.size)

    @Test
    fun `无 tref chap 时回退 chpl`() {
        val chplOnly = concat(box(fourcc("moov"), concat(mvhd(1000), udta(chpl(0L to "A")))))
        val chapters = Mp4ChapterReader.read(chplOnly)
        assertEquals(1, chapters.size)
        assertEquals("A", chapters[0].title)
    }

    @Test
    fun `iTunes 章节名缺失时标题为空（调用方兜底）`() {
        val bytes = itunesBook(2, arrayOf(1 to 1_000, 1 to 1_000))
        val chapters = Mp4ChapterReader.read(bytes)
        assertEquals(2, chapters.size)
        assertEquals("", chapters[0].title)
        assertEquals(1_000L, chapters[1].startMs)
    }

    @Test
    fun `无章节标记返回空列表`() {
        val plain = concat(box(fourcc("moov"), mvhd(1000)))
        assertTrue(Mp4ChapterReader.read(plain).isEmpty())
        assertTrue(ChplChapterReader.read(plain).isEmpty())
    }

    @Test
    fun `畸形字节返回空列表不抛`() {
        assertTrue(Mp4ChapterReader.read(ByteArray(0)).isEmpty())
        assertTrue(Mp4ChapterReader.read(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(ChplChapterReader.read(ByteArray(0)).isEmpty())
    }
}
