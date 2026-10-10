package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已添加内容搜索纯逻辑测试（v1.5.0）：
 * 搜索范围为已添加内容（文件夹名 + 文件清单名），大小写不敏感包含匹配。
 */
class SearchEntriesTest {

    private fun entry(id: String, name: String) = SavedEntry(
        id = id,
        displayName = name,
        uri = id,
        sourceType = SourceType.LOCAL
    )

    private val index = mapOf(
        "a" to listOf(
            IndexedFile("Summer Breeze.mp3", "content://a/1"),
            IndexedFile("Rainy Night.flac", "content://a/2")
        ),
        "b" to listOf(
            IndexedFile("Dream On.m4a", "content://b/1")
        )
    )

    private val entries = listOf(entry("a", "Music Library"), entry("b", "Podcasts"))

    @Test
    fun blankQuery_returnsEmpty() {
        assertEquals(0, searchEntries("   ", entries, index).size)
    }

    @Test
    fun folderNameHit_returnsFolder() {
        val hits = searchEntries("podcast", entries, index)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is SearchHit.Folder)
        assertEquals("b", (hits[0] as SearchHit.Folder).entry.id)
    }

    @Test
    fun fileNameHit_returnsFile() {
        val hits = searchEntries("summer", entries, index)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is SearchHit.File)
        val f = hits[0] as SearchHit.File
        assertEquals("a", f.entry.id)
        assertEquals("Summer Breeze.mp3", f.file.displayName)
    }

    @Test
    fun caseInsensitive_matches() {
        val hits = searchEntries("SUMMER", entries, index)
        assertEquals(1, hits.size)
    }

    @Test
    fun mixedHits_folderAndFiles() {
        // "a" 命中文件夹名（Music Library 不含 a 大写……），用 "music" 命中文件夹 + 无文件
        val hits = searchEntries("music", entries, index)
        assertEquals(1, hits.size)
        assertEquals("a", (hits[0] as SearchHit.Folder).entry.id)
    }

    @Test
    fun noMatch_returnsEmpty() {
        assertEquals(0, searchEntries("zzz", entries, index).size)
    }

    @Test
    fun folderAndFileBothMatch_returnsBoth() {
        val index2 = mapOf(
            "c" to listOf(IndexedFile("Beat It.mp3", "content://c/1"))
        )
        val entries2 = listOf(entry("c", "Beat Collection"))
        val hits = searchEntries("beat", entries2, index2)
        assertEquals(2, hits.size)
    }
}
