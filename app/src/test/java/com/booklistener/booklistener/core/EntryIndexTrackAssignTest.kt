package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.6.13：无音轨号条目写库前按展示排序赋予序号（自然排序、幂等、不影响有 tag 条目） */
class EntryIndexTrackAssignTest {

    private fun file(name: String, track: Int? = null) = IndexedFile(
        displayName = name,
        uri = "content://test/$name",
        album = null,
        artist = null,
        track = track,
        disc = null,
        durationMs = null,
        rawName = name
    )

    @Test
    fun `全无音轨号时按名称自然序赋 1 到 N，返回顺序不变`() {
        // 传入乱序：第10章 应排在第2章之后（自然排序按数字）
        val input = listOf(
            file("第10章.mp3"),
            file("第1章.mp3"),
            file("第2章.mp3")
        )
        val out = assignTrackNumbers(input)
        // 原列表顺序不变
        assertEquals(listOf("第10章.mp3", "第1章.mp3", "第2章.mp3"), out.map { it.displayName })
        // 序号按名称自然序：第1章=1，第2章=2，第10章=3
        assertEquals(listOf(3, 1, 2), out.map { it.track })
    }

    @Test
    fun `有音轨号的条目保持原值不动`() {
        val input = listOf(
            file("b.mp3", track = 5),
            file("a.mp3", track = 2),
            file("c.mp3", track = null)
        )
        val out = assignTrackNumbers(input)
        assertEquals(5, out[0].track)
        assertEquals(2, out[1].track)
        // 唯一的无 tag 条目拿到 1
        assertEquals(1, out[2].track)
    }

    @Test
    fun `混合场景有 tag 不参与编号，无 tag 之间按名称序`() {
        val input = listOf(
            file("track2.mp3", track = 2),   // 有 tag
            file("第3集.mp3"),                // 无 tag
            file("第1集.mp3"),                // 无 tag
            file("track1.mp3", track = 1)    // 有 tag
        )
        val out = assignTrackNumbers(input)
        assertEquals(2, out[0].track)
        // 无 tag 子集名称序：第1集 → 1，第3集 → 2
        assertEquals(2, out[1].track)
        assertEquals(1, out[2].track)
        assertEquals(1, out[3].track)
    }

    @Test
    fun `全部有音轨号时不做任何修改`() {
        val input = listOf(
            file("a.mp3", track = 1),
            file("b.mp3", track = 2)
        )
        val out = assignTrackNumbers(input)
        assertEquals(input, out)
    }

    @Test
    fun `幂等：对结果再次调用序号不变`() {
        val input = listOf(file("B.mp3"), file("A.mp3"), file("C.mp3"))
        val once = assignTrackNumbers(input)
        val twice = assignTrackNumbers(once)
        assertEquals(once, twice)
        assertEquals(listOf(2, 1, 3), twice.map { it.track })
    }

    @Test
    fun `空列表与单元素不崩溃`() {
        assertEquals(emptyList<IndexedFile>(), assignTrackNumbers(emptyList()))
        val single = assignTrackNumbers(listOf(file("only.mp3")))
        assertEquals(1, single[0].track)
    }

    @Test
    fun `disc 缺失与 track 缺失一致处理`() {
        // 有 disc 但无 track：仍参与编号（编号只判 track==null）
        val input = listOf(
            file("disc2-第2集.mp3").copy(disc = 1),
            file("disc1-第1集.mp3").copy(disc = 1)
        )
        val out = assignTrackNumbers(input)
        assertEquals(1, out[0].disc)
        assertEquals(2, out[0].track)
        assertEquals(1, out[1].disc)
        assertEquals(1, out[1].track)
    }
}
