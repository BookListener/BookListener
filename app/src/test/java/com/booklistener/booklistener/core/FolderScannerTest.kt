package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹扫描纯逻辑测试：
 * 覆盖"打开文件夹后列举可播放音频"的核心规则——目录递归、audio/ 过滤、
 * 空名防御、深度上限，防止出现"列不出文件 / 混入非音频"类低级回归。
 */
class FolderScannerTest {

    private val uriBuilder: (String, String) -> String = { tree, doc -> "$tree/$doc" }
    private val rootId: (String) -> String = { "root" }

    private fun entry(
        name: String,
        mime: String,
        docId: String,
        isDir: Boolean = false,
        size: Long = 0L
    ) = FolderEntry(name, mime, docId, isDir, size)

    private val empty: (String, String) -> List<FolderEntry> = { _, _ -> emptyList() }

    @Test
    fun scan_rootAudioFiles_areListed() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(
                        entry("song-a.mp3", "audio/mpeg", "doc-a", size = 100L),
                        entry("song-b.m4a", "audio/mp4", "doc-b", size = 200L),
                        entry("notes.txt", "text/plain", "doc-txt")
                    )
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        val items = scanner.scan("content://tree/root")
        assertEquals(2, items.size)
        assertEquals("song-a", items[0].displayName)
        assertEquals("content://tree/root/doc-a", items[0].path)
        assertEquals(100L, items[0].size)
        assertTrue(items[0].id.startsWith("folder_"))
        assertEquals("song-b", items[1].displayName)
    }

    @Test
    fun scan_directories_areRecursed() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                when (parent) {
                    "root" -> listOf(entry("music", "vnd.android.document/directory", "dir1", isDir = true))
                    "dir1" -> listOf(entry("deep.mp3", "audio/mpeg", "doc-deep", size = 50L))
                    else -> emptyList()
                }
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        val items = scanner.scan("content://tree/root")
        assertEquals(1, items.size)
        assertEquals("deep", items[0].displayName)
        assertEquals("content://tree/root/doc-deep", items[0].path)
    }

    @Test
    fun scan_blankName_isFiltered() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(
                        entry("", "audio/mpeg", "doc-blank"),
                        entry("ok.mp3", "audio/mpeg", "doc-ok")
                    )
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        assertEquals(1, scanner.scan("content://tree/root").size)
    }

    @Test
    fun scan_depthLimit_stopsRecursion() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                listOf(entry("sub", "vnd.android.document/directory", "${parent}/sub", isDir = true))
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        // 深度上限 0：只查根目录，不进入任何子目录
        val items = scanner.scan("content://tree/root", maxDepth = 0)
        assertTrue(items.isEmpty())
    }

    @Test
    fun scan_nullChildrenQuery_tolerated() {
        val scanner = FolderScanner(
            listChildren = { _, _ -> null },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        assertTrue(scanner.scan("content://tree/root").isEmpty())
    }

    @Test
    fun scan_octetStreamMime_audioExtension_stillListed() {
        // SAF 提供方对未知类型常返回 application/octet-stream：必须按扩展名兜底，否则漏列
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(
                        entry("song-a.mp3", "application/octet-stream", "doc-a", size = 100L),
                        entry("song-b.m4a", "application/octet-stream", "doc-b", size = 200L),
                        entry("cover.jpg", "application/octet-stream", "doc-jpg")
                    )
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        val items = scanner.scan("content://tree/root")
        assertEquals(2, items.size)
        assertEquals("song-a", items[0].displayName)
        assertEquals("song-b", items[1].displayName)
    }

    @Test
    fun scan_blankMime_audioExtension_stillListed() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(
                        entry("song.flac", "", "doc-flac", size = 50L),
                        entry("notes.txt", "", "doc-txt")
                    )
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        assertEquals(1, scanner.scan("content://tree/root").size)
    }

    @Test
    fun scan_uppercaseExtension_listed() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(entry("SONG.MP3", "application/octet-stream", "doc-up", size = 10L))
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        val items = scanner.scan("content://tree/root")
        assertEquals(1, items.size)
        assertEquals("SONG", items[0].displayName)
    }

    // ---- v1.6.0：标签解析 + 自然排序集成 ----

    @Test
    fun scan_sortsByNameNaturalOrder_atExit() {
        // 扫描出口统一自然排序：10 排在 2 之后（数字段数值比较）
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(
                        entry("track10.mp3", "audio/mpeg", "doc-10"),
                        entry("track2.mp3", "audio/mpeg", "doc-2"),
                        entry("track1.mp3", "audio/mpeg", "doc-1")
                    )
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder
        )
        val items = scanner.scan("content://tree/root")
        assertEquals(listOf("track1", "track2", "track10"), items.map { it.displayName })
    }

    @Test
    fun scan_tagTitle_overridesFileName() {
        // 注入假 tag：title 优先显示
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(entry("01-hidden.mp3", "audio/mpeg", "doc-a"))
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder,
            tagResolver = { _, _ -> TagResolver.AudioTags("真实歌名", "歌手", "专辑", 1, 1) }
        )
        val items = scanner.scan("content://tree/root")
        assertEquals("真实歌名", items[0].displayName)
    }

    @Test
    fun scan_noTags_fallsBackToFileNameWithoutExt() {
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(entry("02-name.flac", "audio/flac", "doc-b"))
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder,
            tagResolver = { _, _ -> null }
        )
        val items = scanner.scan("content://tree/root")
        assertEquals("02-name", items[0].displayName)
    }

    @Test
    fun scan_sortsByDiscThenTrack_whenTagsPresent() {
        // 有 tag：disc → track → 名称；无 tag 文件排后
        val scanner = FolderScanner(
            listChildren = { _, parent ->
                if (parent == "root") {
                    listOf(
                        entry("d1t2.mp3", "audio/mpeg", "doc-1"),
                        entry("plain.mp3", "audio/mpeg", "doc-plain"),
                        entry("d1t1.mp3", "audio/mpeg", "doc-2"),
                        entry("d2t1.mp3", "audio/mpeg", "doc-3")
                    )
                } else emptyList()
            },
            treeDocumentId = rootId,
            fileUriBuilder = uriBuilder,
            tagResolver = { uri, name ->
                when (name) {
                    "d1t2.mp3" -> TagResolver.AudioTags("B", null, null, 2, 1)
                    "d1t1.mp3" -> TagResolver.AudioTags("A", null, null, 1, 1)
                    "d2t1.mp3" -> TagResolver.AudioTags("C", null, null, 1, 2)
                    else -> null
                }
            }
        )
        val items = scanner.scan("content://tree/root")
        assertEquals(listOf("A", "B", "C", "plain"), items.map { it.displayName })
    }
}
