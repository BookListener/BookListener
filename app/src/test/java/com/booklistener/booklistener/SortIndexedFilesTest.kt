package com.booklistener.booklistener

import com.booklistener.core.IndexedFile
import com.booklistener.core.sortIndexedFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.6.14：条目视图改从 db 读取后，统一经 sortIndexedFiles 按展示序排序
 * （disc → track → 名称自然序）。此函数同时被 assignTrackNumbers 复用同一比较器。
 */
class SortIndexedFilesTest {

    private fun file(name: String, disc: Int? = null, track: Int? = null) = IndexedFile(
        uri = "content://test/$name",
        displayName = name,
        disc = disc,
        track = track
    )

    @Test
    fun `db 乱序数据按展示序重排`() {
        val input = listOf(
            file("3.mp3", track = 3),
            file("1.mp3", track = 1),
            file("2.mp3", track = 2)
        )
        val sorted = sortIndexedFiles(input)
        assertEquals(listOf("1.mp3", "2.mp3", "3.mp3"), sorted.map { it.displayName })
    }

    @Test
    fun `名称自然序 10 排在 2 之后`() {
        val input = listOf(
            file("10.mp3"),
            file("2.mp3"),
            file("1.mp3")
        )
        val sorted = sortIndexedFiles(input)
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), sorted.map { it.displayName })
    }

    @Test
    fun `disc 优先于 track`() {
        val input = listOf(
            file("B2.mp3", disc = 2, track = 1),
            file("A1.mp3", disc = 1, track = 1),
            file("A2.mp3", disc = 1, track = 2)
        )
        val sorted = sortIndexedFiles(input)
        assertEquals(listOf("A1.mp3", "A2.mp3", "B2.mp3"), sorted.map { it.displayName })
    }

    @Test
    fun `track 优先于名称`() {
        val input = listOf(
            file("z.mp3", track = 1),
            file("a.mp3", track = 3),
            file("m.mp3", track = 2)
        )
        val sorted = sortIndexedFiles(input)
        assertEquals(listOf("z.mp3", "m.mp3", "a.mp3"), sorted.map { it.displayName })
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(emptyList<IndexedFile>(), sortIndexedFiles(emptyList()))
    }
}
