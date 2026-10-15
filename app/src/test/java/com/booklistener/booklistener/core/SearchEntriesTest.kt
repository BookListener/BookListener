package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已添加内容搜索纯逻辑测试（v1.5.0 文件夹+文件；v2.0.1 改为面向数据库的专辑+歌曲）：
 * 搜索范围为已添加条目的专辑聚合名 + 歌曲文件名（忽略大小写包含匹配），不再命中文件夹。
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
    fun albumNameHit_returnsAlbum() {
        // 文件无 album 标签 → 专辑名兜底为条目名 "Podcasts"
        val hits = searchEntries("podcast", entries, index)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is SearchHit.Album)
        val album = hits[0] as SearchHit.Album
        assertEquals("b", album.album.entryId)
        assertEquals("Podcasts", album.album.album)
        assertEquals(1, album.album.songCount)
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
    fun albumNameHit_onlyAlbumNoFolderHit() {
        // "music" 命中专辑名（Music Library），但不再产生文件夹命中
        val hits = searchEntries("music", entries, index)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is SearchHit.Album)
        assertEquals("a", (hits[0] as SearchHit.Album).album.entryId)
    }

    @Test
    fun noMatch_returnsEmpty() {
        assertEquals(0, searchEntries("zzz", entries, index).size)
    }

    @Test
    fun albumAndFileBothMatch_returnsBoth() {
        val index2 = mapOf(
            "c" to listOf(IndexedFile("Beat It.mp3", "content://c/1"))
        )
        val entries2 = listOf(entry("c", "Beat Collection"))
        // "beat" 同时命中专辑名（Beat Collection）与歌曲名（Beat It.mp3）
        val hits = searchEntries("beat", entries2, index2)
        assertEquals(2, hits.size)
        assertTrue(hits.any { it is SearchHit.Album })
        assertTrue(hits.any { it is SearchHit.File })
    }
}
