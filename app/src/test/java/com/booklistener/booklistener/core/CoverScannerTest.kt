package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面选择纯逻辑测试（v1.5.0）：文件夹内常见命名图片优先 → 任意图片 → 无封面。
 * 查询注入 lambda，验证选择策略（不含真实文件系统）。
 */
class CoverScannerTest {

    private fun entry(name: String, mime: String = "", isDir: Boolean = false, id: String = name) =
        FolderEntry(
            displayName = name,
            mimeType = mime,
            docId = id,
            isDirectory = isDir,
            size = 0L
        )

    private fun scanner(vararg children: FolderEntry) = CoverScanner(
        listChildren = { _, _ -> children.toList() },
        treeDocumentId = { _ -> "root" },
        fileUriBuilder = { tree, doc -> "$tree/$doc" }
    )

    @Test
    fun preferredCoverName_wins() {
        val s = scanner(
            entry("song.mp3", "audio/mpeg"),
            entry("cover.jpg"),
            entry("random.png")
        )
        assertEquals("content://tree/root/cover.jpg", s.findCover("content://tree/root"))
    }

    @Test
    fun noPreferredName_anyImageUsed() {
        val s = scanner(entry("song.mp3", "audio/mpeg"), entry("random.png"))
        assertEquals("content://tree/root/random.png", s.findCover("content://tree/root"))
    }

    @Test
    fun noImage_returnsNull() {
        val s = scanner(entry("song.mp3", "audio/mpeg"))
        assertNull(s.findCover("content://tree/root"))
    }

    @Test
    fun directories_ignored() {
        val s = scanner(
            entry("sub", isDir = true),
            entry("cover.jpg")
        )
        assertEquals("content://tree/root/cover.jpg", s.findCover("content://tree/root"))
    }

    @Test
    fun preferredCaseInsensitive() {
        // 任意图片（非优先命名）在前，优先命名（大小写不同）在后 → 优先命名赢
        val s = scanner(entry("random1.JPG"), entry("Cover.jpg"))
        assertEquals("content://tree/root/Cover.jpg", s.findCover("content://tree/root"))
    }

    @Test
    fun nullChildren_returnsNull() {
        val s = CoverScanner(listChildren = { _, _ -> null }, treeDocumentId = { _ -> "root" }, fileUriBuilder = { tree, doc -> "$tree/$doc" })
        assertNull(s.findCover("content://tree/root"))
    }
}
