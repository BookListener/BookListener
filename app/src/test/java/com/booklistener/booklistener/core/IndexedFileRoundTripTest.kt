package com.booklistener.core

import com.booklistener.db.EntryIndexEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7.0 索引行转换测试：AudioItem↔IndexedFile↔Entity 往返，字段无丢失；
 * rawName/标签/时长在三条通路中保持。
 */
class IndexedFileRoundTripTest {

    private val item = AudioItem(
        id = "folder_doc1",
        displayName = "标题歌",
        path = "content://tree/doc1",
        sourceType = SourceType.LOCAL,
        size = 2048L,
        album = "专辑甲",
        artist = "作者甲",
        track = 2,
        disc = 1,
        durationMs = 180_000L,
        rawName = "02 标题歌.mp3"
    )

    @Test
    fun audioItem_toIndexedFile_preservesMediaFields() {
        val f = item.toIndexedFile()
        assertEquals("标题歌", f.displayName)
        assertEquals("content://tree/doc1", f.uri)
        assertEquals("专辑甲", f.album)
        assertEquals("作者甲", f.artist)
        assertEquals(2, f.track)
        assertEquals(1, f.disc)
        assertEquals(180_000L, f.durationMs)
        assertEquals("02 标题歌.mp3", f.rawName)
    }

    @Test
    fun indexedFile_toEntity_fromEntity_roundTrip() {
        val f = item.toIndexedFile()
        val e = f.toEntity("entry1")
        assertEquals("entry1", e.entryId)
        val back = IndexedFile.fromEntity(e)
        assertEquals(f, back)
        // entity 字段逐项
        assertEquals("专辑甲", e.album)
        assertEquals("作者甲", e.artist)
        assertEquals(2, e.track)
        assertEquals(1, e.disc)
        assertEquals(180_000L, e.durationMs)
        assertEquals("02 标题歌.mp3", e.rawName)
    }

    @Test
    fun indexedFile_toAudioItem_carriesSourceAndTags() {
        val f = item.toIndexedFile()
        val a = f.toAudioItem(SourceType.WEBDAV)
        assertEquals("content://tree/doc1", a.path)
        assertEquals(SourceType.WEBDAV, a.sourceType)
        assertEquals("专辑甲", a.album)
        assertEquals("作者甲", a.artist)
        assertEquals(180_000L, a.durationMs)
    }

    @Test
    fun defaultNulls_roundTripKeepsNull() {
        val f = IndexedFile("无标签", "uri://x")
        val e = f.toEntity("e")
        assertNull(e.album)
        assertNull(e.artist)
        assertNull(e.track)
        assertNull(e.disc)
        assertNull(e.durationMs)
        assertNull(e.rawName)
        assertEquals(f, IndexedFile.fromEntity(e))
    }
}
